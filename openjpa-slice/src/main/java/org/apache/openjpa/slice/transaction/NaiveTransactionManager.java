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
package org.apache.openjpa.slice.transaction;

import java.sql.SQLException;
import java.util.Set;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.apache.openjpa.lib.util.Localizer;
import org.apache.openjpa.slice.jdbc.SliceStoreManager;

/**
 * A fake transaction manager which runs a serial commit or rollback across
 * the enlisted non-XA resources without any two-phase commit protocol. 
 *  
 * @author Pinaki Poddar 
 *
 */
public class NaiveTransactionManager implements TransactionManager {
    private final ThreadLocal<DistributedNaiveTransaction> _txns = 
        new ThreadLocal<DistributedNaiveTransaction>();
    private static final Localizer _loc = 
        Localizer.forPackage(NaiveTransactionManager.class);

    public void begin() throws NotSupportedException, SystemException {
        DistributedNaiveTransaction txn = getTransaction(false);
        Set<SliceStoreManager> slices = txn.getEnlistedResources();
        for (SliceStoreManager slice : slices) {
            slice.getConnection();
            slice.begin();
        }
    }

    public void commit() throws HeuristicMixedException,
            HeuristicRollbackException, IllegalStateException,
            RollbackException, SecurityException, SystemException {
        DistributedNaiveTransaction txn = getTransaction(false);
        Set<SliceStoreManager> slices = txn.getEnlistedResources();
        for (SliceStoreManager slice : slices) {
            try {
                if (!slice.getConnection().getAutoCommit())
                    slice.commit();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public int getStatus() throws SystemException {
        return getTransaction().getStatus();
    }

    public Transaction getTransaction() throws SystemException {
        return getTransaction(false);
    }

    public void resume(Transaction arg0) throws IllegalStateException,
            InvalidTransactionException, SystemException {
        throw new UnsupportedOperationException();
    }

    public void rollback() throws IllegalStateException, SecurityException,
            SystemException {
        DistributedNaiveTransaction txn = getTransaction(false);
        Set<SliceStoreManager> slices = txn.getEnlistedResources();
        for (SliceStoreManager slice : slices) {
            try {
                if (!slice.getConnection().getAutoCommit())
                    slice.rollback();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public void setRollbackOnly() throws IllegalStateException, SystemException {
        getTransaction().setRollbackOnly();
    }

    public void setTransactionTimeout(int arg0) throws SystemException {
        throw new UnsupportedOperationException();
    }

    public Transaction suspend() throws SystemException {
        throw new UnsupportedOperationException();
    }
    
    DistributedNaiveTransaction getTransaction(boolean mustExist) {
        DistributedNaiveTransaction txn = _txns.get();
        if (txn == null) {
            if (mustExist)
                throw new IllegalStateException(_loc.get("no-txn-on-thread",
                        Thread.currentThread().getName()).getMessage());
            txn = new DistributedNaiveTransaction(this);
            _txns.set(txn);
        }
        return txn;
    }


}
