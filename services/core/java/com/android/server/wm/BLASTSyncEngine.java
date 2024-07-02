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
import static com.android.server.wm.WindowState.BLAST_TIMEOUT_DURATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;

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
 *
 * By default, all Syncs will be serialized (and it is an error to start one while another is
 * active). However, a sync can be explicitly started in "parallel". This does not guarantee that
 * it will run in parallel; however, it will run in parallel as long as it's watched hierarchy
 * doesn't overlap with any other syncs' watched hierarchies.
 *
 * Currently, a sync that is started as "parallel" implicitly ignores the subtree below it's
 * direct members unless those members are activities (WindowStates are considered "part of" the
 * activity). This allows "stratified" parallelism where, eg, a sync that is only at Task-level
 * can run in parallel with another sync that includes only the task's activities.
 *
 * If, at any time, a container is added to a parallel sync that *is* watched by another sync, it
 * will be forced to serialize with it. This is done by adding a dependency. A sync will only
 * finish if it has no active dependencies. At this point it is effectively not parallel anymore.
 *
 * To avoid dependency cycles, if a sync B ultimately depends on a sync A and a container is added
 * to A which is watched by B, that container will, instead, be moved from B to A instead of
 * creating a cyclic dependency.
 *
 * When syncs overlap, this will attempt to finish everything in the order they were started.
 */
class BLASTSyncEngine {
    private static final String TAG = "BLASTSyncEngine";

    /** No specific method. Used by override specifiers. */
    public static final int METHOD_UNDEFINED = -1;

    /** No sync method. Apps will draw/present internally and just report. */
    public static final int METHOD_NONE = 0;

    /** Sync with BLAST. Apps will draw and then send the buffer to be applied in sync. */
    public static final int METHOD_BLAST = 1;

    interface TransactionReadyListener {
        void onTransactionReady(int mSyncId, SurfaceControl.Transaction transaction);
        default void onTransactionCommitTimeout() {}
        default void onReadyTimeout() {}
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
        final String mSyncName;
        int mSyncMethod = METHOD_BLAST;
        final TransactionReadyListener mListener;
        final Runnable mOnTimeout;
        boolean mReady = false;
        final ArraySet<WindowContainer> mRootMembers = new ArraySet<>();
        private SurfaceControl.Transaction mOrphanTransaction = null;
        private String mTraceName;

        private static final ArrayList<SyncGroup> NO_DEPENDENCIES = new ArrayList<>();

        /**
         * When `true`, this SyncGroup will only wait for mRootMembers to draw; otherwise,
         * it waits for the whole subtree(s) rooted at the mRootMembers.
         */
        boolean mIgnoreIndirectMembers = false;

        /** List of SyncGroups that must finish before this one can. */
        @NonNull
        ArrayList<SyncGroup> mDependencies = NO_DEPENDENCIES;

