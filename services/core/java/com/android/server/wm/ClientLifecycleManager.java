/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import android.annotation.NonNull;
import android.app.IApplicationThread;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.ActivityLifecycleItem;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Class that is able to combine multiple client lifecycle transition requests and/or callbacks,
 * and execute them as a single transaction.
 *
 * @see ClientTransaction
 */
class ClientLifecycleManager {
    // TODO(lifecycler): Implement building transactions or global transaction.
    // TODO(lifecycler): Use object pools for transactions and transaction items.

    /**
     * Schedule a transaction, which may consist of multiple callbacks and a lifecycle request.
     * @param transaction A sequence of client transaction items.
     * @throws RemoteException
     *
     * @see ClientTransaction
     */
    void scheduleTransaction(ClientTransaction transaction) throws RemoteException {
        final IApplicationThread client = transaction.getClient();
        transaction.schedule();
        if (!(client instanceof Binder)) {
            // If client is not an instance of Binder - it's a remote call and at this point it is
            // safe to recycle the object. All objects used for local calls will be recycled after
            // the transaction is executed on client in ActivityThread.
            transaction.recycle();
        }
    }

    /**
     * Schedule a single lifecycle request or callback to client activity.
     * @param client Target client.
     * @param activityToken Target activity token.
     * @param stateRequest A request to move target activity to a desired lifecycle state.
     * @throws RemoteException
     *
     * @see ClientTransactionItem
     */
    void scheduleTransaction(@NonNull IApplicationThread client, @NonNull IBinder activityToken,
            @NonNull ActivityLifecycleItem stateRequest) throws RemoteException {
        final ClientTransaction clientTransaction = transactionWithState(client, activityToken,
                stateRequest);
        scheduleTransaction(clientTransaction);
    }

    /**
     * Schedule a single callback delivery to client activity.
     * @param client Target client.
     * @param activityToken Target activity token.
     * @param callback A request to deliver a callback.
     * @throws RemoteException
     *
     * @see ClientTransactionItem
     */
    void scheduleTransaction(@NonNull IApplicationThread client, @NonNull IBinder activityToken,
            @NonNull ClientTransactionItem callback) throws RemoteException {
        final ClientTransaction clientTransaction = transactionWithCallback(client, activityToken,
                callback);
        scheduleTransaction(clientTransaction);
    }

    /**
     * Schedule a single callback delivery to client application.
     * @param client Target client.
     * @param callback A request to deliver a callback.
     * @throws RemoteException
     *
     * @see ClientTransactionItem
     */
    void scheduleTransaction(@NonNull IApplicationThread client,
            @NonNull ClientTransactionItem callback) throws RemoteException {
        final ClientTransaction clientTransaction = transactionWithCallback(client,
                null /* activityToken */, callback);
        scheduleTransaction(clientTransaction);
    }

    /**
     * @return A new instance of {@link ClientTransaction} with a single lifecycle state request.
     *
     * @see ClientTransaction
     * @see ClientTransactionItem
     */
    private static ClientTransaction transactionWithState(@NonNull IApplicationThread client,
            @NonNull IBinder activityToken, @NonNull ActivityLifecycleItem stateRequest) {
        final ClientTransaction clientTransaction = ClientTransaction.obtain(client, activityToken);
        clientTransaction.setLifecycleStateRequest(stateRequest);
        return clientTransaction;
    }

    /**
     * @return A new instance of {@link ClientTransaction} with a single callback invocation.
     *
     * @see ClientTransaction
     * @see ClientTransactionItem
     */
    private static ClientTransaction transactionWithCallback(@NonNull IApplicationThread client,
            IBinder activityToken, @NonNull ClientTransactionItem callback) {
        final ClientTransaction clientTransaction = ClientTransaction.obtain(client, activityToken);
        clientTransaction.addCallback(callback);
        return clientTransaction;
    }
}
