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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.BroadcastRecord.getReceiverProcessName;
import static com.android.server.am.BroadcastRecord.getReceiverUid;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_START_RECEIVER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.RemoteServiceException.CannotDeliverBroadcastException;
import android.app.UidObserver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Alternative {@link BroadcastQueue} implementation which pivots broadcasts to
 * be dispatched on a per-process basis.
 * <p>
 * Each process now has its own broadcast queue represented by a
 * {@link BroadcastProcessQueue} instance. Each queue has a concept of being
 * "runnable at" a particular time in the future, which supports arbitrarily
 * pausing or delaying delivery on a per-process basis.
 */
class BroadcastQueueModernImpl extends BroadcastQueue {
    BroadcastQueueModernImpl(ActivityManagerService service, Handler handler,
            BroadcastConstants constants) {
        this(service, handler, constants, new BroadcastSkipPolicy(service),
                new BroadcastHistory());
    }

    BroadcastQueueModernImpl(ActivityManagerService service, Handler handler,
            BroadcastConstants constants, BroadcastSkipPolicy skipPolicy,
            BroadcastHistory history) {
        super(service, handler, "modern", constants, skipPolicy, history);
        mLocalHandler = new Handler(handler.getLooper(), mLocalCallback);
    }

    // TODO: add support for ordered broadcasts
    // TODO: add support for replacing pending broadcasts
    // TODO: add support for merging pending broadcasts

    // TODO: add trace points for debugging broadcast flows
    // TODO: record broadcast state change timing statistics
    // TODO: record historical broadcast statistics

    // TODO: pause queues for apps involved in backup/restore
    // TODO: pause queues when background services are running
    // TODO: pause queues when processes are frozen

    // TODO: clean up queues for removed apps

