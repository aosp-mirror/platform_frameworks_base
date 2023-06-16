/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_START_RECEIVER;
import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;

import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.LOG_WRITER_INFO;
import static com.android.server.am.BroadcastProcessQueue.insertIntoRunnableList;
import static com.android.server.am.BroadcastProcessQueue.reasonToString;
import static com.android.server.am.BroadcastProcessQueue.removeFromRunnableList;
import static com.android.server.am.BroadcastRecord.DELIVERY_DEFERRED;
import static com.android.server.am.BroadcastRecord.deliveryStateToString;
import static com.android.server.am.BroadcastRecord.getReceiverClassName;
import static com.android.server.am.BroadcastRecord.getReceiverPackageName;
import static com.android.server.am.BroadcastRecord.getReceiverProcessName;
import static com.android.server.am.BroadcastRecord.getReceiverUid;
import static com.android.server.am.BroadcastRecord.isDeliveryStateTerminal;

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.UidObserver;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.Handler;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.BroadcastProcessQueue.BroadcastConsumer;
import com.android.server.am.BroadcastProcessQueue.BroadcastPredicate;
import com.android.server.am.BroadcastRecord.DeliveryState;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Alternative {@link BroadcastQueue} implementation which pivots broadcasts to
 * be dispatched on a per-process basis.
 * <p>
 * Each process now has its own broadcast queue represented by a
 * {@link BroadcastProcessQueue} instance. Each queue has a concept of being
 * "runnable at" a particular time in the future, which supports arbitrarily
 * pausing or delaying delivery on a per-process basis.
 * <p>
 * To keep things easy to reason about, there is a <em>very strong</em>
 * preference to have broadcast interactions flow through a consistent set of
 * methods in this specific order:
 * <ol>
 * <li>{@link #updateRunnableList} promotes a per-process queue to be runnable
 * when it has relevant pending broadcasts
 * <li>{@link #updateRunningList} promotes a runnable queue to be running and
 * schedules delivery of the first broadcast
 * <li>{@link #scheduleReceiverColdLocked} requests any needed cold-starts, and
 * results are reported back via {@link #onApplicationAttachedLocked}
 * <li>{@link #scheduleReceiverWarmLocked} requests dispatch of the currently
 * active broadcast to a running app, and results are reported back via
 * {@link #finishReceiverLocked}
 * </ol>
 */
class BroadcastQueueModernImpl extends BroadcastQueue {
    BroadcastQueueModernImpl(ActivityManagerService service, Handler handler,
            BroadcastConstants fgConstants, BroadcastConstants bgConstants) {
        this(service, handler, fgConstants, bgConstants, new BroadcastSkipPolicy(service),
                new BroadcastHistory(fgConstants));
    }

    BroadcastQueueModernImpl(ActivityManagerService service, Handler handler,
            BroadcastConstants fgConstants, BroadcastConstants bgConstants,
            BroadcastSkipPolicy skipPolicy, BroadcastHistory history) {
        super(service, handler, "modern", skipPolicy, history);

        // For the moment, read agnostic constants from foreground
        mConstants = Objects.requireNonNull(fgConstants);
        mFgConstants = Objects.requireNonNull(fgConstants);
        mBgConstants = Objects.requireNonNull(bgConstants);

        mLocalHandler = new Handler(handler.getLooper(), mLocalCallback);

        // We configure runnable size only once at boot; it'd be too complex to
        // try resizing dynamically at runtime
        mRunning = new BroadcastProcessQueue[mConstants.getMaxRunningQueues()];
    }

    /**
     * Map from UID to per-process broadcast queues. If a UID hosts more than
     * one process, each additional process is stored as a linked list using
     * {@link BroadcastProcessQueue#next}.
     *
     * @see #getProcessQueue
     * @see #getOrCreateProcessQueue
     */
    @GuardedBy("mService")
    private final SparseArray<BroadcastProcessQueue> mProcessQueues = new SparseArray<>();

    /**
     * Head of linked list containing queues which are "runnable". They're
     * sorted by {@link BroadcastProcessQueue#getRunnableAt()} so that we prefer
     * dispatching of longer-waiting broadcasts first.
     *
     * @see BroadcastProcessQueue#insertIntoRunnableList
     * @see BroadcastProcessQueue#removeFromRunnableList
     */
    private BroadcastProcessQueue mRunnableHead = null;

    /**
     * Array of queues which are currently "running", which may have gaps that
     * are {@code null}.
     *
     * @see #getRunningSize
     * @see #getRunningIndexOf
     */
    @GuardedBy("mService")
    private final BroadcastProcessQueue[] mRunning;

    /**
     * Single queue which is "running" but is awaiting a cold start to be
     * completed via {@link #onApplicationAttachedLocked}. To optimize for
     * system health we only request one cold start at a time.
     */
    @GuardedBy("mService")
    private @Nullable BroadcastProcessQueue mRunningColdStart;

    /**
     * Collection of latches waiting for device to reach specific state. The
     * first argument is a function to test for the desired state, and the
     * second argument is the latch to release once that state is reached.
     * <p>
     * This is commonly used for callers that are blocked waiting for an
     * {@link #isIdleLocked} or {@link #isBeyondBarrierLocked} to be reached,
     * without requiring that they periodically poll for the state change.
     * <p>
     * Finally, the presence of any waiting latches will cause all
     * future-runnable processes to be runnable immediately, to aid in reaching
     * the desired state as quickly as possible.
     */
    @GuardedBy("mService")
    private final ArrayList<Pair<BooleanSupplier, CountDownLatch>> mWaitingFor = new ArrayList<>();

    /**
     * Container for holding the set of broadcasts that have been replaced by a newer broadcast
     * sent with {@link Intent#FLAG_RECEIVER_REPLACE_PENDING}.
     */
    @GuardedBy("mService")
    private final AtomicReference<ArraySet<BroadcastRecord>> mReplacedBroadcastsCache =
            new AtomicReference<>();

    /**
     * Container for holding the set of broadcast records that satisfied a certain criteria.
     */
    @GuardedBy("mService")
    private final AtomicReference<ArrayMap<BroadcastRecord, Boolean>> mRecordsLookupCache =
            new AtomicReference<>();

    /**
     * Map from UID to its last known "foreground" state. A UID is considered to be in
     * "foreground" state when it's procState is {@link ActivityManager#PROCESS_STATE_TOP}.
     * <p>
     * We manually maintain this data structure since the lifecycle of
     * {@link ProcessRecord} and {@link BroadcastProcessQueue} can be
     * mismatched.
     */
    @GuardedBy("mService")
    private final SparseBooleanArray mUidForeground = new SparseBooleanArray();

    /**
     * Map from UID to its last known "cached" state.
     * <p>
     * We manually maintain this data structure since the lifecycle of
     * {@link ProcessRecord} and {@link BroadcastProcessQueue} can be
     * mismatched.
     */
    @GuardedBy("mService")
    private final SparseBooleanArray mUidCached = new SparseBooleanArray();

    private final BroadcastConstants mConstants;
    private final BroadcastConstants mFgConstants;
    private final BroadcastConstants mBgConstants;

    /**
     * Timestamp when last {@link #testAllProcessQueues} failure was observed;
     * used for throttling log messages.
     */
    private @UptimeMillisLong long mLastTestFailureTime;

    private static final int MSG_UPDATE_RUNNING_LIST = 1;
    private static final int MSG_DELIVERY_TIMEOUT_SOFT = 2;
    private static final int MSG_DELIVERY_TIMEOUT_HARD = 3;
    private static final int MSG_BG_ACTIVITY_START_TIMEOUT = 4;
    private static final int MSG_CHECK_HEALTH = 5;
    private static final int MSG_CHECK_PENDING_COLD_START_VALIDITY = 6;

    private void enqueueUpdateRunningList() {
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);
        mLocalHandler.sendEmptyMessage(MSG_UPDATE_RUNNING_LIST);
    }

    private final Handler mLocalHandler;

    private final Handler.Callback mLocalCallback = (msg) -> {
        switch (msg.what) {
            case MSG_UPDATE_RUNNING_LIST: {
                updateRunningList();
                return true;
            }
            case MSG_DELIVERY_TIMEOUT_SOFT: {
                deliveryTimeoutSoft((BroadcastProcessQueue) msg.obj, msg.arg1);
                return true;
            }
            case MSG_DELIVERY_TIMEOUT_HARD: {
                deliveryTimeoutHard((BroadcastProcessQueue) msg.obj);
                return true;
            }
            case MSG_BG_ACTIVITY_START_TIMEOUT: {
                synchronized (mService) {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final ProcessRecord app = (ProcessRecord) args.arg1;
                    final BroadcastRecord r = (BroadcastRecord) args.arg2;
                    args.recycle();
                    app.removeBackgroundStartPrivileges(r);
                }
                return true;
            }
            case MSG_CHECK_HEALTH: {
                checkHealth();
                return true;
            }
            case MSG_CHECK_PENDING_COLD_START_VALIDITY: {
                checkPendingColdStartValidity();
                return true;
            }
        }
        return false;
    };

    /**
     * Return the total number of active queues contained inside
     * {@link #mRunning}.
     */
    private int getRunningSize() {
        int size = 0;
        for (int i = 0; i < mRunning.length; i++) {
            if (mRunning[i] != null) size++;
        }
        return size;
    }

    /**
     * Return the number of active queues that are delivering "urgent" broadcasts
     */
    private int getRunningUrgentCount() {
        int count = 0;
        for (int i = 0; i < mRunning.length; i++) {
            if (mRunning[i] != null && mRunning[i].getActive().isUrgent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Return the first index of the given value contained inside
     * {@link #mRunning}, otherwise {@code -1}.
     */
    private int getRunningIndexOf(@Nullable BroadcastProcessQueue test) {
        for (int i = 0; i < mRunning.length; i++) {
            if (mRunning[i] == test) return i;
        }
        return -1;
    }

    /**
     * Consider updating the list of "runnable" queues, specifically with
     * relation to the given queue.
     * <p>
     * Typically called when {@link BroadcastProcessQueue#getRunnableAt()} might
     * have changed, since that influences the order in which we'll promote a
     * "runnable" queue to be "running."
     */
    @GuardedBy("mService")
    private void updateRunnableList(@NonNull BroadcastProcessQueue queue) {
        if (getRunningIndexOf(queue) >= 0) {
            // Already running; they'll be reinserted into the runnable list
            // once they finish running, so no need to update them now
            return;
        }

        // To place ourselves correctly in the runnable list, we may need to
        // update internals that may have been invalidated; we wait until now at
        // the last possible moment to avoid duplicated work
        queue.updateDeferredStates(mBroadcastConsumerDeferApply, mBroadcastConsumerDeferClear);
        queue.updateRunnableAt();

        final boolean wantQueue = queue.isRunnable();
        final boolean inQueue = (queue == mRunnableHead) || (queue.runnableAtPrev != null)
                || (queue.runnableAtNext != null);
        if (wantQueue) {
            if (inQueue) {
                // We're in a good state, but our position within the linked
                // list might need to move based on a runnableAt change
                final boolean prevLower = (queue.runnableAtPrev != null)
                        ? queue.runnableAtPrev.getRunnableAt() <= queue.getRunnableAt() : true;
                final boolean nextHigher = (queue.runnableAtNext != null)
                        ? queue.runnableAtNext.getRunnableAt() >= queue.getRunnableAt() : true;
                if (!prevLower || !nextHigher) {
                    mRunnableHead = removeFromRunnableList(mRunnableHead, queue);
                    mRunnableHead = insertIntoRunnableList(mRunnableHead, queue);
                }
            } else {
                mRunnableHead = insertIntoRunnableList(mRunnableHead, queue);
            }
        } else if (inQueue) {
            mRunnableHead = removeFromRunnableList(mRunnableHead, queue);
        }

        // If app isn't running, and there's nothing in the queue, clean up
        if (queue.isEmpty() && !queue.isActive() && !queue.isProcessWarm()) {
            removeProcessQueue(queue.processName, queue.uid);
        }
    }

    private void updateRunningList() {
        synchronized (mService) {
            updateRunningListLocked();
        }
    }

    /**
     * Consider updating the list of "running" queues.
     * <p>
     * This method can promote "runnable" queues to become "running", subject to
     * a maximum of {@link BroadcastConstants#MAX_RUNNING_PROCESS_QUEUES} warm
     * processes and only one pending cold-start.
     */
    @GuardedBy("mService")
    private void updateRunningListLocked() {
        // Allocated size here implicitly includes the extra reservation for urgent
        // dispatches beyond the MAX_RUNNING_QUEUES soft limit for normal
        // parallelism.  If we're already dispatching some urgent broadcasts,
        // count that against the extra first - its role is to permit progress of
        // urgent broadcast traffic when the normal reservation is fully occupied
        // with less-urgent dispatches, not to generally expand parallelism.
        final int usedExtra = Math.min(getRunningUrgentCount(),
                mConstants.EXTRA_RUNNING_URGENT_PROCESS_QUEUES);
        int avail = mRunning.length - getRunningSize() - usedExtra;
        if (avail == 0) return;

        final int cookie = traceBegin("updateRunningList");
        final long now = SystemClock.uptimeMillis();

        // If someone is waiting for a state, everything is runnable now
        final boolean waitingFor = !mWaitingFor.isEmpty();

        // We're doing an update now, so remove any future update requests;
        // we'll repost below if needed
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);

        boolean updateOomAdj = false;
        BroadcastProcessQueue queue = mRunnableHead;
        while (queue != null && avail > 0) {
            BroadcastProcessQueue nextQueue = queue.runnableAtNext;
            final long runnableAt = queue.getRunnableAt();

            // When broadcasts are skipped or failed during list traversal, we
            // might encounter a queue that is no longer runnable; skip it
            if (!queue.isRunnable()) {
                queue = nextQueue;
                continue;
            }

            // If we've hit the soft limit for non-urgent dispatch parallelism,
            // only consider delivering from queues whose ready broadcast is urgent
            if (getRunningSize() >= mConstants.MAX_RUNNING_PROCESS_QUEUES) {
                if (!queue.isPendingUrgent()) {
                    queue = nextQueue;
                    continue;
                }
            }

            // If queues beyond this point aren't ready to run yet, schedule
            // another pass when they'll be runnable
            if (runnableAt > now && !waitingFor) {
                mLocalHandler.sendEmptyMessageAtTime(MSG_UPDATE_RUNNING_LIST, runnableAt);
                break;
            }

            // We might not have heard about a newly running process yet, so
            // consider refreshing if we think we're cold
            updateWarmProcess(queue);

            final boolean processWarm = queue.isProcessWarm();
            if (processWarm) {
                mService.mOomAdjuster.unfreezeTemporarily(queue.app,
                        CachedAppOptimizer.UNFREEZE_REASON_START_RECEIVER);
                // The process could be killed as part of unfreezing. So, check again if it
                // is still warm.
                if (!queue.isProcessWarm()) {
                    queue = nextQueue;
                    enqueueUpdateRunningList();
                    continue;
                }
            } else {
                // We only offer to run one cold-start at a time to preserve
                // system resources; below we either claim that single slot or
                // skip to look for another warm process
                if (mRunningColdStart == null) {
                    mRunningColdStart = queue;
                } else if (isPendingColdStartValid()) {
                    // Move to considering next runnable queue
                    queue = nextQueue;
                    continue;
                } else {
                    // Pending cold start is not valid, so clear it and move on.
                    clearInvalidPendingColdStart();
                    mRunningColdStart = queue;
                }
            }

            if (DEBUG_BROADCAST) logv("Promoting " + queue
                    + " from runnable to running; process is " + queue.app);
            promoteToRunningLocked(queue);
            boolean completed;
            if (processWarm) {
                updateOomAdj |= queue.runningOomAdjusted;
                try {
                    completed = scheduleReceiverWarmLocked(queue);
                } catch (BroadcastDeliveryFailedException e) {
                    reEnqueueActiveBroadcast(queue);
                    completed = true;
                }
            } else {
                completed = scheduleReceiverColdLocked(queue);
            }
            // If we are done with delivering the broadcasts to the process, we can demote it
            // from the "running" list.
            if (completed) {
                demoteFromRunningLocked(queue);
            }
            // TODO: If delivering broadcasts to a process is finished, we don't have to hold
            // a slot for it.
            avail--;

            // Move to considering next runnable queue
            queue = nextQueue;
        }

        // TODO: We need to update oomAdj early as this currently doesn't guarantee that the
        // procState is updated correctly when the app is handling a broadcast.
        if (updateOomAdj) {
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);
        }

        checkPendingColdStartValidity();
        checkAndRemoveWaitingFor();

        traceEnd(cookie);
    }

    private boolean isPendingColdStartValid() {
        if (mRunningColdStart.app.getPid() > 0) {
            // If the process has already started, check if it wasn't killed.
            return !mRunningColdStart.app.isKilled();
        } else {
            // Otherwise, check if the process start is still pending.
            return mRunningColdStart.app.isPendingStart();
        }
    }

    private void clearInvalidPendingColdStart() {
        logw("Clearing invalid pending cold start: " + mRunningColdStart);
        mRunningColdStart.reEnqueueActiveBroadcast();
        demoteFromRunningLocked(mRunningColdStart);
        clearRunningColdStart();
        enqueueUpdateRunningList();
    }

    private void checkPendingColdStartValidity() {
        // There are a few cases where a starting process gets killed but AMS doesn't report
        // this event. So, once we start waiting for a pending cold start, periodically check
        // if the pending start is still valid and if not, clear it so that the queue doesn't
        // keep waiting for the process start forever.
        synchronized (mService) {
            // If there is no pending cold start, then nothing to do.
            if (mRunningColdStart == null) {
                return;
            }
            if (isPendingColdStartValid()) {
                mLocalHandler.sendEmptyMessageDelayed(MSG_CHECK_PENDING_COLD_START_VALIDITY,
                        mConstants.PENDING_COLD_START_CHECK_INTERVAL_MILLIS);
            } else {
                clearInvalidPendingColdStart();
            }
        }
    }

    private void reEnqueueActiveBroadcast(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        final BroadcastRecord record = queue.getActive();
        final int index = queue.getActiveIndex();
        setDeliveryState(queue, queue.app, record, index, record.receivers.get(index),
                BroadcastRecord.DELIVERY_PENDING, "reEnqueueActiveBroadcast");
        queue.reEnqueueActiveBroadcast();
    }

    @Override
    public boolean onApplicationAttachedLocked(@NonNull ProcessRecord app)
            throws BroadcastDeliveryFailedException {
        if (DEBUG_BROADCAST) {
            logv("Process " + app + " is attached");
        }
        // Process records can be recycled, so always start by looking up the
        // relevant per-process queue
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if (queue != null) {
            setQueueProcess(queue, app);
        }

        boolean didSomething = false;
        if ((mRunningColdStart != null) && (mRunningColdStart == queue)) {
            // We've been waiting for this app to cold start, and it's ready
            // now; dispatch its next broadcast and clear the slot
            mRunningColdStart = null;

            // Now that we're running warm, we can finally request that OOM
            // adjust we've been waiting for
            notifyStartedRunning(queue);
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);

            queue.traceProcessEnd();
            queue.traceProcessRunningBegin();
            try {
                if (scheduleReceiverWarmLocked(queue)) {
                    demoteFromRunningLocked(queue);
                }
            } catch (BroadcastDeliveryFailedException e) {
                reEnqueueActiveBroadcast(queue);
                demoteFromRunningLocked(queue);
                throw e;
            }

            // We might be willing to kick off another cold start
            enqueueUpdateRunningList();
            didSomething = true;
        }
        return didSomething;
    }

    @Override
    public void onApplicationTimeoutLocked(@NonNull ProcessRecord app) {
        onApplicationCleanupLocked(app);
    }

    @Override
    public void onApplicationProblemLocked(@NonNull ProcessRecord app) {
        onApplicationCleanupLocked(app);
    }

    @Override
    public void onApplicationCleanupLocked(@NonNull ProcessRecord app) {
        if (DEBUG_BROADCAST) {
            logv("Process " + app + " is cleaned up");
        }

        // This cleanup callback could be for an old process and not for the one we are waiting
        // on, so explicitly check if this for the same ProcessRecord that a queue has.
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if ((mRunningColdStart != null) && (mRunningColdStart == queue)
                && mRunningColdStart.app == app) {
            clearRunningColdStart();
        }

        if (queue != null && queue.app == app) {
            setQueueProcess(queue, null);

            // If queue was running a broadcast, fail it
            if (queue.isActive()) {
                finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_FAILURE,
                        "onApplicationCleanupLocked");
                demoteFromRunningLocked(queue);
            }

            // Skip any pending registered receivers, since the old process
            // would never be around to receive them
            boolean didSomething = queue.forEachMatchingBroadcast((r, i) -> {
                return (r.receivers.get(i) instanceof BroadcastFilter);
            }, mBroadcastConsumerSkip, true);
            if (didSomething || queue.isEmpty()) {
                updateRunnableList(queue);
                enqueueUpdateRunningList();
            }
        }
    }

    private void clearRunningColdStart() {
        mRunningColdStart.traceProcessEnd();

        // We've been waiting for this app to cold start, and it had
        // trouble; clear the slot and fail delivery below
        mRunningColdStart = null;

        // We might be willing to kick off another cold start
        enqueueUpdateRunningList();
    }

    @Override
    public int getPreferredSchedulingGroupLocked(@NonNull ProcessRecord app) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if ((queue != null) && getRunningIndexOf(queue) >= 0) {
            return queue.getPreferredSchedulingGroupLocked();
        }
        return ProcessList.SCHED_GROUP_UNDEFINED;
    }

    @Override
    public void enqueueBroadcastLocked(@NonNull BroadcastRecord r) {
        if (DEBUG_BROADCAST) logv("Enqueuing " + r + " for " + r.receivers.size() + " receivers");

        final int cookie = traceBegin("enqueueBroadcast");
        r.applySingletonPolicy(mService);

        applyDeliveryGroupPolicy(r);

        r.enqueueTime = SystemClock.uptimeMillis();
        r.enqueueRealTime = SystemClock.elapsedRealtime();
        r.enqueueClockTime = System.currentTimeMillis();
        mHistory.onBroadcastEnqueuedLocked(r);

        ArraySet<BroadcastRecord> replacedBroadcasts = mReplacedBroadcastsCache.getAndSet(null);
        if (replacedBroadcasts == null) {
            replacedBroadcasts = new ArraySet<>();
        }
        boolean enqueuedBroadcast = false;

        for (int i = 0; i < r.receivers.size(); i++) {
            final Object receiver = r.receivers.get(i);
            final BroadcastProcessQueue queue = getOrCreateProcessQueue(
                    getReceiverProcessName(receiver), getReceiverUid(receiver));

            // If this receiver is going to be skipped, skip it now itself and don't even enqueue
            // it.
            final String skipReason = mSkipPolicy.shouldSkipMessage(r, receiver);
            if (skipReason != null) {
                setDeliveryState(null, null, r, i, receiver, BroadcastRecord.DELIVERY_SKIPPED,
                        "skipped by policy at enqueue: " + skipReason);
                continue;
            }

            enqueuedBroadcast = true;
            final BroadcastRecord replacedBroadcast = queue.enqueueOrReplaceBroadcast(
                    r, i, mBroadcastConsumerDeferApply);
            if (replacedBroadcast != null) {
                replacedBroadcasts.add(replacedBroadcast);
            }
            updateRunnableList(queue);
            enqueueUpdateRunningList();
        }

        // Skip any broadcasts that have been replaced by newer broadcasts with
        // FLAG_RECEIVER_REPLACE_PENDING.
        // TODO: Optimize and reuse mBroadcastConsumerSkipAndCanceled for the case of
        // cancelling all receivers for a broadcast.
        skipAndCancelReplacedBroadcasts(replacedBroadcasts);
        replacedBroadcasts.clear();
        mReplacedBroadcastsCache.compareAndSet(null, replacedBroadcasts);

        // If nothing to dispatch, send any pending result immediately
        if (r.receivers.isEmpty() || !enqueuedBroadcast) {
            scheduleResultTo(r);
            notifyFinishBroadcast(r);
        }

        traceEnd(cookie);
    }

    private void skipAndCancelReplacedBroadcasts(ArraySet<BroadcastRecord> replacedBroadcasts) {
        for (int i = 0; i < replacedBroadcasts.size(); ++i) {
            final BroadcastRecord r = replacedBroadcasts.valueAt(i);
            // Skip all the receivers in the replaced broadcast
            for (int rcvrIdx = 0; rcvrIdx < r.receivers.size(); ++rcvrIdx) {
                if (!isDeliveryStateTerminal(r.getDeliveryState(rcvrIdx))) {
                    mBroadcastConsumerSkipAndCanceled.accept(r, rcvrIdx);
                }
            }
        }
    }

    private void applyDeliveryGroupPolicy(@NonNull BroadcastRecord r) {
        if (mService.shouldIgnoreDeliveryGroupPolicy(r.intent.getAction())) {
            return;
        }
        final int policy = r.getDeliveryGroupPolicy();
        final BroadcastConsumer broadcastConsumer;
        switch (policy) {
            case BroadcastOptions.DELIVERY_GROUP_POLICY_ALL:
                // Older broadcasts need to be left as is in this case, so nothing more to do.
                return;
            case BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT:
                broadcastConsumer = mBroadcastConsumerSkipAndCanceled;
                break;
            case BroadcastOptions.DELIVERY_GROUP_POLICY_MERGED:
                // TODO: Allow applying MERGED policy for broadcasts with more than one receiver.
                if (r.receivers.size() > 1) {
                    return;
                }
                final BundleMerger extrasMerger = r.options.getDeliveryGroupExtrasMerger();
                if (extrasMerger == null) {
                    // Extras merger is required to be able to merge the extras. So, if it's not
                    // supplied, then ignore the delivery group policy.
                    return;
                }
                broadcastConsumer = (record, recordIndex) -> {
                    r.intent.mergeExtras(record.intent, extrasMerger);
                    mBroadcastConsumerSkipAndCanceled.accept(record, recordIndex);
                };
                break;
            default:
                logw("Unknown delivery group policy: " + policy);
                return;
        }
        final ArrayMap<BroadcastRecord, Boolean> recordsLookupCache = getRecordsLookupCache();
        forEachMatchingBroadcast(QUEUE_PREDICATE_ANY, (testRecord, testIndex) -> {
            // If the receiver is already in a terminal state, then ignore it.
            if (isDeliveryStateTerminal(testRecord.getDeliveryState(testIndex))) {
                return false;
            }
            // We only allow caller to remove broadcasts they enqueued
            if ((r.callingUid != testRecord.callingUid)
                    || (r.userId != testRecord.userId)
                    || !r.matchesDeliveryGroup(testRecord)) {
                return false;
            }

            // For ordered broadcast, check if the receivers for the new broadcast is a superset
            // of those for the previous one as skipping and removing only one of them could result
            // in an inconsistent state.
            if (testRecord.ordered || testRecord.prioritized) {
                return containsAllReceivers(r, testRecord, recordsLookupCache);
            } else if (testRecord.resultTo != null) {
                return testRecord.getDeliveryState(testIndex) == DELIVERY_DEFERRED
                        ? r.containsReceiver(testRecord.receivers.get(testIndex))
                        : containsAllReceivers(r, testRecord, recordsLookupCache);
            } else {
                return r.containsReceiver(testRecord.receivers.get(testIndex));
            }
        }, broadcastConsumer, true);
        recordsLookupCache.clear();
        mRecordsLookupCache.compareAndSet(null, recordsLookupCache);
    }

    @NonNull
    private ArrayMap<BroadcastRecord, Boolean> getRecordsLookupCache() {
        ArrayMap<BroadcastRecord, Boolean> recordsLookupCache =
                mRecordsLookupCache.getAndSet(null);
        if (recordsLookupCache == null) {
            recordsLookupCache = new ArrayMap<>();
        }
        return recordsLookupCache;
    }

    private boolean containsAllReceivers(@NonNull BroadcastRecord record,
            @NonNull BroadcastRecord testRecord,
            @NonNull ArrayMap<BroadcastRecord, Boolean> recordsLookupCache) {
        final int idx = recordsLookupCache.indexOfKey(testRecord);
        if (idx > 0) {
            return recordsLookupCache.valueAt(idx);
        }
        final boolean containsAll = record.containsAllReceivers(testRecord.receivers);
        recordsLookupCache.put(testRecord, containsAll);
        return containsAll;
    }

    /**
     * Schedule the currently active broadcast on the given queue when we know
     * the process is cold. This kicks off a cold start and will eventually call
     * through to {@link #scheduleReceiverWarmLocked} once it's ready.
     *
     * @return {@code true} if the broadcast delivery is finished and the process queue can
     *         be demoted from the running list. Otherwise {@code false}.
     */
    @CheckResult
    @GuardedBy("mService")
    private boolean scheduleReceiverColdLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        // Remember that active broadcast was scheduled via a cold start
        queue.setActiveViaColdStart(true);

        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        // Ignore registered receivers from a previous PID
        if (receiver instanceof BroadcastFilter) {
            mRunningColdStart = null;
            finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_SKIPPED,
                    "BroadcastFilter for cold app");
            return true;
        }

        final String skipReason = shouldSkipReceiver(queue, r, index);
        if (skipReason != null) {
            mRunningColdStart = null;
            finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_SKIPPED, skipReason);
            return true;
        }

        final ApplicationInfo info = ((ResolveInfo) receiver).activityInfo.applicationInfo;
        final ComponentName component = ((ResolveInfo) receiver).activityInfo.getComponentName();

        if ((info.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
            queue.setActiveWasStopped(true);
        }
        final int intentFlags = r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND;
        final HostingRecord hostingRecord = new HostingRecord(HostingRecord.HOSTING_TYPE_BROADCAST,
                component, r.intent.getAction(), r.getHostingRecordTriggerType());
        final boolean isActivityCapable = (r.options != null
                && r.options.getTemporaryAppAllowlistDuration() > 0);
        final int zygotePolicyFlags = isActivityCapable ? ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE
                : ZYGOTE_POLICY_FLAG_EMPTY;
        final boolean allowWhileBooting = (r.intent.getFlags()
                & Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0;

        if (DEBUG_BROADCAST) logv("Scheduling " + r + " to cold " + queue);
        queue.app = mService.startProcessLocked(queue.processName, info, true, intentFlags,
                hostingRecord, zygotePolicyFlags, allowWhileBooting, false);
        if (queue.app == null) {
            mRunningColdStart = null;
            finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_FAILURE,
                    "startProcessLocked failed");
            return true;
        }
        return false;
    }

    /**
     * Schedule the currently active broadcast on the given queue when we know
     * the process is warm.
     * <p>
     * There is a <em>very strong</em> preference to consistently handle all
     * results by calling through to {@link #finishReceiverLocked}, both in the
     * case where a broadcast is handled by a remote app, and the case where the
     * broadcast was finished locally without the remote app being involved.
     *
     * @return {@code true} if the broadcast delivery is finished and the process queue can
     *         be demoted from the running list. Otherwise {@code false}.
     */
    @CheckResult
    @GuardedBy("mService")
    private boolean scheduleReceiverWarmLocked(@NonNull BroadcastProcessQueue queue)
            throws BroadcastDeliveryFailedException {
        checkState(queue.isActive(), "isActive");

        final int cookie = traceBegin("scheduleReceiverWarmLocked");
        while (queue.isActive()) {
            final BroadcastRecord r = queue.getActive();
            final int index = queue.getActiveIndex();

            if (r.terminalCount == 0) {
                r.dispatchTime = SystemClock.uptimeMillis();
                r.dispatchRealTime = SystemClock.elapsedRealtime();
                r.dispatchClockTime = System.currentTimeMillis();
            }

            final String skipReason = shouldSkipReceiver(queue, r, index);
            if (skipReason == null) {
                final boolean isBlockingDispatch = dispatchReceivers(queue, r, index);
                if (isBlockingDispatch) {
                    traceEnd(cookie);
                    return false;
                }
            } else {
                finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_SKIPPED, skipReason);
            }

            if (shouldRetire(queue)) {
                break;
            }

            // We're on a roll; move onto the next broadcast for this process
            queue.makeActiveNextPending();
        }
        traceEnd(cookie);
        return true;
    }

    /**
     * Consults {@link BroadcastSkipPolicy} and the receiver process state to decide whether or
     * not the broadcast to a receiver can be skipped.
     */
    private String shouldSkipReceiver(@NonNull BroadcastProcessQueue queue,
            @NonNull BroadcastRecord r, int index) {
        final int oldDeliveryState = getDeliveryState(r, index);
        final ProcessRecord app = queue.app;
        final Object receiver = r.receivers.get(index);

        // If someone already finished this broadcast, finish immediately
        if (isDeliveryStateTerminal(oldDeliveryState)) {
            return "already terminal state";
        }

        // Consider additional cases where we'd want to finish immediately
        if (app != null && app.isInFullBackup()) {
            return "isInFullBackup";
        }
        final String skipReason = mSkipPolicy.shouldSkipMessage(r, receiver);
        if (skipReason != null) {
            return skipReason;
        }
        final Intent receiverIntent = r.getReceiverIntent(receiver);
        if (receiverIntent == null) {
            return "getReceiverIntent";
        }

        // Ignore registered receivers from a previous PID
        if ((receiver instanceof BroadcastFilter)
                && ((BroadcastFilter) receiver).receiverList.pid != app.getPid()) {
            return "BroadcastFilter for mismatched PID";
        }
        // The receiver was not handled in this method.
        return null;
    }

    /**
     * A receiver is about to be dispatched.  Start ANR timers, if necessary.
     *
     * @return {@code true} if this a blocking delivery. That is, we are going to block on the
     *         finishReceiver() to be called before moving to the next broadcast. Otherwise,
     *         {@code false}.
     */
    @CheckResult
    private boolean dispatchReceivers(@NonNull BroadcastProcessQueue queue,
            @NonNull BroadcastRecord r, int index) throws BroadcastDeliveryFailedException {
        final ProcessRecord app = queue.app;
        final Object receiver = r.receivers.get(index);

        // Skip ANR tracking early during boot, when requested, or when we
        // immediately assume delivery success
        final boolean assumeDelivered = r.isAssumedDelivered(index);
        if (mService.mProcessesReady && !r.timeoutExempt && !assumeDelivered) {
            queue.lastCpuDelayTime = queue.app.getCpuDelayTime();

            final int softTimeoutMillis = (int) (r.isForeground() ? mFgConstants.TIMEOUT
                    : mBgConstants.TIMEOUT);
            mLocalHandler.sendMessageDelayed(Message.obtain(mLocalHandler,
                    MSG_DELIVERY_TIMEOUT_SOFT, softTimeoutMillis, 0, queue), softTimeoutMillis);
        }

        if (r.mBackgroundStartPrivileges.allowsAny()) {
            app.addOrUpdateBackgroundStartPrivileges(r, r.mBackgroundStartPrivileges);

            final long timeout = r.isForeground() ? mFgConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT
                    : mBgConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT;
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = app;
            args.arg2 = r;
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_BG_ACTIVITY_START_TIMEOUT, args), timeout);
        }
        if (r.options != null && r.options.getTemporaryAppAllowlistDuration() > 0) {
            if (r.options.getTemporaryAppAllowlistType()
                    == PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_APP_FREEZING_DELAYED) {
                // Only delay freezer, don't add to any temp allowlist
                // TODO: Add a unit test
                mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(app,
                        CachedAppOptimizer.UNFREEZE_REASON_START_RECEIVER,
                        r.options.getTemporaryAppAllowlistDuration());
            } else {
                mService.tempAllowlistUidLocked(queue.uid,
                        r.options.getTemporaryAppAllowlistDuration(),
                        r.options.getTemporaryAppAllowlistReasonCode(), r.toShortString(),
                        r.options.getTemporaryAppAllowlistType(), r.callingUid);
            }
        }

        if (DEBUG_BROADCAST) logv("Scheduling " + r + " to warm " + app);
        setDeliveryState(queue, app, r, index, receiver, BroadcastRecord.DELIVERY_SCHEDULED,
                "scheduleReceiverWarmLocked");

        final Intent receiverIntent = r.getReceiverIntent(receiver);
        final IApplicationThread thread = app.getOnewayThread();
        if (thread != null) {
            try {
                if (r.shareIdentity) {
                    mService.mPackageManagerInt.grantImplicitAccess(r.userId, r.intent,
                            UserHandle.getAppId(app.uid), r.callingUid, true);
                }
                queue.lastProcessState = app.mState.getCurProcState();
                if (receiver instanceof BroadcastFilter) {
                    notifyScheduleRegisteredReceiver(app, r, (BroadcastFilter) receiver);
                    thread.scheduleRegisteredReceiver(
                        ((BroadcastFilter) receiver).receiverList.receiver,
                        receiverIntent, r.resultCode, r.resultData, r.resultExtras,
                        r.ordered, r.initialSticky, assumeDelivered, r.userId,
                        app.mState.getReportedProcState(),
                        r.shareIdentity ? r.callingUid : Process.INVALID_UID,
                        r.shareIdentity ? r.callerPackage : null);
                    // TODO: consider making registered receivers of unordered
                    // broadcasts report results to detect ANRs
                    if (assumeDelivered) {
                        finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_DELIVERED,
                                "assuming delivered");
                        return false;
                    }
                } else {
                    notifyScheduleReceiver(app, r, (ResolveInfo) receiver);
                    thread.scheduleReceiver(receiverIntent, ((ResolveInfo) receiver).activityInfo,
                            null, r.resultCode, r.resultData, r.resultExtras, r.ordered,
                            assumeDelivered, r.userId,
                            app.mState.getReportedProcState(),
                            r.shareIdentity ? r.callingUid : Process.INVALID_UID,
                            r.shareIdentity ? r.callerPackage : null);
                }
                return true;
            } catch (RemoteException e) {
                final String msg = "Failed to schedule " + r + " to " + receiver
                        + " via " + app + ": " + e;
                logw(msg);
                app.killLocked("Can't deliver broadcast", ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_UNDELIVERED_BROADCAST, true);
                // If we were trying to deliver a manifest broadcast, throw the error as we need
                // to try redelivering the broadcast to this receiver.
                if (receiver instanceof ResolveInfo) {
                    mLocalHandler.removeMessages(MSG_DELIVERY_TIMEOUT_SOFT, queue);
                    throw new BroadcastDeliveryFailedException(e);
                }
                finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_FAILURE,
                        "remote app");
                return false;
            }
        } else {
            finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_FAILURE,
                    "missing IApplicationThread");
            return false;
        }
    }

    /**
     * Schedule the final {@link BroadcastRecord#resultTo} delivery for an
     * ordered broadcast; assumes the sender is still a warm process.
     */
    private void scheduleResultTo(@NonNull BroadcastRecord r) {
        if (r.resultTo == null) return;
        final ProcessRecord app = r.resultToApp;
        final IApplicationThread thread = (app != null) ? app.getOnewayThread() : null;
        if (thread != null) {
            mService.mOomAdjuster.unfreezeTemporarily(
                    app, CachedAppOptimizer.UNFREEZE_REASON_FINISH_RECEIVER);
            if (r.shareIdentity && app.uid != r.callingUid) {
                mService.mPackageManagerInt.grantImplicitAccess(r.userId, r.intent,
                        UserHandle.getAppId(app.uid), r.callingUid, true);
            }
            try {
                final boolean assumeDelivered = true;
                thread.scheduleRegisteredReceiver(
                        r.resultTo, r.intent,
                        r.resultCode, r.resultData, r.resultExtras, false, r.initialSticky,
                        assumeDelivered, r.userId,
                        app.mState.getReportedProcState(),
                        r.shareIdentity ? r.callingUid : Process.INVALID_UID,
                        r.shareIdentity ? r.callerPackage : null);
            } catch (RemoteException e) {
                final String msg = "Failed to schedule result of " + r + " via " + app + ": " + e;
                logw(msg);
                app.killLocked("Can't deliver broadcast", ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_UNDELIVERED_BROADCAST, true);
            }
        }
        // Clear so both local and remote references can be GC'ed
        r.resultTo = null;
    }

    private void deliveryTimeoutSoft(@NonNull BroadcastProcessQueue queue,
            int softTimeoutMillis) {
        synchronized (mService) {
            deliveryTimeoutSoftLocked(queue, softTimeoutMillis);
        }
    }

    private void deliveryTimeoutSoftLocked(@NonNull BroadcastProcessQueue queue,
            int softTimeoutMillis) {
        if (queue.app != null) {
            // Instead of immediately triggering an ANR, extend the timeout by
            // the amount of time the process was runnable-but-waiting; we're
            // only willing to do this once before triggering an hard ANR
            final long cpuDelayTime = queue.app.getCpuDelayTime() - queue.lastCpuDelayTime;
            final long hardTimeoutMillis = MathUtils.constrain(cpuDelayTime, 0, softTimeoutMillis);
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_DELIVERY_TIMEOUT_HARD, queue),
                    hardTimeoutMillis);
        } else {
            deliveryTimeoutHardLocked(queue);
        }
    }

    private void deliveryTimeoutHard(@NonNull BroadcastProcessQueue queue) {
        synchronized (mService) {
            deliveryTimeoutHardLocked(queue);
        }
    }

    private void deliveryTimeoutHardLocked(@NonNull BroadcastProcessQueue queue) {
        finishReceiverActiveLocked(queue, BroadcastRecord.DELIVERY_TIMEOUT,
                "deliveryTimeoutHardLocked");
        demoteFromRunningLocked(queue);
    }

    @Override
    public boolean finishReceiverLocked(@NonNull ProcessRecord app, int resultCode,
            @Nullable String resultData, @Nullable Bundle resultExtras, boolean resultAbort,
            boolean waitForServices) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if ((queue == null) || !queue.isActive()) {
            logw("Ignoring finishReceiverLocked; no active broadcast for " + queue);
            return false;
        }

        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        if (r.ordered) {
            r.resultCode = resultCode;
            r.resultData = resultData;
            r.resultExtras = resultExtras;
            if (!r.isNoAbort()) {
                r.resultAbort = resultAbort;
            }
        }

        // To ensure that "beyond" high-water marks are updated in a monotonic
        // way, we finish this receiver before possibly skipping any remaining
        // aborted receivers
        finishReceiverActiveLocked(queue,
                BroadcastRecord.DELIVERY_DELIVERED, "remote app");

        // When the caller aborted an ordered broadcast, we mark all
        // remaining receivers as skipped
        if (r.resultAbort) {
            for (int i = index + 1; i < r.receivers.size(); i++) {
                setDeliveryState(null, null, r, i, r.receivers.get(i),
                        BroadcastRecord.DELIVERY_SKIPPED, "resultAbort");
            }
        }

        if (shouldRetire(queue)) {
            demoteFromRunningLocked(queue);
            return true;
        }

        // We're on a roll; move onto the next broadcast for this process
        queue.makeActiveNextPending();
        try {
            if (scheduleReceiverWarmLocked(queue)) {
                demoteFromRunningLocked(queue);
                return true;
            }
        } catch (BroadcastDeliveryFailedException e) {
            reEnqueueActiveBroadcast(queue);
            demoteFromRunningLocked(queue);
            return true;
        }

        return false;
    }

    /**
     * Return true if there are no more broadcasts in the queue or if the queue is not runnable.
     */
    private boolean shouldRetire(@NonNull BroadcastProcessQueue queue) {
        // If we've made reasonable progress, periodically retire ourselves to
        // avoid starvation of other processes and stack overflow when a
        // broadcast is immediately finished without waiting
        final boolean shouldRetire;
        if (UserHandle.isCore(queue.uid)) {
            final int nonBlockingDeliveryCount = queue.getActiveAssumedDeliveryCountSinceIdle();
            final int blockingDeliveryCount = (queue.getActiveCountSinceIdle()
                    - queue.getActiveAssumedDeliveryCountSinceIdle());
            shouldRetire = (blockingDeliveryCount
                    >= mConstants.MAX_CORE_RUNNING_BLOCKING_BROADCASTS) || (nonBlockingDeliveryCount
                    >= mConstants.MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS);
        } else {
            shouldRetire =
                    (queue.getActiveCountSinceIdle() >= mConstants.MAX_RUNNING_ACTIVE_BROADCASTS);
        }

        return !queue.isRunnable() || !queue.isProcessWarm() || shouldRetire;
    }

    /**
     * Terminate all active broadcasts on the queue.
     */
    private void finishReceiverActiveLocked(@NonNull BroadcastProcessQueue queue,
            @DeliveryState int deliveryState, @NonNull String reason) {
        if (!queue.isActive()) {
            logw("Ignoring finishReceiverActiveLocked; no active broadcast for " + queue);
            return;
        }

        final int cookie = traceBegin("finishReceiver");
        final ProcessRecord app = queue.app;
        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        setDeliveryState(queue, app, r, index, receiver, deliveryState, reason);

        if (deliveryState == BroadcastRecord.DELIVERY_TIMEOUT) {
            r.anrCount++;
            if (app != null && !app.isDebugging()) {
                final String packageName = getReceiverPackageName(receiver);
                final String className = getReceiverClassName(receiver);
                mService.appNotResponding(queue.app,
                        TimeoutRecord.forBroadcastReceiver(r.intent, packageName, className));
            }
        } else {
            mLocalHandler.removeMessages(MSG_DELIVERY_TIMEOUT_SOFT, queue);
            mLocalHandler.removeMessages(MSG_DELIVERY_TIMEOUT_HARD, queue);
        }

        // Given that a receiver just finished, check if the "waitingFor" conditions are met.
        checkAndRemoveWaitingFor();

        traceEnd(cookie);
    }

    /**
     * Promote a process to the "running" list.
     */
    @GuardedBy("mService")
    private void promoteToRunningLocked(@NonNull BroadcastProcessQueue queue) {
        // Allocate this available permit and start running!
        final int queueIndex = getRunningIndexOf(null);
        mRunning[queueIndex] = queue;

        // Remove ourselves from linked list of runnable things
        mRunnableHead = removeFromRunnableList(mRunnableHead, queue);

        // Emit all trace events for this process into a consistent track
        queue.runningTraceTrackName = TAG + ".mRunning[" + queueIndex + "]";
        queue.runningOomAdjusted = queue.isPendingManifest()
                || queue.isPendingOrdered()
                || queue.isPendingResultTo();

        // If already warm, we can make OOM adjust request immediately;
        // otherwise we need to wait until process becomes warm
        final boolean processWarm = queue.isProcessWarm();
        if (processWarm) {
            notifyStartedRunning(queue);
        }

        // If we're already warm, schedule next pending broadcast now;
        // otherwise we'll wait for the cold start to circle back around
        queue.makeActiveNextPending();
        if (processWarm) {
            queue.traceProcessRunningBegin();
        } else {
            queue.traceProcessStartingBegin();
        }
    }

    /**
     * Demote a process from the "running" list.
     */
    @GuardedBy("mService")
    private void demoteFromRunningLocked(@NonNull BroadcastProcessQueue queue) {
        if (!queue.isActive()) {
            logw("Ignoring demoteFromRunning; no active broadcast for " + queue);
            return;
        }

        final int cookie = traceBegin("demoteFromRunning");
        // We've drained running broadcasts; maybe move back to runnable
        queue.makeActiveIdle();
        queue.traceProcessEnd();

        final int queueIndex = getRunningIndexOf(queue);
        mRunning[queueIndex] = null;
        updateRunnableList(queue);
        enqueueUpdateRunningList();

        // Tell other OS components that app is not actively running, giving
        // a chance to update OOM adjustment
        notifyStoppedRunning(queue);
        traceEnd(cookie);
    }

    /**
     * Set the delivery state on the given broadcast, then apply any additional
     * bookkeeping related to ordered broadcasts.
     */
    private void setDeliveryState(@Nullable BroadcastProcessQueue queue,
            @Nullable ProcessRecord app, @NonNull BroadcastRecord r, int index,
            @NonNull Object receiver, @DeliveryState int newDeliveryState,
            @NonNull String reason) {
        final int cookie = traceBegin("setDeliveryState");

        // Remember the old state and apply the new state
        final int oldDeliveryState = getDeliveryState(r, index);
        final boolean beyondCountChanged = r.setDeliveryState(index, newDeliveryState, reason);

        // Emit any relevant tracing results when we're changing the delivery
        // state as part of running from a queue
        if (queue != null) {
            if (newDeliveryState == BroadcastRecord.DELIVERY_SCHEDULED) {
                queue.traceActiveBegin();
            } else if ((oldDeliveryState == BroadcastRecord.DELIVERY_SCHEDULED)
                    && isDeliveryStateTerminal(newDeliveryState)) {
                queue.traceActiveEnd();
            }
        }

        // If we're moving into a terminal state, we might have internal
        // bookkeeping to update for ordered broadcasts
        if (!isDeliveryStateTerminal(oldDeliveryState)
                && isDeliveryStateTerminal(newDeliveryState)) {
            if (DEBUG_BROADCAST
                    && newDeliveryState != BroadcastRecord.DELIVERY_DELIVERED) {
                logw("Delivery state of " + r + " to " + receiver
                        + " via " + app + " changed from "
                        + deliveryStateToString(oldDeliveryState) + " to "
                        + deliveryStateToString(newDeliveryState) + " because " + reason);
            }

            notifyFinishReceiver(queue, app, r, index, receiver);
        }

        // When we've reached a new high-water mark, we might be in a position
        // to unblock other receivers or the final resultTo
        if (beyondCountChanged) {
            if (r.beyondCount == r.receivers.size()) {
                scheduleResultTo(r);
            }

            // Our terminal state here might be enough for another process
            // blocked on us to now be runnable
            if (r.ordered || r.prioritized) {
                for (int i = 0; i < r.receivers.size(); i++) {
                    if (!isDeliveryStateTerminal(getDeliveryState(r, i)) || (i == index)) {
                        final Object otherReceiver = r.receivers.get(i);
                        final BroadcastProcessQueue otherQueue = getProcessQueue(
                                getReceiverProcessName(otherReceiver),
                                getReceiverUid(otherReceiver));
                        if (otherQueue != null) {
                            otherQueue.invalidateRunnableAt();
                            updateRunnableList(otherQueue);
                        }
                    }
                }
                enqueueUpdateRunningList();
            }
        }

        traceEnd(cookie);
    }

    private @DeliveryState int getDeliveryState(@NonNull BroadcastRecord r, int index) {
        return r.getDeliveryState(index);
    }

    @Override
    public boolean cleanupDisabledPackageReceiversLocked(@Nullable String packageName,
            @Nullable Set<String> filterByClasses, int userId) {
        final Predicate<BroadcastProcessQueue> queuePredicate;
        final BroadcastPredicate broadcastPredicate;
        if (packageName != null) {
            // Caller provided a package and user ID, so we're focused on queues
            // belonging to a specific UID
            final int uid = mService.mPackageManagerInt.getPackageUid(
                    packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            queuePredicate = (q) -> {
                return q.uid == uid;
            };

            // If caller provided a set of classes, filter to skip only those;
            // otherwise we skip all broadcasts
            if (filterByClasses != null) {
                broadcastPredicate = (r, i) -> {
                    final Object receiver = r.receivers.get(i);
                    if (receiver instanceof ResolveInfo) {
                        final ActivityInfo info = ((ResolveInfo) receiver).activityInfo;
                        return packageName.equals(info.packageName)
                                && filterByClasses.contains(info.name);
                    } else {
                        return false;
                    }
                };
            } else {
                broadcastPredicate = (r, i) -> {
                    final Object receiver = r.receivers.get(i);
                    return packageName.equals(getReceiverPackageName(receiver));
                };
            }
        } else {
            // Caller is cleaning up an entire user ID; skip all broadcasts
            queuePredicate = (q) -> {
                return UserHandle.getUserId(q.uid) == userId;
            };
            broadcastPredicate = BROADCAST_PREDICATE_ANY;

            cleanupUserStateLocked(mUidCached, userId);
            cleanupUserStateLocked(mUidForeground, userId);
        }
        return forEachMatchingBroadcast(queuePredicate, broadcastPredicate,
                mBroadcastConsumerSkip, true);
    }

    @GuardedBy("mService")
    private void cleanupUserStateLocked(@NonNull SparseBooleanArray uidState, int userId) {
        for (int i = uidState.size() - 1; i >= 0; --i) {
            final int uid = uidState.keyAt(i);
            if (UserHandle.getUserId(uid) == userId) {
                uidState.removeAt(i);
            }
        }
    }

    private static final Predicate<BroadcastProcessQueue> QUEUE_PREDICATE_ANY =
            (q) -> true;
    private static final BroadcastPredicate BROADCAST_PREDICATE_ANY =
            (r, i) -> true;

    /**
     * Typical consumer that will skip the given broadcast, usually as a result
     * of it matching a predicate.
     */
    private final BroadcastConsumer mBroadcastConsumerSkip = (r, i) -> {
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_SKIPPED,
                "mBroadcastConsumerSkip");
    };

    /**
     * Typical consumer that will both skip the given broadcast and mark it as
     * cancelled, usually as a result of it matching a predicate.
     */
    private final BroadcastConsumer mBroadcastConsumerSkipAndCanceled = (r, i) -> {
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_SKIPPED,
                "mBroadcastConsumerSkipAndCanceled");
        r.resultCode = Activity.RESULT_CANCELED;
        r.resultData = null;
        r.resultExtras = null;
    };

    private final BroadcastConsumer mBroadcastConsumerDeferApply = (r, i) -> {
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_DEFERRED,
                "mBroadcastConsumerDeferApply");
    };

    private final BroadcastConsumer mBroadcastConsumerDeferClear = (r, i) -> {
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_PENDING,
                "mBroadcastConsumerDeferClear");
    };

    /**
     * Verify that all known {@link #mProcessQueues} are in the state tested by
     * the given {@link Predicate}.
     */
    private boolean testAllProcessQueues(@NonNull Predicate<BroadcastProcessQueue> test,
            @NonNull String label, @NonNull PrintWriter pw) {
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                if (!test.test(leaf)) {
                    final long now = SystemClock.uptimeMillis();
                    if (now > mLastTestFailureTime + DateUtils.SECOND_IN_MILLIS) {
                        mLastTestFailureTime = now;
                        pw.println("Test " + label + " failed due to " + leaf.toShortString() + " "
                                + leaf.describeStateLocked());
                        pw.flush();
                    }
                    return false;
                }
                leaf = leaf.processNameNext;
            }
        }
        pw.println("Test " + label + " passed");
        pw.flush();
        return true;
    }

    private boolean forEachMatchingBroadcast(
            @NonNull Predicate<BroadcastProcessQueue> queuePredicate,
            @NonNull BroadcastPredicate broadcastPredicate,
            @NonNull BroadcastConsumer broadcastConsumer, boolean andRemove) {
        boolean didSomething = false;
        for (int i = mProcessQueues.size() - 1; i >= 0; i--) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                if (queuePredicate.test(leaf)) {
                    if (leaf.forEachMatchingBroadcast(broadcastPredicate,
                            broadcastConsumer, andRemove)) {
                        updateRunnableList(leaf);
                        didSomething = true;
                    }
                }
                leaf = leaf.processNameNext;
            }
        }
        if (didSomething) {
            enqueueUpdateRunningList();
        }
        return didSomething;
    }

    private boolean forEachMatchingQueue(
            @NonNull Predicate<BroadcastProcessQueue> queuePredicate,
            @NonNull Consumer<BroadcastProcessQueue> queueConsumer) {
        boolean didSomething = false;
        for (int i = mProcessQueues.size() - 1; i >= 0; i--) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                if (queuePredicate.test(leaf)) {
                    queueConsumer.accept(leaf);
                    updateRunnableList(leaf);
                    didSomething = true;
                }
                leaf = leaf.processNameNext;
            }
        }
        if (didSomething) {
            enqueueUpdateRunningList();
        }
        return didSomething;
    }

    @Override
    public void start(@NonNull ContentResolver resolver) {
        mFgConstants.startObserving(mHandler, resolver);
        mBgConstants.startObserving(mHandler, resolver);

        mService.registerUidObserver(new UidObserver() {
            @Override
            public void onUidStateChanged(int uid, int procState, long procStateSeq,
                    int capability) {
                synchronized (mService) {
                    if (procState == ActivityManager.PROCESS_STATE_TOP) {
                        mUidForeground.put(uid, true);
                    } else {
                        mUidForeground.delete(uid);
                    }
                    refreshProcessQueuesLocked(uid);
                }
            }
        }, ActivityManager.UID_OBSERVER_PROCSTATE,
                ActivityManager.PROCESS_STATE_TOP, "android");

        mService.registerUidObserver(new UidObserver() {
            @Override
            public void onUidStateChanged(int uid, int procState, long procStateSeq,
                    int capability) {
                synchronized (mService) {
                    if (procState > ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
                        mUidCached.put(uid, true);
                    } else {
                        mUidCached.delete(uid);
                    }
                    refreshProcessQueuesLocked(uid);
                }
            }
        }, ActivityManager.UID_OBSERVER_PROCSTATE,
                ActivityManager.PROCESS_STATE_LAST_ACTIVITY, "android");

        // Kick off periodic health checks
        mLocalHandler.sendEmptyMessage(MSG_CHECK_HEALTH);
    }

    @Override
    public boolean isIdleLocked() {
        return isIdleLocked(LOG_WRITER_INFO);
    }

    public boolean isIdleLocked(@NonNull PrintWriter pw) {
        return testAllProcessQueues(q -> q.isIdle(), "idle", pw);
    }

    @Override
    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime) {
        return isBeyondBarrierLocked(barrierTime, LOG_WRITER_INFO);
    }

    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime,
            @NonNull PrintWriter pw) {
        return testAllProcessQueues(q -> q.isBeyondBarrierLocked(barrierTime), "barrier", pw);
    }

    @Override
    public boolean isDispatchedLocked(@NonNull Intent intent) {
        return isDispatchedLocked(intent, LOG_WRITER_INFO);
    }

    public boolean isDispatchedLocked(@NonNull Intent intent, @NonNull PrintWriter pw) {
        return testAllProcessQueues(q -> q.isDispatched(intent),
                "dispatch of " + intent, pw);
    }

    @Override
    public void waitForIdle(@NonNull PrintWriter pw) {
        waitFor(() -> isIdleLocked(pw));
    }

    @Override
    public void waitForBarrier(@NonNull PrintWriter pw) {
        final long now = SystemClock.uptimeMillis();
        synchronized (mService) {
            forEachMatchingQueue(QUEUE_PREDICATE_ANY,
                    q -> q.addPrioritizeEarliestRequest());
        }
        try {
            waitFor(() -> isBeyondBarrierLocked(now, pw));
        } finally {
            synchronized (mService) {
                forEachMatchingQueue(QUEUE_PREDICATE_ANY,
                        q -> q.removePrioritizeEarliestRequest());
            }
        }
    }

    @Override
    public void waitForDispatched(@NonNull Intent intent, @NonNull PrintWriter pw) {
        waitFor(() -> isDispatchedLocked(intent, pw));
    }

    private void waitFor(@NonNull BooleanSupplier condition) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (mService) {
            mWaitingFor.add(Pair.create(condition, latch));
        }
        enqueueUpdateRunningList();
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkAndRemoveWaitingFor() {
        if (!mWaitingFor.isEmpty()) {
            mWaitingFor.removeIf((pair) -> {
                if (pair.first.getAsBoolean()) {
                    pair.second.countDown();
                    return true;
                } else {
                    return false;
                }
            });
        }
    }

    @Override
    public void forceDelayBroadcastDelivery(@NonNull String targetPackage,
            long delayedDurationMs) {
        synchronized (mService) {
            forEachMatchingQueue(
                    (q) -> targetPackage.equals(q.getPackageName()),
                    (q) -> q.forceDelayBroadcastDelivery(delayedDurationMs));
        }
    }

    @Override
    public String describeStateLocked() {
        return getRunningSize() + " running";
    }

    @Override
    public boolean isDelayBehindServices() {
        // TODO: implement
        return false;
    }

    @Override
    public void backgroundServicesFinishedLocked(int userId) {
        // TODO: implement
    }

    private void checkHealth() {
        synchronized (mService) {
            checkHealthLocked();
        }
    }

    private void checkHealthLocked() {
        try {
            assertHealthLocked();

            // If no health issues found above, check again in the future
            mLocalHandler.sendEmptyMessageDelayed(MSG_CHECK_HEALTH,
                    DateUtils.MINUTE_IN_MILLIS);

        } catch (Exception e) {
            // Throw up a message to indicate that something went wrong, and
            // dump current state for later inspection
            Slog.wtf(TAG, e);
            dumpToDropBoxLocked(e.toString());
        }
    }

    /**
     * Check overall health, confirming things are in a reasonable state and
     * that we're not wedged. If we determine we're in an unhealthy state, dump
     * current state once and stop future health checks to avoid spamming.
     */
    @VisibleForTesting
    void assertHealthLocked() {
        // Verify all runnable queues are sorted
        BroadcastProcessQueue prev = null;
        BroadcastProcessQueue next = mRunnableHead;
        while (next != null) {
            checkState(next.runnableAtPrev == prev, "runnableAtPrev");
            checkState(next.isRunnable(), "isRunnable " + next);
            if (prev != null) {
                checkState(next.getRunnableAt() >= prev.getRunnableAt(),
                        "getRunnableAt " + next + " vs " + prev);
            }
            prev = next;
            next = next.runnableAtNext;
        }

        // Verify all running queues are active
        for (BroadcastProcessQueue queue : mRunning) {
            if (queue != null) {
                checkState(queue.isActive(), "isActive " + queue);
            }
        }

        // Verify that pending cold start hasn't been orphaned
        if (mRunningColdStart != null) {
            checkState(getRunningIndexOf(mRunningColdStart) >= 0,
                    "isOrphaned " + mRunningColdStart);
        }

        // Verify health of all known process queues
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                leaf.assertHealthLocked();
                leaf = leaf.processNameNext;
            }
        }
    }

    @SuppressWarnings("CheckResult")
    private void updateWarmProcess(@NonNull BroadcastProcessQueue queue) {
        if (!queue.isProcessWarm()) {
            // This is a bit awkward; we're in the middle of traversing the
            // runnable queue, so we can't reorder that list if the runnable
            // time changes here. However, if this process was just found to be
            // warm via this operation, we're going to immediately promote it to
            // be running, and any side effect of this operation will then apply
            // after it's finished and is returned to the runnable list.
            queue.setProcessAndUidState(
                    mService.getProcessRecordLocked(queue.processName, queue.uid),
                    mUidForeground.get(queue.uid, false),
                    mUidCached.get(queue.uid, false));
        }
    }

    /**
     * Update the {@link ProcessRecord} associated with the given
     * {@link BroadcastProcessQueue}. Also updates any runnable status that
     * might have changed as a side-effect.
     */
    private void setQueueProcess(@NonNull BroadcastProcessQueue queue,
            @Nullable ProcessRecord app) {
        if (queue.setProcessAndUidState(app, mUidForeground.get(queue.uid, false),
                mUidCached.get(queue.uid, false))) {
            updateRunnableList(queue);
        }
    }

    /**
     * Refresh the process queues with the latest process state so that runnableAt
     * can be updated.
     */
    @GuardedBy("mService")
    private void refreshProcessQueuesLocked(int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            // Update internal state by refreshing values previously
            // read from any known running process
            setQueueProcess(leaf, leaf.app);
            leaf = leaf.processNameNext;
        }
        enqueueUpdateRunningList();
    }

    /**
     * Inform other parts of OS that the given broadcast queue has started
     * running, typically for internal bookkeeping.
     */
    private void notifyStartedRunning(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            queue.app.mReceivers.incrementCurReceivers();

            // Don't bump its LRU position if it's in the background restricted.
            if (mService.mInternal.getRestrictionLevel(
                    queue.uid) < ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                mService.updateLruProcessLocked(queue.app, false, null);
            }

            mService.mOomAdjuster.unfreezeTemporarily(queue.app,
                    CachedAppOptimizer.UNFREEZE_REASON_START_RECEIVER);

            if (queue.runningOomAdjusted) {
                queue.app.mState.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_RECEIVER);
                mService.enqueueOomAdjTargetLocked(queue.app);
            }
        }
    }

    /**
     * Inform other parts of OS that the given broadcast queue has stopped
     * running, typically for internal bookkeeping.
     */
    private void notifyStoppedRunning(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            queue.app.mReceivers.decrementCurReceivers();

            if (queue.runningOomAdjusted) {
                mService.enqueueOomAdjTargetLocked(queue.app);
            }
        }
    }

    /**
     * Inform other parts of OS that the given broadcast was just scheduled for
     * a registered receiver, typically for internal bookkeeping.
     */
    private void notifyScheduleRegisteredReceiver(@NonNull ProcessRecord app,
            @NonNull BroadcastRecord r, @NonNull BroadcastFilter receiver) {
        reportUsageStatsBroadcastDispatched(app, r);
    }

    /**
     * Inform other parts of OS that the given broadcast was just scheduled for
     * a manifest receiver, typically for internal bookkeeping.
     */
    private void notifyScheduleReceiver(@NonNull ProcessRecord app,
            @NonNull BroadcastRecord r, @NonNull ResolveInfo receiver) {
        reportUsageStatsBroadcastDispatched(app, r);

        final String receiverPackageName = receiver.activityInfo.packageName;
        app.addPackage(receiverPackageName,
                receiver.activityInfo.applicationInfo.longVersionCode, mService.mProcessStats);

        final boolean targetedBroadcast = r.intent.getComponent() != null;
        final boolean targetedSelf = Objects.equals(r.callerPackage, receiverPackageName);
        if (targetedBroadcast && !targetedSelf) {
            mService.mUsageStatsService.reportEvent(receiverPackageName,
                    r.userId, Event.APP_COMPONENT_USED);
        }

        mService.notifyPackageUse(receiverPackageName,
                PackageManager.NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER);

        mService.mPackageManagerInt.setPackageStoppedState(
                receiverPackageName, false, r.userId);
    }

    private void reportUsageStatsBroadcastDispatched(@NonNull ProcessRecord app,
            @NonNull BroadcastRecord r) {
        final long idForResponseEvent = (r.options != null)
                ? r.options.getIdForResponseEvent() : 0L;
        if (idForResponseEvent <= 0) return;

        final String targetPackage;
        if (r.intent.getPackage() != null) {
            targetPackage = r.intent.getPackage();
        } else if (r.intent.getComponent() != null) {
            targetPackage = r.intent.getComponent().getPackageName();
        } else {
            targetPackage = null;
        }
        if (targetPackage == null) return;

        mService.mUsageStatsService.reportBroadcastDispatched(r.callingUid, targetPackage,
                UserHandle.of(r.userId), idForResponseEvent, SystemClock.elapsedRealtime(),
                mService.getUidStateLocked(app.uid));
    }

    /**
     * Inform other parts of OS that the given broadcast was just finished,
     * typically for internal bookkeeping.
     */
    private void notifyFinishReceiver(@Nullable BroadcastProcessQueue queue,
            @Nullable ProcessRecord app, @NonNull BroadcastRecord r, int index,
            @NonNull Object receiver) {
        if (r.wasDeliveryAttempted(index)) {
            logBroadcastDeliveryEventReported(queue, app, r, index, receiver);
        }

        final boolean recordFinished = (r.terminalCount == r.receivers.size());
        if (recordFinished) {
            notifyFinishBroadcast(r);
        }
    }

    private void logBroadcastDeliveryEventReported(@Nullable BroadcastProcessQueue queue,
            @Nullable ProcessRecord app, @NonNull BroadcastRecord r, int index,
            @NonNull Object receiver) {
        // Report statistics for each individual receiver
        final int uid = getReceiverUid(receiver);
        final int senderUid = (r.callingUid == -1) ? Process.SYSTEM_UID : r.callingUid;
        final String actionName = r.intent.getAction();
        final int receiverType = (receiver instanceof BroadcastFilter)
                ? BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME
                : BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
        final int type;
        final int receiverProcessState;
        if (queue == null) {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_UNKNOWN;
            receiverProcessState = ActivityManager.PROCESS_STATE_UNKNOWN;
        } else if (queue.getActiveViaColdStart()) {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
            receiverProcessState = ActivityManager.PROCESS_STATE_NONEXISTENT;
        } else {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
            receiverProcessState = queue.lastProcessState;
        }
        // With the new per-process queues, there's no delay between being
        // "dispatched" and "scheduled", so we report no "receive delay"
        final long dispatchDelay = r.scheduledTime[index] - r.enqueueTime;
        final long receiveDelay = 0;
        final long finishDelay = r.terminalTime[index] - r.scheduledTime[index];
        if (queue != null) {
            final int packageState = queue.getActiveWasStopped()
                    ? SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED
                    : SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
            FrameworkStatsLog.write(BROADCAST_DELIVERY_EVENT_REPORTED, uid, senderUid, actionName,
                    receiverType, type, dispatchDelay, receiveDelay, finishDelay, packageState,
                    app != null ? app.info.packageName : null, r.callerPackage,
                    r.calculateTypeForLogging(), r.getDeliveryGroupPolicy(), r.intent.getFlags(),
                    BroadcastRecord.getReceiverPriority(receiver), r.callerProcState,
                    receiverProcessState);
        }
    }

    private void notifyFinishBroadcast(@NonNull BroadcastRecord r) {
        mService.notifyBroadcastFinishedLocked(r);
        r.finishTime = SystemClock.uptimeMillis();
        r.nextReceiver = r.receivers.size();
        mHistory.onBroadcastFinishedLocked(r);

        BroadcastQueueImpl.logBootCompletedBroadcastCompletionLatencyIfPossible(r);

        if (r.intent.getComponent() == null && r.intent.getPackage() == null
                && (r.intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
            int manifestCount = 0;
            int manifestSkipCount = 0;
            for (int i = 0; i < r.receivers.size(); i++) {
                if (r.receivers.get(i) instanceof ResolveInfo) {
                    manifestCount++;
                    if (r.delivery[i] == BroadcastRecord.DELIVERY_SKIPPED) {
                        manifestSkipCount++;
                    }
                }
            }

            final long dispatchTime = SystemClock.uptimeMillis() - r.enqueueTime;
            mService.addBroadcastStatLocked(r.intent.getAction(), r.callerPackage,
                    manifestCount, manifestSkipCount, dispatchTime);
        }
    }

    @VisibleForTesting
    @NonNull BroadcastProcessQueue getOrCreateProcessQueue(@NonNull ProcessRecord app) {
        return getOrCreateProcessQueue(app.processName, app.info.uid);
    }

    @VisibleForTesting
    @NonNull BroadcastProcessQueue getOrCreateProcessQueue(@NonNull String processName,
            int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                return leaf;
            } else if (leaf.processNameNext == null) {
                break;
            }
            leaf = leaf.processNameNext;
        }

        BroadcastProcessQueue created = new BroadcastProcessQueue(mConstants, processName, uid);
        setQueueProcess(created, mService.getProcessRecordLocked(processName, uid));

        if (leaf == null) {
            mProcessQueues.put(uid, created);
        } else {
            leaf.processNameNext = created;
        }
        return created;
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue getProcessQueue(@NonNull ProcessRecord app) {
        return getProcessQueue(app.processName, app.info.uid);
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue getProcessQueue(@NonNull String processName, int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                return leaf;
            }
            leaf = leaf.processNameNext;
        }
        return null;
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue removeProcessQueue(@NonNull ProcessRecord app) {
        return removeProcessQueue(app.processName, app.info.uid);
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue removeProcessQueue(@NonNull String processName,
            int uid) {
        BroadcastProcessQueue prev = null;
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                if (prev != null) {
                    prev.processNameNext = leaf.processNameNext;
                } else {
                    if (leaf.processNameNext != null) {
                        mProcessQueues.put(uid, leaf.processNameNext);
                    } else {
                        mProcessQueues.remove(uid);
                    }
                }
                return leaf;
            }
            prev = leaf;
            leaf = leaf.processNameNext;
        }
        return null;
    }

    @Override
    @NeverCompile
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(BroadcastQueueProto.QUEUE_NAME, mQueueName);
        mHistory.dumpDebug(proto);
        proto.end(token);
    }

    @Override
    @NeverCompile
    public boolean dumpLocked(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args, int opti, boolean dumpConstants, boolean dumpHistory,
            boolean dumpAll, @Nullable String dumpPackage, boolean needSep) {
        final long now = SystemClock.uptimeMillis();
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.increaseIndent();
        ipw.println();

        ipw.println("📋 Per-process queues:");
        ipw.increaseIndent();
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                leaf.dumpLocked(now, ipw);
                leaf = leaf.processNameNext;
            }
        }
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("🧍 Runnable:");
        ipw.increaseIndent();
        if (mRunnableHead == null) {
            ipw.println("(none)");
        } else {
            BroadcastProcessQueue queue = mRunnableHead;
            while (queue != null) {
                TimeUtils.formatDuration(queue.getRunnableAt(), now, ipw);
                ipw.print(' ');
                ipw.print(reasonToString(queue.getRunnableAtReason()));
                ipw.print(' ');
                ipw.print(queue.toShortString());
                ipw.println();
                queue = queue.runnableAtNext;
            }
        }
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("🏃 Running:");
        ipw.increaseIndent();
        for (BroadcastProcessQueue queue : mRunning) {
            if ((queue != null) && (queue == mRunningColdStart)) {
                ipw.print("🥶 ");
            } else {
                ipw.print("\u3000 ");
            }
            if (queue != null) {
                ipw.println(queue.toShortString());
            } else {
                ipw.println("(none)");
            }
        }
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("Broadcasts with ignored delivery group policies:");
        ipw.increaseIndent();
        mService.dumpDeliveryGroupPolicyIgnoredActions(ipw);
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("Cached UIDs:");
        ipw.increaseIndent();
        ipw.println(mUidCached);
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("Foreground UIDs:");
        ipw.increaseIndent();
        ipw.println(mUidForeground);
        ipw.decreaseIndent();
        ipw.println();

        if (dumpConstants) {
            mConstants.dump(ipw);
        }

        if (dumpHistory) {
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            needSep = mHistory.dumpLocked(ipw, dumpPackage, mQueueName, sdf, dumpAll, needSep);
        }
        return needSep;
    }
}
