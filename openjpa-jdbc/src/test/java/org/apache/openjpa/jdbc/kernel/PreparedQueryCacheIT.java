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

import org.apache.openjpa.conf.OpenJPAConfiguration;
import org.apache.openjpa.kernel.*;
import org.apache.openjpa.util.CacheMap;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class PreparedQueryCacheIT {

    /*
     * L'idea di questo Integration Test è quella di acquisire confidenza sull'interazione tra due classi (che in questo
     * frangente sono il nostro SUT):
     * - CacheMap
     * - PreparedQueryCacheImpl
     *
     * La seconda di queste classi infatti è il gestore della cache per le Prepared Query del modulo jdbc di OpenJPA.
     * Per fare ciò si serve della classe CacheMap, a cui delega il compito vero e proprio di mantenere in cache
     * gli Object di cui viene richiesto il caching.
     * PreparedQueryCacheImp però implementa controlli per verificare se "cachare" o meno una prepared query. Infatti si
     * serve di due diverse CacheMap: una viene usata come cache "vera e propria", metre l'altra viene usata per tenere
     * traccia di quali PreparedStatement non dovranno essere messi in cache.
     *
     * La strategia di questo Integration Test per controllare che tutto avvenga come ci si aspetta, è quello di usare
     * la reflection per accedere alle CacheMap di PreparedQueryCacheImpl, in modo da poter controllare il loro contenuto,
     * e quindi verificar che l'interazione con le CacheMap stesse sia avvenuto con successo.
     */

    static PreparedQueryCacheImpl preparedQueryCache = new PreparedQueryCacheImpl();
    static CacheMapExt delegate = new CacheMapExt();
    static CacheMapExt uncachables = new CacheMapExt();

    //usiamo questa estensione di cacheMap per avere i getter sulle mappe di cache
    static class CacheMapExt extends CacheMap {
        public CacheMapExt() {
            super(false, 1, 1, 0.75f, 0);
        }

        public int getMainCacheSize() {
            return super.cacheMap.size();
        }

        public int getSoftMapSize() {
            return super.softMap.size();
        }

        public int getTotalSize() {
            return super.cacheMap.size() + super.softMap.size() + super.pinnedMap.size();
        }

        public boolean isInMainCache(Object o) {
            return super.cacheMap.containsKey(o);
        }

        public boolean isInSoftCache(Object o) {
            return super.softMap.containsKey(o);
        }
    }



    @BeforeClass
    public static void setCacheMaps() throws NoSuchFieldException, IllegalAccessException {
        //usiamo la reflection per settare le cache map

        Field delegateField = PreparedQueryCacheImpl.class.getDeclaredField("_delegate");
        delegateField.setAccessible(true);
        delegateField.set(preparedQueryCache, delegate);

        Field uncachableField = PreparedQueryCacheImpl.class.getDeclaredField("_uncachables");
        uncachableField.setAccessible(true);
        uncachableField.set(preparedQueryCache, uncachables);


    }

    @Test
    public void cacheQuery() {
        String queryId = "firstQuery";

        Broker broker = mock(BrokerImpl.class);
        Mockito.when(broker.getMultithreaded()).thenReturn(false);
        Mockito.when(broker.getFetchConfiguration()).thenReturn(mock(FetchConfiguration.class));
        OpenJPAConfiguration openJPAConfiguration = Mockito.mock(OpenJPAConfiguration.class);
        Mockito.when(broker.getConfiguration()).thenReturn(openJPAConfiguration);
        Mockito.when(openJPAConfiguration.getLog()).thenReturn("");

        //Costruiamo una query da registrare (e quindi cachare) con preparedQueryCache
        Query query = new QueryImpl(broker, QueryLanguages.LANG_PREPARED_SQL, mock(StoreQuery.class));
        assertTrue(preparedQueryCache.register(queryId, query, null));

        //Ora ci assciuriamo che nella cacheMap risulti una entry relativa alla nostra Query
        assertTrue(delegate.containsKey(queryId));
        PreparedQuery preparedQuery = (PreparedQuery) delegate.get(queryId);
        assertSame(QueryLanguages.LANG_PREPARED_SQL, preparedQuery.getLanguage());
        assertEquals(1, delegate.getMainCacheSize());
        assertEquals(0, delegate.getSoftMapSize());
        assertEquals(0, uncachables.getTotalSize()); //non ci devono essere oggetti uncachable in questo momento

        preparedQueryCache.invalidate(queryId);
        //Avendo invalidato la cache per la nostra query, ci aspettiamo che venga rimossa dalla cache. Di conseguenza
        //quindi ci aspettiamo che la CacheMap non contenga elementi.
        assertEquals(0, delegate.getTotalSize());


    }

    @Test
    public void uncachableQuery() {
        String queryId = "anotherQuery";

        Broker broker = mock(BrokerImpl.class);
        Mockito.when(broker.getMultithreaded()).thenReturn(false);
        Mockito.when(broker.getFetchConfiguration()).thenReturn(mock(FetchConfiguration.class));
        OpenJPAConfiguration openJPAConfiguration = Mockito.mock(OpenJPAConfiguration.class);
        Mockito.when(broker.getConfiguration()).thenReturn(openJPAConfiguration);
        Mockito.when(openJPAConfiguration.getLog()).thenReturn("");

        //Costruiamo una query da registrare (e quindi cachare) con preparedQueryCache
        Query query = new QueryImpl(broker, QueryLanguages.LANG_PREPARED_SQL, mock(StoreQuery.class));

        assertTrue(preparedQueryCache.register(queryId, query, mock(FetchConfiguration.class)));
        PreparedQueryCache.Exclusion exclusion = new PreparedQueryCacheImpl.WeakExclusion("", "This is a test");
        assertNotNull(preparedQueryCache.markUncachable(queryId, exclusion));
        assertFalse(delegate.containsKey(queryId)); //non deve più essere in cache
        assertTrue(uncachables.containsKey(queryId)); //deve essere nella map excluded

    }
  
}

