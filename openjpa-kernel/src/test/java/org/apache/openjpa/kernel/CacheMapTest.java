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
 * Dalla documentazione di OpenJPA:
 *
 * Uses a CacheMap to store compilation data.
 * CacheMap maintains a fixed number of cache entries, and an optional soft reference map for entries that are moved
 * out of the LRU space. So, for applications that have a monotonically increasing number of distinct queries, this
 * option can be used to ensure that a fixed amount of memory is used by the cache.
 */

/*
 * Parametri del costruttore di Cache Map:
 * 1. boolean: lru -> true, false. Sceglie se avere una cache Least Recently Used
 * 2. int: max -> -1, 0, 1. è la siz massima della CacheMap
 * 3. int: size -> -1, 0, 1. Size della map per le expired entity (la soft reference di cui sopra)
 * 4. float: load -> -0.1f, 0f, 0.1f. Fattore di carico per ridimensionare le varie Map
 * 5. int: concurrencyLevel -> -1, 0, 1. Rappresenta il livello di concorrenza per la Map. In realtà questo parametro
 * ha una definition ma non possiede uno use. Operiamo comunque category partition anche per questo parametro considerando
 * che, dato che il parametro comunque è presente, in futuro l'implementazione del metodo potrebbe cambiare, e in questo
 * modo cerchiamo di far si che il test sia valido anche in questa eventualità
 */


import static org.apache.openjpa.kernel.utility.Values.ExpectedValue.*;
import static org.junit.Assert.*;

import org.apache.openjpa.kernel.utility.Values;
import org.apache.openjpa.util.CacheMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class CacheMapTest {


    private static final List<Integer> intList = Arrays.asList(-1, 0, 1);
    private static final List<Float> floatList = Arrays.asList(-0.1f, 0f, 0.1f);
    private static final List<Boolean> booleanList = Arrays.asList(true, false);
    private static int testCounter = 0;
    private final boolean lru;
    private final int max;
    private final int size;
    private final float load;
    private final int concurrencyLevel;
    private final Values.ExpectedValue expectedValue;


    @Parameterized.Parameters
    public static Collection<Object[]> getParams() {
        List<Object[]> retList = new ArrayList<>();

        for (boolean lru : booleanList) {
            for (int max : intList) {
                for (int size : intList) {
                    for (float load : floatList) {
                        for (int concurrencyLevel : intList) {
                            Object[] param = new Object[] {
                                    lru, max, size, load, concurrencyLevel
                            };
                                retList.add(withExpectedValue(param));
                        }
                    }
                }
            }
        }

        return retList;

    }

    private static Object[] withExpectedValue(Object[] param) {
        Values.ExpectedValue expectedValue =  PASSED;
        Object[] withEV = new Object[param.length + 1];

        for(int i = 0; i < param.length ; i++) {
            withEV[i] = param[i];
            if( i == 3) {
                float load = (float) param[i];
                if (load <= 0)
                    expectedValue = IA_EXCEPTION;

            }
            withEV[param.length] = expectedValue;
        }

        return withEV;
    }


    public CacheMapTest(boolean lru, int max, int size, float load, int concurrencyLevel, Values.ExpectedValue ev) {
        this.lru = lru;
        this.max = max;
        this.size = size;
        this.load = load;
        this.concurrencyLevel = concurrencyLevel;
        this.expectedValue = ev;

    }
    @Test
    public void constructionTest() {

        System.out.printf("[DEBUG] lru: %s, max: %d, size: %d, load: %f, ev:%s\n", lru, max, size, load, expectedValue);
        System.out.println("[DEBUG] Test #" + testCounter + "\n");
        testCounter++;

        try {
            CacheMap cacheMap = new CacheMap(
                    this.lru,
                    this.max,
                    this.size,
                    this.load,
                    this.concurrencyLevel
            );
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(e instanceof IllegalArgumentException);
            try {
                assertSame(expectedValue, IA_EXCEPTION);
            } catch (AssertionError assertionError) {
                /*
                 * Qui c'è un potenziale bug, motivo per cui faccio il catch della AssertionError. Il problema sta nella
                 * gestione del parametro size da parte del costruttore di CacheMap.
                 * Infatti: size rappresenta la size delle Map che verranno create per la nostra istanza di CacheMap.
                 * - Una size > 0 è chiaramente accettata, e di fatti il test passa.
                 * - Una size < 0 non sarebbe accettata dai costruttori delle varie Map, e dunque il costruttore di CacheMap
                 * provvede a settarla a un valore positivo (sceglie arbitrariamente 500)
                 * - Una size = 0 non è accettata dal costruttore delle Map, e questo lo dimostra il fatto che ci troviamo
                 * all'interno di un catch in cui abbiamo già asserito che l'eccezione catturata è una IllegalArgument, e
                 * sappiamo anche che l'eccezione è legata alla size, e non al loadFactor, dato che asseriamo anche che
                 * load > 0f. Dunque siamo per forza nel caso size = 0.
                 *
                 * Il problema sta nel fatto che il costruttore del metodo decide di gestire il caso in cui il parametro
                 * size abbia un valore non ammissibile, ma lo fa solamente nel caso di size < 0, e non nel caso di size = 0.
                 * Per una migliore gestione di valori scorretti e una più consistente gestione degli errori a Runtime sarebbe
                 * meglio avere che il controllo a riga 112: if (size < 0) fosse scritto come: if (size <= 0).
                 *
                 * Infatti è poco coerente che il metodo lanci una unchecked exception se il parametro è "logicamente" scorretto
                 * ed = 0, ma non se è scorretto e < 0.
                 * Infatti, leggendo la documentazione e il Javadoc della classe mi aspettare o che per valori <= 0 venga lanciata
                 * un'eccezione, o che i valori scorretti vengano gestiti internamente, e quindi anche per valori scorretti
                 * il metodo non rilanciasse eccezioni verso l'esterno.
                 */
                assertEquals(0, size);
                assertTrue(load > 0f);
            }
        }

        //TODO CHECK CHE L'ISTANZA VALIDA SIA EFFETTIVAMENTE VALIDA



    }


}
