/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;
import static android.text.TextUtils.formatSimple;

import static com.android.internal.util.FrameworkStatsLog.BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED__EVENT__BOOT_COMPLETED;
import static com.android.internal.util.FrameworkStatsLog.BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED__EVENT__LOCKED_BOOT_COMPLETED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_FINISH_RECEIVER;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_START_RECEIVER;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.PowerExemptionManager.TempAllowListType;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.IPermissionManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

/**
 * BROADCASTS
 *
 * We keep three broadcast queues and associated bookkeeping, one for those at
 * foreground priority, and one for normal (background-priority) broadcasts, and one to
 * offload special broadcasts that we know take a long time, such as BOOT_COMPLETED.
 */
public final class BroadcastQueue {
    private static final String TAG = "BroadcastQueue";
    private static final String TAG_MU = TAG + POSTFIX_MU;
    private static final String TAG_BROADCAST = TAG + POSTFIX_BROADCAST;

    static final int MAX_BROADCAST_HISTORY = ActivityManager.isLowRamDeviceStatic() ? 10 : 50;
    static final int MAX_BROADCAST_SUMMARY_HISTORY
            = ActivityManager.isLowRamDeviceStatic() ? 25 : 300;

    final ActivityManagerService mService;

    /**
     * Behavioral parameters such as timeouts and deferral policy, tracking Settings
     * for runtime configurability
     */
    final BroadcastConstants mConstants;

    /**
     * Recognizable moniker for this queue
     */
    final String mQueueName;

    /**
     * If true, we can delay broadcasts while waiting services to finish in the previous
     * receiver's process.
     */
    final boolean mDelayBehindServices;

    /**
     * Lists of all active broadcasts that are to be executed immediately
     * (without waiting for another broadcast to finish).  Currently this only
     * contains broadcasts to registered receivers, to avoid spinning up
     * a bunch of processes to execute IntentReceiver components.  Background-
     * and foreground-priority broadcasts are queued separately.
     */
    final ArrayList<BroadcastRecord> mParallelBroadcasts = new ArrayList<>();

    /**
     * Tracking of the ordered broadcast queue, including deferral policy and alarm
     * prioritization.
     */
    final BroadcastDispatcher mDispatcher;

    /**
     * Refcounting for completion callbacks of split/deferred broadcasts.  The key
     * is an opaque integer token assigned lazily when a broadcast is first split
     * into multiple BroadcastRecord objects.
     */
    final SparseIntArray mSplitRefcounts = new SparseIntArray();
    private int mNextToken = 0;

    /**
     * Historical data of past broadcasts, for debugging.  This is a ring buffer
     * whose last element is at mHistoryNext.
     */
    final BroadcastRecord[] mBroadcastHistory = new BroadcastRecord[MAX_BROADCAST_HISTORY];
    int mHistoryNext = 0;

    /**
     * Summary of historical data of past broadcasts, for debugging.  This is a
     * ring buffer whose last element is at mSummaryHistoryNext.
     */
    final Intent[] mBroadcastSummaryHistory = new Intent[MAX_BROADCAST_SUMMARY_HISTORY];
    int mSummaryHistoryNext = 0;

    /**
     * Various milestone timestamps of entries in the mBroadcastSummaryHistory ring
     * buffer, also tracked via the mSummaryHistoryNext index.  These are all in wall
     * clock time, not elapsed.
     */
    final long[] mSummaryHistoryEnqueueTime = new  long[MAX_BROADCAST_SUMMARY_HISTORY];
    final long[] mSummaryHistoryDispatchTime = new  long[MAX_BROADCAST_SUMMARY_HISTORY];
    final long[] mSummaryHistoryFinishTime = new  long[MAX_BROADCAST_SUMMARY_HISTORY];

    /**
     * Set when we current have a BROADCAST_INTENT_MSG in flight.
     */
    boolean mBroadcastsScheduled = false;

    /**
     * True if we have a pending unexpired BROADCAST_TIMEOUT_MSG posted to our handler.
     */
    boolean mPendingBroadcastTimeoutMessage;

    /**
     * Intent broadcasts that we have tried to start, but are
     * waiting for the application's process to be created.  We only
     * need one per scheduling class (instead of a list) because we always
     * process broadcasts one at a time, so no others can be started while
     * waiting for this one.
     */
    BroadcastRecord mPendingBroadcast = null;

    /**
     * The receiver index that is pending, to restart the broadcast if needed.
     */
    int mPendingBroadcastRecvIndex;

    static final int BROADCAST_INTENT_MSG = ActivityManagerService.FIRST_BROADCAST_QUEUE_MSG;
    static final int BROADCAST_TIMEOUT_MSG = ActivityManagerService.FIRST_BROADCAST_QUEUE_MSG + 1;

    // log latency metrics for ordered broadcasts during BOOT_COMPLETED processing
    boolean mLogLatencyMetrics = true;

    final BroadcastHandler mHandler;

