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

import org.apache.openjpa.enhance.PersistenceCapable;
import org.apache.openjpa.meta.ClassMetaData;
import org.apache.openjpa.meta.FieldMetaData;
import org.apache.openjpa.meta.ValueMetaData;
import org.apache.openjpa.util.CallbackException;
import org.apache.openjpa.util.ImplHelper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.apache.openjpa.kernel.AttachManagerTest.validBroker;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AttachManagerAttachTest {

    private final int NO = 1;
    private final int YES = 2;
    BrokerImpl broker;
    boolean copyNew;
    OpCallbacks callback;
    AttachManager attachManager;

    List<Object> objList = Arrays.asList("foo", "bar");

    /*
     * I seguenti metodi di test sono volti a testare in maniera approfondita i vari branch condizionali dei metodi
     * - public Object attach(Object pc)
     * - Object attach(Object toAttach, PersistenceCapable into, OpenJPAStateManager owner, ValueMetaData ownerMeta, boolean explicit)
     */


    @Before
    public void setUp() {
        //Settiamo i valori di default per l'istanza. Se alcuni test hanno esigenze specifiche li sovrascriveranno prima
        //di eseguire il loro test
        broker = validBroker(YES);
        copyNew = true;
        callback = (op, arg, sm) -> {
            System.out.println("...Mocking the processing argument method...");
            return OpCallbacks.ACT_RUN;
        };

    }

    @Test
    public void attachTestWithCBException() {
        attachManager = new AttachManager(broker, copyNew, callback);

        //testiamo il caso in cui all'interno della attach() invocata nel metodo attach(Object obj) si generi una
        //CallbackException

        String noMod = "Not_modified";
        Object attachedList = "Not_modified";
        try (MockedStatic<ImplHelper> mockedStatic = Mockito.mockStatic(ImplHelper.class)) {

            //Settiamo il mock per la AttachStrategy
            AttachStrategy mockAttachStrategy = mock(AttachStrategy.class);
            Mockito.when(mockAttachStrategy.attach(attachManager, this.objList, null, null, null, null, true))
                    .thenThrow(new CallbackException(new RuntimeException())); //Settiamo il lancio dell'eccezione nel metodo

            PersistenceCapable persistenceCapableMocked = mock(PersistenceCapable.class);
            Mockito.when(persistenceCapableMocked.pcGetStateManager()).thenReturn(null);
            Mockito.when(persistenceCapableMocked.pcGetDetachedState()).thenReturn(mockAttachStrategy);

            mockedStatic.when(() -> ImplHelper.toPersistenceCapable(this.objList, broker.getConfiguration()))
                    .thenReturn(persistenceCapableMocked);
            mockedStatic.when(() -> ImplHelper.getManagedInstance(any())).thenCallRealMethod();

            //A questo punto iniziamo il test del meccanismo di attach
            attachManager.fireBeforeAttach(this.objList, null);
            attachedList = attachManager.attach(this.objList);

            //vogliamo un'eccezione
            fail("I was expecting a CallbackException, but it wasn't thrown");


        } catch (CallbackException nullPointerException) {
            assertEquals(noMod, attachedList); //il "return null" non deve essere chiamato perchè si deve generare l'eccezione
            //Quindi attachedList dovrà ancora avere il valore con cui è stato inizializzato
        }
    }

    @Test
    public void multiParamAttachNullTest() {
        attachManager = new AttachManager(broker, copyNew, callback);

        Object nullAttached = attachManager.attach(null, mock(PersistenceCapable.class),
                mock(OpenJPAStateManager.class), mock(ValueMetaData.class), true);
        assertNull(nullAttached);
    }

    @Test
    public void multiParamAttachAlreadyAttached() {


        Object firstAttached = null;
        Object secondAttached = null;
        try (MockedStatic<ImplHelper> mockedStatic = Mockito.mockStatic(ImplHelper.class)) {
            StateManagerImpl stateManagerMock = Mockito.mock(StateManagerImpl.class);
            Mockito.when(broker.getStateManagerImpl(any(), anyBoolean())).thenReturn(stateManagerMock);
            Mockito.when(stateManagerMock.isNew()).thenReturn(true);
            attachManager = new AttachManager(broker, copyNew, callback);

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
            firstAttached = attachManager.attach(this.objList);
            PersistenceCapable myPC = mock(PersistenceCapable.class);
            attachManager.setAttachedCopy(firstAttached, myPC);
            //controlliamo che la procedura di attach abbia restituito l'istanza attached
            assertEquals(this.objList, firstAttached);

            /*
             * Ora siamo nella situazione in cui stiamo provando a fare l'attach di un'istanza sui cui in realtà
             * l'attach è già stata chiamata.
             * Quello che ci aspettiamo è che, essendo objList già associato a firstAttached, se andiamo a richiedere
             * all'AttachManager la attachedCopy di secondAttach, questa ritorni null, dato che quando abbiamo richiesto
             * l'attach di objList a secondAttach, objList era già attached a firstAttached.
             *
             */

            secondAttached = attachManager.attach(objList, mock(PersistenceCapable.class), mock(OpenJPAStateManager.class),
                    mock(ValueMetaData.class), true);


        } catch (RuntimeException nullPointerException) {
            nullPointerException.printStackTrace();
            fail("No exception expected");
        }

        assertNotNull(firstAttached);
        assertNotNull(secondAttached);
        assertNull(attachManager.getAttachedCopy(secondAttached));
    }

    @Test
    public void callBackExceptionNoFailFast() {
        broker = validBroker(NO);

        attachManager = new AttachManager(broker, copyNew, callback);

        Object attachedList = "init";
        try (MockedStatic<ImplHelper> mockedStatic = Mockito.mockStatic(ImplHelper.class)) {

            //Settiamo il mock per la AttachStrategy
            AttachStrategy mockAttachStrategy = mock(AttachStrategy.class);
            Mockito.when(mockAttachStrategy.attach(attachManager, this.objList, null, null, null, null, true))
                    .thenThrow(new CallbackException(new RuntimeException())); //Settiamo il lancio dell'eccezione nel metodo

            PersistenceCapable persistenceCapableMocked = mock(PersistenceCapable.class);
            Mockito.when(persistenceCapableMocked.pcGetStateManager()).thenReturn(null);
            Mockito.when(persistenceCapableMocked.pcGetDetachedState()).thenReturn(mockAttachStrategy);

            mockedStatic.when(() -> ImplHelper.toPersistenceCapable(this.objList, broker.getConfiguration()))
                    .thenReturn(persistenceCapableMocked);
            mockedStatic.when(() -> ImplHelper.getManagedInstance(any())).thenCallRealMethod();

            //A questo punto iniziamo il test del meccanismo di attach
            attachManager.fireBeforeAttach(this.objList, null);
            attachedList = attachManager.attach(this.objList);

            /*
             * Notiamo come in questo caso, a differenza di prima, sebbene internamente il nostro mock lanci un'eccezione
             * di tipo CallbackException, per questo Test abbiamo settato il failFast a "NO", dunque l'eccezione non viene
             * rilanciata verso l'esterno proprio alla luce di questo diverso setting della nostra istanza di AttachManager.
             * Dunque il comportamento aspettato è che la nostra attachedList, inizializzata con una generica stringa "init",
             * dopo la (fallita) invocazione di attach(..) abbia ora il valore di null, e non vogliamo che ci venga propagata
             * verso l'esterno nessuna CallbackException (o in generale nessuna RuntimeException)
             */
            assertNull(attachedList);



        } catch (RuntimeException nullPointerException) { //CallbackException extends RuntimeException
            fail("I was NOT expecting a CallbackException, but it was thrown");

        }






    }

    @Test
    public void attachHandlingCascade() {
        OpCallbacks callback2 = (op, arg, sm) -> {
            System.out.println("...Mocking the processing argument method...");
            return OpCallbacks.ACT_CASCADE;
        };

        //Setup dei mock aggiuntivi per questo test
        BrokerImpl mockedBroker = validBroker(YES); //Non tocchiamo il broker degli altri test
        StateManagerImpl stateManager = Mockito.mock(StateManagerImpl.class);
        ClassMetaData classMetaData = Mockito.mock(ClassMetaData.class);

        Mockito.when(mockedBroker.getStateManagerImpl(objList, true)).thenReturn(stateManager);
        Mockito.when(stateManager.getMetaData()).thenReturn(classMetaData);
        Mockito.when(classMetaData.getDefinedFields()).thenReturn(new FieldMetaData[]{});


        Object attached = new Object();
        try {
            attachManager = new AttachManager(mockedBroker, copyNew, callback2);
            attached = attachManager.attach(objList, mock(PersistenceCapable.class), mock(OpenJPAStateManager.class),
                    mock(ValueMetaData.class), true);
        } catch (Exception e) {
            //failFast attivo per avere il throw verso l'esterno di eventuali eccezioni a Runtime
            fail("No exception expected");
        }
        assertEquals(objList, attached);


    }

    @Test
    public void visitedNodeContainsTest() throws NoSuchFieldException, IllegalAccessException {
        //Test realizzato dopo report badua/jacoco

        OpCallbacks callback2 = (op, arg, sm) -> {
            System.out.println("...Mocking the processing argument method...");
            return OpCallbacks.ACT_CASCADE;
        };


        String toAttach = "toAttach";
        StateManagerImpl stateManager = mock(StateManagerImpl.class);
        BrokerImpl anotherBroker = validBroker(2); //dovendo aggiungere dei mock usiamo un broker diverso per
        //non influenzare gli altri test
        when(anotherBroker.getStateManagerImpl(toAttach, true)).thenReturn(stateManager);
        when(anotherBroker.getStateManager(toAttach)).thenReturn(stateManager);

        //facciamo in modo che risulti che toAttach sia già stato visitato durante un'operazione di attach
        Field visited = AttachManager.class.getDeclaredField("_visitedNodes");
        visited.setAccessible(true);
        AttachManager manipulatedAttachManager = new AttachManager(anotherBroker, copyNew, callback2);
        Collection<StateManagerImpl> newVisitedList = new ArrayList<>();
        newVisitedList.add(anotherBroker.getStateManagerImpl(toAttach, true));
        visited.set(manipulatedAttachManager, newVisitedList);

        //ora controlliamo che ci venga restituito ciò che ci si aspetta dal branch che stiamo esplorando
        assertEquals(toAttach, manipulatedAttachManager.attach(toAttach));



    }

}
