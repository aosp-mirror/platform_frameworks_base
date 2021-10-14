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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SYNC_ENGINE;

import android.annotation.NonNull;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;

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
 *      Once all this drawing is complete, all the transactions will be merged and delivered
 *      to TransactionReadyListener.
 *
 * This works primarily by setting-up state and then watching/waiting for the registered subtrees
 * to enter into a "finished" state (either by receiving drawn content or by disappearing). This
 * checks the subtrees during surface-placement.
 */
class BLASTSyncEngine {
    private static final String TAG = "BLASTSyncEngine";

    interface TransactionReadyListener {
        void onTransactionReady(int mSyncId, SurfaceControl.Transaction transaction);
    }

    /**
     * Holds state associated with a single synchronous set of operations.
     */
    class SyncGroup {
        final int mSyncId;
        final TransactionReadyListener mListener;
        boolean mReady = false;
        final ArraySet<WindowContainer> mRootMembers = new ArraySet<>();
        private SurfaceControl.Transaction mOrphanTransaction = null;

        private SyncGroup(TransactionReadyListener listener, int id) {
            mSyncId = id;
            mListener = listener;
        }

        /**
         * Gets a transaction to dump orphaned operations into. Orphaned operations are operations
         * that were on the mSyncTransactions of "root" subtrees which have been removed during the
         * sync period.
         */
        @NonNull
        SurfaceControl.Transaction getOrphanTransaction() {
            if (mOrphanTransaction == null) {
                // Lazy since this isn't common
                mOrphanTransaction = mWm.mTransactionFactory.get();
            }
            return mOrphanTransaction;
        }

        private void onSurfacePlacement() {
            if (!mReady) return;
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: onSurfacePlacement checking %s",
                    mSyncId, mRootMembers);
            for (int i = mRootMembers.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mRootMembers.valueAt(i);
                if (!wc.isSyncFinished()) {
                    ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d:  Unfinished container: %s",
                            mSyncId, wc);
                    return;
                }
            }
            finishNow();
        }

        private void finishNow() {
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Finished!", mSyncId);
            SurfaceControl.Transaction merged = mWm.mTransactionFactory.get();
            if (mOrphanTransaction != null) {
                merged.merge(mOrphanTransaction);
            }
            for (WindowContainer wc : mRootMembers) {
                wc.finishSync(merged, false /* cancel */);
            }
            mListener.onTransactionReady(mSyncId, merged);
            mActiveSyncs.remove(mSyncId);
        }

        private void setReady(boolean ready) {
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Set ready", mSyncId);
            mReady = ready;
            if (!ready) return;
            mWm.mWindowPlacerLocked.requestTraversal();
        }

        private void addToSync(WindowContainer wc) {
            if (!mRootMembers.add(wc)) {
                return;
            }
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Adding to group: %s", mSyncId, wc);
            wc.setSyncGroup(this);
            wc.prepareSync();
            mWm.mWindowPlacerLocked.requestTraversal();
        }

        void onCancelSync(WindowContainer wc) {
            mRootMembers.remove(wc);
        }
    }

    private final WindowManagerService mWm;
    private int mNextSyncId = 0;
    private final SparseArray<SyncGroup> mActiveSyncs = new SparseArray<>();

    BLASTSyncEngine(WindowManagerService wms) {
        mWm = wms;
    }

    int startSyncSet(TransactionReadyListener listener) {
        final int id = mNextSyncId++;
        final SyncGroup s = new SyncGroup(listener, id);
        mActiveSyncs.put(id, s);
        ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Started for listener: %s", id, listener);
        return id;
    }

    void addToSyncSet(int id, WindowContainer wc) {
        mActiveSyncs.get(id).addToSync(wc);
    }

    void setReady(int id, boolean ready) {
        mActiveSyncs.get(id).setReady(ready);
    }

    void setReady(int id) {
        setReady(id, true);
    }

    /**
     * Aborts the sync (ie. it doesn't wait for ready or anything to finish)
     */
    void abort(int id) {
        mActiveSyncs.get(id).finishNow();
    }

    void onSurfacePlacement() {
        // backwards since each state can remove itself if finished
        for (int i = mActiveSyncs.size() - 1; i >= 0; --i) {
            mActiveSyncs.valueAt(i).onSurfacePlacement();
        }
    }
}