    private final class BroadcastHandler extends Handler {
        public BroadcastHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BROADCAST_INTENT_MSG: {
                    if (DEBUG_BROADCAST) Slog.v(
                            TAG_BROADCAST, "Received BROADCAST_INTENT_MSG ["
                            + mQueueName + "]");
                    processNextBroadcast(true);
                } break;
                case BROADCAST_TIMEOUT_MSG: {
                    synchronized (mService) {
                        broadcastTimeoutLocked(true);
                    }
                } break;
            }
        }
    }

    BroadcastQueue(ActivityManagerService service, Handler handler,
            String name, BroadcastConstants constants, boolean allowDelayBehindServices) {
        mService = service;
        mHandler = new BroadcastHandler(handler.getLooper());
        mQueueName = name;
        mDelayBehindServices = allowDelayBehindServices;

        mConstants = constants;
        mDispatcher = new BroadcastDispatcher(this, mConstants, mHandler, mService);
    }

    void start(ContentResolver resolver) {
        mDispatcher.start();
        mConstants.startObserving(mHandler, resolver);
    }

    @Override
    public String toString() {
        return mQueueName;
    }

    public boolean isPendingBroadcastProcessLocked(int pid) {
        return mPendingBroadcast != null && mPendingBroadcast.curApp.getPid() == pid;
    }

    boolean isPendingBroadcastProcessLocked(ProcessRecord app) {
        return mPendingBroadcast != null && mPendingBroadcast.curApp == app;
    }

    public void enqueueParallelBroadcastLocked(BroadcastRecord r) {
        r.enqueueClockTime = System.currentTimeMillis();
        r.enqueueTime = SystemClock.uptimeMillis();
        r.enqueueRealTime = SystemClock.elapsedRealtime();
        mParallelBroadcasts.add(r);
        enqueueBroadcastHelper(r);
    }

    public void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        r.enqueueClockTime = System.currentTimeMillis();
        r.enqueueTime = SystemClock.uptimeMillis();
        r.enqueueRealTime = SystemClock.elapsedRealtime();
        mDispatcher.enqueueOrderedBroadcastLocked(r);
        enqueueBroadcastHelper(r);
    }

    /**
     * Don't call this method directly; call enqueueParallelBroadcastLocked or
     * enqueueOrderedBroadcastLocked.
     */
    private void enqueueBroadcastHelper(BroadcastRecord r) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                createBroadcastTraceTitle(r, BroadcastRecord.DELIVERY_PENDING),
                System.identityHashCode(r));
        }
    }

    /**
     * Find the same intent from queued parallel broadcast, replace with a new one and return
     * the old one.
     */
    public final BroadcastRecord replaceParallelBroadcastLocked(BroadcastRecord r) {
        return replaceBroadcastLocked(mParallelBroadcasts, r, "PARALLEL");
    }

    /**
     * Find the same intent from queued ordered broadcast, replace with a new one and return
     * the old one.
     */
    public final BroadcastRecord replaceOrderedBroadcastLocked(BroadcastRecord r) {
        return mDispatcher.replaceBroadcastLocked(r, "ORDERED");
    }

    private BroadcastRecord replaceBroadcastLocked(ArrayList<BroadcastRecord> queue,
            BroadcastRecord r, String typeForLogging) {
        final Intent intent = r.intent;
        for (int i = queue.size() - 1; i > 0; i--) {
            final BroadcastRecord old = queue.get(i);
            if (old.userId == r.userId && intent.filterEquals(old.intent)) {
                if (DEBUG_BROADCAST) {
                    Slog.v(TAG_BROADCAST, "***** DROPPING "
                            + typeForLogging + " [" + mQueueName + "]: " + intent);
                }
                queue.set(i, r);
                return old;
            }
        }
        return null;
    }

    private final void processCurBroadcastLocked(BroadcastRecord r,
            ProcessRecord app) throws RemoteException {
        if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                "Process cur broadcast " + r + " for app " + app);
        final IApplicationThread thread = app.getThread();
        if (thread == null) {
            throw new RemoteException();
        }
        if (app.isInFullBackup()) {
            skipReceiverLocked(r);
            return;
        }

        r.receiver = thread.asBinder();
        r.curApp = app;
        final ProcessReceiverRecord prr = app.mReceivers;
        prr.addCurReceiver(r);
        app.mState.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_RECEIVER);
        // Don't bump its LRU position if it's in the background restricted.
        if (mService.mInternal.getRestrictionLevel(app.info.packageName, app.userId)
                < RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
            mService.updateLruProcessLocked(app, false, null);
        }
        // Make sure the oom adj score is updated before delivering the broadcast.
        // Force an update, even if there are other pending requests, overall it still saves time,
        // because time(updateOomAdj(N apps)) <= N * time(updateOomAdj(1 app)).
        mService.enqueueOomAdjTargetLocked(app);
        mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);

        // Tell the application to launch this receiver.
        maybeReportBroadcastDispatchedEventLocked(r, r.curReceiver.applicationInfo.uid);
        r.intent.setComponent(r.curComponent);

        boolean started = false;
        try {
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                    "Delivering to component " + r.curComponent
                    + ": " + r);
            mService.notifyPackageUse(r.intent.getComponent().getPackageName(),
                                      PackageManager.NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER);
            thread.scheduleReceiver(new Intent(r.intent), r.curReceiver,
                    mService.compatibilityInfoForPackage(r.curReceiver.applicationInfo),
                    r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId,
                    app.mState.getReportedProcState());
            if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                    "Process cur broadcast " + r + " DELIVERED for app " + app);
            started = true;
        } finally {
            if (!started) {
                if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                        "Process cur broadcast " + r + ": NOT STARTED!");
                r.receiver = null;
                r.curApp = null;
                prr.removeCurReceiver(r);
            }
        }

        // if something bad happens here, launch the app and try again
        if (app.isKilled()) {
            throw new RemoteException("app gets killed during broadcasting");
        }
    }

    /**
     * Called by ActivityManagerService to notify that the uid has process started, if there is any
     * deferred BOOT_COMPLETED broadcast, the BroadcastDispatcher can dispatch the broadcast now.
     * @param uid
     */
    public void updateUidReadyForBootCompletedBroadcastLocked(int uid) {
        mDispatcher.updateUidReadyForBootCompletedBroadcastLocked(uid);
    }

    public boolean sendPendingBroadcastsLocked(ProcessRecord app) {
        boolean didSomething = false;
        final BroadcastRecord br = mPendingBroadcast;
        if (br != null && br.curApp.getPid() > 0 && br.curApp.getPid() == app.getPid()) {
            if (br.curApp != app) {
                Slog.e(TAG, "App mismatch when sending pending broadcast to "
                        + app.processName + ", intended target is " + br.curApp.processName);
                return false;
            }
            try {
                mPendingBroadcast = null;
                br.mIsReceiverAppRunning = false;
                processCurBroadcastLocked(br, app);
                didSomething = true;
            } catch (Exception e) {
                Slog.w(TAG, "Exception in new application when starting receiver "
                        + br.curComponent.flattenToShortString(), e);
                logBroadcastReceiverDiscardLocked(br);
                finishReceiverLocked(br, br.resultCode, br.resultData,
                        br.resultExtras, br.resultAbort, false);
                scheduleBroadcastsLocked();
                // We need to reset the state if we failed to start the receiver.
                br.state = BroadcastRecord.IDLE;
                throw new RuntimeException(e.getMessage());
            }
        }
        return didSomething;
    }

    public void skipPendingBroadcastLocked(int pid) {
        final BroadcastRecord br = mPendingBroadcast;
        if (br != null && br.curApp.getPid() == pid) {
            br.state = BroadcastRecord.IDLE;
            br.nextReceiver = mPendingBroadcastRecvIndex;
            mPendingBroadcast = null;
            scheduleBroadcastsLocked();
        }
    }

    // Skip the current receiver, if any, that is in flight to the given process
    public void skipCurrentReceiverLocked(ProcessRecord app) {
        BroadcastRecord r = null;
        final BroadcastRecord curActive = mDispatcher.getActiveBroadcastLocked();
        if (curActive != null && curActive.curApp == app) {
            // confirmed: the current active broadcast is to the given app
            r = curActive;
        }

        // If the current active broadcast isn't this BUT we're waiting for
        // mPendingBroadcast to spin up the target app, that's what we use.
        if (r == null && mPendingBroadcast != null && mPendingBroadcast.curApp == app) {
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                    "[" + mQueueName + "] skip & discard pending app " + r);
            r = mPendingBroadcast;
        }

        if (r != null) {
            skipReceiverLocked(r);
        }
    }

    private void skipReceiverLocked(BroadcastRecord r) {
        logBroadcastReceiverDiscardLocked(r);
        finishReceiverLocked(r, r.resultCode, r.resultData,
                r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();
    }

    public void scheduleBroadcastsLocked() {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Schedule broadcasts ["
                + mQueueName + "]: current="
                + mBroadcastsScheduled);

        if (mBroadcastsScheduled) {
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(BROADCAST_INTENT_MSG, this));
        mBroadcastsScheduled = true;
    }

    public BroadcastRecord getMatchingOrderedReceiver(IBinder receiver) {
        BroadcastRecord br = mDispatcher.getActiveBroadcastLocked();
        if (br != null && br.receiver == receiver) {
            return br;
        }
        return null;
    }

    // > 0 only, no worry about "eventual" recycling
    private int nextSplitTokenLocked() {
        int next = mNextToken + 1;
        if (next <= 0) {
            next = 1;
        }
        mNextToken = next;
        return next;
    }

    private void postActivityStartTokenRemoval(ProcessRecord app, BroadcastRecord r) {
        // the receiver had run for less than allowed bg activity start timeout,
        // so allow the process to still start activities from bg for some more time
        String msgToken = (app.toShortString() + r.toString()).intern();
        // first, if there exists a past scheduled request to remove this token, drop
        // that request - we don't want the token to be swept from under our feet...
        mHandler.removeCallbacksAndMessages(msgToken);
        // ...then schedule the removal of the token after the extended timeout
        mHandler.postAtTime(() -> {
            synchronized (mService) {
                app.removeAllowBackgroundActivityStartsToken(r);
            }
        }, msgToken, (r.receiverTime + mConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT));
    }

    public boolean finishReceiverLocked(BroadcastRecord r, int resultCode,
            String resultData, Bundle resultExtras, boolean resultAbort, boolean waitForServices) {
        final int state = r.state;
        final ActivityInfo receiver = r.curReceiver;
        final long finishTime = SystemClock.uptimeMillis();
        final long elapsed = finishTime - r.receiverTime;
        r.state = BroadcastRecord.IDLE;
        final int curIndex = r.nextReceiver - 1;
        if (curIndex >= 0 && curIndex < r.receivers.size() && r.curApp != null) {
            final Object curReceiver = r.receivers.get(curIndex);
            FrameworkStatsLog.write(BROADCAST_DELIVERY_EVENT_REPORTED, r.curApp.uid,
                    r.callingUid == -1 ? Process.SYSTEM_UID : r.callingUid,
                    r.intent.getAction(),
                    curReceiver instanceof BroadcastFilter
                    ? BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME
                    : BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST,
                    r.mIsReceiverAppRunning
                    ? BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM
                    : BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD,
                    r.dispatchTime - r.enqueueTime,
                    r.receiverTime - r.dispatchTime,
                    finishTime - r.receiverTime);
        }
        if (state == BroadcastRecord.IDLE) {
            Slog.w(TAG_BROADCAST, "finishReceiver [" + mQueueName + "] called but state is IDLE");
        }
        if (r.allowBackgroundActivityStarts && r.curApp != null) {
            if (elapsed > mConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT) {
                // if the receiver has run for more than allowed bg activity start timeout,
                // just remove the token for this process now and we're done
                r.curApp.removeAllowBackgroundActivityStartsToken(r);
            } else {
                // It gets more time; post the removal to happen at the appropriate moment
                postActivityStartTokenRemoval(r.curApp, r);
            }
        }
        // If we're abandoning this broadcast before any receivers were actually spun up,
        // nextReceiver is zero; in which case time-to-process bookkeeping doesn't apply.
        if (r.nextReceiver > 0) {
            r.duration[r.nextReceiver - 1] = elapsed;
        }

        // if this receiver was slow, impose deferral policy on the app.  This will kick in
        // when processNextBroadcastLocked() next finds this uid as a receiver identity.
        if (!r.timeoutExempt) {
            // r.curApp can be null if finish has raced with process death - benign
            // edge case, and we just ignore it because we're already cleaning up
            // as expected.
            if (r.curApp != null
                    && mConstants.SLOW_TIME > 0 && elapsed > mConstants.SLOW_TIME) {
                // Core system packages are exempt from deferral policy
                if (!UserHandle.isCore(r.curApp.uid)) {
                    if (DEBUG_BROADCAST_DEFERRAL) {
                        Slog.i(TAG_BROADCAST, "Broadcast receiver " + (r.nextReceiver - 1)
                                + " was slow: " + receiver + " br=" + r);
                    }
                    mDispatcher.startDeferring(r.curApp.uid);
                } else {
                    if (DEBUG_BROADCAST_DEFERRAL) {
                        Slog.i(TAG_BROADCAST, "Core uid " + r.curApp.uid
                                + " receiver was slow but not deferring: "
                                + receiver + " br=" + r);
                    }
                }
            }
        } else {
            if (DEBUG_BROADCAST_DEFERRAL) {
                Slog.i(TAG_BROADCAST, "Finished broadcast " + r.intent.getAction()
                        + " is exempt from deferral policy");
            }
        }

        r.receiver = null;
        r.intent.setComponent(null);
        if (r.curApp != null && r.curApp.mReceivers.hasCurReceiver(r)) {
            r.curApp.mReceivers.removeCurReceiver(r);
            mService.enqueueOomAdjTargetLocked(r.curApp);
        }
        if (r.curFilter != null) {
            r.curFilter.receiverList.curBroadcast = null;
        }
        r.curFilter = null;
        r.curReceiver = null;
        r.curApp = null;
        mPendingBroadcast = null;

        r.resultCode = resultCode;
        r.resultData = resultData;
        r.resultExtras = resultExtras;
        if (resultAbort && (r.intent.getFlags()&Intent.FLAG_RECEIVER_NO_ABORT) == 0) {
            r.resultAbort = resultAbort;
        } else {
            r.resultAbort = false;
        }

        // If we want to wait behind services *AND* we're finishing the head/
        // active broadcast on its queue
        if (waitForServices && r.curComponent != null && r.queue.mDelayBehindServices
                && r.queue.mDispatcher.getActiveBroadcastLocked() == r) {
            ActivityInfo nextReceiver;
            if (r.nextReceiver < r.receivers.size()) {
                Object obj = r.receivers.get(r.nextReceiver);
                nextReceiver = (obj instanceof ActivityInfo) ? (ActivityInfo)obj : null;
            } else {
                nextReceiver = null;
            }
            // Don't do this if the next receive is in the same process as the current one.
            if (receiver == null || nextReceiver == null
                    || receiver.applicationInfo.uid != nextReceiver.applicationInfo.uid
                    || !receiver.processName.equals(nextReceiver.processName)) {
                // In this case, we are ready to process the next receiver for the current broadcast,
                // but are on a queue that would like to wait for services to finish before moving
                // on.  If there are background services currently starting, then we will go into a
                // special state where we hold off on continuing this broadcast until they are done.
                if (mService.mServices.hasBackgroundServicesLocked(r.userId)) {
                    Slog.i(TAG, "Delay finish: " + r.curComponent.flattenToShortString());
                    r.state = BroadcastRecord.WAITING_SERVICES;
                    return false;
                }
            }
        }

        r.curComponent = null;

        // We will process the next receiver right now if this is finishing
        // an app receiver (which is always asynchronous) or after we have
        // come back from calling a receiver.
        return state == BroadcastRecord.APP_RECEIVE
                || state == BroadcastRecord.CALL_DONE_RECEIVE;
    }

    public void backgroundServicesFinishedLocked(int userId) {
        BroadcastRecord br = mDispatcher.getActiveBroadcastLocked();
        if (br != null) {
            if (br.userId == userId && br.state == BroadcastRecord.WAITING_SERVICES) {
                Slog.i(TAG, "Resuming delayed broadcast");
                br.curComponent = null;
                br.state = BroadcastRecord.IDLE;
                processNextBroadcastLocked(false, false);
            }
        }
    }

    void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver,
            Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky, int sendingUser,
            int receiverUid, int callingUid, long dispatchDelay,
            long receiveDelay) throws RemoteException {
        // Send the intent to the receiver asynchronously using one-way binder calls.
        if (app != null) {
            final IApplicationThread thread = app.getThread();
            if (thread != null) {
                // If we have an app thread, do the call through that so it is
                // correctly ordered with other one-way calls.
                try {
                    thread.scheduleRegisteredReceiver(receiver, intent, resultCode,
                            data, extras, ordered, sticky, sendingUser,
                            app.mState.getReportedProcState());
                } catch (RemoteException ex) {
                    // Failed to call into the process. It's either dying or wedged. Kill it gently.
                    synchronized (mService) {
                        final String msg = "Failed to schedule " + intent + " to " + receiver
                                + " via " + app + ": " + ex;
                        Slog.w(TAG, msg);
                        app.killLocked("Can't deliver broadcast", ApplicationExitInfo.REASON_OTHER,
                                ApplicationExitInfo.SUBREASON_UNDELIVERED_BROADCAST, true);
                    }
                    throw ex;
                }
            } else {
                // Application has died. Receiver doesn't exist.
                throw new RemoteException("app.thread must not be null");
            }
        } else {
            receiver.performReceive(intent, resultCode, data, extras, ordered,
                    sticky, sendingUser);
        }
        if (!ordered) {
            FrameworkStatsLog.write(BROADCAST_DELIVERY_EVENT_REPORTED,
                    receiverUid == -1 ? Process.SYSTEM_UID : receiverUid,
                    callingUid == -1 ? Process.SYSTEM_UID : callingUid,
                    intent.getAction(),
                    BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME,
                    BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM,
                    dispatchDelay, receiveDelay, 0 /* finish_delay */);
        }
    }

    private void deliverToRegisteredReceiverLocked(BroadcastRecord r,
            BroadcastFilter filter, boolean ordered, int index) {
        boolean skip = false;
        if (r.options != null && !r.options.testRequireCompatChange(filter.owningUid)) {
            Slog.w(TAG, "Compat change filtered: broadcasting " + r.intent.toString()
                    + " to uid " + filter.owningUid + " due to compat change "
                    + r.options.getRequireCompatChangeId());
            skip = true;
        }
        if (!mService.validateAssociationAllowedLocked(r.callerPackage, r.callingUid,
                filter.packageName, filter.owningUid)) {
            Slog.w(TAG, "Association not allowed: broadcasting "
                    + r.intent.toString()
                    + " from " + r.callerPackage + " (pid=" + r.callingPid
                    + ", uid=" + r.callingUid + ") to " + filter.packageName + " through "
                    + filter);
            skip = true;
        }
        if (!skip && !mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                r.callingPid, r.resolvedType, filter.receiverList.uid)) {
            Slog.w(TAG, "Firewall blocked: broadcasting "
                    + r.intent.toString()
                    + " from " + r.callerPackage + " (pid=" + r.callingPid
                    + ", uid=" + r.callingUid + ") to " + filter.packageName + " through "
                    + filter);
            skip = true;
        }
        // Check that the sender has permission to send to this receiver
        if (filter.requiredPermission != null) {
            int perm = mService.checkComponentPermission(filter.requiredPermission,
                    r.callingPid, r.callingUid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires " + filter.requiredPermission
                        + " due to registered receiver " + filter);
                skip = true;
            } else {
                final int opCode = AppOpsManager.permissionToOpCode(filter.requiredPermission);
                if (opCode != AppOpsManager.OP_NONE
                        && mService.getAppOpsManager().noteOpNoThrow(opCode, r.callingUid,
                        r.callerPackage, r.callerFeatureId, "Broadcast sent to protected receiver")
                        != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid="
                            + r.callingPid + ", uid=" + r.callingUid + ")"
                            + " requires appop " + AppOpsManager.permissionToOp(
                                    filter.requiredPermission)
                            + " due to registered receiver " + filter);
                    skip = true;
                }
            }
        }

        if (!skip && (filter.receiverList.app == null || filter.receiverList.app.isKilled()
                || filter.receiverList.app.mErrorState.isCrashing())) {
            Slog.w(TAG, "Skipping deliver [" + mQueueName + "] " + r
                    + " to " + filter.receiverList + ": process gone or crashing");
            skip = true;
        }

        // Ensure that broadcasts are only sent to other Instant Apps if they are marked as
        // visible to Instant Apps.
        final boolean visibleToInstantApps =
                (r.intent.getFlags() & Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0;

        if (!skip && !visibleToInstantApps && filter.instantApp
                && filter.receiverList.uid != r.callingUid) {
            Slog.w(TAG, "Instant App Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")"
                    + " not specifying FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS");
            skip = true;
        }

        if (!skip && !filter.visibleToInstantApp && r.callerInstantApp
                && filter.receiverList.uid != r.callingUid) {
            Slog.w(TAG, "Instant App Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " requires receiver be visible to instant apps"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            skip = true;
        }

        // Check that the receiver has the required permission(s) to receive this broadcast.
        if (!skip && r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            for (int i = 0; i < r.requiredPermissions.length; i++) {
                String requiredPermission = r.requiredPermissions[i];
                int perm = mService.checkComponentPermission(requiredPermission,
                        filter.receiverList.pid, filter.receiverList.uid, -1, true);
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: receiving "
                            + r.intent.toString()
                            + " to " + filter.receiverList.app
                            + " (pid=" + filter.receiverList.pid
                            + ", uid=" + filter.receiverList.uid + ")"
                            + " requires " + requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
                int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                        && mService.getAppOpsManager().noteOpNoThrow(appOp,
                        filter.receiverList.uid, filter.packageName, filter.featureId,
                        "Broadcast delivered to registered receiver " + filter.receiverId)
                        != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: receiving "
                            + r.intent.toString()
                            + " to " + filter.receiverList.app
                            + " (pid=" + filter.receiverList.pid
                            + ", uid=" + filter.receiverList.uid + ")"
                            + " requires appop " + AppOpsManager.permissionToOp(
                            requiredPermission)
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
            }
        }
        if (!skip && (r.requiredPermissions == null || r.requiredPermissions.length == 0)) {
            int perm = mService.checkComponentPermission(null,
                    filter.receiverList.pid, filter.receiverList.uid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: security check failed when receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }
        // Check that the receiver does *not* have any excluded permissions
        if (!skip && r.excludedPermissions != null && r.excludedPermissions.length > 0) {
            for (int i = 0; i < r.excludedPermissions.length; i++) {
                String excludedPermission = r.excludedPermissions[i];
                final int perm = mService.checkComponentPermission(excludedPermission,
                        filter.receiverList.pid, filter.receiverList.uid, -1, true);

                int appOp = AppOpsManager.permissionToOpCode(excludedPermission);
                if (appOp != AppOpsManager.OP_NONE) {
                    // When there is an app op associated with the permission,
                    // skip when both the permission and the app op are
                    // granted.
                    if ((perm == PackageManager.PERMISSION_GRANTED) && (
                            mService.getAppOpsManager().checkOpNoThrow(appOp,
                                    filter.receiverList.uid,
                                    filter.packageName)
                                    == AppOpsManager.MODE_ALLOWED)) {
                        Slog.w(TAG, "Appop Denial: receiving "
                                + r.intent.toString()
                                + " to " + filter.receiverList.app
                                + " (pid=" + filter.receiverList.pid
                                + ", uid=" + filter.receiverList.uid + ")"
                                + " excludes appop " + AppOpsManager.permissionToOp(
                                excludedPermission)
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")");
                        skip = true;
                        break;
                    }
                } else {
                    // When there is no app op associated with the permission,
                    // skip when permission is granted.
                    if (perm == PackageManager.PERMISSION_GRANTED) {
                        Slog.w(TAG, "Permission Denial: receiving "
                                + r.intent.toString()
                                + " to " + filter.receiverList.app
                                + " (pid=" + filter.receiverList.pid
                                + ", uid=" + filter.receiverList.uid + ")"
                                + " excludes " + excludedPermission
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")");
                        skip = true;
                        break;
                    }
                }
            }
        }

        // Check that the receiver does *not* belong to any of the excluded packages
        if (!skip && r.excludedPackages != null && r.excludedPackages.length > 0) {
            if (ArrayUtils.contains(r.excludedPackages, filter.packageName)) {
                Slog.w(TAG, "Skipping delivery of excluded package "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " excludes package " + filter.packageName
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }

        // If the broadcast also requires an app op check that as well.
        if (!skip && r.appOp != AppOpsManager.OP_NONE
                && mService.getAppOpsManager().noteOpNoThrow(r.appOp,
                filter.receiverList.uid, filter.packageName, filter.featureId,
                "Broadcast delivered to registered receiver " + filter.receiverId)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Appop Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " requires appop " + AppOpsManager.opToName(r.appOp)
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            skip = true;
        }

        // Ensure that broadcasts are only sent to other apps if they are explicitly marked as
        // exported, or are System level broadcasts
        if (!skip && !filter.exported && mService.checkComponentPermission(null, r.callingPid,
                r.callingUid, filter.receiverList.uid, filter.exported)
                != PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "Exported Denial: sending "
                    + r.intent.toString()
                    + ", action: " + r.intent.getAction()
                    + " from " + r.callerPackage
                    + " (uid=" + r.callingUid + ")"
                    + " due to receiver " + filter.receiverList.app
                    + " (uid " + filter.receiverList.uid + ")"
                    + " not specifying RECEIVER_EXPORTED");
            skip = true;
        }

        if (skip) {
            r.delivery[index] = BroadcastRecord.DELIVERY_SKIPPED;
            return;
        }

        // If permissions need a review before any of the app components can run, we drop
        // the broadcast and if the calling app is in the foreground and the broadcast is
        // explicit we launch the review UI passing it a pending intent to send the skipped
        // broadcast.
        if (!requestStartTargetPermissionsReviewIfNeededLocked(r, filter.packageName,
                filter.owningUserId)) {
            r.delivery[index] = BroadcastRecord.DELIVERY_SKIPPED;
            return;
        }

        r.delivery[index] = BroadcastRecord.DELIVERY_DELIVERED;

        // If this is not being sent as an ordered broadcast, then we
        // don't want to touch the fields that keep track of the current
        // state of ordered broadcasts.
        if (ordered) {
            r.receiver = filter.receiverList.receiver.asBinder();
            r.curFilter = filter;
            filter.receiverList.curBroadcast = r;
            r.state = BroadcastRecord.CALL_IN_RECEIVE;
            if (filter.receiverList.app != null) {
                // Bump hosting application to no longer be in background
                // scheduling class.  Note that we can't do that if there
                // isn't an app...  but we can only be in that case for
                // things that directly call the IActivityManager API, which
                // are already core system stuff so don't matter for this.
                r.curApp = filter.receiverList.app;
                filter.receiverList.app.mReceivers.addCurReceiver(r);
                mService.enqueueOomAdjTargetLocked(r.curApp);
                mService.updateOomAdjPendingTargetsLocked(
                        OOM_ADJ_REASON_START_RECEIVER);
            }
        } else if (filter.receiverList.app != null) {
            mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(filter.receiverList.app,
                    OOM_ADJ_REASON_START_RECEIVER);
        }

        try {
            if (DEBUG_BROADCAST_LIGHT) Slog.i(TAG_BROADCAST,
                    "Delivering to " + filter + " : " + r);
            if (filter.receiverList.app != null && filter.receiverList.app.isInFullBackup()) {
                // Skip delivery if full backup in progress
                // If it's an ordered broadcast, we need to continue to the next receiver.
                if (ordered) {
                    skipReceiverLocked(r);
                }
            } else {
                r.receiverTime = SystemClock.uptimeMillis();
                maybeAddAllowBackgroundActivityStartsToken(filter.receiverList.app, r);
                maybeScheduleTempAllowlistLocked(filter.owningUid, r, r.options);
                maybeReportBroadcastDispatchedEventLocked(r, filter.owningUid);
                performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver,
                        new Intent(r.intent), r.resultCode, r.resultData,
                        r.resultExtras, r.ordered, r.initialSticky, r.userId,
                        filter.receiverList.uid, r.callingUid,
                        r.dispatchTime - r.enqueueTime,
                        r.receiverTime - r.dispatchTime);
                // parallel broadcasts are fire-and-forget, not bookended by a call to
                // finishReceiverLocked(), so we manage their activity-start token here
                if (filter.receiverList.app != null
                        && r.allowBackgroundActivityStarts && !r.ordered) {
                    postActivityStartTokenRemoval(filter.receiverList.app, r);
                }
            }
            if (ordered) {
                r.state = BroadcastRecord.CALL_DONE_RECEIVE;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failure sending broadcast " + r.intent, e);
            // Clean up ProcessRecord state related to this broadcast attempt
            if (filter.receiverList.app != null) {
                filter.receiverList.app.removeAllowBackgroundActivityStartsToken(r);
                if (ordered) {
                    filter.receiverList.app.mReceivers.removeCurReceiver(r);
                    // Something wrong, its oom adj could be downgraded, but not in a hurry.
                    mService.enqueueOomAdjTargetLocked(r.curApp);
                }
            }
            // And BroadcastRecord state related to ordered delivery, if appropriate
            if (ordered) {
                r.receiver = null;
                r.curFilter = null;
                filter.receiverList.curBroadcast = null;
            }
        }
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(
            BroadcastRecord receiverRecord, String receivingPackageName,
            final int receivingUserId) {
        if (!mService.getPackageManagerInternal().isPermissionsReviewRequired(
                receivingPackageName, receivingUserId)) {
            return true;
        }

        final boolean callerForeground = receiverRecord.callerApp != null
                ? receiverRecord.callerApp.mState.getSetSchedGroup()
                != ProcessList.SCHED_GROUP_BACKGROUND : true;

        // Show a permission review UI only for explicit broadcast from a foreground app
        if (callerForeground && receiverRecord.intent.getComponent() != null) {
            IIntentSender target = mService.mPendingIntentController.getIntentSender(
                    ActivityManager.INTENT_SENDER_BROADCAST, receiverRecord.callerPackage,
                    receiverRecord.callerFeatureId, receiverRecord.callingUid,
                    receiverRecord.userId, null, null, 0,
                    new Intent[]{receiverRecord.intent},
                    new String[]{receiverRecord.intent.resolveType(mService.mContext
                            .getContentResolver())},
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, receivingPackageName);
            intent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));

            if (DEBUG_PERMISSIONS_REVIEW) {
                Slog.i(TAG, "u" + receivingUserId + " Launching permission review for package "
                        + receivingPackageName);
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mService.mContext.startActivityAsUser(intent, new UserHandle(receivingUserId));
                }
            });
        } else {
            Slog.w(TAG, "u" + receivingUserId + " Receiving a broadcast in package"
                    + receivingPackageName + " requires a permissions review");
        }

        return false;
    }

    void maybeScheduleTempAllowlistLocked(int uid, BroadcastRecord r,
            @Nullable BroadcastOptions brOptions) {
        if (brOptions == null || brOptions.getTemporaryAppAllowlistDuration() <= 0) {
            return;
        }
        long duration = brOptions.getTemporaryAppAllowlistDuration();
        final @TempAllowListType int type = brOptions.getTemporaryAppAllowlistType();
        final @ReasonCode int reasonCode = brOptions.getTemporaryAppAllowlistReasonCode();
        final String reason = brOptions.getTemporaryAppAllowlistReason();

        if (duration > Integer.MAX_VALUE) {
            duration = Integer.MAX_VALUE;
        }
        // XXX ideally we should pause the broadcast until everything behind this is done,
        // or else we will likely start dispatching the broadcast before we have opened
        // access to the app (there is a lot of asynchronicity behind this).  It is probably
        // not that big a deal, however, because the main purpose here is to allow apps
        // to hold wake locks, and they will be able to acquire their wake lock immediately
        // it just won't be enabled until we get through this work.
        StringBuilder b = new StringBuilder();
        b.append("broadcast:");
        UserHandle.formatUid(b, r.callingUid);
        b.append(":");
        if (r.intent.getAction() != null) {
            b.append(r.intent.getAction());
        } else if (r.intent.getComponent() != null) {
            r.intent.getComponent().appendShortString(b);
        } else if (r.intent.getData() != null) {
            b.append(r.intent.getData());
        }
        b.append(",reason:");
        b.append(reason);
        if (DEBUG_BROADCAST) {
            Slog.v(TAG, "Broadcast temp allowlist uid=" + uid + " duration=" + duration
                    + " type=" + type + " : " + b.toString());
        }
        mService.tempAllowlistUidLocked(uid, duration, reasonCode, b.toString(), type,
                r.callingUid);
    }

    /**
     * Return true if all given permissions are signature-only perms.
     */
    final boolean isSignaturePerm(String[] perms) {
        if (perms == null) {
            return false;
        }
        IPermissionManager pm = AppGlobals.getPermissionManager();
        for (int i = perms.length-1; i >= 0; i--) {
            try {
                PermissionInfo pi = pm.getPermissionInfo(perms[i], "android", 0);
                if (pi == null) {
                    // a required permission that no package has actually
                    // defined cannot be signature-required.
                    return false;
                }
                if ((pi.protectionLevel & (PermissionInfo.PROTECTION_MASK_BASE
                        | PermissionInfo.PROTECTION_FLAG_PRIVILEGED))
                        != PermissionInfo.PROTECTION_SIGNATURE) {
                    // If this a signature permission and NOT allowed for privileged apps, it
                    // is okay...  otherwise, nope!
                    return false;
                }
            } catch (RemoteException e) {
                return false;
            }
        }
        return true;
    }

    private void processNextBroadcast(boolean fromMsg) {
        synchronized (mService) {
            processNextBroadcastLocked(fromMsg, false);
        }
    }

    static String broadcastDescription(BroadcastRecord r, ComponentName component) {
        return r.intent.toString()
                + " from " + r.callerPackage + " (pid=" + r.callingPid
                + ", uid=" + r.callingUid + ") to " + component.flattenToShortString();
    }

    final void processNextBroadcastLocked(boolean fromMsg, boolean skipOomAdj) {
        BroadcastRecord r;

        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "processNextBroadcast ["
                + mQueueName + "]: "
                + mParallelBroadcasts.size() + " parallel broadcasts; "
                + mDispatcher.describeStateLocked());

        mService.updateCpuStats();

        if (fromMsg) {
            mBroadcastsScheduled = false;
        }

        // First, deliver any non-serialized broadcasts right away.
        while (mParallelBroadcasts.size() > 0) {
            r = mParallelBroadcasts.remove(0);
            r.dispatchTime = SystemClock.uptimeMillis();
            r.dispatchRealTime = SystemClock.elapsedRealtime();
            r.dispatchClockTime = System.currentTimeMillis();
            r.mIsReceiverAppRunning = true;

            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    createBroadcastTraceTitle(r, BroadcastRecord.DELIVERY_PENDING),
                    System.identityHashCode(r));
                Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    createBroadcastTraceTitle(r, BroadcastRecord.DELIVERY_DELIVERED),
                    System.identityHashCode(r));
            }

            final int N = r.receivers.size();
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Processing parallel broadcast ["
                    + mQueueName + "] " + r);
            for (int i=0; i<N; i++) {
                Object target = r.receivers.get(i);
                if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                        "Delivering non-ordered on [" + mQueueName + "] to registered "
                        + target + ": " + r);
                deliverToRegisteredReceiverLocked(r,
                        (BroadcastFilter) target, false, i);
            }
            addBroadcastToHistoryLocked(r);
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Done with parallel broadcast ["
                    + mQueueName + "] " + r);
        }

        // Now take care of the next serialized one...

        // If we are waiting for a process to come up to handle the next
        // broadcast, then do nothing at this point.  Just in case, we
        // check that the process we're waiting for still exists.
        if (mPendingBroadcast != null) {
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                    "processNextBroadcast [" + mQueueName + "]: waiting for "
                    + mPendingBroadcast.curApp);

            boolean isDead;
            if (mPendingBroadcast.curApp.getPid() > 0) {
                synchronized (mService.mPidsSelfLocked) {
                    ProcessRecord proc = mService.mPidsSelfLocked.get(
                            mPendingBroadcast.curApp.getPid());
                    isDead = proc == null || proc.mErrorState.isCrashing();
                }
            } else {
                final ProcessRecord proc = mService.mProcessList.getProcessNamesLOSP().get(
                        mPendingBroadcast.curApp.processName, mPendingBroadcast.curApp.uid);
                isDead = proc == null || !proc.isPendingStart();
            }
            if (!isDead) {
                // It's still alive, so keep waiting
                return;
            } else {
                Slog.w(TAG, "pending app  ["
                        + mQueueName + "]" + mPendingBroadcast.curApp
                        + " died before responding to broadcast");
                mPendingBroadcast.state = BroadcastRecord.IDLE;
                mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                mPendingBroadcast = null;
            }
        }

        boolean looped = false;

        do {
            final long now = SystemClock.uptimeMillis();
            r = mDispatcher.getNextBroadcastLocked(now);

            if (r == null) {
                // No more broadcasts are deliverable right now, so all done!
                mDispatcher.scheduleDeferralCheckLocked(false);
                synchronized (mService.mAppProfiler.mProfilerLock) {
                    mService.mAppProfiler.scheduleAppGcsLPf();
                }
                if (looped && !skipOomAdj) {
                    // If we had finished the last ordered broadcast, then
                    // make sure all processes have correct oom and sched
                    // adjustments.
                    mService.updateOomAdjPendingTargetsLocked(
                            OOM_ADJ_REASON_START_RECEIVER);
                }

                // when we have no more ordered broadcast on this queue, stop logging
                if (mService.mUserController.mBootCompleted && mLogLatencyMetrics) {
                    mLogLatencyMetrics = false;
                }

                return;
            }

            boolean forceReceive = false;

            // Ensure that even if something goes awry with the timeout
            // detection, we catch "hung" broadcasts here, discard them,
            // and continue to make progress.
            //
            // This is only done if the system is ready so that early-stage receivers
            // don't get executed with timeouts; and of course other timeout-
            // exempt broadcasts are ignored.
            int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
            if (mService.mProcessesReady && !r.timeoutExempt && r.dispatchTime > 0) {
                if ((numReceivers > 0) &&
                        (now > r.dispatchTime + (2 * mConstants.TIMEOUT * numReceivers))) {
                    Slog.w(TAG, "Hung broadcast ["
                            + mQueueName + "] discarded after timeout failure:"
                            + " now=" + now
                            + " dispatchTime=" + r.dispatchTime
                            + " startTime=" + r.receiverTime
                            + " intent=" + r.intent
                            + " numReceivers=" + numReceivers
                            + " nextReceiver=" + r.nextReceiver
                            + " state=" + r.state);
                    broadcastTimeoutLocked(false); // forcibly finish this broadcast
                    forceReceive = true;
                    r.state = BroadcastRecord.IDLE;
                }
            }

            if (r.state != BroadcastRecord.IDLE) {
                if (DEBUG_BROADCAST) Slog.d(TAG_BROADCAST,
                        "processNextBroadcast("
                        + mQueueName + ") called when not idle (state="
                        + r.state + ")");
                return;
            }

            // Is the current broadcast is done for any reason?
            if (r.receivers == null || r.nextReceiver >= numReceivers
                    || r.resultAbort || forceReceive) {
                // Send the final result if requested
                if (r.resultTo != null) {
                    boolean sendResult = true;

                    // if this was part of a split/deferral complex, update the refcount and only
                    // send the completion when we clear all of them
                    if (r.splitToken != 0) {
                        int newCount = mSplitRefcounts.get(r.splitToken) - 1;
                        if (newCount == 0) {
                            // done!  clear out this record's bookkeeping and deliver
                            if (DEBUG_BROADCAST_DEFERRAL) {
                                Slog.i(TAG_BROADCAST,
                                        "Sending broadcast completion for split token "
                                        + r.splitToken + " : " + r.intent.getAction());
                            }
                            mSplitRefcounts.delete(r.splitToken);
                        } else {
                            // still have some split broadcast records in flight; update refcount
                            // and hold off on the callback
                            if (DEBUG_BROADCAST_DEFERRAL) {
                                Slog.i(TAG_BROADCAST,
                                        "Result refcount now " + newCount + " for split token "
                                        + r.splitToken + " : " + r.intent.getAction()
                                        + " - not sending completion yet");
                            }
                            sendResult = false;
                            mSplitRefcounts.put(r.splitToken, newCount);
                        }
                    }
                    if (sendResult) {
                        if (r.callerApp != null) {
                            mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(
                                    r.callerApp, OOM_ADJ_REASON_FINISH_RECEIVER);
                        }
                        try {
                            if (DEBUG_BROADCAST) {
                                Slog.i(TAG_BROADCAST, "Finishing broadcast [" + mQueueName + "] "
                                        + r.intent.getAction() + " app=" + r.callerApp);
                            }
                            if (r.dispatchTime == 0) {
                                // The dispatch time here could be 0, in case it's a parallel
                                // broadcast but it has a result receiver. Set it to now.
                                r.dispatchTime = now;
                            }
                            r.mIsReceiverAppRunning = true;
                            performReceiveLocked(r.callerApp, r.resultTo,
                                    new Intent(r.intent), r.resultCode,
                                    r.resultData, r.resultExtras, false, false, r.userId,
                                    r.callingUid, r.callingUid,
                                    r.dispatchTime - r.enqueueTime,
                                    now - r.dispatchTime);
                            logBootCompletedBroadcastCompletionLatencyIfPossible(r);
                            // Set this to null so that the reference
                            // (local and remote) isn't kept in the mBroadcastHistory.
                            r.resultTo = null;
                        } catch (RemoteException e) {
                            r.resultTo = null;
                            Slog.w(TAG, "Failure ["
                                    + mQueueName + "] sending broadcast result of "
                                    + r.intent, e);
                        }
                    }
                }

                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Cancelling BROADCAST_TIMEOUT_MSG");
                cancelBroadcastTimeoutLocked();

                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                        "Finished with ordered broadcast " + r);

                // ... and on to the next...
                addBroadcastToHistoryLocked(r);
                if (r.intent.getComponent() == null && r.intent.getPackage() == null
                        && (r.intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    // This was an implicit broadcast... let's record it for posterity.
                    mService.addBroadcastStatLocked(r.intent.getAction(), r.callerPackage,
                            r.manifestCount, r.manifestSkipCount, r.finishTime-r.dispatchTime);
                }
                mDispatcher.retireBroadcastLocked(r);
                r = null;
                looped = true;
                continue;
            }

            // Check whether the next receiver is under deferral policy, and handle that
            // accordingly.  If the current broadcast was already part of deferred-delivery
            // tracking, we know that it must now be deliverable as-is without re-deferral.
            if (!r.deferred) {
                final int receiverUid = r.getReceiverUid(r.receivers.get(r.nextReceiver));
                if (mDispatcher.isDeferringLocked(receiverUid)) {
                    if (DEBUG_BROADCAST_DEFERRAL) {
                        Slog.i(TAG_BROADCAST, "Next receiver in " + r + " uid " + receiverUid
                                + " at " + r.nextReceiver + " is under deferral");
                    }
                    // If this is the only (remaining) receiver in the broadcast, "splitting"
                    // doesn't make sense -- just defer it as-is and retire it as the
                    // currently active outgoing broadcast.
                    BroadcastRecord defer;
                    if (r.nextReceiver + 1 == numReceivers) {
                        if (DEBUG_BROADCAST_DEFERRAL) {
                            Slog.i(TAG_BROADCAST, "Sole receiver of " + r
                                    + " is under deferral; setting aside and proceeding");
                        }
                        defer = r;
                        mDispatcher.retireBroadcastLocked(r);
                    } else {
                        // Nontrivial case; split out 'uid's receivers to a new broadcast record
                        // and defer that, then loop and pick up continuing delivery of the current
                        // record (now absent those receivers).

                        // The split operation is guaranteed to match at least at 'nextReceiver'
                        defer = r.splitRecipientsLocked(receiverUid, r.nextReceiver);
                        if (DEBUG_BROADCAST_DEFERRAL) {
                            Slog.i(TAG_BROADCAST, "Post split:");
                            Slog.i(TAG_BROADCAST, "Original broadcast receivers:");
                            for (int i = 0; i < r.receivers.size(); i++) {
                                Slog.i(TAG_BROADCAST, "  " + r.receivers.get(i));
                            }
                            Slog.i(TAG_BROADCAST, "Split receivers:");
                            for (int i = 0; i < defer.receivers.size(); i++) {
                                Slog.i(TAG_BROADCAST, "  " + defer.receivers.get(i));
                            }
                        }
                        // Track completion refcount as well if relevant
                        if (r.resultTo != null) {
                            int token = r.splitToken;
                            if (token == 0) {
                                // first split of this record; refcount for 'r' and 'deferred'
                                r.splitToken = defer.splitToken = nextSplitTokenLocked();
                                mSplitRefcounts.put(r.splitToken, 2);
                                if (DEBUG_BROADCAST_DEFERRAL) {
                                    Slog.i(TAG_BROADCAST,
                                            "Broadcast needs split refcount; using new token "
                                            + r.splitToken);
                                }
                            } else {
                                // new split from an already-refcounted situation; increment count
                                final int curCount = mSplitRefcounts.get(token);
                                if (DEBUG_BROADCAST_DEFERRAL) {
                                    if (curCount == 0) {
                                        Slog.wtf(TAG_BROADCAST,
                                                "Split refcount is zero with token for " + r);
                                    }
                                }
                                mSplitRefcounts.put(token, curCount + 1);
                                if (DEBUG_BROADCAST_DEFERRAL) {
                                    Slog.i(TAG_BROADCAST, "New split count for token " + token
                                            + " is " + (curCount + 1));
                                }
                            }
                        }
                    }
                    mDispatcher.addDeferredBroadcast(receiverUid, defer);
                    r = null;
                    looped = true;
                    continue;
                }
            }
        } while (r == null);

        // Get the next receiver...
        int recIdx = r.nextReceiver++;

        // Keep track of when this receiver started, and make sure there
        // is a timeout message pending to kill it if need be.
        r.receiverTime = SystemClock.uptimeMillis();
        if (recIdx == 0) {
            r.dispatchTime = r.receiverTime;
            r.dispatchRealTime = SystemClock.elapsedRealtime();
            r.dispatchClockTime = System.currentTimeMillis();

            if (mLogLatencyMetrics) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.BROADCAST_DISPATCH_LATENCY_REPORTED,
                        r.dispatchClockTime - r.enqueueClockTime);
            }

            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    createBroadcastTraceTitle(r, BroadcastRecord.DELIVERY_PENDING),
                    System.identityHashCode(r));
                Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    createBroadcastTraceTitle(r, BroadcastRecord.DELIVERY_DELIVERED),
                    System.identityHashCode(r));
            }
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Processing ordered broadcast ["
                    + mQueueName + "] " + r);
        }
        if (! mPendingBroadcastTimeoutMessage) {
            long timeoutTime = r.receiverTime + mConstants.TIMEOUT;
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                    "Submitting BROADCAST_TIMEOUT_MSG ["
                    + mQueueName + "] for " + r + " at " + timeoutTime);
            setBroadcastTimeoutLocked(timeoutTime);
        }

        final BroadcastOptions brOptions = r.options;
        final Object nextReceiver = r.receivers.get(recIdx);

        if (nextReceiver instanceof BroadcastFilter) {
            // Simple case: this is a registered receiver who gets
            // a direct call.
            BroadcastFilter filter = (BroadcastFilter)nextReceiver;
            if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                    "Delivering ordered ["
                    + mQueueName + "] to registered "
                    + filter + ": " + r);
            r.mIsReceiverAppRunning = true;
            deliverToRegisteredReceiverLocked(r, filter, r.ordered, recIdx);
            if (r.receiver == null || !r.ordered) {
                // The receiver has already finished, so schedule to
                // process the next one.
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Quick finishing ["
                        + mQueueName + "]: ordered="
                        + r.ordered + " receiver=" + r.receiver);
                r.state = BroadcastRecord.IDLE;
                scheduleBroadcastsLocked();
            } else {
                if (filter.receiverList != null) {
                    maybeAddAllowBackgroundActivityStartsToken(filter.receiverList.app, r);
                    // r is guaranteed ordered at this point, so we know finishReceiverLocked()
                    // will get a callback and handle the activity start token lifecycle.
                }
            }
            return;
        }

        // Hard case: need to instantiate the receiver, possibly
        // starting its application process to host it.

        ResolveInfo info =
            (ResolveInfo)nextReceiver;
        ComponentName component = new ComponentName(
                info.activityInfo.applicationInfo.packageName,
                info.activityInfo.name);

        boolean skip = false;
        if (brOptions != null &&
                (info.activityInfo.applicationInfo.targetSdkVersion
                        < brOptions.getMinManifestReceiverApiLevel() ||
                info.activityInfo.applicationInfo.targetSdkVersion
                        > brOptions.getMaxManifestReceiverApiLevel())) {
            Slog.w(TAG, "Target SDK mismatch: receiver " + info.activityInfo
                    + " targets " + info.activityInfo.applicationInfo.targetSdkVersion
                    + " but delivery restricted to ["
                    + brOptions.getMinManifestReceiverApiLevel() + ", "
                    + brOptions.getMaxManifestReceiverApiLevel()
                    + "] broadcasting " + broadcastDescription(r, component));
            skip = true;
        }
        if (brOptions != null &&
                !brOptions.testRequireCompatChange(info.activityInfo.applicationInfo.uid)) {
            Slog.w(TAG, "Compat change filtered: broadcasting " + broadcastDescription(r, component)
                    + " to uid " + info.activityInfo.applicationInfo.uid + " due to compat change "
                    + r.options.getRequireCompatChangeId());
            skip = true;
        }
        if (!skip && !mService.validateAssociationAllowedLocked(r.callerPackage, r.callingUid,
                component.getPackageName(), info.activityInfo.applicationInfo.uid)) {
            Slog.w(TAG, "Association not allowed: broadcasting "
                    + broadcastDescription(r, component));
            skip = true;
        }
        if (!skip) {
            skip = !mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                    r.callingPid, r.resolvedType, info.activityInfo.applicationInfo.uid);
            if (skip) {
                Slog.w(TAG, "Firewall blocked: broadcasting "
                        + broadcastDescription(r, component));
            }
        }
        int perm = mService.checkComponentPermission(info.activityInfo.permission,
                r.callingPid, r.callingUid, info.activityInfo.applicationInfo.uid,
                info.activityInfo.exported);
        if (!skip && perm != PackageManager.PERMISSION_GRANTED) {
            if (!info.activityInfo.exported) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + broadcastDescription(r, component)
                        + " is not exported from uid " + info.activityInfo.applicationInfo.uid);
            } else {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + broadcastDescription(r, component)
                        + " requires " + info.activityInfo.permission);
            }
            skip = true;
        } else if (!skip && info.activityInfo.permission != null) {
            final int opCode = AppOpsManager.permissionToOpCode(info.activityInfo.permission);
            if (opCode != AppOpsManager.OP_NONE && mService.getAppOpsManager().noteOpNoThrow(opCode,
                    r.callingUid, r.callerPackage, r.callerFeatureId,
                    "Broadcast delivered to " + info.activityInfo.name)
                    != AppOpsManager.MODE_ALLOWED) {
                Slog.w(TAG, "Appop Denial: broadcasting "
                        + broadcastDescription(r, component)
                        + " requires appop " + AppOpsManager.permissionToOp(
                                info.activityInfo.permission));
                skip = true;
            }
        }

        boolean isSingleton = false;
        try {
            isSingleton = mService.isSingleton(info.activityInfo.processName,
                    info.activityInfo.applicationInfo,
                    info.activityInfo.name, info.activityInfo.flags);
        } catch (SecurityException e) {
            Slog.w(TAG, e.getMessage());
            skip = true;
        }
        if ((info.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
            if (ActivityManager.checkUidPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS,
                    info.activityInfo.applicationInfo.uid)
                            != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: Receiver " + component.flattenToShortString()
                        + " requests FLAG_SINGLE_USER, but app does not hold "
                        + android.Manifest.permission.INTERACT_ACROSS_USERS);
                skip = true;
            }
        }
        if (!skip && info.activityInfo.applicationInfo.isInstantApp()
                && r.callingUid != info.activityInfo.applicationInfo.uid) {
            Slog.w(TAG, "Instant App Denial: receiving "
                    + r.intent
                    + " to " + component.flattenToShortString()
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")"
                    + " Instant Apps do not support manifest receivers");
            skip = true;
        }
        if (!skip && r.callerInstantApp
                && (info.activityInfo.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) == 0
                && r.callingUid != info.activityInfo.applicationInfo.uid) {
            Slog.w(TAG, "Instant App Denial: receiving "
                    + r.intent
                    + " to " + component.flattenToShortString()
                    + " requires receiver have visibleToInstantApps set"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            skip = true;
        }
        if (r.curApp != null && r.curApp.mErrorState.isCrashing()) {
            // If the target process is crashing, just skip it.
            Slog.w(TAG, "Skipping deliver ordered [" + mQueueName + "] " + r
                    + " to " + r.curApp + ": process crashing");
            skip = true;
        }
        if (!skip) {
            boolean isAvailable = false;
            try {
                isAvailable = AppGlobals.getPackageManager().isPackageAvailable(
                        info.activityInfo.packageName,
                        UserHandle.getUserId(info.activityInfo.applicationInfo.uid));
            } catch (Exception e) {
                // all such failures mean we skip this receiver
                Slog.w(TAG, "Exception getting recipient info for "
                        + info.activityInfo.packageName, e);
            }
            if (!isAvailable) {
                Slog.w(TAG_BROADCAST,
                        "Skipping delivery to " + info.activityInfo.packageName + " / "
                        + info.activityInfo.applicationInfo.uid
                        + " : package no longer available");
                skip = true;
            }
        }

        // If permissions need a review before any of the app components can run, we drop
        // the broadcast and if the calling app is in the foreground and the broadcast is
        // explicit we launch the review UI passing it a pending intent to send the skipped
        // broadcast.
        if (!skip) {
            if (!requestStartTargetPermissionsReviewIfNeededLocked(r,
                    info.activityInfo.packageName, UserHandle.getUserId(
                            info.activityInfo.applicationInfo.uid))) {
                Slog.w(TAG_BROADCAST,
                        "Skipping delivery: permission review required for "
                                + broadcastDescription(r, component));
                skip = true;
            }
        }

        // This is safe to do even if we are skipping the broadcast, and we need
        // this information now to evaluate whether it is going to be allowed to run.
        final int receiverUid = info.activityInfo.applicationInfo.uid;
        // If it's a singleton, it needs to be the same app or a special app
        if (r.callingUid != Process.SYSTEM_UID && isSingleton
                && mService.isValidSingletonCall(r.callingUid, receiverUid)) {
            info.activityInfo = mService.getActivityInfoForUser(info.activityInfo, 0);
        }
        String targetProcess = info.activityInfo.processName;
        ProcessRecord app = mService.getProcessRecordLocked(targetProcess,
                info.activityInfo.applicationInfo.uid);

        if (!skip) {
            final int allowed = mService.getAppStartModeLOSP(
                    info.activityInfo.applicationInfo.uid, info.activityInfo.packageName,
                    info.activityInfo.applicationInfo.targetSdkVersion, -1, true, false, false);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                // We won't allow this receiver to be launched if the app has been
                // completely disabled from launches, or it was not explicitly sent
                // to it and the app is in a state that should not receive it
                // (depending on how getAppStartModeLOSP has determined that).
                if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
                    Slog.w(TAG, "Background execution disabled: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString());
                    skip = true;
                } else if (((r.intent.getFlags()&Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND) != 0)
                        || (r.intent.getComponent() == null
                            && r.intent.getPackage() == null
                            && ((r.intent.getFlags()
                                    & Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND) == 0)
                            && !isSignaturePerm(r.requiredPermissions))) {
                    mService.addBackgroundCheckViolationLocked(r.intent.getAction(),
                            component.getPackageName());
                    Slog.w(TAG, "Background execution not allowed: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString());
                    skip = true;
                }
            }
        }

        if (!skip && !Intent.ACTION_SHUTDOWN.equals(r.intent.getAction())
                && !mService.mUserController
                .isUserRunning(UserHandle.getUserId(info.activityInfo.applicationInfo.uid),
                        0 /* flags */)) {
            skip = true;
            Slog.w(TAG,
                    "Skipping delivery to " + info.activityInfo.packageName + " / "
                            + info.activityInfo.applicationInfo.uid + " : user is not running");
        }

        if (!skip && r.excludedPermissions != null && r.excludedPermissions.length > 0) {
            for (int i = 0; i < r.excludedPermissions.length; i++) {
                String excludedPermission = r.excludedPermissions[i];
                try {
                    perm = AppGlobals.getPackageManager()
                        .checkPermission(excludedPermission,
                                info.activityInfo.applicationInfo.packageName,
                                UserHandle
                                .getUserId(info.activityInfo.applicationInfo.uid));
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }

                int appOp = AppOpsManager.permissionToOpCode(excludedPermission);
                if (appOp != AppOpsManager.OP_NONE) {
                    // When there is an app op associated with the permission,
                    // skip when both the permission and the app op are
                    // granted.
                    if ((perm == PackageManager.PERMISSION_GRANTED) && (
                                mService.getAppOpsManager().checkOpNoThrow(appOp,
                                info.activityInfo.applicationInfo.uid,
                                info.activityInfo.packageName)
                            == AppOpsManager.MODE_ALLOWED)) {
                        skip = true;
                        break;
                    }
                } else {
                    // When there is no app op associated with the permission,
                    // skip when permission is granted.
                    if (perm == PackageManager.PERMISSION_GRANTED) {
                        skip = true;
                        break;
                    }
                }
            }
        }

        // Check that the receiver does *not* belong to any of the excluded packages
        if (!skip && r.excludedPackages != null && r.excludedPackages.length > 0) {
            if (ArrayUtils.contains(r.excludedPackages, component.getPackageName())) {
                Slog.w(TAG, "Skipping delivery of excluded package "
                        + r.intent + " to "
                        + component.flattenToShortString()
                        + " excludes package " + component.getPackageName()
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }

        if (!skip && info.activityInfo.applicationInfo.uid != Process.SYSTEM_UID &&
                r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            for (int i = 0; i < r.requiredPermissions.length; i++) {
                String requiredPermission = r.requiredPermissions[i];
                try {
                    perm = AppGlobals.getPackageManager().
                            checkPermission(requiredPermission,
                                    info.activityInfo.applicationInfo.packageName,
                                    UserHandle
                                    .getUserId(info.activityInfo.applicationInfo.uid));
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString()
                            + " requires " + requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
                int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp) {
                    if (!noteOpForManifestReceiver(appOp, r, info, component)) {
                        skip = true;
                        break;
                    }
                }
            }
        }
        if (!skip && r.appOp != AppOpsManager.OP_NONE) {
            if (!noteOpForManifestReceiver(r.appOp, r, info, component)) {
                skip = true;
            }
        }

        if (skip) {
            if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                    "Skipping delivery of ordered [" + mQueueName + "] "
                    + r + " for reason described above");
            r.delivery[recIdx] = BroadcastRecord.DELIVERY_SKIPPED;
            r.receiver = null;
            r.curFilter = null;
            r.state = BroadcastRecord.IDLE;
            r.manifestSkipCount++;
            scheduleBroadcastsLocked();
            return;
        }
        r.manifestCount++;

        r.delivery[recIdx] = BroadcastRecord.DELIVERY_DELIVERED;
        r.state = BroadcastRecord.APP_RECEIVE;
        r.curComponent = component;
        r.curReceiver = info.activityInfo;
        if (DEBUG_MU && r.callingUid > UserHandle.PER_USER_RANGE) {
            Slog.v(TAG_MU, "Updated broadcast record activity info for secondary user, "
                    + info.activityInfo + ", callingUid = " + r.callingUid + ", uid = "
                    + receiverUid);
        }
        final boolean isActivityCapable =
                (brOptions != null && brOptions.getTemporaryAppAllowlistDuration() > 0);
        maybeScheduleTempAllowlistLocked(receiverUid, r, brOptions);

        // Report that a component is used for explicit broadcasts.
        if (r.intent.getComponent() != null && r.curComponent != null
                && !TextUtils.equals(r.curComponent.getPackageName(), r.callerPackage)) {
            mService.mUsageStatsService.reportEvent(
                    r.curComponent.getPackageName(), r.userId, Event.APP_COMPONENT_USED);
        }

        // Broadcast is being executed, its package can't be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.curComponent.getPackageName(), false, r.userId);
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.curComponent.getPackageName() + ": " + e);
        }

        // Is this receiver's application already running?
        if (app != null && app.getThread() != null && !app.isKilled()) {
            try {
                app.addPackage(info.activityInfo.packageName,
                        info.activityInfo.applicationInfo.longVersionCode, mService.mProcessStats);
                maybeAddAllowBackgroundActivityStartsToken(app, r);
                r.mIsReceiverAppRunning = true;
                processCurBroadcastLocked(r, app);
                return;
            } catch (RemoteException e) {
                final String msg = "Failed to schedule " + r.intent + " to " + info
                        + " via " + app + ": " + e;
                Slog.w(TAG, msg);
                app.killLocked("Can't deliver broadcast", ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_UNDELIVERED_BROADCAST, true);
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "Failed sending broadcast to "
                        + r.curComponent + " with " + r.intent, e);
                // If some unexpected exception happened, just skip
                // this broadcast.  At this point we are not in the call
                // from a client, so throwing an exception out from here
                // will crash the entire system instead of just whoever
                // sent the broadcast.
                logBroadcastReceiverDiscardLocked(r);
                finishReceiverLocked(r, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, false);
                scheduleBroadcastsLocked();
                // We need to reset the state if we failed to start the receiver.
                r.state = BroadcastRecord.IDLE;
                return;
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        // Not running -- get it started, to be executed when the app comes up.
        if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                "Need to start app ["
                + mQueueName + "] " + targetProcess + " for broadcast " + r);
        r.curApp = mService.startProcessLocked(targetProcess,
                info.activityInfo.applicationInfo, true,
                r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                new HostingRecord(HostingRecord.HOSTING_TYPE_BROADCAST, r.curComponent,
                        r.intent.getAction(), getHostingRecordTriggerType(r)),
                isActivityCapable ? ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE : ZYGOTE_POLICY_FLAG_EMPTY,
                (r.intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0, false);
        if (r.curApp == null) {
            // Ah, this recipient is unavailable.  Finish it if necessary,
            // and mark the broadcast record as ready for the next.
            Slog.w(TAG, "Unable to launch app "
                    + info.activityInfo.applicationInfo.packageName + "/"
                    + receiverUid + " for broadcast "
                    + r.intent + ": process is bad");
            logBroadcastReceiverDiscardLocked(r);
            finishReceiverLocked(r, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, false);
            scheduleBroadcastsLocked();
            r.state = BroadcastRecord.IDLE;
            return;
        }

        maybeAddAllowBackgroundActivityStartsToken(r.curApp, r);
        mPendingBroadcast = r;
        mPendingBroadcastRecvIndex = recIdx;
    }

    private String getHostingRecordTriggerType(BroadcastRecord r) {
        if (r.alarm) {
            return HostingRecord.TRIGGER_TYPE_ALARM;
        } else if (r.pushMessage) {
            return HostingRecord.TRIGGER_TYPE_PUSH_MESSAGE;
        } else if (r.pushMessageOverQuota) {
            return HostingRecord.TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA;
        }
        return HostingRecord.TRIGGER_TYPE_UNKNOWN;
    }

    @Nullable
    private String getTargetPackage(BroadcastRecord r) {
        if (r.intent == null) {
            return null;
        }
        if (r.intent.getPackage() != null) {
            return r.intent.getPackage();
        } else if (r.intent.getComponent() != null) {
            return r.intent.getComponent().getPackageName();
        }
        return null;
    }

    private void logBootCompletedBroadcastCompletionLatencyIfPossible(BroadcastRecord r) {
        // Only log after last receiver.
        // In case of split BOOT_COMPLETED broadcast, make sure only call this method on the
        // last BroadcastRecord of the split broadcast which has non-null resultTo.
        final int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
        if (r.nextReceiver < numReceivers) {
            return;
        }
        final String action = r.intent.getAction();
        int event = 0;
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            event = BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED__EVENT__LOCKED_BOOT_COMPLETED;
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            event = BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED__EVENT__BOOT_COMPLETED;
        }
        if (event != 0) {
            final int dispatchLatency = (int)(r.dispatchTime - r.enqueueTime);
            final int completeLatency = (int)
                    (SystemClock.uptimeMillis() - r.enqueueTime);
            final int dispatchRealLatency = (int)(r.dispatchRealTime - r.enqueueRealTime);
            final int completeRealLatency = (int)
                    (SystemClock.elapsedRealtime() - r.enqueueRealTime);
            int userType = FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__TYPE_UNKNOWN;
            // This method is called very infrequently, no performance issue we call
            // LocalServices.getService() here.
            final UserManagerInternal umInternal = LocalServices.getService(
                    UserManagerInternal.class);
            final UserInfo userInfo = umInternal.getUserInfo(r.userId);
            if (userInfo != null) {
                userType = UserManager.getUserTypeForStatsd(userInfo.userType);
            }
            Slog.i(TAG_BROADCAST,
                    "BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED action:"
                            + action
                            + " dispatchLatency:" + dispatchLatency
                            + " completeLatency:" + completeLatency
                            + " dispatchRealLatency:" + dispatchRealLatency
                            + " completeRealLatency:" + completeRealLatency
                            + " receiversSize:" + numReceivers
                            + " userId:" + r.userId
                            + " userType:" + (userInfo != null? userInfo.userType : null));
            FrameworkStatsLog.write(
                    BOOT_COMPLETED_BROADCAST_COMPLETION_LATENCY_REPORTED,
                    event,
                    dispatchLatency,
                    completeLatency,
                    dispatchRealLatency,
                    completeRealLatency,
                    r.userId,
                    userType);
        }
    }

    private void maybeReportBroadcastDispatchedEventLocked(BroadcastRecord r, int targetUid) {
        if (r.options == null || r.options.getIdForResponseEvent() <= 0) {
            return;
        }
        final String targetPackage = getTargetPackage(r);
        // Ignore non-explicit broadcasts
        if (targetPackage == null) {
            return;
        }
        getUsageStatsManagerInternal().reportBroadcastDispatched(
                r.callingUid, targetPackage, UserHandle.of(r.userId),
                r.options.getIdForResponseEvent(), SystemClock.elapsedRealtime(),
                mService.getUidStateLocked(targetUid));
    }

    @NonNull
    private UsageStatsManagerInternal getUsageStatsManagerInternal() {
        final UsageStatsManagerInternal usageStatsManagerInternal =
                LocalServices.getService(UsageStatsManagerInternal.class);
        return usageStatsManagerInternal;
    }

    private boolean noteOpForManifestReceiver(int appOp, BroadcastRecord r, ResolveInfo info,
            ComponentName component) {
        if (ArrayUtils.isEmpty(info.activityInfo.attributionTags)) {
            return noteOpForManifestReceiverInner(appOp, r, info, component, null);
        } else {
            // Attribution tags provided, noteOp each tag
            for (String tag : info.activityInfo.attributionTags) {
                if (!noteOpForManifestReceiverInner(appOp, r, info, component, tag)) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean noteOpForManifestReceiverInner(int appOp, BroadcastRecord r, ResolveInfo info,
            ComponentName component, String tag) {
        if (mService.getAppOpsManager().noteOpNoThrow(appOp,
                    info.activityInfo.applicationInfo.uid,
                    info.activityInfo.packageName,
                    tag,
                    "Broadcast delivered to " + info.activityInfo.name)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Appop Denial: receiving "
                    + r.intent + " to "
                    + component.flattenToShortString()
                    + " requires appop " + AppOpsManager.opToName(appOp)
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            return false;
        }
        return true;
    }

    private void maybeAddAllowBackgroundActivityStartsToken(ProcessRecord proc, BroadcastRecord r) {
        if (r == null || proc == null || !r.allowBackgroundActivityStarts) {
            return;
        }
        String msgToken = (proc.toShortString() + r.toString()).intern();
        // first, if there exists a past scheduled request to remove this token, drop
        // that request - we don't want the token to be swept from under our feet...
        mHandler.removeCallbacksAndMessages(msgToken);
        // ...then add the token
        proc.addOrUpdateAllowBackgroundActivityStartsToken(r, r.mBackgroundActivityStartsToken);
    }

    final void setBroadcastTimeoutLocked(long timeoutTime) {
        if (! mPendingBroadcastTimeoutMessage) {
            Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG, this);
            mHandler.sendMessageAtTime(msg, timeoutTime);
            mPendingBroadcastTimeoutMessage = true;
        }
    }

    final void cancelBroadcastTimeoutLocked() {
        if (mPendingBroadcastTimeoutMessage) {
            mHandler.removeMessages(BROADCAST_TIMEOUT_MSG, this);
            mPendingBroadcastTimeoutMessage = false;
        }
    }

    final void broadcastTimeoutLocked(boolean fromMsg) {
        if (fromMsg) {
            mPendingBroadcastTimeoutMessage = false;
        }

        if (mDispatcher.isEmpty() || mDispatcher.getActiveBroadcastLocked() == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        BroadcastRecord r = mDispatcher.getActiveBroadcastLocked();
        if (fromMsg) {
            if (!mService.mProcessesReady) {
                // Only process broadcast timeouts if the system is ready; some early
                // broadcasts do heavy work setting up system facilities
                return;
            }

            // If the broadcast is generally exempt from timeout tracking, we're done
            if (r.timeoutExempt) {
                if (DEBUG_BROADCAST) {
                    Slog.i(TAG_BROADCAST, "Broadcast timeout but it's exempt: "
                            + r.intent.getAction());
                }
                return;
            }

            long timeoutTime = r.receiverTime + mConstants.TIMEOUT;
            if (timeoutTime > now) {
                // We can observe premature timeouts because we do not cancel and reset the
                // broadcast timeout message after each receiver finishes.  Instead, we set up
                // an initial timeout then kick it down the road a little further as needed
                // when it expires.
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Premature timeout ["
                        + mQueueName + "] @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for "
                        + timeoutTime);
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
        }

        if (r.state == BroadcastRecord.WAITING_SERVICES) {
            // In this case the broadcast had already finished, but we had decided to wait
            // for started services to finish as well before going on.  So if we have actually
            // waited long enough time timeout the broadcast, let's give up on the whole thing
            // and just move on to the next.
            Slog.i(TAG, "Waited long enough for: " + (r.curComponent != null
                    ? r.curComponent.flattenToShortString() : "(null)"));
            r.curComponent = null;
            r.state = BroadcastRecord.IDLE;
            processNextBroadcastLocked(false, false);
            return;
        }

        // If the receiver app is being debugged we quietly ignore unresponsiveness, just
        // tidying up and moving on to the next broadcast without crashing or ANRing this
        // app just because it's stopped at a breakpoint.
        final boolean debugging = (r.curApp != null && r.curApp.isDebugging());

        Slog.w(TAG, "Timeout of broadcast " + r + " - receiver=" + r.receiver
                + ", started " + (now - r.receiverTime) + "ms ago");
        r.receiverTime = now;
        if (!debugging) {
            r.anrCount++;
        }

        ProcessRecord app = null;
        String anrMessage = null;

        Object curReceiver;
        if (r.nextReceiver > 0) {
            curReceiver = r.receivers.get(r.nextReceiver-1);
            r.delivery[r.nextReceiver-1] = BroadcastRecord.DELIVERY_TIMEOUT;
        } else {
            curReceiver = r.curReceiver;
        }
        Slog.w(TAG, "Receiver during timeout of " + r + " : " + curReceiver);
        logBroadcastReceiverDiscardLocked(r);
        if (curReceiver != null && curReceiver instanceof BroadcastFilter) {
            BroadcastFilter bf = (BroadcastFilter)curReceiver;
            if (bf.receiverList.pid != 0
                    && bf.receiverList.pid != ActivityManagerService.MY_PID) {
                synchronized (mService.mPidsSelfLocked) {
                    app = mService.mPidsSelfLocked.get(
                            bf.receiverList.pid);
                }
            }
        } else {
            app = r.curApp;
        }

        if (app != null) {
            anrMessage = "Broadcast of " + r.intent.toString();
        }

        if (mPendingBroadcast == r) {
            mPendingBroadcast = null;
        }

        // Move on to the next receiver.
        finishReceiverLocked(r, r.resultCode, r.resultData,
                r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();

        if (!debugging && anrMessage != null) {
            mService.mAnrHelper.appNotResponding(app, anrMessage);
        }
    }

    private final int ringAdvance(int x, final int increment, final int ringSize) {
        x += increment;
        if (x < 0) return (ringSize - 1);
        else if (x >= ringSize) return 0;
        else return x;
    }

    private final void addBroadcastToHistoryLocked(BroadcastRecord original) {
        if (original.callingUid < 0) {
            // This was from a registerReceiver() call; ignore it.
            return;
        }
        original.finishTime = SystemClock.uptimeMillis();

        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                createBroadcastTraceTitle(original, BroadcastRecord.DELIVERY_DELIVERED),
                System.identityHashCode(original));
        }

        final ApplicationInfo info = original.callerApp != null ? original.callerApp.info : null;
        final String callerPackage = info != null ? info.packageName : original.callerPackage;
        if (callerPackage != null) {
            mService.mHandler.obtainMessage(ActivityManagerService.DISPATCH_SENDING_BROADCAST_EVENT,
                    original.callingUid, 0, callerPackage).sendToTarget();
        }

        // Note sometimes (only for sticky broadcasts?) we reuse BroadcastRecords,
        // So don't change the incoming record directly.
        final BroadcastRecord historyRecord = original.maybeStripForHistory();

        mBroadcastHistory[mHistoryNext] = historyRecord;
        mHistoryNext = ringAdvance(mHistoryNext, 1, MAX_BROADCAST_HISTORY);

        mBroadcastSummaryHistory[mSummaryHistoryNext] = historyRecord.intent;
        mSummaryHistoryEnqueueTime[mSummaryHistoryNext] = historyRecord.enqueueClockTime;
        mSummaryHistoryDispatchTime[mSummaryHistoryNext] = historyRecord.dispatchClockTime;
        mSummaryHistoryFinishTime[mSummaryHistoryNext] = System.currentTimeMillis();
        mSummaryHistoryNext = ringAdvance(mSummaryHistoryNext, 1, MAX_BROADCAST_SUMMARY_HISTORY);
    }

    boolean cleanupDisabledPackageReceiversLocked(
            String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        boolean didSomething = false;
        for (int i = mParallelBroadcasts.size() - 1; i >= 0; i--) {
            didSomething |= mParallelBroadcasts.get(i).cleanupDisabledPackageReceiversLocked(
                    packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }

        didSomething |= mDispatcher.cleanupDisabledPackageReceiversLocked(packageName,
                filterByClasses, userId, doit);

        return didSomething;
    }

    final void logBroadcastReceiverDiscardLocked(BroadcastRecord r) {
        final int logIndex = r.nextReceiver - 1;
        if (logIndex >= 0 && logIndex < r.receivers.size()) {
            Object curReceiver = r.receivers.get(logIndex);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter) curReceiver;
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_FILTER,
                        bf.owningUserId, System.identityHashCode(r),
                        r.intent.getAction(), logIndex, System.identityHashCode(bf));
            } else {
                ResolveInfo ri = (ResolveInfo) curReceiver;
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                        UserHandle.getUserId(ri.activityInfo.applicationInfo.uid),
                        System.identityHashCode(r), r.intent.getAction(), logIndex, ri.toString());
            }
        } else {
            if (logIndex < 0) Slog.w(TAG,
                    "Discarding broadcast before first receiver is invoked: " + r);
            EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                    -1, System.identityHashCode(r),
                    r.intent.getAction(),
                    r.nextReceiver,
                    "NONE");
        }
    }

    private String createBroadcastTraceTitle(BroadcastRecord record, int state) {
        return formatSimple("Broadcast %s from %s (%s) %s",
                state == BroadcastRecord.DELIVERY_PENDING ? "in queue" : "dispatched",
                record.callerPackage == null ? "" : record.callerPackage,
                record.callerApp == null ? "process unknown" : record.callerApp.toShortString(),
                record.intent == null ? "" : record.intent.getAction());
    }

    boolean isIdle() {
        return mParallelBroadcasts.isEmpty() && mDispatcher.isIdle()
                && (mPendingBroadcast == null);
    }

    // Used by wait-for-broadcast-idle : fast-forward all current deferrals to
    // be immediately deliverable.
    void cancelDeferrals() {
        synchronized (mService) {
            mDispatcher.cancelDeferralsLocked();
            scheduleBroadcastsLocked();
        }
    }

    String describeState() {
        synchronized (mService) {
            return mParallelBroadcasts.size() + " parallel; "
                    + mDispatcher.describeStateLocked();
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(BroadcastQueueProto.QUEUE_NAME, mQueueName);
        int N;
        N = mParallelBroadcasts.size();
        for (int i = N - 1; i >= 0; i--) {
            mParallelBroadcasts.get(i).dumpDebug(proto, BroadcastQueueProto.PARALLEL_BROADCASTS);
        }
        mDispatcher.dumpDebug(proto, BroadcastQueueProto.ORDERED_BROADCASTS);
        if (mPendingBroadcast != null) {
            mPendingBroadcast.dumpDebug(proto, BroadcastQueueProto.PENDING_BROADCAST);
        }

        int lastIndex = mHistoryNext;
        int ringIndex = lastIndex;
        do {
            // increasing index = more recent entry, and we want to print the most
            // recent first and work backwards, so we roll through the ring backwards.
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = mBroadcastHistory[ringIndex];
            if (r != null) {
                r.dumpDebug(proto, BroadcastQueueProto.HISTORICAL_BROADCASTS);
            }
        } while (ringIndex != lastIndex);

        lastIndex = ringIndex = mSummaryHistoryNext;
        do {
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
            Intent intent = mBroadcastSummaryHistory[ringIndex];
            if (intent == null) {
                continue;
            }
            long summaryToken = proto.start(BroadcastQueueProto.HISTORICAL_BROADCASTS_SUMMARY);
            intent.dumpDebug(proto, BroadcastQueueProto.BroadcastSummary.INTENT,
                    false, true, true, false);
            proto.write(BroadcastQueueProto.BroadcastSummary.ENQUEUE_CLOCK_TIME_MS,
                    mSummaryHistoryEnqueueTime[ringIndex]);
            proto.write(BroadcastQueueProto.BroadcastSummary.DISPATCH_CLOCK_TIME_MS,
                    mSummaryHistoryDispatchTime[ringIndex]);
            proto.write(BroadcastQueueProto.BroadcastSummary.FINISH_CLOCK_TIME_MS,
                    mSummaryHistoryFinishTime[ringIndex]);
            proto.end(summaryToken);
        } while (ringIndex != lastIndex);
        proto.end(token);
    }

    final boolean dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage, boolean needSep) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        if (!mParallelBroadcasts.isEmpty() || !mDispatcher.isEmpty()
                || mPendingBroadcast != null) {
            boolean printed = false;
            for (int i = mParallelBroadcasts.size() - 1; i >= 0; i--) {
                BroadcastRecord br = mParallelBroadcasts.get(i);
                if (dumpPackage != null && !dumpPackage.equals(br.callerPackage)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    printed = true;
                    pw.println("  Active broadcasts [" + mQueueName + "]:");
                }
                pw.println("  Active Broadcast " + mQueueName + " #" + i + ":");
                br.dump(pw, "    ", sdf);
            }

            mDispatcher.dumpLocked(pw, dumpPackage, mQueueName, sdf);

            if (dumpPackage == null || (mPendingBroadcast != null
                    && dumpPackage.equals(mPendingBroadcast.callerPackage))) {
                pw.println();
                pw.println("  Pending broadcast [" + mQueueName + "]:");
                if (mPendingBroadcast != null) {
                    mPendingBroadcast.dump(pw, "    ", sdf);
                } else {
                    pw.println("    (null)");
                }
                needSep = true;
            }
        }

        mConstants.dump(pw);

        int i;
        boolean printed = false;

        i = -1;
        int lastIndex = mHistoryNext;
        int ringIndex = lastIndex;
        do {
            // increasing index = more recent entry, and we want to print the most
            // recent first and work backwards, so we roll through the ring backwards.
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = mBroadcastHistory[ringIndex];
            if (r == null) {
                continue;
            }

            i++; // genuine record of some sort even if we're filtering it out
            if (dumpPackage != null && !dumpPackage.equals(r.callerPackage)) {
                continue;
            }
            if (!printed) {
                if (needSep) {
                    pw.println();
                }
                needSep = true;
                pw.println("  Historical broadcasts [" + mQueueName + "]:");
                printed = true;
            }
            if (dumpAll) {
                pw.print("  Historical Broadcast " + mQueueName + " #");
                        pw.print(i); pw.println(":");
                r.dump(pw, "    ", sdf);
            } else {
                pw.print("  #"); pw.print(i); pw.print(": "); pw.println(r);
                pw.print("    ");
                pw.println(r.intent.toShortString(false, true, true, false));
                if (r.targetComp != null && r.targetComp != r.intent.getComponent()) {
                    pw.print("    targetComp: "); pw.println(r.targetComp.toShortString());
                }
                Bundle bundle = r.intent.getExtras();
                if (bundle != null) {
                    pw.print("    extras: "); pw.println(bundle.toString());
                }
            }
        } while (ringIndex != lastIndex);

        if (dumpPackage == null) {
            lastIndex = ringIndex = mSummaryHistoryNext;
            if (dumpAll) {
                printed = false;
                i = -1;
            } else {
                // roll over the 'i' full dumps that have already been issued
                for (int j = i;
                        j > 0 && ringIndex != lastIndex;) {
                    ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                    BroadcastRecord r = mBroadcastHistory[ringIndex];
                    if (r == null) {
                        continue;
                    }
                    j--;
                }
            }
            // done skipping; dump the remainder of the ring. 'i' is still the ordinal within
            // the overall broadcast history.
            do {
                ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                Intent intent = mBroadcastSummaryHistory[ringIndex];
                if (intent == null) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    pw.println("  Historical broadcasts summary [" + mQueueName + "]:");
                    printed = true;
                }
                if (!dumpAll && i >= 50) {
                    pw.println("  ...");
                    break;
                }
                i++;
                pw.print("  #"); pw.print(i); pw.print(": ");
                pw.println(intent.toShortString(false, true, true, false));
                pw.print("    ");
                TimeUtils.formatDuration(mSummaryHistoryDispatchTime[ringIndex]
                        - mSummaryHistoryEnqueueTime[ringIndex], pw);
                pw.print(" dispatch ");
                TimeUtils.formatDuration(mSummaryHistoryFinishTime[ringIndex]
                        - mSummaryHistoryDispatchTime[ringIndex], pw);
                pw.println(" finish");
                pw.print("    enq=");
                pw.print(sdf.format(new Date(mSummaryHistoryEnqueueTime[ringIndex])));
                pw.print(" disp=");
                pw.print(sdf.format(new Date(mSummaryHistoryDispatchTime[ringIndex])));
                pw.print(" fin=");
                pw.println(sdf.format(new Date(mSummaryHistoryFinishTime[ringIndex])));
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    pw.print("    extras: "); pw.println(bundle.toString());
                }
            } while (ringIndex != lastIndex);
        }

        return needSep;
    }
}
