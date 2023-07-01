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


import org.apache.openjpa.util.CacheMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/*
 * Lo scopo di questa classe di test è quello di verificare e acquisire confidenza sul comportamento dei meccanismi di
 * read e write lock della CacheMap. Per farlo useremo due istanze valida di CacheMap, una di tipologia LRU e una non
 * LRU. Il resto dei parametri verrà settato a valori ammissibili di default.
 */

@RunWith(Parameterized.class)
public class CacheMapLockTest {

    private static final boolean lru = true;
    private static final boolean notLru = false;
    private static final int max = 1000;
    private static final int size = max/2;
    private static final float load = 0.75f;
    private static final int concLevel = 0; //è un don't care in realtà, andrebbe bene anche un valore negativo allo stato attuale della classe
    //ma in vista di un'eventuale modifica all'implementazione del costruttore di CacheMap, dove si farà uso anche di questo parametro,
    //scegliamo un valore che dovrebbe poter essere valido, in modo da mantenere anche in questo caso la validità del test

    private final CacheMap cacheMap;

    @Rule
    public Timeout timeout = new Timeout(5, TimeUnit.SECONDS); //Per PIT


    @Parameterized.Parameters
    public static Collection<CacheMap> buildMaps() {

        CacheMap lruMap = new CacheMap(lru, max, size, load, concLevel);
        CacheMap nonLruMap = new CacheMap(notLru, max, size, load, concLevel);

        assertNotNull(lruMap);
        assertNotNull(nonLruMap);
        lruMap.put("key", "value");
        nonLruMap.put("key", "value");

        List<CacheMap> mapList = new ArrayList<>();
        mapList.add(lruMap);
        mapList.add(nonLruMap);

        return mapList;

    }

    public CacheMapLockTest(CacheMap map) {
        this.cacheMap = map;
    }

    @Test
    public void readLockTest() throws InterruptedException {
        //Test su lettura concorrente
        //Il test avrà successo solo se (come ci si aspetta) sarà possibile eseguire letture concorrenti sulla CacheMap.
        //Le letture da parte di readThread infatti avvengono mentre il main thread del test ha anch'esso un readLock settato.
        //Se la lettura concorrente non fosse possibile avremmo un deadlock (da cui il timeout del test).
        this.cacheMap.readLock();
        Thread readThread = new Thread(() -> {
            this.cacheMap.readLock();
            assertTrue(this.cacheMap.containsKey("key"));
            assertTrue(this.cacheMap.containsValue("value"));
            assertFalse(this.cacheMap.containsValue("foo"));
            this.cacheMap.readUnlock();

        });
        readThread.start();
        readThread.join(3000);
        this.cacheMap.readUnlock();


    }

    @Test
    public void writeLockTest() throws InterruptedException {
        /*
         * In questo test, a differenza di quello sopra, prendiamo dal main thread un writeLock, e dal readThread un readLock.
         * Quello che ci aspettiamo è che il thread non possa prendere un readLock finchè è attivo il writeLock del main thread.
         * Per assicurarci la sincronizzazione di tutte le operazioni del test, oltre alle join con un tempo massimo di attesa
         * utilizziamo anche un CountDownLatch. Inoltre facciamo leggere al thread una entry che al momento del suo lancio
         * non è presente, e viene aggiunta solo in seguito dal main thread.
         */
        String toWrite = "fromWriteLock";
        this.cacheMap.writeLock();
        assertFalse(this.cacheMap.containsValue(toWrite)); //inizialmente non ci deve essere
        CountDownLatch latch = new CountDownLatch(1);
        Thread readThread = new Thread(() -> {
            this.cacheMap.readLock();
            latch.countDown();
            assertTrue(this.cacheMap.containsValue(toWrite));
            this.cacheMap.readUnlock();

        });
        readThread.start();
        readThread.join(700);
        assertTrue(readThread.isAlive());
        assertEquals(1, latch.getCount());
        this.cacheMap.put("anotherKey", toWrite);
        assertTrue(this.cacheMap.containsValue(toWrite)); //verifico l'immissione
        this.cacheMap.writeUnlock();
        readThread.join(700);
        assertFalse(readThread.isAlive());
        assertEquals(0, latch.getCount());

    }

    @Test
    public void orderedAccessTest() throws InterruptedException {
        /*
         * Vogliamo controllare che, qualora ci fossero più richieste di lock attive per la CacheMap, venga rispettato
         * l'ordine con cui il lock viene concesso in base all'ordine di arrivo delle richieste
         */
        this.cacheMap.writeLock(); //innanzitutto "blocco" dal main thread la cache map
        CountDownLatch firstThreadLatch = new CountDownLatch(1);
        CountDownLatch secondThreadLatch = new CountDownLatch(1);

        Thread firstThread = new Thread(() -> {
            firstThreadLatch.countDown();
            this.cacheMap.writeLock();
            this.cacheMap.put("first", "thread");
            assertFalse(this.cacheMap.containsKey("second")); //Il secondo thread NON deve già aver scritto
            this.cacheMap.writeUnlock();

        });

        Thread secondThread = new Thread(() -> {
            secondThreadLatch.countDown();
            this.cacheMap.writeLock();
            this.cacheMap.put("second", "thread");
            assertTrue(this.cacheMap.containsKey("first")); //Il primo thread DEVE già aver scritto
            this.cacheMap.writeUnlock();

        });

        firstThread.start();
        assertTrue(firstThreadLatch.await(3, TimeUnit.SECONDS));
        //Se siamo arrivati qui, siamo sicuri che il primo thread sia in attesa del lock, quindi lanciamo il secondo
        secondThread.start();
        assertTrue(secondThreadLatch.await(3, TimeUnit.SECONDS));
        //Ora anche il secondo thread è in attesa del lock
        //A questo punto rilasciamo il lock
        this.cacheMap.writeUnlock();
        //Aspettiamo i due thread. Se non vengono eseguiti nell'ordine corretto saranno loro stessi a far fallire
        //il test. Per essere sicuri di evitare deadlock mettiamo comunque un tempo limite di esecuzione al test.
        firstThread.join();
        secondThread.join();



    }


}
