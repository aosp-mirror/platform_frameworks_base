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

import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;

import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.BroadcastProcessQueue.insertIntoRunnableList;
import static com.android.server.am.BroadcastProcessQueue.removeFromRunnableList;
import static com.android.server.am.BroadcastRecord.deliveryStateToString;
import static com.android.server.am.BroadcastRecord.getReceiverPackageName;
import static com.android.server.am.BroadcastRecord.getReceiverProcessName;
import static com.android.server.am.BroadcastRecord.getReceiverUid;
import static com.android.server.am.BroadcastRecord.isDeliveryStateTerminal;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_FINISH_RECEIVER;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_START_RECEIVER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.RemoteServiceException.CannotDeliverBroadcastException;
import android.app.UidObserver;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
                new BroadcastHistory());
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
        mRunning = new BroadcastProcessQueue[mConstants.MAX_RUNNING_PROCESS_QUEUES];
    }

    // TODO: add support for replacing pending broadcasts
    // TODO: add support for merging pending broadcasts

    // TODO: consider reordering foreground broadcasts within queue

    // TODO: pause queues when background services are running
    // TODO: pause queues when processes are frozen

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
     * Collection of latches waiting for queue to go idle.
     */
    @GuardedBy("mService")
    private final ArrayList<CountDownLatch> mWaitingForIdle = new ArrayList<>();

    private final BroadcastConstants mConstants;
    private final BroadcastConstants mFgConstants;
    private final BroadcastConstants mBgConstants;

    private static final int MSG_UPDATE_RUNNING_LIST = 1;
    private static final int MSG_DELIVERY_TIMEOUT = 2;
    private static final int MSG_BG_ACTIVITY_START_TIMEOUT = 3;

    private void enqueueUpdateRunningList() {
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);
        mLocalHandler.sendEmptyMessage(MSG_UPDATE_RUNNING_LIST);
    }

    private final Handler mLocalHandler;

    private final Handler.Callback mLocalCallback = (msg) -> {
        switch (msg.what) {
            case MSG_UPDATE_RUNNING_LIST: {
                synchronized (mService) {
                    updateRunningList();
                }
                return true;
            }
            case MSG_DELIVERY_TIMEOUT: {
                synchronized (mService) {
                    finishReceiverLocked((BroadcastProcessQueue) msg.obj,
                            BroadcastRecord.DELIVERY_TIMEOUT);
                }
                return true;
            }
            case MSG_BG_ACTIVITY_START_TIMEOUT: {
                synchronized (mService) {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final ProcessRecord app = (ProcessRecord) args.arg1;
                    final BroadcastRecord r = (BroadcastRecord) args.arg2;
                    args.recycle();
                    app.removeAllowBackgroundActivityStartsToken(r);
                }
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

    /**
     * Consider updating the list of "running" queues.
     * <p>
     * This method can promote "runnable" queues to become "running", subject to
     * a maximum of {@link BroadcastConstants#MAX_RUNNING_PROCESS_QUEUES} warm
     * processes and only one pending cold-start.
     */
    @GuardedBy("mService")
    private void updateRunningList() {
        int avail = mRunning.length - getRunningSize();
        if (avail == 0) return;

        final int cookie = traceBegin(TAG, "updateRunningList");
        final long now = SystemClock.uptimeMillis();

        // If someone is waiting to go idle, everything is runnable now
        final boolean waitingForIdle = !mWaitingForIdle.isEmpty();

        // We're doing an update now, so remove any future update requests;
        // we'll repost below if needed
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);

        boolean updateOomAdj = false;
        BroadcastProcessQueue queue = mRunnableHead;
        while (queue != null && avail > 0) {
            BroadcastProcessQueue nextQueue = queue.runnableAtNext;
            final long runnableAt = queue.getRunnableAt();

            // If queues beyond this point aren't ready to run yet, schedule
            // another pass when they'll be runnable
            if (runnableAt > now && !waitingForIdle) {
                mLocalHandler.sendEmptyMessageAtTime(MSG_UPDATE_RUNNING_LIST, runnableAt);
                break;
            }

            // We might not have heard about a newly running process yet, so
            // consider refreshing if we think we're cold
            updateWarmProcess(queue);

            final boolean processWarm = queue.isProcessWarm();
            if (!processWarm) {
                // We only offer to run one cold-start at a time to preserve
                // system resources; below we either claim that single slot or
                // skip to look for another warm process
                if (mRunningColdStart == null) {
                    mRunningColdStart = queue;
                } else {
                    // Move to considering next runnable queue
                    queue = nextQueue;
                    continue;
                }
            }

            if (DEBUG_BROADCAST) logv("Promoting " + queue
                    + " from runnable to running; process is " + queue.app);

            // Allocate this available permit and start running!
            final int queueIndex = getRunningIndexOf(null);
            mRunning[queueIndex] = queue;
            avail--;

            // Remove ourselves from linked list of runnable things
            mRunnableHead = removeFromRunnableList(mRunnableHead, queue);

            // Emit all trace events for this process into a consistent track
            queue.traceTrackName = TAG + ".mRunning[" + queueIndex + "]";

            // If we're already warm, boost OOM adjust now; if cold we'll boost
            // it after the app has been started
            if (processWarm) {
                notifyStartedRunning(queue);
            }

            // If we're already warm, schedule next pending broadcast now;
            // otherwise we'll wait for the cold start to circle back around
            queue.makeActiveNextPending();
            if (processWarm) {
                queue.traceProcessRunningBegin();
                scheduleReceiverWarmLocked(queue);
            } else {
                queue.traceProcessStartingBegin();
                scheduleReceiverColdLocked(queue);
            }

            // We've moved at least one process into running state above, so we
            // need to kick off an OOM adjustment pass
            updateOomAdj = true;

            // Move to considering next runnable queue
            queue = nextQueue;
        }

        if (updateOomAdj) {
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);
        }

        if (waitingForIdle && isIdleLocked()) {
            mWaitingForIdle.forEach((latch) -> latch.countDown());
            mWaitingForIdle.clear();
        }

        traceEnd(TAG, cookie);
    }

    @Override
    public boolean onApplicationAttachedLocked(@NonNull ProcessRecord app) {
        boolean didSomething = false;
        if ((mRunningColdStart != null) && (mRunningColdStart.app == app)) {
            // We've been waiting for this app to cold start, and it's ready
            // now; dispatch its next broadcast and clear the slot
            final BroadcastProcessQueue queue = mRunningColdStart;
            mRunningColdStart = null;

            queue.traceProcessEnd();
            queue.traceProcessRunningBegin();
            scheduleReceiverWarmLocked(queue);

            // We might be willing to kick off another cold start
            enqueueUpdateRunningList();
            didSomething = true;
        }
        return didSomething;
    }

    @Override
    public boolean onApplicationTimeoutLocked(@NonNull ProcessRecord app) {
        return onApplicationCleanupLocked(app);
    }

    @Override
    public boolean onApplicationProblemLocked(@NonNull ProcessRecord app) {
        return onApplicationCleanupLocked(app);
    }

    @Override
    public boolean onApplicationCleanupLocked(@NonNull ProcessRecord app) {
        boolean didSomething = false;
        if ((mRunningColdStart != null) && (mRunningColdStart.app == app)) {
            // We've been waiting for this app to cold start, and it had
            // trouble; clear the slot and fail delivery below
            mRunningColdStart = null;

            // We might be willing to kick off another cold start
            enqueueUpdateRunningList();
            didSomething = true;
        }

        final BroadcastProcessQueue queue = getProcessQueue(app);
        if (queue != null) {
            queue.app = null;

            // If queue was running a broadcast, fail it
            if (queue.isActive()) {
                finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
                didSomething = true;
            }

            // If queue has nothing else pending, consider cleaning it
            if (queue.isEmpty()) {
                updateRunnableList(queue);
            }
        }

        return didSomething;
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
        // TODO: handle empty receivers to deliver result immediately
        if (r.receivers == null) return;

        final IntentFilter removeMatchingFilter = (r.options != null)
                ? r.options.getRemoveMatchingFilter() : null;
        if (removeMatchingFilter != null) {
            final Predicate<Intent> removeMatching = removeMatchingFilter.asPredicate();
            skipMatchingBroadcasts(QUEUE_PREDICATE_ANY, (testRecord, testReceiver) -> {
                // We only allow caller to clear broadcasts they enqueued
                return (testRecord.callingUid == r.callingUid)
                        && removeMatching.test(testRecord.intent);
            });
        }

        r.enqueueTime = SystemClock.uptimeMillis();
        r.enqueueRealTime = SystemClock.elapsedRealtime();
        r.enqueueClockTime = System.currentTimeMillis();

        for (int i = 0; i < r.receivers.size(); i++) {
            final Object receiver = r.receivers.get(i);
            final BroadcastProcessQueue queue = getOrCreateProcessQueue(
                    getReceiverProcessName(receiver), getReceiverUid(receiver));
            queue.enqueueBroadcast(r, i);
            updateRunnableList(queue);
            enqueueUpdateRunningList();
        }
    }

    /**
     * Schedule the currently active broadcast on the given queue when we know
     * the process is cold. This kicks off a cold start and will eventually call
     * through to {@link #scheduleReceiverWarmLocked} once it's ready.
     */
    private void scheduleReceiverColdLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        // Remember that active broadcast was scheduled via a cold start
        queue.setActiveViaColdStart(true);

        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        final ApplicationInfo info = ((ResolveInfo) receiver).activityInfo.applicationInfo;
        final ComponentName component = ((ResolveInfo) receiver).activityInfo.getComponentName();

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
        if (queue.app != null) {
            notifyStartedRunning(queue);
        } else {
            mRunningColdStart = null;
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
        }
    }

    /**
     * Schedule the currently active broadcast on the given queue when we know
     * the process is warm.
     * <p>
     * There is a <em>very strong</em> preference to consistently handle all
     * results by calling through to {@link #finishReceiverLocked}, both in the
     * case where a broadcast is handled by a remote app, and the case where the
     * broadcast was finished locally without the remote app being involved.
     */
    private void scheduleReceiverWarmLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        final ProcessRecord app = queue.app;
        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        // If someone already finished this broadcast, finish immediately
        final int oldDeliveryState = getDeliveryState(r, index);
        if (isDeliveryStateTerminal(oldDeliveryState)) {
            finishReceiverLocked(queue, oldDeliveryState);
            return;
        }

        // Consider additional cases where we'd want fo finish immediately
        if (app.isInFullBackup()) {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_SKIPPED);
            return;
        }
        if (mSkipPolicy.shouldSkip(r, receiver)) {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_SKIPPED);
            return;
        }
        final Intent receiverIntent = r.getReceiverIntent(receiver);
        if (receiverIntent == null) {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_SKIPPED);
            return;
        }

        if (mService.mProcessesReady && !r.timeoutExempt) {
            final long timeout = r.isForeground() ? mFgConstants.TIMEOUT : mBgConstants.TIMEOUT;
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_DELIVERY_TIMEOUT, queue), timeout);
        }

        if (r.allowBackgroundActivityStarts) {
            app.addOrUpdateAllowBackgroundActivityStartsToken(r, r.mBackgroundActivityStartsToken);

            final long timeout = r.isForeground() ? mFgConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT
                    : mBgConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT;
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = app;
            args.arg2 = r;
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_BG_ACTIVITY_START_TIMEOUT, args), timeout);
        }

        if (r.options != null && r.options.getTemporaryAppAllowlistDuration() > 0) {
            mService.tempAllowlistUidLocked(queue.uid,
                    r.options.getTemporaryAppAllowlistDuration(),
                    r.options.getTemporaryAppAllowlistReasonCode(), r.toShortString(),
                    r.options.getTemporaryAppAllowlistType(), r.callingUid);
        }

        if (DEBUG_BROADCAST) logv("Scheduling " + r + " to warm " + app);
        setDeliveryState(queue, app, r, index, receiver, BroadcastRecord.DELIVERY_SCHEDULED);

        final IApplicationThread thread = app.getThread();
        if (thread != null) {
            try {
                if (receiver instanceof BroadcastFilter) {
                    notifyScheduleRegisteredReceiver(app, r, (BroadcastFilter) receiver);
                    thread.scheduleRegisteredReceiver(
                            ((BroadcastFilter) receiver).receiverList.receiver, receiverIntent,
                            r.resultCode, r.resultData, r.resultExtras, r.ordered, r.initialSticky,
                            r.userId, app.mState.getReportedProcState());

                    // TODO: consider making registered receivers of unordered
                    // broadcasts report results to detect ANRs
                    if (!r.ordered) {
                        finishReceiverLocked(queue, BroadcastRecord.DELIVERY_DELIVERED);
                    }
                } else {
                    notifyScheduleReceiver(app, r, (ResolveInfo) receiver);
                    thread.scheduleReceiver(receiverIntent, ((ResolveInfo) receiver).activityInfo,
                            null, r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId,
                            app.mState.getReportedProcState());
                }
            } catch (RemoteException e) {
                final String msg = "Failed to schedule " + r + " to " + receiver
                        + " via " + app + ": " + e;
                Slog.w(TAG, msg);
                app.scheduleCrashLocked(msg, CannotDeliverBroadcastException.TYPE_ID, null);
                app.setKilled(true);
                finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
            }
        } else {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
        }
    }

    /**
     * Schedule the final {@link BroadcastRecord#resultTo} delivery for an
     * ordered broadcast; assumes the sender is still a warm process.
     */
    private void scheduleResultTo(@NonNull BroadcastRecord r) {
        if ((r.callerApp == null) || (r.resultTo == null)) return;
        final ProcessRecord app = r.callerApp;
        final IApplicationThread thread = app.getThread();
        if (thread != null) {
            mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(
                    app, OOM_ADJ_REASON_FINISH_RECEIVER);
            try {
                thread.scheduleRegisteredReceiver(r.resultTo, r.intent,
                        r.resultCode, r.resultData, r.resultExtras, false, r.initialSticky,
                        r.userId, app.mState.getReportedProcState());
            } catch (RemoteException e) {
                final String msg = "Failed to schedule result of " + r + " via " + app + ": " + e;
                Slog.w(TAG, msg);
                app.scheduleCrashLocked(msg, CannotDeliverBroadcastException.TYPE_ID, null);
            }
        }
    }

    @Override
    public boolean finishReceiverLocked(@NonNull ProcessRecord app, int resultCode,
            @Nullable String resultData, @Nullable Bundle resultExtras, boolean resultAbort,
            boolean waitForServices) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        final BroadcastRecord r = queue.getActive();
        r.resultCode = resultCode;
        r.resultData = resultData;
        r.resultExtras = resultExtras;
        if (!r.isNoAbort()) {
            r.resultAbort = resultAbort;
        }

        // When the caller aborted an ordered broadcast, we mark all remaining
        // receivers as skipped
        if (r.ordered && r.resultAbort) {
            for (int i = r.finishedCount + 1; i < r.receivers.size(); i++) {
                setDeliveryState(null, null, r, i, r.receivers.get(i),
                        BroadcastRecord.DELIVERY_SKIPPED);
            }
        }

        return finishReceiverLocked(queue, BroadcastRecord.DELIVERY_DELIVERED);
    }

    private boolean finishReceiverLocked(@NonNull BroadcastProcessQueue queue,
            @DeliveryState int deliveryState) {
        checkState(queue.isActive(), "isActive");

        final ProcessRecord app = queue.app;
        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        setDeliveryState(queue, app, r, index, receiver, deliveryState);

        if (deliveryState == BroadcastRecord.DELIVERY_TIMEOUT) {
            r.anrCount++;
            if (app != null && !app.isDebugging()) {
                mService.appNotResponding(queue.app, TimeoutRecord
                        .forBroadcastReceiver("Broadcast of " + r.toShortString()));
            }
        } else {
            mLocalHandler.removeMessages(MSG_DELIVERY_TIMEOUT, queue);
        }

        // Even if we have more broadcasts, if we've made reasonable progress
        // and someone else is waiting, retire ourselves to avoid starvation
        final boolean shouldRetire = (mRunnableHead != null)
                && (queue.getActiveCountSinceIdle() >= mConstants.MAX_RUNNING_ACTIVE_BROADCASTS);

        if (queue.isRunnable() && queue.isProcessWarm() && !shouldRetire) {
            // We're on a roll; move onto the next broadcast for this process
            queue.makeActiveNextPending();
            scheduleReceiverWarmLocked(queue);
            return true;
        } else {
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
            return false;
        }
    }

    /**
     * Set the delivery state on the given broadcast, then apply any additional
     * bookkeeping related to ordered broadcasts.
     */
    private void setDeliveryState(@Nullable BroadcastProcessQueue queue,
            @Nullable ProcessRecord app, @NonNull BroadcastRecord r, int index,
            @NonNull Object receiver, @DeliveryState int newDeliveryState) {
        final int oldDeliveryState = getDeliveryState(r, index);

        if (newDeliveryState != BroadcastRecord.DELIVERY_DELIVERED) {
            Slog.w(TAG, "Delivery state of " + r + " to " + receiver
                    + " via " + app + " changed from "
                    + deliveryStateToString(oldDeliveryState) + " to "
                    + deliveryStateToString(newDeliveryState));
        }

        // Only apply state when we haven't already reached a terminal state;
        // this is how we ignore racing timeout messages
        if (!isDeliveryStateTerminal(oldDeliveryState)) {
            r.setDeliveryState(index, newDeliveryState);
        }

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
            r.finishedCount++;
            notifyFinishReceiver(queue, r, index, receiver);

            if (r.ordered) {
                if (r.finishedCount < r.receivers.size()) {
                    // We just finished an ordered receiver, which means the
                    // next receiver might now be runnable
                    final Object nextReceiver = r.receivers.get(r.finishedCount);
                    final BroadcastProcessQueue nextQueue = getProcessQueue(
                            getReceiverProcessName(nextReceiver), getReceiverUid(nextReceiver));
                    nextQueue.invalidateRunnableAt();
                    updateRunnableList(nextQueue);
                } else {
                    // Everything finished, so deliver final result
                    scheduleResultTo(r);
                }
            }
        }
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
        }
        return skipMatchingBroadcasts(queuePredicate, broadcastPredicate);
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
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_SKIPPED);
    };

    private boolean skipMatchingBroadcasts(
            @NonNull Predicate<BroadcastProcessQueue> queuePredicate,
            @NonNull BroadcastPredicate broadcastPredicate) {
        // Note that we carefully preserve any "skipped" broadcasts in their
        // queues so that we follow our normal flow for "finishing" a broadcast,
        // which is where we handle things like ordered broadcasts.
        boolean didSomething = false;
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                if (queuePredicate.test(leaf)) {
                    didSomething |= leaf.removeMatchingBroadcasts(broadcastPredicate,
                            mBroadcastConsumerSkip);
                }
                leaf = leaf.processNameNext;
            }
        }
        return didSomething;
    }

    @Override
    public void start(@NonNull ContentResolver resolver) {
        mFgConstants.startObserving(mHandler, resolver);
        mBgConstants.startObserving(mHandler, resolver);

        mService.registerUidObserver(new UidObserver() {
            @Override
            public void onUidCachedChanged(int uid, boolean cached) {
                synchronized (mService) {
                    BroadcastProcessQueue leaf = mProcessQueues.get(uid);
                    while (leaf != null) {
                        leaf.setProcessCached(cached);
                        updateRunnableList(leaf);
                        leaf = leaf.processNameNext;
                    }
                    enqueueUpdateRunningList();
                }
            }
        }, ActivityManager.UID_OBSERVER_CACHED, 0, "android");
    }

    @Override
    public boolean isIdleLocked() {
        return (mRunnableHead == null) && (getRunningSize() == 0);
    }

    @Override
    public void waitForIdle(@Nullable PrintWriter pw) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (mService) {
            mWaitingForIdle.add(latch);
        }
        enqueueUpdateRunningList();
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void waitForBarrier(@Nullable PrintWriter pw) {
        // TODO: implement
        throw new UnsupportedOperationException();
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

    private int traceBegin(String trackName, String methodName) {
        final int cookie = methodName.hashCode();
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                trackName, methodName, cookie);
        return cookie;
    }

    private void traceEnd(String trackName, int cookie) {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                trackName, cookie);
    }

    private void updateWarmProcess(@NonNull BroadcastProcessQueue queue) {
        if (!queue.isProcessWarm()) {
            queue.app = mService.getProcessRecordLocked(queue.processName, queue.uid);
        }
    }

    /**
     * Inform other parts of OS that the given broadcast queue has started
     * running, typically for internal bookkeeping.
     */
    private void notifyStartedRunning(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            queue.app.mReceivers.incrementCurReceivers();

            queue.app.mState.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_RECEIVER);

            // Don't bump its LRU position if it's in the background restricted.
            if (mService.mInternal.getRestrictionLevel(
                    queue.uid) < ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                mService.updateLruProcessLocked(queue.app, false, null);
            }

            mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(queue.app,
                    OOM_ADJ_REASON_START_RECEIVER);

            mService.enqueueOomAdjTargetLocked(queue.app);
        }
    }

    /**
     * Inform other parts of OS that the given broadcast queue has stopped
     * running, typically for internal bookkeeping.
     */
    private void notifyStoppedRunning(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            // Update during our next pass; no need for an immediate update
            mService.enqueueOomAdjTargetLocked(queue.app);

            queue.app.mReceivers.decrementCurReceivers();
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
            @NonNull BroadcastRecord r, int index, @NonNull Object receiver) {
        // Report statistics for each individual receiver
        final int uid = getReceiverUid(receiver);
        final int senderUid = (r.callingUid == -1) ? Process.SYSTEM_UID : r.callingUid;
        final String actionName = ActivityManagerService.getShortAction(r.intent.getAction());
        final int receiverType = (receiver instanceof BroadcastFilter)
                ? BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME
                : BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
        final int type;
        if (queue == null) {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_UNKNOWN;
        } else if (queue.getActiveViaColdStart()) {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
        } else {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
        }
        // With the new per-process queues, there's no delay between being
        // "dispatched" and "scheduled", so we report no "receive delay"
        final long dispatchDelay = r.scheduledTime[index] - r.enqueueTime;
        final long receiveDelay = 0;
        final long finishDelay = r.duration[index];
        FrameworkStatsLog.write(BROADCAST_DELIVERY_EVENT_REPORTED, uid, senderUid, actionName,
                receiverType, type, dispatchDelay, receiveDelay, finishDelay);

        final boolean recordFinished = (r.finishedCount == r.receivers.size());
        if (recordFinished) {
            mHistory.addBroadcastToHistoryLocked(r);

            r.nextReceiver = r.receivers.size();
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
        created.app = mService.getProcessRecordLocked(processName, uid);

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
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(BroadcastQueueProto.QUEUE_NAME, mQueueName);
        mHistory.dumpDebug(proto);
        proto.end(token);
    }

    @Override
    public boolean dumpLocked(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args, int opti, boolean dumpAll, @Nullable String dumpPackage,
            boolean needSep) {
        final long now = SystemClock.uptimeMillis();
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.increaseIndent();

        ipw.println();
        ipw.println("📋 Per-process queues:");
        ipw.increaseIndent();
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                leaf.dumpLocked(ipw);
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
                ipw.println(queue.toShortString());
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

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        needSep = mHistory.dumpLocked(ipw, dumpPackage, mQueueName, sdf, dumpAll, needSep);
        return needSep;
    }
}
