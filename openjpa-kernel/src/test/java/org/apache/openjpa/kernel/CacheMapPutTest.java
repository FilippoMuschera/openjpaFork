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

package org.apache.openjpa.kernel;

/*
 * Lo scopo di questa classe di test è quello di verificare e acquisire confidenza sul comportamento dei di put e get
 * della CacheMap. Per farlo useremo due istanze valida di CacheMap, una di tipologia LRU e una non
 * LRU. Il resto dei parametri verrà settato a valori ammissibili di default.
 */

import org.apache.openjpa.util.CacheMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class CacheMapPutTest {

    private static final boolean lru = true;
    private static final boolean notLru = false;
    private static final int max = 1;
    private static final int size = 1;
    private static final float load = 0.75f;
    private static final int concLevel = 0; //è un don't care in realtà, andrebbe bene anche un valore negativo allo stato attuale della classe
    //ma in vista di un'eventuale modifica all'implementazione del costruttore di CacheMap, dove si farà uso anche di questo parametro,
    //scegliamo un valore che dovrebbe poter essere valido, in modo da mantenere anche in questo caso la validità del test

    @Rule
    public Timeout timeout = new Timeout(3, TimeUnit.SECONDS); //Per PIT

    private final CacheMap cacheMap;


    @Parameterized.Parameters
    public static Collection<CacheMap> buildMaps() {

        CacheMap lruMap = new CacheMap(lru, max, size, load, concLevel) {
            public int getSoftMapActualSize() { //per reflection
                readLock();
                try {
                    return this.softMap.size();
                } finally {
                    readUnlock();
                }
            }

            public boolean pinnedMapContainsValue(Object o) { //per reflection
                readLock();
                try {
                    return this.pinnedMap.containsValue(o);
                } finally {
                    readUnlock();
                }
            }

            public boolean isValInMainMap(Object o) {
                readLock();
                try {
                    return this.cacheMap.containsValue(o);
                } finally {
                    readUnlock();
                }
            }

            public boolean isInSoftMap(Object o) {
                readLock();
                try {
                    return this.softMap.containsValue(o);
                } finally {
                    readUnlock();
                }
            }
        };
        CacheMap nonLruMap = new CacheMap(notLru, max, size, load, concLevel) {
            public int getSoftMapActualSize() { //per reflection
                readLock();
                try {
                    return this.softMap.size();
                } finally {
                    readUnlock();
                }
            }

            public boolean pinnedMapContainsValue(Object o) { //per reflection
                readLock();
                try {
                    return this.pinnedMap.containsValue(o);
                } finally {
                    readUnlock();
                }
            }
            public boolean isValInMainMap(Object o) {
                readLock();
                try {
                    return this.cacheMap.containsValue(o);
                } finally {
                    readUnlock();
                }
            }

            public boolean isInSoftMap(Object o) {
                readLock();
                try {
                    return this.softMap.containsValue(o);
                } finally {
                    readUnlock();
                }
            }
        };

        assertNotNull(lruMap);
        assertNotNull(nonLruMap);

        List<CacheMap> mapList = new ArrayList<>();
        mapList.add(lruMap);
        mapList.add(nonLruMap);

        return mapList;

    }

    public CacheMapPutTest(CacheMap map) {
        this.cacheMap = map;
    }

    @Before
    public void cleanMap() {
        this.cacheMap.clear();
        assertEquals(0, this.cacheMap.entrySet().size());
    }

    @Test
    public void putTest() {
        Object key = "key";
        Object value = "value";

        //Ritorna il valore che aveva precedentemente quella entry. Al primo tentativo voglio null perchè la cacheMap è vuota
        //Al secondo voglio un notNull perchè ho già aggiunto la stessa entry ala riga prima, quindi mi aspetto che verrà ritornata quella
        assertNull(this.cacheMap.put(key, value));
        assertNotNull(this.cacheMap.put(key, value));

        assertTrue(this.cacheMap.containsKey(key));
        assertTrue(this.cacheMap.containsValue(value));

        this.cacheMap.remove(key);
        assertTrue(this.cacheMap.isEmpty());
    }

    @Test
    public void softMapTest() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Object key = "key";
        Object value = "value";
        Object secondKey = 2;
        Object secondValue = 3;

        this.cacheMap.put(key, value);
        /*
         * In questo test sfruttiamo la reflection per invocare un metodo, da noi definito, che ci restituisce la size
         * reale della softMap. Quello definito in CacheMap infatti ritorna solamente la maxSize della softMap.
         * Lo facciamo perchè quello che vogliamo controllare in questo test è il passaggio delle entry dalla cacheMap
         * principale a quella soft, quando quella principale è piena.
         * Per come è realizzata la CacheMap, ha una size massima di 1 entry. Dunque ci aspettiamo che quando andiamo a
         * inserire una entry nella CacheMap, questa venga inserita nella cache "principale", e quindi vogliamo vedere
         * che la softMap sia vuota. A questo punto inseriamo un altro elemento nella CacheMap, che però avendo size massima
         * pari a 1, dovrà fare posto alla nuova entry, spostando quella vecchia nella softMap.
         * Usiamo quindi la reflection e il metodo da noi definito per controllare questo aspetto.
         */
        assertEquals(0, this.cacheMap.getClass().getMethod("getSoftMapActualSize").invoke(this.cacheMap));
        this.cacheMap.put(secondKey, secondValue);

        assertTrue(this.cacheMap.containsKey(secondKey));
        assertTrue(this.cacheMap.containsValue(secondValue));
        assertEquals(1, this.cacheMap.getCacheSize());
        assertEquals(1, this.cacheMap.getClass().getMethod("getSoftMapActualSize").invoke(this.cacheMap));

        //Ora proviamo a rimettere la prima entry nella cache principale, e ci aspettiamo che la seconda finisca nella softMap
        this.cacheMap.put(key, value);
        assertTrue((Boolean) this.cacheMap.getClass().getMethod("isValInMainMap", Object.class).invoke(this.cacheMap, value));
        assertTrue((Boolean) this.cacheMap.getClass().getMethod("isInSoftMap", Object.class).invoke(this.cacheMap, secondValue));


    }

    @Test
    public void pinnedTest() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Object key = "key";
        Object value = "value";

        for (Object secondValue : Arrays.asList(null, 3)){ //proviamo con 2 diversi tipi di oggetto. Entrambe devono andare a buon fine
            this.cacheMap.put(key, value);
            this.cacheMap.pin(key);
            assertTrue(this.cacheMap.getPinnedKeys().contains(key));

            //Ora proviamo ad aggiornare nella CacheMap un oggetto che è pinned
            this.cacheMap.put(key, secondValue);

            //Ora controlliamo che il valore aggiornato sia nella cacheMap, e sia pinned
            assertTrue(this.cacheMap.containsValue(secondValue));
            assertFalse(this.cacheMap.containsValue(value)); //deve essere stato sovrascritto

            //usiamo la reflection per assicurarci che la entry pinned sia stata aggiornata
            assertTrue((Boolean) this.cacheMap.getClass().getMethod("pinnedMapContainsValue", Object.class).invoke(this.cacheMap, secondValue));

            //ora lo rimuoviamo
            this.cacheMap.remove(key);
            assertFalse(this.cacheMap.containsKey(key)); //ora è stato rimosso, non deve essere ancora in cache

            //Avendo pinnato la sua key, ci aspettiamo che qual ora il suo valore venga aggiornato, lo ritroviamo come pinned
            this.cacheMap.put(key, value);
            assertTrue(this.cacheMap.getPinnedKeys().contains(key));

            //testiamo ura l'unpin
            this.cacheMap.unpin(key);
            assertTrue(this.cacheMap.getPinnedKeys().isEmpty());


        }



    }

    @Test
    public void noHardRefsCache() {
        this.cacheMap.setCacheSize(0);
        //Non posso inserire oggetti in una Map di (max) size 0
        assertNull(this.cacheMap.put("", ""));
        this.cacheMap.setCacheSize(size); //resetta la size della cacheMap per gli altri test
    }

    @Test
    public void testGet() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        //La get sposta la entry nella cacheMap principale se si trovava nella softCache.
        String key = "key";
        String value = "value";
        assertFalse(this.cacheMap.containsKey(key) || this.cacheMap.containsValue(value)); //deve essere vuota
        assertNull(this.cacheMap.get(key));

        this.cacheMap.put(key, value);
        this.cacheMap.put(0, 0); //ora (key, value) aggiunti alla riga sopra sono in softCache
        assertTrue((Boolean) this.cacheMap.getClass().getMethod("isInSoftMap", Object.class).invoke(this.cacheMap, value));

        //Eseguendo la get ritorna in cache principale
        assertNotNull(this.cacheMap.get(key));
        //Quindi in soft cache ci sarà l'altra entry
        assertTrue((Boolean) this.cacheMap.getClass().getMethod("isInSoftMap", Object.class).invoke(this.cacheMap, 0));

        //ora get dalla cache principale per estendere la coverage
        assertNotNull(this.cacheMap.get(key));

    }
}
