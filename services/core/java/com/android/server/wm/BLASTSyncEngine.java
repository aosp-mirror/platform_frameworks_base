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

import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Set;

/**
 * Utility class for collecting WindowContainers that will merge transactions.
 * For example to use to synchronously resize all the children of a window container
 *   1. Open a new sync set, and pass the listener that will be invoked
 *        int id startSyncSet(TransactionReadyListener)
 *      the returned ID will be eventually passed to the TransactionReadyListener in combination
 *      with a set of WindowContainers that are ready, meaning onTransactionReady was called for
 *      those WindowContainers. You also use it to refer to the operation in future steps.
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
 *      to redraw and to deliver a buffer to {@link WindowState#finishDrawing}.
 *      Once all this drawing is complete the WindowContainer that's ready will be added to the
 *      set of ready WindowContainers. When the final onTransactionReady is called, it will merge
 *      the transactions of the all the WindowContainers and will be delivered to the
 *      TransactionReadyListener
 */
class BLASTSyncEngine {
    private static final String TAG = "BLASTSyncEngine";

    interface TransactionReadyListener {
        void onTransactionReady(int mSyncId, Set<WindowContainer> windowContainersReady);
    };

    // Holds state associated with a single synchronous set of operations.
    class SyncState implements TransactionReadyListener {
        int mSyncId;
        int mRemainingTransactions;
        TransactionReadyListener mListener;
        boolean mReady = false;
        Set<WindowContainer> mWindowContainersReady = new ArraySet<>();

        private void tryFinish() {
            if (mRemainingTransactions == 0 && mReady) {
                mListener.onTransactionReady(mSyncId, mWindowContainersReady);
                mPendingSyncs.remove(mSyncId);
            }
        }

        public void onTransactionReady(int mSyncId, Set<WindowContainer> windowContainersReady) {
            mRemainingTransactions--;
            mWindowContainersReady.addAll(windowContainersReady);
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
            mRemainingTransactions = 0;
        }
    };

    private int mNextSyncId = 0;

    private final ArrayMap<Integer, SyncState> mPendingSyncs = new ArrayMap<>();

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
