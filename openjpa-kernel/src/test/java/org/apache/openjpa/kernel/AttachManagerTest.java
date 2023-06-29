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

import org.apache.openjpa.conf.OpenJPAConfiguration;
import org.apache.openjpa.enhance.PersistenceCapable;
import org.apache.openjpa.kernel.utility.Values;
import org.apache.openjpa.meta.MetaDataDefaults;
import org.apache.openjpa.meta.MetaDataFactory;
import org.apache.openjpa.meta.MetaDataRepository;
import org.apache.openjpa.util.ImplHelper;
import org.apache.openjpa.util.ProxyManagerImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.*;

import static org.apache.openjpa.kernel.utility.Values.ExpectedValue.*;
import static org.apache.openjpa.kernel.utility.Values.Parameter.INVALID;
import static org.apache.openjpa.kernel.utility.Values.Parameter.VALID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;

@RunWith(Parameterized.class)
public class AttachManagerTest {

    static BrokerImpl broker;
    boolean bool;
    OpCallbacks op;
    Values.ExpectedValue expectedValue;
    List<?> objList;
    private static int debugTestCounter = 0;

    public AttachManagerTest(Object brokerParam, boolean newCopy, Object callBack, List<?> toAttachList) {
        //setup del broker
        if (brokerParam == null) broker = null;
        else {
            if (brokerParam == VALID) {
                broker = validBroker(debugTestCounter % 2 == 0 ? 1 : 2); //alterniamo il failFast tra true e false, test uni-dimensionale
            } else {
                invalidBroker();
            }
        }
        //setup newCopy che è immediato
        this.bool = newCopy;
        //setup della callback
        if (callBack == null) this.op = null;
        else {
            if (callBack == VALID) {
                this.op = (op, arg, sm) -> {
                    System.out.println("...Mocking the processing argument method...");
                    return OpCallbacks.ACT_RUN;
                };
            } else {

                this.op = (op, arg, sm) -> {
                    throw new RuntimeException();
                };
            }
        }

        this.objList = toAttachList;
        this.expectedValue = evaluateExpectedValue(brokerParam, callBack);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParams() {
        List<Object[]> returnList = new ArrayList<>();

        for (Object broker : Arrays.asList(null, VALID, INVALID)) {
            for (boolean newCopy : Arrays.asList(true, false)) {
                for (Object callBack : Arrays.asList(null, VALID, INVALID)) {
                    for (List<?> toAttachList : Arrays.asList(Collections.singletonList("foo"), Collections.singletonList(null),
                            Collections.singletonList(""), Arrays.asList("foo", "bar"), null)) {
                        returnList.add(new Object[]{
                                broker, newCopy, callBack, toAttachList
                        });
                    }
                }
            }
        }

        return refineParametrizedList(returnList);

    }

    private static Collection<Object[]> refineParametrizedList(List<Object[]> inputList) {
        /*
         * Lo scopo di questo metodo è rimuovere dalla lista che verrà usata per istanzaire i test parametrizzati
         * tutte quelle combinazioni di parametri dove ce n'è più di uno scorretto. Questo perchè in quel caso il
         * test rischierebbe di essere ambiguo, non sapendo chi dei parametri porterebbe per primo a una failure. Per saperlo
         * sarebbe necessaria un'approfondita analisi white box del SUT, che preferiamo evitare.
         *
         * Es.: methodToTest(null, invalidInstance), dove invalidInstance fa il throw di una RuntimeException non appena
         * viene invocato un suo metodo, restituisce NullPointerException oppure RuntimeException?
         */
        List<Object[]> outputList = new ArrayList<>(inputList);

        for (Object[] paramList : inputList) {
            int broker = 0;
            int callBack = 2;
            if (bothNonValid(paramList[broker], paramList[callBack])) {
                outputList.remove(paramList);
            }
        }

        return outputList;


    }

    private static boolean bothNonValid(Object broker, Object cb) {
        return (broker == null && (cb == null || cb == INVALID)) || cb == null && broker == INVALID;
    }

    private Values.ExpectedValue evaluateExpectedValue(Object brokerValue, Object callBackValue) {
        if (brokerValue == null) return NP_EXCEPTION;
        if (brokerValue == INVALID) return RUNTIME_EXCEPTION;

        if (callBackValue == null) return NP_EXCEPTION;
        if (callBackValue == INVALID) return RUNTIME_EXCEPTION;

        return PASSED;
    }

    private void invalidBroker() {
        broker = Mockito.mock(BrokerImpl.class);
        //Il broker invalido lancia una generica eccezione unchecked durante l'esecuzione
        Mockito.when(broker.getConfiguration()).thenThrow(new RuntimeException());
        Mockito.when(broker.fireLifecycleEvent(any(), any(), any(), Mockito.anyInt())).thenThrow(new RuntimeException());


    }

    static BrokerImpl validBroker(int failFast) {
        BrokerImpl myBroker = Mockito.mock(BrokerImpl.class);
        MetaDataRepository metaDataRepository = Mockito.mock(MetaDataRepository.class);
        MetaDataFactory metaDataFactory = Mockito.mock(MetaDataFactory.class);
        MetaDataDefaults metaDataDefaults = Mockito.mock(MetaDataDefaults.class);
        OpenJPAConfiguration configuration = Mockito.mock(OpenJPAConfiguration.class);

        Mockito.when(myBroker.getConfiguration()).thenReturn(configuration);
        Mockito.when(configuration.getMetaDataRepositoryInstance()).thenReturn(metaDataRepository);
        Mockito.when(configuration.getProxyManagerInstance()).thenReturn(new ProxyManagerImpl());
        Mockito.when(metaDataRepository.getMetaDataFactory()).thenReturn(metaDataFactory);
        Mockito.when(metaDataFactory.getDefaults()).thenReturn(metaDataDefaults);
        //facciamo "ruotare" il valore di ritorno così da coprire più branch condizionali
        Mockito.when(metaDataDefaults.getCallbackMode()).thenReturn(failFast);

        Mockito.when(myBroker.fireLifecycleEvent(any(), any(), any(), Mockito.anyInt())).thenReturn(false);

        return myBroker;

    }


    @Test
    public void attachManagerInstanceTest() {
        System.out.printf("[DEBUG] - Broker = %s, boolean = %b, op = %s, attachList = %s, expected = %s\n", broker, bool, op, objList, expectedValue);
        System.out.printf("[DEBUG] - Test #%d\n\n", debugTestCounter);
        debugTestCounter++;
        AttachManager attachManager;
        try {
            attachManager = new AttachManager(
                    broker,
                    this.bool,
                    this.op

            );
        } catch (NullPointerException nullPointerException) {
            assertEquals(this.expectedValue, NP_EXCEPTION);
            return;
        } catch (RuntimeException runtimeException) {
            assertEquals(this.expectedValue, RUNTIME_EXCEPTION);
            return;
        }

        assertEquals(broker, attachManager.getBroker());
        assertEquals(bool, attachManager.getCopyNew());
        assertEquals(op, attachManager.getBehavior());


        /*
         * Se il costruttore non ha generato un'eccezione e i parametri dell'oggetto creato corrispondono a quanto
         * ci aspettiamo, ci aspettiamo di trovarci in presenza di un'istanza valida di AttachManager. Per acquisire
         * confidenza sulla sua validità proviamo a testare il suo comportamento rispetto al meccanismo di attach della
         * classe AttachManager.
         * Ci aspettiamo che il metodo attach(...) ci restituisca un'istanza uguale a quella che gli diamo in input,
         * che rappresenta la versione attached del nostro oggetto.
         * Ci interessa infatti testare che il nostro AttachManager lanci la procedura di attach, e che quindi poi
         * l'oggetto di cui abbiamo richiesto l'attach lo risulti davvero dopo che chiamiamo "setAttachCopy(...).
         * La procedura di attach viene implementata dall'oggetto AttachStrategy. Essendo questo uno UnitTest, mockiamo
         * il comportamento della classe AttachStrategy, simulando la riuscita dell'attach da parte sua.
         */
        Object attachedList;
            try (MockedStatic<ImplHelper> mockedStatic = Mockito.mockStatic(ImplHelper.class)) {

                //Settiamo il mock per la AttachStrategy
                AttachStrategy mockAttachStrategy = Mockito.mock(AttachStrategy.class);
                Mockito.when(mockAttachStrategy.attach(attachManager, this.objList, null, null, null, null, true))
                               .thenReturn(this.objList);

                PersistenceCapable persistenceCapableMocked = Mockito.mock(PersistenceCapable.class);
                Mockito.when(persistenceCapableMocked.pcGetStateManager()).thenReturn(null);
                Mockito.when(persistenceCapableMocked.pcGetDetachedState()).thenReturn(mockAttachStrategy);

                mockedStatic.when(() -> ImplHelper.toPersistenceCapable(this.objList, broker.getConfiguration()))
                        .thenReturn(persistenceCapableMocked);
                mockedStatic.when(() -> ImplHelper.getManagedInstance(any())).thenCallRealMethod();

                //A questo punto iniziamo il test del meccanismo di attach
                attachManager.fireBeforeAttach(this.objList, null);
                attachedList = attachManager.attach(this.objList);
                //controlliamo che la procedura di attach abbia restituito l'istanza attached
                assertEquals(this.objList, attachedList);


        } catch (NullPointerException nullPointerException) {
            assertEquals(NP_EXCEPTION, this.expectedValue);
            return;
        } catch (RuntimeException runtimeException) {
            assertEquals(RUNTIME_EXCEPTION, this.expectedValue);
            return;
        }

        //Se l'attach è andato a buon fine, ora testiamo il comportamento della nostra istanza valida, per acquisire
        //confidenza sul suo comportamento con la gestione delle attached copy

        PersistenceCapable persistenceCapable = Mockito.mock(PersistenceCapable.class);
        Object attachedCopy = null;
        try {
            attachManager.setAttachedCopy(attachedList, persistenceCapable);
            attachedCopy = attachManager.getAttachedCopy(this.objList);
        } catch (Exception e) {
            e.printStackTrace();
            fail("No exception expected here");
        }

        //deve risultare che la persistenza a cui è associato this.objList sia persistenceCapable, come richiesto
        assertEquals(persistenceCapable, attachedCopy);


    }



}

