/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.openjpa.jdbc.kernel;

import org.apache.openjpa.conf.Compatibility;
import org.apache.openjpa.conf.OpenJPAConfiguration;
import org.apache.openjpa.jdbc.sql.DBDictionary;
import org.apache.openjpa.kernel.*;
import org.apache.openjpa.lib.log.Log;
import org.apache.openjpa.meta.MetaDataRepository;
import org.apache.openjpa.persistence.EntityManagerImplExt;
import org.apache.openjpa.persistence.QueryImpl;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;


@RunWith(PowerMockRunner.class)
@PrepareForTest(  {PreparedQueryImpl.class, PreparedQueryCacheImpl.class}  )
public class QueryImplIT {

    /*
     * Questo test d'integrazione vuole essere un estensione di PreparedQueryCacheIT. Infatti, qui aggiungiamo al SUT
     * anche la classe QueryImpl.
     * Il SUT è quindi ora composto da:
     * - QueryImpl
     * - PreparedQueryCacheImpl
     * - CacheMap
     *
     * L'interazione che vogliamo testare dunque è quella in cui abbiamo una QueryImpl (che rappresenta una query per un
     * database) che viene compilata e diventa una PreparedQuery) e infine eseguita. Durante la sua esecuzione, QueryImpl
     * avrà uno scambio di messaggi con PreparedQueryCacheImpl per richiedere che, se possibile, la prepared query compilata
     * a partire dalla query iniziale, venga mantenuta in cache per possibili utilizzi futuri più efficienti.
     *
     * PreparedQueryCacheImpl, una volta ricevuto questo messaggio provvederà a controllare se la query è già presente in
     * cache, e all'occorrenza provvederà ad aggiungerla ad essa.
     *
     * CacheMap, tramite uno scambio di messaggi con PreparedQueryCacheImpl infine avrà il compito di mantenere effettivamente
     * in cache la query, ed eventualmente di spostare altre entry nella softCache per fare spazio alla più recente.
     *
     * Essendo dunque il SUT composto da queste tre classi, le altre, laddove necessario, sono state mockate.
     * Come già fatto per PreparedQueryCacheIT, anche qui abbiamo usato una classe che estende CacheMap in modo da avere
     * dei getter in più per le singole mappe di cache, ed è stata usata la reflection per associare queste CacheMap alla
     * PreparedQueryCacheImpl.
     *
     * Lo scambio di messaggi dunque avverà con il seguente schema:
     *
     *          QueryImpl ----> PreparedQueryCacheImpl -----> CacheMap
     *
     *
     * L'idea del test è quella di creare una prima query, compilarla ed eseguirla per ottenere il risultato da essa generato
     * (l'oggetto ottenuto come risultato sarà un semplice mock, dal momento che non è questa la parte del sistema con cui ci
     * interessa acquisire confidenza in questo momento). A questo punto vogliamo controllare che la query sia presente in
     * cache, sia se vi accediamo direttamente tramite la CacheMap, sia passando da PreparedCacheQueryImpl.
     *
     * A questo punto, avendo il sistema nello stato appena descritto, vogliamo verificare che, se la cache è piena QueryImpl
     * richiede l'esecuzione è l'immissione in cache di un'altra query, venga fatto posto nella CacheMap per la nuova query,
     * spostando una query meno recente in soft cache.
     *
     * Per facilitare questo punto, la cache "primaria" di CacheMap è stata creata con spazio per un solo oggetto. In questo modo,
     * avendo nel nostro test già effettuato l'esecuzione di una query, ci aspettiamo che la cache sia piena.
     * Dunque, andando ad eseguire una seconda query (con un ID diverso dalla prima, in modo che non risulti come un "aggiornamento"
     * della prima query, ma come un oggetto del tutto nuovo) ci aspettiamo che, finita l'esecuzione, in cache ci sia la seconda query,
     * mentre ci aspettiamo che la prima, non avendo più posto nella cache primaria, sia stata spostata in maniera trasparente
     * nella softMap della CacheMap.
     *
     * La terza interazione che andiamo a testare è invece quella di una query che non è cachable per le caratteristiche che possiede.
     * Quello che ci aspettiamo che succeda non è solamente che non venga inserita nella cache insieme alle due query precedenti, ma
     * ci aspettiamo che venga inserita nella CacheMap dove sono tenute in memoria tutte le query uncachable.
     * Per generare una query che non è cachable sfruttiamo il mock di alcuni suoi metodi per fare in modo che, quando interrogati
     * ritornino dei valori che non sono compatibili con il caching della query.
     */

