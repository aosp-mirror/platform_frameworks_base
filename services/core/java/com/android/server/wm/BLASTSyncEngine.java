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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SYNC_ENGINE;

import android.annotation.NonNull;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;

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
     * Represents the desire to make a {@link BLASTSyncEngine.SyncGroup} while another is active.
     *
     * @see #queueSyncSet
     */
    private static class PendingSyncSet {
        /** Called immediately when the {@link BLASTSyncEngine} is free. */
        private Runnable mStartSync;

        /** Posted to the main handler after {@link #mStartSync} is called. */
        private Runnable mApplySync;
    }

    /**
     * Holds state associated with a single synchronous set of operations.
     */
    class SyncGroup {
        final int mSyncId;
        final TransactionReadyListener mListener;
        final Runnable mOnTimeout;
        boolean mReady = false;
        final ArraySet<WindowContainer> mRootMembers = new ArraySet<>();
        private SurfaceControl.Transaction mOrphanTransaction = null;
        private String mTraceName;

        private SyncGroup(TransactionReadyListener listener, int id, String name) {
            mSyncId = id;
            mListener = listener;
            mOnTimeout = () -> {
                Slog.w(TAG, "Sync group " + mSyncId + " timeout");
                synchronized (mWm.mGlobalLock) {
                    onTimeout();
                }
            };
            if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                mTraceName = name + "SyncGroupReady";
                Trace.asyncTraceBegin(TRACE_TAG_WINDOW_MANAGER, mTraceName, id);
            }
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
            if (mTraceName != null) {
                Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, mTraceName, mSyncId);
            }
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Finished!", mSyncId);
            SurfaceControl.Transaction merged = mWm.mTransactionFactory.get();
            if (mOrphanTransaction != null) {
                merged.merge(mOrphanTransaction);
            }
            for (WindowContainer wc : mRootMembers) {
                wc.finishSync(merged, false /* cancel */);
            }
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "onTransactionReady");
            mListener.onTransactionReady(mSyncId, merged);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            mActiveSyncs.remove(mSyncId);
            mWm.mH.removeCallbacks(mOnTimeout);

            // Immediately start the next pending sync-transaction if there is one.
            if (mActiveSyncs.size() == 0 && !mPendingSyncSets.isEmpty()) {
                ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "PendingStartTransaction found");
                final PendingSyncSet pt = mPendingSyncSets.remove(0);
                pt.mStartSync.run();
                if (mActiveSyncs.size() == 0) {
                    throw new IllegalStateException("Pending Sync Set didn't start a sync.");
                }
                // Post this so that the now-playing transition setup isn't interrupted.
                mWm.mH.post(() -> {
                    synchronized (mWm.mGlobalLock) {
                        pt.mApplySync.run();
                    }
                });
            }
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

        private void onTimeout() {
            if (!mActiveSyncs.contains(mSyncId)) return;
            boolean allFinished = true;
            for (int i = mRootMembers.size() - 1; i >= 0; --i) {
                final WindowContainer<?> wc = mRootMembers.valueAt(i);
                if (!wc.isSyncFinished()) {
                    allFinished = false;
                    Slog.i(TAG, "Unfinished container: " + wc);
                }
            }
            if (allFinished && !mReady) {
                Slog.w(TAG, "Sync group " + mSyncId + " timed-out because not ready. If you see "
                        + "this, please file a bug.");
            }
            finishNow();
        }
    }

    private final WindowManagerService mWm;
    private int mNextSyncId = 0;
    private final SparseArray<SyncGroup> mActiveSyncs = new SparseArray<>();

    /**
     * A queue of pending sync-sets waiting for their turn to run.
     *
     * @see #queueSyncSet
     */
    private final ArrayList<PendingSyncSet> mPendingSyncSets = new ArrayList<>();

    BLASTSyncEngine(WindowManagerService wms) {
        mWm = wms;
    }

    /**
     * Prepares a {@link SyncGroup} that is not active yet. Caller must call {@link #startSyncSet}
     * before calling {@link #addToSyncSet(int, WindowContainer)} on any {@link WindowContainer}.
     */
    SyncGroup prepareSyncSet(TransactionReadyListener listener, String name) {
        return new SyncGroup(listener, mNextSyncId++, name);
    }

    int startSyncSet(TransactionReadyListener listener) {
        return startSyncSet(listener, WindowState.BLAST_TIMEOUT_DURATION, "");
    }

    int startSyncSet(TransactionReadyListener listener, long timeoutMs, String name) {
        final SyncGroup s = prepareSyncSet(listener, name);
        startSyncSet(s, timeoutMs);
        return s.mSyncId;
    }

    void startSyncSet(SyncGroup s) {
        startSyncSet(s, WindowState.BLAST_TIMEOUT_DURATION);
    }

    void startSyncSet(SyncGroup s, long timeoutMs) {
        if (mActiveSyncs.size() != 0) {
            // We currently only support one sync at a time, so start a new SyncGroup when there is
            // another may cause issue.
            ProtoLog.w(WM_DEBUG_SYNC_ENGINE,
                    "SyncGroup %d: Started when there is other active SyncGroup", s.mSyncId);
        }
        mActiveSyncs.put(s.mSyncId, s);
        ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Started for listener: %s",
                s.mSyncId, s.mListener);
        scheduleTimeout(s, timeoutMs);
    }

    boolean hasActiveSync() {
        return mActiveSyncs.size() != 0;
    }

    @VisibleForTesting
    void scheduleTimeout(SyncGroup s, long timeoutMs) {
        mWm.mH.postDelayed(s.mOnTimeout, timeoutMs);
    }

    void addToSyncSet(int id, WindowContainer wc) {
        getSyncGroup(id).addToSync(wc);
    }

    void setReady(int id, boolean ready) {
        getSyncGroup(id).setReady(ready);
    }

    void setReady(int id) {
        setReady(id, true);
    }

    boolean isReady(int id) {
        return getSyncGroup(id).mReady;
    }

    /**
     * Aborts the sync (ie. it doesn't wait for ready or anything to finish)
     */
    void abort(int id) {
        getSyncGroup(id).finishNow();
    }

    private SyncGroup getSyncGroup(int id) {
        final SyncGroup syncGroup = mActiveSyncs.get(id);
        if (syncGroup == null) {
            throw new IllegalStateException("SyncGroup is not started yet id=" + id);
        }
        return syncGroup;
    }

    void onSurfacePlacement() {
        // backwards since each state can remove itself if finished
        for (int i = mActiveSyncs.size() - 1; i >= 0; --i) {
            mActiveSyncs.valueAt(i).onSurfacePlacement();
        }
    }

    /**
     * Queues a sync operation onto this engine. It will wait until any current/prior sync-sets
     * have finished to run. This is needed right now because currently {@link BLASTSyncEngine}
     * only supports 1 sync at a time.
     *
     * Code-paths should avoid using this unless absolutely necessary. Usually, we use this for
     * difficult edge-cases that we hope to clean-up later.
     *
     * @param startSync will be called immediately when the {@link BLASTSyncEngine} is free to
     *                  "reserve" the {@link BLASTSyncEngine} by calling one of the
     *                  {@link BLASTSyncEngine#startSyncSet} variants.
     * @param applySync will be posted to the main handler after {@code startSync} has been
     *                  called. This is posted so that it doesn't interrupt any clean-up for the
     *                  prior sync-set.
     */
    void queueSyncSet(@NonNull Runnable startSync, @NonNull Runnable applySync) {
        final PendingSyncSet pt = new PendingSyncSet();
        pt.mStartSync = startSync;
        pt.mApplySync = applySync;
        mPendingSyncSets.add(pt);
    }

    /** @return {@code true} if there are any sync-sets waiting to start. */
    boolean hasPendingSyncSets() {
        return !mPendingSyncSets.isEmpty();
    }
}
