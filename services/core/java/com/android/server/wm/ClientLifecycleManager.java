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
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.os.Binder;
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
     * Schedules a transaction, which may consist of multiple callbacks and a lifecycle request.
     * @param transaction A sequence of client transaction items.
     * @throws RemoteException
     *
     * @see ClientTransaction
     * @deprecated use {@link #scheduleTransactionItem(IApplicationThread, ClientTransactionItem)}.
     */
    @Deprecated
    void scheduleTransaction(@NonNull ClientTransaction transaction) throws RemoteException {
        final IApplicationThread client = transaction.getClient();
        try {
            transaction.schedule();
        } finally {
            if (!(client instanceof Binder)) {
                // If client is not an instance of Binder - it's a remote call and at this point it
                // is safe to recycle the object. All objects used for local calls will be recycled
                // after the transaction is executed on client in ActivityThread.
                transaction.recycle();
            }
        }
    }

    /**
     * Schedules a single transaction item, either a callback or a lifecycle request, delivery to
     * client application.
     * @throws RemoteException
     * @see ClientTransactionItem
     */
    void scheduleTransactionItem(@NonNull IApplicationThread client,
            @NonNull ClientTransactionItem transactionItem) throws RemoteException {
        final ClientTransaction clientTransaction = ClientTransaction.obtain(client);
        if (transactionItem.isActivityLifecycleItem()) {
            clientTransaction.setLifecycleStateRequest((ActivityLifecycleItem) transactionItem);
        } else {
            clientTransaction.addCallback(transactionItem);
        }
        scheduleTransaction(clientTransaction);
    }

    /**
     * Schedules a single transaction item with a lifecycle request, delivery to client application.
     * @throws RemoteException
     * @see ClientTransactionItem
     */
    void scheduleTransactionAndLifecycleItems(@NonNull IApplicationThread client,
            @NonNull ClientTransactionItem transactionItem,
            @NonNull ActivityLifecycleItem lifecycleItem) throws RemoteException {
        final ClientTransaction clientTransaction = ClientTransaction.obtain(client);
        clientTransaction.addCallback(transactionItem);
        clientTransaction.setLifecycleStateRequest(lifecycleItem);
        scheduleTransaction(clientTransaction);
    }
}