    static PreparedQueryCacheImpl preparedQueryCache = new PreparedQueryCacheImpl();
    static PreparedQueryCacheIT.CacheMapExt delegate = new PreparedQueryCacheIT.CacheMapExt();
    static PreparedQueryCacheIT.CacheMapExt uncachables = new PreparedQueryCacheIT.CacheMapExt();


    @BeforeClass
    public static void setCacheMaps() throws NoSuchFieldException, IllegalAccessException {
        //usiamo la reflection per settare le cache map nella nostra istanza di PreparedQueryCacheImpl

        Field delegateField = PreparedQueryCacheImpl.class.getDeclaredField("_delegate");
        delegateField.setAccessible(true);
        delegateField.set(preparedQueryCache, delegate);

        Field uncachableField = PreparedQueryCacheImpl.class.getDeclaredField("_uncachables");
        uncachableField.setAccessible(true);
        uncachableField.set(preparedQueryCache, uncachables);

        preparedQueryCache.endConfiguration();
        preparedQueryCache.setEnableStatistics(false);



    }

    @Test
    public void queriesCacheHandlingTest() throws Exception {

        //Classi da Mockare
        DelegatingBroker delegBroker = mock(DelegatingBroker.class);
        OpenJPAConfiguration delegConfig = mock(OpenJPAConfiguration.class);
        Broker broker = mock(BrokerImpl.class);
        FetchConfiguration fetch = mock(FetchConfiguration.class);
        OpenJPAConfiguration openJPAConfiguration = Mockito.mock(OpenJPAConfiguration.class);
        Log log = mock(Log.class);
        Compatibility compatibility = mock(Compatibility.class);
        MetaDataRepository metaDataRepository = mock(MetaDataRepository.class);
        JDBCStore jdbc = mock(JDBCStore.class);


        //Mock dei metodi
        when(delegBroker.getCachePreparedQuery()).thenReturn(true);
        when(delegConfig.getQuerySQLCacheInstance()).thenReturn(preparedQueryCache);
        when(broker.getMultithreaded()).thenReturn(false);
        when(fetch.getFetchBatchSize()).thenReturn(0);
        when(fetch.clone()).thenReturn(fetch);
        when(broker.getFetchConfiguration()).thenReturn(fetch);
        when(broker.getConfiguration()).thenReturn(openJPAConfiguration);
        when(log.isTraceEnabled()).thenReturn(false);
        when(openJPAConfiguration.getLog(any())).thenReturn(log);
        when(openJPAConfiguration.getCompatibilityInstance()).thenReturn(compatibility);
        when(compatibility.getConvertPositionalParametersToNamed()).thenReturn(false);
        when(openJPAConfiguration.getMetaDataRepositoryInstance()).thenReturn(metaDataRepository);
        when(metaDataRepository.getMetaData((Class<?>) any(), any(), anyBoolean())).thenReturn(null);
        when(jdbc.getDBDictionary()).thenReturn(new DBDictionary());


        //Costruiamo una query da registrare (e quindi cachare) con preparedQueryCache
        StoreQuery storeQuery = new SQLStoreQuery(jdbc);
        Query query = new org.apache.openjpa.kernel.QueryImpl(broker, QueryLanguages.LANG_PREPARED_SQL, storeQuery);
        query.setQuery("SELECT ? from someDB"); //settiamo un generico testo della query. L'unica parte importante è la "SELECT", per far si che
        //venga riconosciuta come una query valida

        Query spiedQuery = Mockito.spy(query);
        doReturn(Arrays.asList("done", "this is the result")).when(spiedQuery).execute(any(Map.class));//simuliamo il ritorno dell' exec della query

        //Istanziamo QueryImpl, a cui "attacchiamo" la nostra istanza di PreparedQueryCacheImpl
        org.apache.openjpa.persistence.QueryImpl queryImpl = new QueryImpl(new EntityManagerImplExt(preparedQueryCache), spiedQuery );

        //Questo non è una sorta di "mock", è solo che il metodo per settare l'id è package private, e trovandomi in un
        //package diverso da quello di persistence devo fare così per non modificare la visibilità di quel metodo.
        Field id = QueryImpl.class.getDeclaredField("_id");
        id.setAccessible(true);
        String idString = "myId";
        id.set(queryImpl, idString);


        //Qui devo usare per forza PowerMock perchè all'interno del metodo viene eseguita la new di un'istanza di
        //questa classe senza nessuna Factory, dunque non ho altro modo per Mockarne la costruzione.
        //Questo mock serve a "bypassare" la fase di verifica del risultato della query, dato che non è la query in se
        //il focus di questo test, bensì il suo inserimento nella cache.
        PreparedQueryImpl preparedQueryMock = PowerMockito.mock(PreparedQueryImpl.class);
        PowerMockito.when(preparedQueryMock.isInitialized()).thenReturn(true);
        PowerMockito.when(preparedQueryMock.getIdentifier()).thenReturn(idString);
        //Qui scegliamo di chiamare i metodi reali del mock per il semplice fatto che è all'interno di questi che si decide
        //se la query è cachable oppure no, e controlliamo questo comportamento tramite gli altri metodi mockati.
        PowerMockito.when(preparedQueryMock.initialize(any())).thenCallRealMethod();
        PowerMockito.doCallRealMethod().when(preparedQueryMock, "extractSelectExecutor", any());
        //mock del costruttore
        PowerMockito.whenNew(PreparedQueryImpl.class).withArguments(anyString(), any(DelegatingQuery.class)).thenReturn(preparedQueryMock);

        queryImpl.compile(); //compilo la query

        //La cache prima dell'esecuzione deve ancora essere vuota. Non abbiamo richiesto nessun inserimento ne eseguito nessuna query
        assertEquals(0, delegate.getTotalSize());
        assertEquals(0, uncachables.getTotalSize());
        //eseguiamo la query
        queryImpl.getResultList(); //ne ignoriamo il risultato dato che non è quello il focus del test

        assertEquals(1, delegate.getMainCacheSize()); //deve esserci la query in cache ora
        assertEquals(0, uncachables.getTotalSize()); //non deve essere finita tra gli uncachable
        assertTrue(delegate.containsKey(idString));
        //controlliamo che l'oggetto cachato sia quello che ci aspettiamo (ovvero la PreparedQuery costruita a partire dalla nostra QueryImpl)
        PreparedQueryImpl fromCache = (PreparedQueryImpl) delegate.get(idString);
        assertEquals(preparedQueryMock, fromCache);
        assertEquals(preparedQueryCache.get(idString), fromCache);

        //Seconda query, con id diverso dalla prima

        org.apache.openjpa.persistence.QueryImpl queryImpl2 = new QueryImpl(new EntityManagerImplExt(preparedQueryCache), spiedQuery );
        String idString2 = idString + 2;
        id.set(queryImpl2, idString2);
        PowerMockito.when(preparedQueryMock.getIdentifier()).thenReturn(idString2);
        queryImpl2.compile();
        queryImpl2.getResultList();
        assertTrue(delegate.isInMainCache(idString2));
        assertFalse(delegate.isInMainCache(idString));
        assertTrue(delegate.isInSoftCache(idString));
        assertEquals(1, delegate.getSoftMapSize());

        //Terza query, non cachabile
        PowerMockito.when(preparedQueryMock.isInitialized()).thenReturn(false); //in questo modo la query non sarà cachabile
        org.apache.openjpa.persistence.QueryImpl queryImpl3 = new QueryImpl(new EntityManagerImplExt(preparedQueryCache), spiedQuery );
        String idString3 = idString + 3;
        id.set(queryImpl3, idString3);
        PowerMockito.when(preparedQueryMock.getIdentifier()).thenReturn(idString3);
        queryImpl3.compile();
        queryImpl3.getResultList();
        assertEquals(1, uncachables.getTotalSize()); //deve effettivamente essere tra gli uncachable
        assertEquals(2, delegate.getTotalSize()); //il numero di query realmente cached non deve essere cambiato




    }



}
