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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class CacheMapMaxDimensionTest {

    private final static int size = 3;
    private final static int max = size/2;
    private static CacheMapExt cacheMap;

    /*
     * Breve test per acquisire confidenza sul fatto che venga gestita correttamente la dimensione massima della CacheMap
     * nel caso in cui viene specificata una dimensione massima più piccola di quella iniziale. Quello che ci aspettiamo
     * è che venga rispettata comunque la dimensione massima della cacheMap, a discapito di quella iniziale che è più
     * grande. Questo perchè il vincolo sulla dimensione massima della CacheMap, è più rilevante rispetto a quello sulla
     * sua dimensione iniziale.
     *
     * Per realizzare il testi ci siamo serviti di una estensione della classe CacheMap, a cui abbiamo semplicemente aggiunto
     * dei getter per la size delle varie mappe di cache, che normalmente non sono accessibili.
     *
     * Il test è parametrizzato per avere due run, una con una cache non LRU e una con una cache LRU.
     */

    private static class CacheMapExt extends CacheMap {
        public CacheMapExt(boolean lru) {
            super(lru, max, size, 0.75f, 0);
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
    }

    @Parameterized.Parameters
    public static Collection<Boolean> getParams() {
        return Arrays.asList(true, false);
    }

    public CacheMapMaxDimensionTest(boolean b) {
        cacheMap = new CacheMapExt(b);
    }

    @Test
    public  void testSize() {

        cacheMap.put(1,1);
        assertEquals(0, cacheMap.getSoftMapSize()); //la entry deve aver preso il posto disponibile nella cacheMap

        //Da qui in poi invece ci si aspetta che la entry di cui si fa la put venga messo in cacheMap, e gli altri finiscano
        //nella softMap. Ci aspettiamo anche che la pinnedMap sia sempre vuota dal momento che non eseguiamo nessuna
        //operazione di pin sulla cacheMap.
        cacheMap.put(2,2);
        System.out.printf("Main cache size: %d, soft map size: %d, total size:%d\n",
                cacheMap.getMainCacheSize(), cacheMap.getSoftMapSize(), cacheMap.getTotalSize());
        assertTrue(cacheMap.getMainCacheSize() == max && cacheMap.getSoftMapSize() == cacheMap.getTotalSize() - cacheMap.getMainCacheSize());

        cacheMap.put(3,3);
        System.out.printf("Main cache size: %d, soft map size: %d, total size:%d\n",
                cacheMap.getMainCacheSize(), cacheMap.getSoftMapSize(), cacheMap.getTotalSize());
        assertTrue(cacheMap.getMainCacheSize() == max && cacheMap.getSoftMapSize() == cacheMap.getTotalSize() - cacheMap.getMainCacheSize());

        cacheMap.put(4,4);
        System.out.printf("Main cache size: %d, soft map size: %d, total size:%d\n",
                cacheMap.getMainCacheSize(), cacheMap.getSoftMapSize(), cacheMap.getTotalSize());
        assertTrue(cacheMap.getMainCacheSize() == max && cacheMap.getSoftMapSize() == cacheMap.getTotalSize() - cacheMap.getMainCacheSize());


    }

}
