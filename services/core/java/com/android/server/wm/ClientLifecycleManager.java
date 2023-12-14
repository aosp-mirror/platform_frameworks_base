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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

/**
 * Class that is able to combine multiple client lifecycle transition requests and/or callbacks,
 * and execute them as a single transaction.
 *
 * @see ClientTransaction
 */
class ClientLifecycleManager {

    private static final String TAG = "ClientLifecycleManager";

    /** Mapping from client process binder to its pending transaction. */
    @VisibleForTesting
    final ArrayMap<IBinder, ClientTransaction> mPendingTransactions = new ArrayMap<>();

    private WindowManagerService mWms;

    void setWindowManager(@NonNull WindowManagerService wms) {
        mWms = wms;
    }

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
     * Similar to {@link #scheduleTransactionItem}, but is called without WM lock.
     *
     * @see WindowProcessController#setReportedProcState(int)
     */
    void scheduleTransactionItemUnlocked(@NonNull IApplicationThread client,
            @NonNull ClientTransactionItem transactionItem) throws RemoteException {
        // Immediately dispatching to client, and must not access WMS.
        final ClientTransaction clientTransaction = ClientTransaction.obtain(client);
        if (transactionItem.isActivityLifecycleItem()) {
            clientTransaction.setLifecycleStateRequest((ActivityLifecycleItem) transactionItem);
        } else {
            clientTransaction.addCallback(transactionItem);
        }
        scheduleTransaction(clientTransaction);
    }

    /**
     * Schedules a single transaction item, either a callback or a lifecycle request, delivery to
     * client application.
     * @throws RemoteException
     * @see ClientTransactionItem
     */
    void scheduleTransactionItem(@NonNull IApplicationThread client,
            @NonNull ClientTransactionItem transactionItem) throws RemoteException {
        // The behavior is different depending on the flag.
        // When flag is on, we wait until RootWindowContainer#performSurfacePlacementNoTrace to
        // dispatch all pending transactions at once.
        if (Flags.bundleClientTransactionFlag()) {
            final ClientTransaction clientTransaction = getOrCreatePendingTransaction(client);
            clientTransaction.addTransactionItem(transactionItem);

            onClientTransactionItemScheduledLocked(clientTransaction);
        } else {
            // TODO(b/260873529): cleanup after launch.
            final ClientTransaction clientTransaction = ClientTransaction.obtain(client);
            if (transactionItem.isActivityLifecycleItem()) {
                clientTransaction.setLifecycleStateRequest((ActivityLifecycleItem) transactionItem);
            } else {
                clientTransaction.addCallback(transactionItem);
            }
            scheduleTransaction(clientTransaction);
        }
    }

    /**
     * Schedules a single transaction item with a lifecycle request, delivery to client application.
     * @throws RemoteException
     * @see ClientTransactionItem
     */
    void scheduleTransactionAndLifecycleItems(@NonNull IApplicationThread client,
            @NonNull ClientTransactionItem transactionItem,
            @NonNull ActivityLifecycleItem lifecycleItem) throws RemoteException {
        // The behavior is different depending on the flag.
        // When flag is on, we wait until RootWindowContainer#performSurfacePlacementNoTrace to
        // dispatch all pending transactions at once.
        if (Flags.bundleClientTransactionFlag()) {
            final ClientTransaction clientTransaction = getOrCreatePendingTransaction(client);
            clientTransaction.addTransactionItem(transactionItem);
            clientTransaction.addTransactionItem(lifecycleItem);

            onClientTransactionItemScheduledLocked(clientTransaction);
        } else {
            // TODO(b/260873529): cleanup after launch.
            final ClientTransaction clientTransaction = ClientTransaction.obtain(client);
            clientTransaction.addCallback(transactionItem);
            clientTransaction.setLifecycleStateRequest(lifecycleItem);
            scheduleTransaction(clientTransaction);
        }
    }

    /** Executes all the pending transactions. */
    void dispatchPendingTransactions() {
        final int size = mPendingTransactions.size();
        for (int i = 0; i < size; i++) {
            final ClientTransaction transaction = mPendingTransactions.valueAt(i);
            try {
                scheduleTransaction(transaction);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to deliver transaction for " + transaction.getClient());
            }
        }
        mPendingTransactions.clear();
    }

    @NonNull
    private ClientTransaction getOrCreatePendingTransaction(@NonNull IApplicationThread client) {
        final IBinder clientBinder = client.asBinder();
        final ClientTransaction pendingTransaction = mPendingTransactions.get(clientBinder);
        if (pendingTransaction != null) {
            return pendingTransaction;
        }

        // Create new transaction if there is no existing.
        final ClientTransaction transaction = ClientTransaction.obtain(client);
        mPendingTransactions.put(clientBinder, transaction);
        return transaction;
    }

    /** Must only be called with WM lock. */
    private void onClientTransactionItemScheduledLocked(
            @NonNull ClientTransaction clientTransaction) throws RemoteException {
        // TODO(b/260873529): make sure WindowSurfacePlacer#requestTraversal is called before
        // ClientTransaction scheduled when needed.

        if (mWms != null && (mWms.mWindowPlacerLocked.isInLayout()
                || mWms.mWindowPlacerLocked.isTraversalScheduled())) {
            // The pending transactions will be dispatched when
            // RootWindowContainer#performSurfacePlacementNoTrace.
            return;
        }

        // Dispatch the pending transaction immediately.
        mPendingTransactions.remove(clientTransaction.getClient().asBinder());
        scheduleTransaction(clientTransaction);
    }
}
