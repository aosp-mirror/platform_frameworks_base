/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.ITaskOrganizer;
import android.view.SurfaceControl;

import java.util.HashMap;

/**
 * Utility class for collecting and merging transactions from various sources asynchronously.
 * For example to use to synchronously resize all the children of a window container
 *   1. Open a new sync set, and pass the listener that will be invoked
 *        int id startSyncSet(TransactionReadyListener)
 *      the returned ID will be eventually passed to the TransactionReadyListener in combination
 *      with the prepared transaction. You also use it to refer to the operation in future steps.
 *   2. Ask each child to participate:
 *       addToSyncSet(int id, WindowContainer wc)
 *      if the child thinks it will be affected by a configuration change (a.k.a. has a visible
 *      window in its sub hierarchy, then we will increment a counter of expected callbacks
 *      At this point the containers hierarchy will redirect pendingTransaction and sub hierarchy
 *      updates in to the sync engine.
 *   3. Apply your configuration changes to the window containers.
 *   4. Tell the engine that the sync set is ready
 *       setReady(int id)
 *   5. If there were no sub windows anywhere in the hierarchy to wait on, then
 *      transactionReady is immediately invoked, otherwise all the windows are poked
 *      to redraw and to deliver a buffer to WMS#finishDrawing.
 *      Once all this drawing is complete the combined transaction of all the buffers
 *      and pending transaction hierarchy changes will be delivered to the TransactionReadyListener
 */
class BLASTSyncEngine {
    private static final String TAG = "BLASTSyncEngine";

    interface TransactionReadyListener {
        void transactionReady(int mSyncId, SurfaceControl.Transaction mergedTransaction);
    };

    // Holds state associated with a single synchronous set of operations.
    class SyncState implements TransactionReadyListener {
        int mSyncId;
        SurfaceControl.Transaction mMergedTransaction;
        int mRemainingTransactions;
        TransactionReadyListener mListener;
        boolean mReady = false;

        private void tryFinish() {
            if (mRemainingTransactions == 0 && mReady) {
                mListener.transactionReady(mSyncId, mMergedTransaction);
                mPendingSyncs.remove(mSyncId);
            }
        }

        public void transactionReady(int mSyncId, SurfaceControl.Transaction mergedTransaction) {
            mRemainingTransactions--;
            mMergedTransaction.merge(mergedTransaction);
            tryFinish();
        }

        void setReady() {
            mReady = true;
            tryFinish();
        }

        boolean addToSync(WindowContainer wc) {
            if (wc.prepareForSync(this, mSyncId)) {
                mRemainingTransactions++;
                return true;
            }
            return false;
        }

        SyncState(TransactionReadyListener l, int id) {
            mListener = l;
            mSyncId = id;
            mMergedTransaction = new SurfaceControl.Transaction();
            mRemainingTransactions = 0;
        }
    };

    int mNextSyncId = 0;

    final HashMap<Integer, SyncState> mPendingSyncs = new HashMap();

    BLASTSyncEngine() {
    }

    int startSyncSet(TransactionReadyListener listener) {
        final int id = mNextSyncId++;
        final SyncState s = new SyncState(listener, id);
        mPendingSyncs.put(id, s);
        return id;
    }

    boolean addToSyncSet(int id, WindowContainer wc) {
        final SyncState st = mPendingSyncs.get(id);
        return st.addToSync(wc);
    }

    void setReady(int id) {
        final SyncState st = mPendingSyncs.get(id);
        st.setReady();
    }
}
