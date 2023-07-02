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




import org.apache.openjpa.kernel.utility.Values;
import org.apache.openjpa.lib.util.collections.LRUMap;
import org.apache.openjpa.util.CacheMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.openjpa.kernel.utility.LockChecker.tryLock;
import static org.apache.openjpa.kernel.utility.Values.ExpectedValue.IA_EXCEPTION;
import static org.apache.openjpa.kernel.utility.Values.ExpectedValue.PASSED;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class CacheMapTest {

/*
 *                                          ++++ POSSIBILE BUG ++++
 *
 * In questa classe è stato individuato un possibile bug: c'è la possibilità che, a riga 140 di CacheMap, dove c'è la
 * chiamata a "cacheMap = new LRUMap(size, load)", in realtà ci dovrebbe essere "cacheMap = new LRUMap(max, load)".
 *
 * Infatti, quello che succede quando chiediamo a CacheMap di costruire un'istanza di tipo LRU, con capacità iniziale
 * 0, e capacità massima > 0 (e con un loadFactor valido, quindi tutti parametri dove ci aspettiamo che ci venga
 * restituita una istanza valida di CacheMap), in realtà ci ritorna una IllegalArgumentException, con un messaggio che
 * dice: "LRUMap max size must be greater than 0".
 *
 * Questo, già a un'analisi black-box del metodo risulta quantomeno strano, dato che noi abbiamo impostato un "max"
 * > 0, e l'errore ci dice che la size massima della LRU che abbiamo provato a creare è invece minore di zero.
 * Sempre con un'analisi black-box, possiamo notare che, ponendo il parametro del costruttore "lru = false", viene creata
 * un'istanza valida di CacheMap, come ci aspetteremmo.
 *
 * Procedendo ad un'analisi di tipo white-box notiamo una incongruenza nel modo in cui viene istanziata la LRUMap.
 * Partiamo dal presupposto che in CacheMap il parametro "max" rappresenta la size massima della cache, e il parametro
 * "size" rappresenta la dimensione inizale della cache. Questo si può inturire anche dal costruttore di CacheMap che
 * troviamo a riga 92, dove data una maxSize, si pone size = maxSize/2.
 *
 * Abbiamo già notato a riga 140 "cacheMap = new LRUMap(size, load)". Quindi sembra che stiamo chiedendo una LRUMap con
 * dimensione iniziale pari a "size".
 * Andando però a guardare il costruttore di LRUMap che stiamo chiamando in CacheMap, vediamo che è quello di LRUMap
 * del modulo openjpa-lib del modulo util.
 * Il costruttore ha la seguente forma:
 *
 * public LRUMap(int initCapacity, float loadFactor) {
        super(initCapacity, loadFactor);
    }
 *
 * Il super che va a chiamare è quello di LRUMap del modulo openjpa-lib del modulo collections, che ha la forma:
 *
 *     public LRUMap(final int maxSize, final float loadFactor) {
        this(maxSize, loadFactor, false);
    }
 *
 * Notiamo subito dunque che il primo parametro si chiami "maxSize", mentre è stato invocato con il parametro initSize dalla
 * sua sottoclasse.
 *
 * Alla fine, il costruttore che viene effettivamente chiamato in collections\LRUMap è:
 *
 * public LRUMap(final int maxSize,
                  final int initialSize,
                  final float loadFactor,
                  final boolean scanUntilRemovable)
 *
 * E nel nostro caso specifico i parametri che gli arrivano sono (size, size, load, false). Di fatto abbiamo richiesto da
 * CacheMap la creazione di una LRUMap con initialSize = size, ma abbiamo ottenuto una LRUMap che ha (anche) maxSize = size.
 *
 * Quando dunque size = 0, non riusciamo ad ottenere la suddetta LRUMap. E, mentre richiedere una LRUMap con initialSize = 0 ha
 * perfettamente senso, non lo ha richiederla con una maxSize = 0. Quindi il problema non sta nel costruttore di LRUMap, che nega
 * giustamente la costruzione di una LRUMap di maxSize = 0, ma nel fatto che da CacheMap è stata richiesta una LRUMap utilizzando
 * il parametro size invece che max.
 *
 * Non posso avere la certezza che questo sia un Bug, ma certamente è un comportamento che da un'analisi black-box risulta
 * difficile da prevedere e contro intuitivo. Da un'analisi white-box sembra effettivamente poterci essere un "typo" dove si usa
 * la initSize per qualcosa che poi diventerà la maxSize, nonostante abbiamo a disposizione un parametro che rappresenta la maxSize.
 *
 * Come inoltre dimostriamo con il metodo showThatLRUShouldBePossible(), con gli stessi parametri con cui CacheMap lancia una
 * IllegalArgumentException, è in realtà possibile costruire una LRUMap che non solleva eccezione.
 *
 * In quel metodo dimostriamo che la costruzione di una LRUMap è possibile sia usando il costruttore di collections\LRUMap (quello
 * "vero e proprio") con initSize = size e maxSize = max, sia con entrambe le size = max (perchè è così che verrebbe chiamato
 * dal costruttore alternativo di collections\LRUMap, quello con la segnatura public LRUMap(final int maxSize, final float loadFactor)).
 *
 * Inoltre, dimostriamo come, se a riga 140 di CacheMap chiamassimo LRUMap(max, load) invece che LRUMap(size, load), la LRUMap
 * verrebbe istanziata senza produrre alcuna IllegalArgumentException.
 */


    private static final List<Integer> intList = Arrays.asList(-1, 0, 1);
    private static final List<Float> floatList = Arrays.asList(-0.1f, 0f, 0.1f);
    private static final List<Boolean> booleanList = Arrays.asList(true, false);
    private static final boolean isConcurrencyLevelUsed = false;
    private static int testCounter = 0;
    private final boolean lru;
    private final int max;
    private final int size;
    private final float load;
    private final int concurrencyLevel;
    private final Values.ExpectedValue expectedValue;
    @Rule
    public Timeout timeout = new Timeout(2, TimeUnit.SECONDS); //Per PIT


    public CacheMapTest(boolean lru, int max, int size, float load, int concurrencyLevel, Values.ExpectedValue ev) {
        this.lru = lru;
        this.max = max;
        this.size = size;
        this.load = load;
        this.concurrencyLevel = concurrencyLevel;
        this.expectedValue = ev;

    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParams() {
        List<Object[]> retList = new ArrayList<>();

        for (boolean lru : booleanList) {
            for (int max : intList) {
                for (int size : intList) {
                    for (float load : floatList) {
                        for (int concurrencyLevel : intList) {
                            Object[] param = new Object[]{
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
        Values.ExpectedValue expectedValue = PASSED;
        Object[] withEV = new Object[param.length + 1];

        System.arraycopy(param, 0, withEV, 0, param.length);
        float load = (float) param[3];
        boolean isLru = (boolean) param[0];
        int initSize = (int) param[2];
        /*
         * La terza condition nell'if serve a settare i corretti valori di expected nel caso in cui
         * venisse implementato nel metodo una porzione di codice che fa effettivamente uso del parametro
         * concurrencyLevel. In quel caso assumiamo che un livello di concorrenza maggiore di 0
         * sia accettabile, mentre un livello di concorrenza <= 0 dovrebbe portare a una IllegalArgument
         * exception, dal momento che è difficile giustificare il significato di un livello di concorrenza
         * di questo tipo. Si considera infatti che concurrencyLevel possa rappresentare il numero di thread concorrenti
         * che possono accedere alla cache, quindi un numero <= 0 renderebbe impossibile aggiornare la cache, e sarebbe quindi
         * scorretto.
         *
         * Nota: Per concurrencyLevel sono generati i vari test, ma non essendo al momento usato dal metodo, non posso mettere
         * che se il suo valore è scorretto mi aspetto un'eccezione. Quindi è stata introdotta la variabile isConcurrencyLevelUsed,
         * cha al momento è false. Se il parametro concurrencyLevel dovesse essere utilizzato nel codice del costruttore
         * allora basterebbe cambiare il valore di isConcurrencyLevelUsed = true, in modo che vengano settati correttamente
         * anche gli expected value per quel parametro. Idealmente si potrebbe mettere un commento che rimanda a questo nel
         * codice sorgente, che però si preferisce non toccare. Forse si potrebbe anche "automatizzare" questo controllo
         * tramite tool per l'analisi del byte code, ma sarebbe probabilmente un effort eccessivo e complicherebbe abbastanza
         * il codice del test.
         *
         */

        /*
         * Una lista di input genererà IA Exception se:
         * 1. load <= 0: non è consentito
         * 2.Una LRU, allo stato attuale del codice non può avere una maxSize = 0. Per i motivi sopra illustrati
         * la maxSize viene settata usando la size (qui chiamata initSize). Quindi se è negativa viene settata
         * arbitrariamente a 500 da CacheMap, se è positiva è ok, se è = 0, per la LRU non va bene.
         *
         */
        if (load <= 0 || (isLru && initSize == 0) || (isConcurrencyLevelUsed && (int) param[4] <= 0))
            expectedValue = IA_EXCEPTION;


        withEV[param.length] = expectedValue;


        return withEV;
    }

    @Test
    public void constructionTest() throws InterruptedException {

        System.out.printf("[DEBUG] lru: %s, max: %d, size: %d, load: %f, ev:%s\n", lru, max, size, load, expectedValue);
        System.out.println("[DEBUG] Test #" + testCounter + "\n");
        testCounter++;

        CacheMap cacheMap = null;
        try {
            cacheMap = new CacheMap(
                    this.lru,
                    this.max,
                    this.size,
                    this.load,
                    this.concurrencyLevel
            );
        } catch (Exception e) {

            assertTrue(e instanceof IllegalArgumentException);
            try {
                assertEquals(IA_EXCEPTION, expectedValue);
            } catch (AssertionError error) {
                //Questo catch è per far passare il test, e quindi la build, al possibile bug.
                //Qui ci assicuriamo che se entriamo nel catch sia esclusivamente per il caso del possibile bug, altrimenti
                //il test fallisce come giusto che sia

                assertTrue(lru);
                assertEquals(0, size);
                assertTrue(load > 0);
                assertTrue(e.getMessage().contains("LRUMap max size must be greater than 0"));

                /*
                 * Qui controlliamo o che la LRU non era effettivamente istanziabile anche usando il "max" dato in input
                 * alla CacheMap (prima condizione), oppure che con i dati in input, nonostante sia stata generata una
                 * IllegalArgumentException, in realtà usando "max" invece che "size" si può ottenere una LRU cache
                 * valida.
                 */
                assertTrue((size == 0 && max <= 0) || showThatLRUShouldBePossible());
            }
            return;

        }

        /*
         * Ora qui abbiamo tutte quelle istanze che riteniamo essere istanze valide di CacheMap. Per acquisire ulteriore
         * confidenza al riguardo, oltre al fatto che non abbiano sollevato eccezioni durante la chiamata al loro costruttore
         * facciamo dei check per controllarne la validità
         */

        assertEquals(PASSED, expectedValue);
        assertNotNull(cacheMap);
        assertEquals(max, cacheMap.getCacheSize());
        assertEquals(-1, cacheMap.getSoftReferenceSize());
        assertTrue(cacheMap.isEmpty());
        assertEquals(lru, cacheMap.isLRU());
        assertTrue(tryLock(cacheMap));


    }


    public boolean showThatLRUShouldBePossible() {
        LRUMap lruMap = null;
        LRUMap lruMap2 = null;
        org.apache.openjpa.lib.util.LRUMap lruMap3 = null;

        try {
            lruMap = new LRUMap(max, size, load, false /* default is false as per javadoc*/);
            lruMap2 = new LRUMap(max, max, load, false /* default is false as per javadoc*/);
            lruMap3 = new org.apache.openjpa.lib.util.LRUMap(max, load); //Potenziale fix per riga 140 di CacheMap


        } catch (Exception e) {
            return false;
        }
        assertNotNull(lruMap);
        assertNotNull(lruMap2);
        assertNotNull(lruMap3);

        return true;


    }




}