        private SyncGroup(TransactionReadyListener listener, int id, String name) {
            mSyncId = id;
            mSyncName = name;
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

        /**
         * Check if the sync-group ignores a particular container. This is used to allow syncs at
         * different levels to run in parallel. The primary example is Recents while an activity
         * sync is happening.
         */
        boolean isIgnoring(WindowContainer wc) {
            // Some heuristics to avoid unnecessary work:
            // 1. For now, require an explicit acknowledgement of potential "parallelism" across
            //    hierarchy levels (horizontal).
            if (!mIgnoreIndirectMembers) return false;
            // 2. Don't check WindowStates since they are below the relevant abstraction level (
            //    anything activity/token and above).
            if (wc.asWindowState() != null) return false;
            // Obviously, don't ignore anything that is directly part of this group.
            return wc.mSyncGroup != this;
        }

        /** @return `true` if it finished. */
        private boolean tryFinish() {
            if (!mReady) return false;
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: onSurfacePlacement checking %s",
                    mSyncId, mRootMembers);
            if (!mDependencies.isEmpty()) {
                ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d:  Unfinished dependencies: %s",
                        mSyncId, mDependencies);
                return false;
            }
            for (int i = mRootMembers.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mRootMembers.valueAt(i);
                if (!wc.isSyncFinished(this)) {
                    ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d:  Unfinished container: %s",
                            mSyncId, wc);
                    return false;
                }
            }
            finishNow();
            return true;
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
                wc.finishSync(merged, this, false /* cancel */);
            }

            final ArraySet<WindowContainer> wcAwaitingCommit = new ArraySet<>();
            for (WindowContainer wc : mRootMembers) {
                wc.waitForSyncTransactionCommit(wcAwaitingCommit);
            }

            final int syncId = mSyncId;
            final long mergedTxId = merged.getId();
            final String syncName = mSyncName;
            class CommitCallback implements Runnable {
                // Can run a second time if the action completes after the timeout.
                boolean ran = false;
                public void onCommitted(SurfaceControl.Transaction t) {
                    // Don't wait to hold the global lock to remove the timeout runnable
                    mHandler.removeCallbacks(this);
                    synchronized (mWm.mGlobalLock) {
                        if (ran) {
                            return;
                        }
                        ran = true;
                        for (WindowContainer wc : wcAwaitingCommit) {
                            wc.onSyncTransactionCommitted(t);
                        }
                        t.apply();
                        wcAwaitingCommit.clear();
                    }
                }

                // Called in timeout
                @Override
                public void run() {
                    // Sometimes we get a trace, sometimes we get a bugreport without
                    // a trace. Since these kind of ANRs can trigger such an issue,
                    // try and ensure we will have some visibility in both cases.
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "onTransactionCommitTimeout");
                    Slog.e(TAG, "WM sent Transaction (#" + syncId + ", " + syncName + ", tx="
                            + mergedTxId + ") to organizer, but never received commit callback."
                            + " Application ANR likely to follow.");
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    synchronized (mWm.mGlobalLock) {
                        mListener.onTransactionCommitTimeout();
                        onCommitted(merged.mNativeObject != 0
                                ? merged : mWm.mTransactionFactory.get());
                    }
                }
            };
            CommitCallback callback = new CommitCallback();
            merged.addTransactionCommittedListener(Runnable::run,
                    () -> callback.onCommitted(new SurfaceControl.Transaction()));
            mHandler.postDelayed(callback, BLAST_TIMEOUT_DURATION);

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "onTransactionReady");
            mListener.onTransactionReady(mSyncId, merged);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            mActiveSyncs.remove(this);
            mHandler.removeCallbacks(mOnTimeout);

            // Immediately start the next pending sync-transaction if there is one.
            if (mActiveSyncs.size() == 0 && !mPendingSyncSets.isEmpty()) {
                ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "PendingStartTransaction found");
                final PendingSyncSet pt = mPendingSyncSets.remove(0);
                pt.mStartSync.run();
                if (mActiveSyncs.size() == 0) {
                    throw new IllegalStateException("Pending Sync Set didn't start a sync.");
                }
                // Post this so that the now-playing transition setup isn't interrupted.
                mHandler.post(() -> {
                    synchronized (mWm.mGlobalLock) {
                        pt.mApplySync.run();
                    }
                });
            }
            // Notify idle listeners
            for (int i = mOnIdleListeners.size() - 1; i >= 0; --i) {
                // If an idle listener adds a sync, though, then stop notifying.
                if (mActiveSyncs.size() > 0) break;
                mOnIdleListeners.get(i).run();
            }
        }

        /** returns true if readiness changed. */
        private boolean setReady(boolean ready) {
            if (mReady == ready) {
                return false;
            }
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Set ready %b", mSyncId, ready);
            mReady = ready;
            if (ready) {
                mWm.mWindowPlacerLocked.requestTraversal();
            }
            return true;
        }

        private void addToSync(WindowContainer wc) {
            if (mRootMembers.contains(wc)) {
                return;
            }
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Adding to group: %s", mSyncId, wc);
            final SyncGroup dependency = wc.getSyncGroup();
            if (dependency != null && dependency != this && !dependency.isIgnoring(wc)) {
                // This syncgroup now conflicts with another one, so the whole group now must
                // wait on the other group.
                Slog.w(TAG, "SyncGroup " + mSyncId + " conflicts with " + dependency.mSyncId
                        + ": Making " + mSyncId + " depend on " + dependency.mSyncId);
                if (mDependencies.contains(dependency)) {
                    // nothing, it's already a dependency.
                } else if (dependency.dependsOn(this)) {
                    Slog.w(TAG, " Detected dependency cycle between " + mSyncId + " and "
                            + dependency.mSyncId + ": Moving " + wc + " to " + mSyncId);
                    // Since dependency already depends on this, make this now `wc`'s watcher
                    if (wc.mSyncGroup == null) {
                        wc.setSyncGroup(this);
                    } else {
                        // Explicit replacement.
                        wc.mSyncGroup.mRootMembers.remove(wc);
                        mRootMembers.add(wc);
                        wc.mSyncGroup = this;
                    }
                } else {
                    if (mDependencies == NO_DEPENDENCIES) {
                        mDependencies = new ArrayList<>();
                    }
                    mDependencies.add(dependency);
                }
            } else {
                mRootMembers.add(wc);
                wc.setSyncGroup(this);
            }
            wc.prepareSync();
            if (wc.mSyncState == WindowContainer.SYNC_STATE_NONE && wc.mSyncGroup != null) {
                Slog.w(TAG, "addToSync: unset SyncGroup " + wc.mSyncGroup.mSyncId
                        + " for non-sync " + wc);
                wc.mSyncGroup = null;
            }
            if (mReady) {
                mWm.mWindowPlacerLocked.requestTraversal();
            }
        }

        private boolean dependsOn(SyncGroup group) {
            if (mDependencies.isEmpty()) return false;
            // BFS search with membership check. We don't expect cycle here (since this is
            // explicitly called to avoid cycles) but just to be safe.
            final ArrayList<SyncGroup> fringe = mTmpFringe;
            fringe.clear();
            fringe.add(this);
            for (int head = 0; head < fringe.size(); ++head) {
                final SyncGroup next = fringe.get(head);
                if (next == group) {
                    fringe.clear();
                    return true;
                }
                for (int i = 0; i < next.mDependencies.size(); ++i) {
                    if (fringe.contains(next.mDependencies.get(i))) continue;
                    fringe.add(next.mDependencies.get(i));
                }
            }
            fringe.clear();
            return false;
        }

        void onCancelSync(WindowContainer wc) {
            mRootMembers.remove(wc);
        }

        private void onTimeout() {
            if (!mActiveSyncs.contains(this)) return;
            boolean allFinished = true;
            for (int i = mRootMembers.size() - 1; i >= 0; --i) {
                final WindowContainer<?> wc = mRootMembers.valueAt(i);
                if (!wc.isSyncFinished(this)) {
                    allFinished = false;
                    Slog.i(TAG, "Unfinished container: " + wc);
                    wc.forAllActivities(a -> {
                        if (a.isVisibleRequested()) {
                            if (a.isRelaunching()) {
                                Slog.i(TAG, "  " + a + " is relaunching");
                            }
                            a.forAllWindows(w -> {
                                Slog.i(TAG, "  " + w + " " + w.mWinAnimator.drawStateToString());
                            }, true /* traverseTopToBottom */);
                        } else if (a.mDisplayContent != null && !a.mDisplayContent
                                .mUnknownAppVisibilityController.allResolved()) {
                            Slog.i(TAG, "  UnknownAppVisibility: " + a.mDisplayContent
                                    .mUnknownAppVisibilityController.getDebugMessage());
                        }
                    });
                }
            }

            for (int i = mDependencies.size() - 1; i >= 0; --i) {
                allFinished = false;
                Slog.i(TAG, "Unfinished dependency: " + mDependencies.get(i).mSyncId);
            }
            if (allFinished && !mReady) {
                Slog.w(TAG, "Sync group " + mSyncId + " timed-out because not ready. If you see "
                        + "this, please file a bug.");
                mListener.onReadyTimeout();
            }
            finishNow();
            removeFromDependencies(this);
        }
    }

    private final WindowManagerService mWm;
    private final Handler mHandler;
    private int mNextSyncId = 0;

    /** Currently active syncs. Intentionally ordered by start time. */
    private final ArrayList<SyncGroup> mActiveSyncs = new ArrayList<>();

    /**
     * A queue of pending sync-sets waiting for their turn to run.
     *
     * @see #queueSyncSet
     */
    private final ArrayList<PendingSyncSet> mPendingSyncSets = new ArrayList<>();

    private final ArrayList<Runnable> mOnIdleListeners = new ArrayList<>();

    private final ArrayList<SyncGroup> mTmpFinishQueue = new ArrayList<>();
    private final ArrayList<SyncGroup> mTmpFringe = new ArrayList<>();

    BLASTSyncEngine(WindowManagerService wms) {
        this(wms, wms.mH);
    }

    @VisibleForTesting
    BLASTSyncEngine(WindowManagerService wms, Handler mainHandler) {
        mWm = wms;
        mHandler = mainHandler;
    }

    /**
     * Prepares a {@link SyncGroup} that is not active yet. Caller must call {@link #startSyncSet}
     * before calling {@link #addToSyncSet(int, WindowContainer)} on any {@link WindowContainer}.
     */
    SyncGroup prepareSyncSet(TransactionReadyListener listener, String name) {
        return new SyncGroup(listener, mNextSyncId++, name);
    }

    int startSyncSet(TransactionReadyListener listener, long timeoutMs, String name,
            boolean parallel) {
        final SyncGroup s = prepareSyncSet(listener, name);
        startSyncSet(s, timeoutMs, parallel);
        return s.mSyncId;
    }

    void startSyncSet(SyncGroup s) {
        startSyncSet(s, BLAST_TIMEOUT_DURATION, false /* parallel */);
    }

    void startSyncSet(SyncGroup s, long timeoutMs, boolean parallel) {
        final boolean alreadyRunning = mActiveSyncs.size() > 0;
        if (!parallel && alreadyRunning) {
            // We only support overlapping syncs when explicitly declared `parallel`.
            Slog.e(TAG, "SyncGroup " + s.mSyncId
                    + ": Started when there is other active SyncGroup");
        }
        mActiveSyncs.add(s);
        // For now, parallel implies this.
        s.mIgnoreIndirectMembers = parallel;
        ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Started %sfor listener: %s",
                s.mSyncId, (parallel && alreadyRunning ? "(in parallel) " : ""), s.mListener);
        scheduleTimeout(s, timeoutMs);
    }

    @Nullable
    SyncGroup getSyncSet(int id) {
        for (int i = 0; i < mActiveSyncs.size(); ++i) {
            if (mActiveSyncs.get(i).mSyncId != id) continue;
            return mActiveSyncs.get(i);
        }
        return null;
    }

    boolean hasActiveSync() {
        return mActiveSyncs.size() != 0;
    }

    @VisibleForTesting
    void scheduleTimeout(SyncGroup s, long timeoutMs) {
        mHandler.postDelayed(s.mOnTimeout, timeoutMs);
    }

    void addToSyncSet(int id, WindowContainer wc) {
        getSyncGroup(id).addToSync(wc);
    }

    void setSyncMethod(int id, int method) {
        final SyncGroup syncGroup = getSyncGroup(id);
        if (!syncGroup.mRootMembers.isEmpty()) {
            throw new IllegalStateException(
                    "Not allow to change sync method after adding group member, id=" + id);
        }
        syncGroup.mSyncMethod = method;
    }

    boolean setReady(int id, boolean ready) {
        return getSyncGroup(id).setReady(ready);
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
        final SyncGroup group = getSyncGroup(id);
        group.finishNow();
        removeFromDependencies(group);
    }

    private SyncGroup getSyncGroup(int id) {
        final SyncGroup syncGroup = getSyncSet(id);
        if (syncGroup == null) {
            throw new IllegalStateException("SyncGroup is not started yet id=" + id);
        }
        return syncGroup;
    }

    /**
     * Just removes `group` from any dependency lists. Does not try to evaluate anything. However,
     * it will schedule traversals if any groups were changed in a way that could make them ready.
     */
    private void removeFromDependencies(SyncGroup group) {
        boolean anyChange = false;
        for (int i = 0; i < mActiveSyncs.size(); ++i) {
            final SyncGroup active = mActiveSyncs.get(i);
            if (!active.mDependencies.remove(group)) continue;
            if (!active.mDependencies.isEmpty()) continue;
            anyChange = true;
        }
        if (!anyChange) return;
        mWm.mWindowPlacerLocked.requestTraversal();
    }

    void onSurfacePlacement() {
        if (mActiveSyncs.isEmpty()) return;
        // queue in-order since we want interdependent syncs to become ready in the same order they
        // started in.
        mTmpFinishQueue.addAll(mActiveSyncs);
        // There shouldn't be any dependency cycles or duplicates, but add an upper-bound just
        // in case. Assuming absolute worst case, each visit will try and revisit everything
        // before it, so n + (n-1) + (n-2) ... = (n+1)*n/2
        int visitBounds = ((mActiveSyncs.size() + 1) * mActiveSyncs.size()) / 2;
        while (!mTmpFinishQueue.isEmpty()) {
            if (visitBounds <= 0) {
                Slog.e(TAG, "Trying to finish more syncs than theoretically possible. This "
                        + "should never happen. Most likely a dependency cycle wasn't detected.");
            }
            --visitBounds;
            final SyncGroup group = mTmpFinishQueue.remove(0);
            final int grpIdx = mActiveSyncs.indexOf(group);
            // Skip if it's already finished:
            if (grpIdx < 0) continue;
            if (!group.tryFinish()) continue;
            // Finished, so update dependencies of any prior groups and retry if unblocked.
            int insertAt = 0;
            for (int i = 0; i < mActiveSyncs.size(); ++i) {
                final SyncGroup active = mActiveSyncs.get(i);
                if (!active.mDependencies.remove(group)) continue;
                // Anything afterwards is already in queue.
                if (i >= grpIdx) continue;
                if (!active.mDependencies.isEmpty()) continue;
                // `active` became unblocked so it can finish, since it started earlier, it should
                // be checked next to maintain order.
                mTmpFinishQueue.add(insertAt, mActiveSyncs.get(i));
                insertAt += 1;
            }
        }
    }

    /** Only use this for tests! */
    void tryFinishForTest(int syncId) {
        getSyncSet(syncId).tryFinish();
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

    void addOnIdleListener(Runnable onIdleListener) {
        mOnIdleListeners.add(onIdleListener);
    }
}
