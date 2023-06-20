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
import org.apache.openjpa.meta.ValueMetaData;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.apache.openjpa.kernel.AttachManagerTest.validBroker;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(Parameterized.class)
public class AttachManagerAttachParamTest {

    /*
     * Test parametrizzati sul metodo attach(..) di AttachManager. Questo semplice test ha lo scopo di controllare che
     * il metodo attach(..) vada a buon fine, con ogni tipo di parametro.
     * Infatti se l'oggetto di cui fare l'attach è null non ci aspettiamo nessun tipo di eccezione. Stessa cosa anche per
     * gli altri parametri, dato che sono valori ammissibili per il metodo, come dimostra anche la chiamata a questo metodo
     * eseguita a riga 105 di AttachManager.
     */

    Object toAttach;
    PersistenceCapable pc;
    OpenJPAStateManager owner;
    ValueMetaData metaData;
    boolean explicit;
    AttachManager attachManager;

    @Parameterized.Parameters
    public static Collection<Object[]> getParams() {

        List<Object[]> retList = new ArrayList<>();

        for (Object toAttach : Arrays.asList(null, "foo")) {
            for (PersistenceCapable pc : Arrays.asList(null, mock(PersistenceCapable.class))) {
                for (OpenJPAStateManager owner : Arrays.asList(null, mock(OpenJPAStateManager.class))) {
                    for (ValueMetaData metaData : Arrays.asList(null, mock(ValueMetaData.class))) {
                        for (boolean explicit : Arrays.asList(true, false)) {
                            retList.add(new Object[] {
                                    toAttach, pc, owner, metaData, explicit
                            });
                        }
                    }
                }
            }
        }

        return retList;
    }


    public AttachManagerAttachParamTest(Object toAttach, PersistenceCapable pc, OpenJPAStateManager owner, ValueMetaData metaData,
                                        boolean explicit) {
        this.toAttach = toAttach;
        this.pc = pc;
        this.owner = owner;
        this.metaData = metaData;
        this.explicit = explicit;

        this.attachManager = new AttachManager(validBroker(2 /*YES*/), true, (op, arg, sm) -> OpCallbacks.ACT_NONE);
    }

    @Test
    public void attachParamTest() {
        try {
            Object attachedCopy = attachManager.attach(toAttach, pc, owner, metaData, explicit);
            assertEquals(toAttach, attachedCopy);

            //controlli

            attachManager.setAttachedCopy(attachedCopy, mock(PersistenceCapable.class));
            assertNotNull(attachManager.getAttachedCopy(attachedCopy));




        } catch (Exception e) {
            e.printStackTrace();
            fail("No exception expected");
        }

    }




}
