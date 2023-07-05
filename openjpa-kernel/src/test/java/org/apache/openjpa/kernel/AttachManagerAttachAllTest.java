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
import org.apache.openjpa.lib.util.Localizer;
import org.apache.openjpa.meta.ClassMetaData;
import org.apache.openjpa.meta.FieldMetaData;
import org.apache.openjpa.util.CallbackException;
import org.apache.openjpa.util.OptimisticException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.apache.openjpa.kernel.AttachManagerTest.validBroker;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AttachManagerAttachAllTest {

    BrokerImpl broker;
    boolean copyNew;
    OpCallbacks callback;
    private static int wasAfterAttachInvoked = 0;


    @Before
    public void setUp() {
        broker = validBroker(2 /*failfast = yes*/);
        copyNew = true;
        callback = (op, arg, sm) -> OpCallbacks.ACT_CASCADE;
        wasAfterAttachInvoked = 0;
    }

    @Test
    public void nullInstancesTest() {
        Collection instances = null;
        AttachManager attachManager = new AttachManager(broker, copyNew, callback);

        try {
            attachManager.fireBeforeAttach(instances, mock(ClassMetaData.class));
            attachManager.attachAll(instances);
        } catch (RuntimeException npe) {
           assertTrue(npe instanceof NullPointerException);
           return;
        }
        fail("There should have been an exception");
    }


    @Test
    public void emptyCollectionTest() {
        Collection instances = Collections.emptyList();
        AttachManager attachManager = new AttachManager(broker, copyNew, callback);
        Object[] retList = null;

        try {
            attachManager.fireBeforeAttach(instances, mock(ClassMetaData.class));
           retList = attachManager.attachAll(instances);
        } catch (RuntimeException e) {
            fail("No exception expected");
        }
        assertNotNull(retList);
        assertEquals(0, retList.length); //empty input empty output

    }

    @Test
    public void validCollectionTest() {
        Collection instances = Arrays.asList("foo", "bar", "test");

        BrokerImpl mockedBroker = validBroker(2); //Non tocchiamo il broker degli altri test, dovendo mockare anche altri metodi
        StateManagerImpl stateManager = mock(StateManagerImpl.class);
        ClassMetaData classMetaData = mock(ClassMetaData.class);

        Mockito.when(mockedBroker.getStateManagerImpl(any(), anyBoolean())).thenReturn(stateManager);
        Mockito.when(stateManager.getMetaData()).thenReturn(classMetaData);
        Mockito.when(classMetaData.getDefinedFields()).thenReturn(new FieldMetaData[]{});

        AttachManager attachManager = new AttachManager(mockedBroker, copyNew, callback);
        Object[] retList = null;

        try {

            attachManager.fireBeforeAttach(instances, mock(ClassMetaData.class));
            retList = attachManager.attachAll(instances);
        } catch (RuntimeException e) {
            e.printStackTrace();
            fail("No exception expected");
        }
        assertNotNull(retList);
        assertEquals(instances.size(), retList.length); //check sulla size

        int i = 0;
        for (Object elem : instances) {
            assertEquals(elem, retList[i]); //check singole istanze
            i++;
        }
    }

    @Test
    public void attachAllWithAllExceptionTest() {

        Collection instances = Arrays.asList("foo", "bar", "test");

        BrokerImpl mockedBroker = validBroker(2); //Non tocchiamo il broker degli altri test, dovendo mockare anche altri metodi
        StateManagerImpl stateManager = mock(StateManagerImpl.class);
        ClassMetaData classMetaData = mock(ClassMetaData.class);

        Mockito.when(mockedBroker.getStateManagerImpl(any(), anyBoolean())).thenReturn(stateManager);
        Mockito.when(stateManager.getMetaData()).thenReturn(classMetaData);
        Mockito.when(classMetaData.getDefinedFields()).thenThrow(new MyOptException());

        AttachManager attachManager = new AttachManager(mockedBroker, copyNew, callback);
        Object[] retList = null;

        try {

            attachManager.fireBeforeAttach(instances, mock(ClassMetaData.class));
            retList = attachManager.attachAll(instances);
        } catch (RuntimeException e) {
            assertTrue(e instanceof OptimisticException);
            assertEquals(instances.size(), ((OptimisticException) e).getNestedThrowables().length);
            assertNull(retList); //tutte le istanze generano exception quando fanno la attach, dunque mi aspetto un null come return
            return;
        }
        fail("(Multiple) exceptions expected");

    }

    @Test
    public void attachAllWithSomeExceptionTest() {

        Collection instances = Arrays.asList("foo", "bar", "test");

        BrokerImpl mockedBroker = validBroker(1); //Non tocchiamo il broker degli altri test, dovendo mockare anche altri metodi
        StateManagerImpl throwingStateManager = mock(StateManagerImpl.class);
        StateManagerImpl simpleStateManager = mock(StateManagerImpl.class);
        ClassMetaData throwingClassMetaData = mock(ClassMetaData.class);
        ClassMetaData simpleClassMetaData = mock(ClassMetaData.class);

        /*
         * In questo test abbiamo due diverse reazioni all'attach da parte delle istanze. Tramite i mock facciamo in modo
         * che "foo" e "test" abbiano una attach con successo, mentre quando si prova a fare l'attach di "bar", viene lanciata
         * un'eccezione.
         */

        Mockito.when(mockedBroker.getStateManagerImpl("bar", true)).thenReturn(throwingStateManager);
        Mockito.when(mockedBroker.getStateManagerImpl("foo", true)).thenReturn(simpleStateManager);
        Mockito.when(mockedBroker.getStateManagerImpl("test", true)).thenReturn(simpleStateManager);

        Mockito.when(throwingStateManager.getMetaData()).thenReturn(throwingClassMetaData);
        Mockito.when(throwingClassMetaData.getDefinedFields()).thenThrow(new MyOptException());

        Mockito.when(simpleStateManager.getMetaData()).thenReturn(simpleClassMetaData);
        Mockito.when(simpleClassMetaData.getDefinedFields()).thenReturn(new FieldMetaData[]{});

        AttachManager attachManager = new AttachManager(mockedBroker, copyNew, callback);
        Object[] retList = null;
        attachManager.fireBeforeAttach(instances, mock(ClassMetaData.class));


        try {

            retList = attachManager.attachAll(instances);

        } catch (RuntimeException e) {
            assertTrue(e instanceof OptimisticException);
            /*
             * Qui voglio verificare che l'eccezione sia stata lanciata solo per uno delle istanze di cui ho richiesto
             * l'attach, per farlo mi assicuro quindi che l'eccezione che mi arriva non ne abbia di annidate (perchè così
             * vuol dire che c'è stata una sola eccezione, e non c'è stato bisogno di annidarne altre)
             */
            assertEquals(0, ((OptimisticException) e).getNestedThrowables().length); //solo un'istanza genera l'eccezione
        }

        assertNull(retList);


    }

    @Test
    public void attachAllCallbackExcepTest() {
        Collection instances = Arrays.asList("foo", "bar", "test");

        BrokerImpl mockedBroker = validBroker(2); //Non tocchiamo il broker degli altri test, dovendo mockare anche altri metodi
        StateManagerImpl stateManager = mock(StateManagerImpl.class);
        ClassMetaData classMetaData = mock(ClassMetaData.class);

        Mockito.when(mockedBroker.getStateManagerImpl(any(), anyBoolean())).thenReturn(stateManager);
        Mockito.when(stateManager.getMetaData()).thenReturn(classMetaData);
        Mockito.when(classMetaData.getDefinedFields())
                .thenThrow(new CallbackException(new IllegalArgumentException())); //CB exception settata dal mock

        AttachManager attachManager = new AttachManager(mockedBroker, copyNew, callback);
        Object[] retList = null;
        attachManager.fireBeforeAttach(instances, mock(ClassMetaData.class));

        try {

            retList = attachManager.attachAll(instances);
        } catch (RuntimeException e) {
            assertFalse(e instanceof OptimisticException);
            assertTrue(e instanceof CallbackException);
            //è una callback excpetion e non una optimistic, dunque non voglio che ci sia un'eccezione annidata per ogni istanza della lista
            assertFalse(((CallbackException) e).getNestedThrowables().length > 1);
            assertNull(retList); //tutte le istanze generano exception quando fanno la attach, dunque mi aspetto un null come return
            return;
        }
        fail("(Multiple) exceptions expected");

    }


    @Test
    public void chackInvokefterException() throws NoSuchFieldException, IllegalAccessException {
        //Test realizzato dopo report PIT

        List<String> toAttach = new ArrayList<>();
        toAttach.add("foo");
        toAttach.add("bar");


        OpCallbacks callback2 = (op, arg, sm) -> {
            System.out.println("...Mocking the processing argument method...");
            return OpCallbacks.ACT_CASCADE;
        };

        BrokerImpl anotherBroker = validBroker(2);
        StateManagerImpl stateManager = mock(StateManagerImpl.class);


        when(anotherBroker.getStateManagerImpl(any(), anyBoolean())).thenReturn(stateManager);
        when(anotherBroker.getStateManager(toAttach)).thenReturn(stateManager);
        when(stateManager.isNew()).thenReturn(false);


        AttachManager manipulatedAttachManager = new AttachManager(anotherBroker, copyNew, callback2);
        manipulatedAttachManager.setAttachedCopy(toAttach, mock(PersistenceCapable.class)); //_attached ora ha size = 1


        /*
         * A questo punto abbiamo un attachManager che risulta gestire già l'ogetto toAttach. Ora vogliamo invocare
         * la attachAll, fare in modo che venga invocata la invokeAfterAttac(), e controllare che venga effettuata la
         * chiamata al postAttach di toAttach.
         */


        when(anotherBroker.fireLifecycleEvent(any(), any(), any(), anyInt())).thenAnswer(invocation -> {

            wasAfterAttachInvoked += 1;

            return true;
        });
        Object[] ret = null;
        try {
            ret = manipulatedAttachManager.attachAll(Collections.emptyList());

        } catch (Exception e) {
            fail("No exception expected here");
        }
        assertEquals(1, wasAfterAttachInvoked);
        assertEquals(0, ret.length);

    }







    private static class MyOptException extends OptimisticException {

        public MyOptException(Localizer.Message msg) {
            super(Localizer.forPackage(AttachManager.class).get("some message"));
        }

        public MyOptException() {
            this(null);
        }

        @Override
        public Object getFailedObject() {
            return "fooString";
        }
    }



}