    /**
     * Maximum number of process queues to dispatch broadcasts to
     * simultaneously.
     */
    // TODO: shift hard-coded defaults to BroadcastConstants
    private static final int MAX_RUNNING_PROCESS_QUEUES = 4;

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
     * Collection of queues which are "runnable". They're sorted by
     * {@link BroadcastProcessQueue#getRunnableAt()} so that we prefer
     * dispatching of longer-waiting broadcasts first.
     */
    @GuardedBy("mService")
    private final ArrayList<BroadcastProcessQueue> mRunnable = new ArrayList<>();

    /**
     * Collection of queues which are "running". This will never be larger than
     * {@link #MAX_RUNNING_PROCESS_QUEUES}.
     */
    @GuardedBy("mService")
    private final ArrayList<BroadcastProcessQueue> mRunning = new ArrayList<>();

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

    private static final int MSG_UPDATE_RUNNING_LIST = 1;

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
        }
        return false;
    };

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
        if (mRunning.contains(queue)) {
            // Already running; they'll be reinserted into the runnable list
            // once they finish running, so no need to update them now
            return;
        }

        // TODO: better optimize by using insertion sort data structure
        mRunnable.remove(queue);
        if (queue.isRunnable()) {
            mRunnable.add(queue);
        }
        mRunnable.sort(null);
    }

    /**
     * Consider updating the list of "running" queues.
     * <p>
     * This method can promote "runnable" queues to become "running", subject to
     * a maximum of {@link #MAX_RUNNING_PROCESS_QUEUES} warm processes and only
     * one pending cold-start.
     */
    @GuardedBy("mService")
    private void updateRunningList() {
        int avail = MAX_RUNNING_PROCESS_QUEUES - mRunning.size();
        if (avail == 0) return;

        // If someone is waiting to go idle, everything is runnable now
        final boolean waitingForIdle = !mWaitingForIdle.isEmpty();

        // We're doing an update now, so remove any future update requests;
        // we'll repost below if needed
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);

        boolean updateOomAdj = false;
        final long now = SystemClock.uptimeMillis();
        for (int i = 0; i < mRunnable.size() && avail > 0; i++) {
            final BroadcastProcessQueue queue = mRunnable.get(i);
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
                    continue;
                }
            }

            if (DEBUG_BROADCAST) logv("Promoting " + queue
                    + " from runnable to running; process is " + queue.app);

            // Allocate this available permit and start running!
            mRunnable.remove(i);
            mRunning.add(queue);
            avail--;
            i--;

            queue.makeActiveNextPending();

            // If we're already warm, schedule it; otherwise we'll wait for the
            // cold start to circle back around
            if (processWarm) {
                scheduleReceiverWarmLocked(queue);
            } else {
                scheduleReceiverColdLocked(queue);
            }

            mService.enqueueOomAdjTargetLocked(queue.app);
            updateOomAdj = true;
        }

        if (updateOomAdj) {
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);
        }

        if (waitingForIdle && isIdleLocked()) {
            mWaitingForIdle.forEach((latch) -> latch.countDown());
            mWaitingForIdle.clear();
        }
    }

    @Override
    public boolean onApplicationAttachedLocked(@NonNull ProcessRecord app) {
        boolean didSomething = false;
        if ((mRunningColdStart != null) && (mRunningColdStart.app == app)) {
            // We've been waiting for this app to cold start, and it's ready
            // now; dispatch its next broadcast and clear the slot
            scheduleReceiverWarmLocked(mRunningColdStart);
            mRunningColdStart = null;

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
        }

        return didSomething;
    }

    @Override
    public int getPreferredSchedulingGroupLocked(@NonNull ProcessRecord app) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if ((queue != null) && mRunning.contains(queue)) {
            return queue.getPreferredSchedulingGroupLocked();
        }
        return ProcessList.SCHED_GROUP_UNDEFINED;
    }

    @Override
    public void enqueueBroadcastLocked(@NonNull BroadcastRecord r) {
        // TODO: handle empty receivers to deliver result immediately
        if (r.receivers == null) return;

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

    private void scheduleReceiverColdLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        final BroadcastRecord r = queue.getActive();
        final Object receiver = queue.getActiveReceiver();

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
        if (queue.app == null) {
            mRunningColdStart = null;
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
        }
    }

    private void scheduleReceiverWarmLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        final ProcessRecord app = queue.app;
        final BroadcastRecord r = queue.getActive();
        final Object receiver = queue.getActiveReceiver();

        // TODO: schedule ANR timeout trigger event
        // TODO: apply temp allowlist exemptions
        // TODO: apply background activity launch exemptions

        if (mSkipPolicy.shouldSkip(r, receiver)) {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_SKIPPED);
            return;
        }

        final Intent receiverIntent = r.getReceiverIntent(receiver);
        if (receiverIntent == null) {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_SKIPPED);
            return;
        }

        if (DEBUG_BROADCAST) logv("Scheduling " + r + " to warm " + app);
        final IApplicationThread thread = app.getThread();
        if (thread != null) {
            try {
                queue.setActiveDeliveryState(BroadcastRecord.DELIVERY_SCHEDULED);
                if (receiver instanceof BroadcastFilter) {
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
                    thread.scheduleReceiver(receiverIntent, ((ResolveInfo) receiver).activityInfo,
                            null, r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId,
                            app.mState.getReportedProcState());
                }
            } catch (RemoteException e) {
                finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
                synchronized (app.mService) {
                    app.scheduleCrashLocked(TAG, CannotDeliverBroadcastException.TYPE_ID, null);
                }
            }
        } else {
            finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE);
        }
    }

    @Override
    public boolean finishReceiverLocked(@NonNull ProcessRecord app, int resultCode,
            @Nullable String resultData, @Nullable Bundle resultExtras, boolean resultAbort,
            boolean waitForServices) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        return finishReceiverLocked(queue, BroadcastRecord.DELIVERY_DELIVERED);
    }

    private boolean finishReceiverLocked(@NonNull BroadcastProcessQueue queue, int deliveryState) {
        checkState(queue.isActive(), "isActive");

        if (deliveryState != BroadcastRecord.DELIVERY_DELIVERED) {
            Slog.w(TAG, "Failed delivery of " + queue.getActive() + " to " + queue);
        }

        queue.setActiveDeliveryState(deliveryState);

        // TODO: cancel any outstanding ANR timeout
        // TODO: limit number of broadcasts in a row to avoid starvation
        // TODO: if we're the last receiver of this broadcast, record to history

        if (queue.isRunnable() && queue.isProcessWarm()) {
            // We're on a roll; move onto the next broadcast for this process
            queue.makeActiveNextPending();
            scheduleReceiverWarmLocked(queue);
            return true;
        } else {
            // We've drained running broadcasts; maybe move back to runnable
            queue.makeActiveIdle();
            mRunning.remove(queue);
            // App is no longer running a broadcast, so update its OOM
            // adjust during our next pass; no need for an immediate update
            mService.enqueueOomAdjTargetLocked(queue.app);
            updateRunnableList(queue);
            enqueueUpdateRunningList();
            return false;
        }
    }

    @Override
    public boolean cleanupDisabledPackageReceiversLocked(String packageName,
            Set<String> filterByClasses, int userId, boolean doit) {
        // TODO: implement
        return false;
    }

    @Override
    void start(@NonNull ContentResolver resolver) {
        super.start(resolver);

        mService.registerUidObserver(new UidObserver() {
            @Override
            public void onUidCachedChanged(int uid, boolean cached) {
                synchronized (mService) {
                    BroadcastProcessQueue leaf = mProcessQueues.get(uid);
                    while (leaf != null) {
                        leaf.setProcessCached(cached);
                        updateRunnableList(leaf);
                        leaf = leaf.next;
                    }
                    enqueueUpdateRunningList();
                }
            }
        }, ActivityManager.UID_OBSERVER_CACHED, 0, "android");
    }

    @Override
    public boolean isIdleLocked() {
        return mRunnable.isEmpty() && mRunning.isEmpty();
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
        return mRunnable.size() + " runnable, " + mRunning.size() + " running";
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

    private void updateWarmProcess(@NonNull BroadcastProcessQueue queue) {
        if (!queue.isProcessWarm()) {
            queue.app = mService.getProcessRecordLocked(queue.processName, queue.uid);
        }
    }

    private @NonNull BroadcastProcessQueue getOrCreateProcessQueue(@NonNull ProcessRecord app) {
        return getOrCreateProcessQueue(app.processName, app.info.uid);
    }

    private @NonNull BroadcastProcessQueue getOrCreateProcessQueue(@NonNull String processName,
            int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                return leaf;
            } else if (leaf.next == null) {
                break;
            }
            leaf = leaf.next;
        }

        BroadcastProcessQueue created = new BroadcastProcessQueue(processName, uid);
        created.app = mService.getProcessRecordLocked(processName, uid);

        if (leaf == null) {
            mProcessQueues.put(uid, created);
        } else {
            leaf.next = created;
        }
        return created;
    }

    private @Nullable BroadcastProcessQueue getProcessQueue(@NonNull ProcessRecord app) {
        return getProcessQueue(app.processName, app.info.uid);
    }

    private @Nullable BroadcastProcessQueue getProcessQueue(@NonNull String processName, int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                return leaf;
            }
            leaf = leaf.next;
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
                leaf = leaf.next;
            }
        }
        ipw.decreaseIndent();

        ipw.println();
        ipw.println("🧍 Runnable:");
        ipw.increaseIndent();
        if (mRunnable.isEmpty()) {
            ipw.println("(none)");
        } else {
            for (BroadcastProcessQueue queue : mRunnable) {
                TimeUtils.formatDuration(queue.getRunnableAt(), now, ipw);
                ipw.print(' ');
                ipw.println(queue.toShortString());
            }
        }
        ipw.decreaseIndent();

        ipw.println();
        ipw.println("🏃 Running:");
        ipw.increaseIndent();
        if (mRunning.isEmpty()) {
            ipw.println("(none)");
        } else {
            for (BroadcastProcessQueue queue : mRunning) {
                if (queue == mRunningColdStart) {
                    ipw.print("🥶 ");
                } else {
                    ipw.print("\u3000 ");
                }
                ipw.println(queue.toShortString());
            }
        }
        ipw.decreaseIndent();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        needSep = mHistory.dumpLocked(ipw, dumpPackage, mQueueName, sdf, dumpAll, needSep);
        return needSep;
    }
}
