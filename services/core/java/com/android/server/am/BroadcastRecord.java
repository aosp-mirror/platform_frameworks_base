/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.AppProtoEnums.BROADCAST_TYPE_ALARM;
import static android.app.AppProtoEnums.BROADCAST_TYPE_BACKGROUND;
import static android.app.AppProtoEnums.BROADCAST_TYPE_DEFERRABLE_UNTIL_ACTIVE;
import static android.app.AppProtoEnums.BROADCAST_TYPE_FOREGROUND;
import static android.app.AppProtoEnums.BROADCAST_TYPE_INITIAL_STICKY;
import static android.app.AppProtoEnums.BROADCAST_TYPE_INTERACTIVE;
import static android.app.AppProtoEnums.BROADCAST_TYPE_NONE;
import static android.app.AppProtoEnums.BROADCAST_TYPE_ORDERED;
import static android.app.AppProtoEnums.BROADCAST_TYPE_PRIORITIZED;
import static android.app.AppProtoEnums.BROADCAST_TYPE_PUSH_MESSAGE;
import static android.app.AppProtoEnums.BROADCAST_TYPE_PUSH_MESSAGE_OVER_QUOTA;
import static android.app.AppProtoEnums.BROADCAST_TYPE_RESULT_TO;
import static android.app.AppProtoEnums.BROADCAST_TYPE_STICKY;

import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_ALL;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_CHANGE_ID;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_NONE;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_TARGET_T_ONLY;

import android.annotation.CheckResult;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.app.ActivityManager.ProcessState;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.app.BroadcastOptions.DeliveryGroupPolicy;
import android.app.compat.CompatChanges;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.PrintWriterPrinter;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * An active intent broadcast.
 */
final class BroadcastRecord extends Binder {
    final @NonNull Intent intent;    // the original intent that generated us
    final @Nullable ComponentName targetComp; // original component name set on the intent
    final @Nullable ProcessRecord callerApp; // process that sent this
    final @Nullable String callerPackage; // who sent this
    final @Nullable String callerFeatureId; // which feature in the package sent this
    final int callingPid;   // the pid of who sent this
    final int callingUid;   // the uid of who sent this
    final @ProcessState int callerProcState; // Procstate of the caller process at enqueue time.

    final int originalStickyCallingUid;
            // if this is a sticky broadcast, the Uid of the original sender
    final boolean callerInstantApp; // caller is an Instant App?
    final boolean callerInstrumented; // caller is being instrumented?
    final boolean ordered;  // serialize the send to receivers?
    final boolean sticky;   // originated from existing sticky data?
    final boolean alarm;    // originated from an alarm triggering?
    final boolean pushMessage; // originated from a push message?
    final boolean pushMessageOverQuota; // originated from a push message which was over quota?
    final boolean interactive; // originated from user interaction?
    final boolean initialSticky; // initial broadcast from register to sticky?
    final boolean prioritized; // contains more than one priority tranche
    final boolean deferUntilActive; // infinitely deferrable broadcast
    final boolean shareIdentity;  // whether the broadcaster's identity should be shared
    final boolean urgent;    // has been classified as "urgent"
    final int userId;       // user id this broadcast was for
    final @Nullable String resolvedType; // the resolved data type
    final @Nullable String[] requiredPermissions; // permissions the caller has required
    final @Nullable String[] excludedPermissions; // permissions to exclude
    final @Nullable String[] excludedPackages; // packages to exclude
    final int appOp;        // an app op that is associated with this broadcast
    final @Nullable BroadcastOptions options; // BroadcastOptions supplied by caller
    final @NonNull List<Object> receivers;   // contains BroadcastFilter and ResolveInfo
    final @DeliveryState int[] delivery;   // delivery state of each receiver
    final @NonNull String[] deliveryReasons; // reasons for delivery state of each receiver
    final int[] blockedUntilBeyondCount; // blocked until count of each receiver
    @Nullable ProcessRecord resultToApp; // who receives final result if non-null
    @Nullable IIntentReceiver resultTo; // who receives final result if non-null
    boolean deferred;
    int splitCount;         // refcount for result callback, when split
    int splitToken;         // identifier for cross-BroadcastRecord refcount
    @UptimeMillisLong       long enqueueTime;        // when broadcast enqueued
    @ElapsedRealtimeLong    long enqueueRealTime;    // when broadcast enqueued
    @CurrentTimeMillisLong  long enqueueClockTime;   // when broadcast enqueued
    // When broadcast is originally enqueued. Only used in case of replacing broadcasts
    // with FLAG_RECEIVER_REPLACE_PENDING. If it is 0, then 'enqueueClockTime' is the original
    // enqueue time.
    @UptimeMillisLong       long originalEnqueueClockTime;
    @UptimeMillisLong       long dispatchTime;       // when broadcast dispatch started
    @ElapsedRealtimeLong    long dispatchRealTime;   // when broadcast dispatch started
    @CurrentTimeMillisLong  long dispatchClockTime;  // when broadcast dispatch started
    @UptimeMillisLong       long receiverTime;       // when receiver started for timeouts
    @UptimeMillisLong       long finishTime;         // when broadcast finished
    final @UptimeMillisLong long[] scheduledTime;    // when each receiver was scheduled
    final @UptimeMillisLong long[] terminalTime;     // when each receiver was terminal
    final boolean timeoutExempt;  // true if this broadcast is not subject to receiver timeouts
    int resultCode;         // current result code value.
    @Nullable String resultData;      // current result data value.
    @Nullable Bundle resultExtras;    // current result extra data values.
    boolean resultAbort;    // current result abortBroadcast value.
    int nextReceiver;       // next receiver to be executed.
    int state;
    int anrCount;           // has this broadcast record hit any ANRs?
    int manifestCount;      // number of manifest receivers dispatched.
    int manifestSkipCount;  // number of manifest receivers skipped.
    int terminalCount;      // number of receivers in terminal state.
    int deferredCount;      // number of receivers in deferred state.
    int beyondCount;        // high-water number of receivers we've moved beyond.
    @Nullable BroadcastQueue queue;   // the outbound queue handling this broadcast

    // Determines the privileges the app's process has in regard to background starts.
    final BackgroundStartPrivileges mBackgroundStartPrivileges;

    // Filter the intent extras by using the rules of the package visibility before broadcasting
    // the intent to the receiver.
    @Nullable
    final BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver;

    // Cache of records that are "matching" this. Only used at the time of enqueuing this record
    // into the queue.
    @Nullable
    private ArrayMap<BroadcastRecord, Boolean> mMatchingRecordsCache;

    private @Nullable String mCachedToString;
    private @Nullable String mCachedToShortString;

    /**
     * When enabled, assume that {@link UserHandle#isCore(int)} apps should
     * treat {@link BroadcastOptions#DEFERRAL_POLICY_DEFAULT} as
     * {@link BroadcastOptions#DEFERRAL_POLICY_UNTIL_ACTIVE}.
     */
    static boolean CORE_DEFER_UNTIL_ACTIVE = false;

    /** Empty immutable list of receivers */
    static final List<Object> EMPTY_RECEIVERS = List.of();

    static final int IDLE = 0;
    static final int APP_RECEIVE = 1;
    static final int CALL_IN_RECEIVE = 2;
    static final int CALL_DONE_RECEIVE = 3;
    static final int WAITING_SERVICES = 4;

    /** Initial state: waiting to run in future */
    static final int DELIVERY_PENDING = 0;
    /** Terminal state: finished successfully */
    static final int DELIVERY_DELIVERED = 1;
    /** Terminal state: skipped due to internal policy */
    static final int DELIVERY_SKIPPED = 2;
    /** Terminal state: timed out during attempted delivery */
    static final int DELIVERY_TIMEOUT = 3;
    /** Intermediate state: currently executing */
    static final int DELIVERY_SCHEDULED = 4;
    /** Terminal state: failure to dispatch */
    static final int DELIVERY_FAILURE = 5;
    /** Intermediate state: currently deferred while app is cached */
    static final int DELIVERY_DEFERRED = 6;

    @IntDef(flag = false, prefix = { "DELIVERY_" }, value = {
            DELIVERY_PENDING,
            DELIVERY_DELIVERED,
            DELIVERY_SKIPPED,
            DELIVERY_TIMEOUT,
            DELIVERY_SCHEDULED,
            DELIVERY_FAILURE,
            DELIVERY_DEFERRED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeliveryState {}

    static @NonNull String deliveryStateToString(@DeliveryState int deliveryState) {
        switch (deliveryState) {
            case DELIVERY_PENDING: return "PENDING";
            case DELIVERY_DELIVERED: return "DELIVERED";
            case DELIVERY_SKIPPED: return "SKIPPED";
            case DELIVERY_TIMEOUT: return "TIMEOUT";
            case DELIVERY_SCHEDULED: return "SCHEDULED";
            case DELIVERY_FAILURE: return "FAILURE";
            case DELIVERY_DEFERRED: return "DEFERRED";
            default: return Integer.toString(deliveryState);
        }
    }

    /**
     * Return if the given delivery state is "terminal", where no additional
     * delivery state changes will be made.
     */
    static boolean isDeliveryStateTerminal(@DeliveryState int deliveryState) {
        switch (deliveryState) {
            case DELIVERY_DELIVERED:
            case DELIVERY_SKIPPED:
            case DELIVERY_TIMEOUT:
            case DELIVERY_FAILURE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Return if the given delivery state is "beyond", which means that we've
     * moved beyond this receiver, and future receivers are now unblocked.
     */
    static boolean isDeliveryStateBeyond(@DeliveryState int deliveryState) {
        switch (deliveryState) {
            case DELIVERY_DELIVERED:
            case DELIVERY_SKIPPED:
            case DELIVERY_TIMEOUT:
            case DELIVERY_FAILURE:
            case DELIVERY_DEFERRED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Return true if this receiver should be assumed to have been delivered.
     */
    boolean isAssumedDelivered(int index) {
        return (receivers.get(index) instanceof BroadcastFilter) && !ordered
                && (resultTo == null);
    }

    ProcessRecord curApp;       // hosting application of current receiver.
    ComponentName curComponent; // the receiver class that is currently running.
    ActivityInfo curReceiver;   // the manifest receiver that is currently running.
    BroadcastFilter curFilter;  // the registered receiver currently running.
    Bundle curFilteredExtras;   // the bundle that has been filtered by the package visibility rules

    int curAppLastProcessState; // The last process state of the current receiver before receiving

    boolean mIsReceiverAppRunning; // Was the receiver's app already running.

    boolean mWasReceiverAppStopped; // Was the receiver app stopped prior to starting

    // Private refcount-management bookkeeping; start > 0
    static AtomicInteger sNextToken = new AtomicInteger(1);

    @NeverCompile
    void dump(PrintWriter pw, String prefix, SimpleDateFormat sdf) {
        final long now = SystemClock.uptimeMillis();

        pw.print(prefix); pw.print(this); pw.print(" to user "); pw.println(userId);
        pw.print(prefix); pw.println(intent.toInsecureString());
        if (targetComp != null && targetComp != intent.getComponent()) {
            pw.print(prefix); pw.print("  targetComp: "); pw.println(targetComp.toShortString());
        }
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            pw.print(prefix); pw.print("  extras: "); pw.println(bundle.toString());
        }
        pw.print(prefix); pw.print("caller="); pw.print(callerPackage); pw.print(" ");
                pw.print(callerApp != null ? callerApp.toShortString() : "null");
                pw.print(" pid="); pw.print(callingPid);
                pw.print(" uid="); pw.println(callingUid);
        if ((requiredPermissions != null && requiredPermissions.length > 0)
                || appOp != AppOpsManager.OP_NONE) {
            pw.print(prefix); pw.print("requiredPermissions=");
            pw.print(Arrays.toString(requiredPermissions));
            pw.print("  appOp="); pw.println(appOp);
        }
        if (excludedPermissions != null && excludedPermissions.length > 0) {
            pw.print(prefix); pw.print("excludedPermissions=");
            pw.print(Arrays.toString(excludedPermissions));
        }
        if (excludedPackages != null && excludedPackages.length > 0) {
            pw.print(prefix); pw.print("excludedPackages=");
            pw.print(Arrays.toString(excludedPackages));
        }
        if (options != null) {
            pw.print(prefix); pw.print("options="); pw.println(options.toBundle());
        }
        pw.print(prefix); pw.print("enqueueClockTime=");
                pw.print(sdf.format(new Date(enqueueClockTime)));
                pw.print(" dispatchClockTime=");
                pw.print(sdf.format(new Date(dispatchClockTime)));
        if (originalEnqueueClockTime > 0) {
            pw.print(" originalEnqueueClockTime=");
            pw.print(sdf.format(new Date(originalEnqueueClockTime)));
        }
        pw.println();
        pw.print(prefix); pw.print("dispatchTime=");
                TimeUtils.formatDuration(dispatchTime, now, pw);
                pw.print(" (");
                TimeUtils.formatDuration(dispatchTime - enqueueTime, pw);
                pw.print(" since enq)");
        if (finishTime != 0) {
            pw.print(" finishTime="); TimeUtils.formatDuration(finishTime, now, pw);
            pw.print(" (");
            TimeUtils.formatDuration(finishTime-dispatchTime, pw);
            pw.print(" since disp)");
        } else {
            pw.print(" receiverTime="); TimeUtils.formatDuration(receiverTime, now, pw);
        }
        pw.println("");
        if (anrCount != 0) {
            pw.print(prefix); pw.print("anrCount="); pw.println(anrCount);
        }
        if (resultTo != null || resultCode != -1 || resultData != null) {
            pw.print(prefix); pw.print("resultTo="); pw.print(resultTo);
                    pw.print(" resultCode="); pw.print(resultCode);
                    pw.print(" resultData="); pw.println(resultData);
        }
        if (resultExtras != null) {
            pw.print(prefix); pw.print("resultExtras="); pw.println(resultExtras);
        }
        if (resultAbort || ordered || sticky || initialSticky) {
            pw.print(prefix); pw.print("resultAbort="); pw.print(resultAbort);
                    pw.print(" ordered="); pw.print(ordered);
                    pw.print(" sticky="); pw.print(sticky);
                    pw.print(" initialSticky="); pw.print(initialSticky);
                    pw.print(" originalStickyCallingUid="); pw.println(originalStickyCallingUid);
        }
        if (nextReceiver != 0) {
            pw.print(prefix); pw.print("nextReceiver="); pw.println(nextReceiver);
        }
        if (curFilter != null) {
            pw.print(prefix); pw.print("curFilter="); pw.println(curFilter);
        }
        if (curReceiver != null) {
            pw.print(prefix); pw.print("curReceiver="); pw.println(curReceiver);
        }
        if (curApp != null) {
            pw.print(prefix); pw.print("curApp="); pw.println(curApp);
            pw.print(prefix); pw.print("curComponent=");
                    pw.println((curComponent != null ? curComponent.toShortString() : "--"));
            if (curReceiver != null && curReceiver.applicationInfo != null) {
                pw.print(prefix); pw.print("curSourceDir=");
                        pw.println(curReceiver.applicationInfo.sourceDir);
            }
        }
        if (curFilteredExtras != null) {
            pw.print(" filtered extras: "); pw.println(curFilteredExtras);
        }
        if (state != IDLE) {
            String stateStr = " (?)";
            switch (state) {
                case APP_RECEIVE:       stateStr=" (APP_RECEIVE)"; break;
                case CALL_IN_RECEIVE:   stateStr=" (CALL_IN_RECEIVE)"; break;
                case CALL_DONE_RECEIVE: stateStr=" (CALL_DONE_RECEIVE)"; break;
                case WAITING_SERVICES:  stateStr=" (WAITING_SERVICES)"; break;
            }
            pw.print(prefix); pw.print("state="); pw.print(state); pw.println(stateStr);
        }
        pw.print(prefix); pw.print("terminalCount="); pw.println(terminalCount);
        final int N = receivers != null ? receivers.size() : 0;
        String p2 = prefix + "  ";
        PrintWriterPrinter printer = new PrintWriterPrinter(pw);
        for (int i = 0; i < N; i++) {
            Object o = receivers.get(i);
            pw.print(prefix);
            pw.print(deliveryStateToString(delivery[i]));
            pw.print(' ');
            if (scheduledTime[i] != 0) {
                pw.print("scheduled ");
                TimeUtils.formatDuration(scheduledTime[i] - enqueueTime, pw);
                pw.print(' ');
            }
            if (terminalTime[i] != 0) {
                pw.print("terminal ");
                TimeUtils.formatDuration(terminalTime[i] - scheduledTime[i], pw);
                pw.print(' ');
            }
            pw.print("("); pw.print(blockedUntilBeyondCount[i]); pw.print(") ");
            pw.print("#"); pw.print(i); pw.print(": ");
            if (o instanceof BroadcastFilter) {
                pw.println(o);
                ((BroadcastFilter) o).dumpBrief(pw, p2);
            } else if (o instanceof ResolveInfo) {
                pw.println("(manifest)");
                ((ResolveInfo) o).dump(printer, p2, 0);
            } else {
                pw.println(o);
            }
            if (deliveryReasons[i] != null) {
                pw.print(p2); pw.print("reason: "); pw.println(deliveryReasons[i]);
            }
        }
    }

    BroadcastRecord(BroadcastQueue queue,
            Intent intent, ProcessRecord callerApp, String callerPackage,
            @Nullable String callerFeatureId, int callingPid, int callingUid,
            boolean callerInstantApp, String resolvedType,
            String[] requiredPermissions, String[] excludedPermissions,
            String[] excludedPackages, int appOp,
            BroadcastOptions options, List receivers,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode,
            String resultData, Bundle resultExtras, boolean serialized, boolean sticky,
            boolean initialSticky, int userId,
            @NonNull BackgroundStartPrivileges backgroundStartPrivileges,
            boolean timeoutExempt,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            int callerAppProcessState) {
        this(queue, intent, callerApp, callerPackage, callerFeatureId, callingPid,
                callingUid, callerInstantApp, resolvedType, requiredPermissions,
                excludedPermissions, excludedPackages, appOp, options, receivers, resultToApp,
                resultTo, resultCode, resultData, resultExtras, serialized, sticky,
                initialSticky, userId, -1, backgroundStartPrivileges, timeoutExempt,
                filterExtrasForReceiver, callerAppProcessState);
    }

    BroadcastRecord(BroadcastQueue _queue,
            Intent _intent, ProcessRecord _callerApp, String _callerPackage,
            @Nullable String _callerFeatureId, int _callingPid, int _callingUid,
            boolean _callerInstantApp, String _resolvedType,
            String[] _requiredPermissions, String[] _excludedPermissions,
            String[] _excludedPackages, int _appOp,
            BroadcastOptions _options, List _receivers,
            ProcessRecord _resultToApp, IIntentReceiver _resultTo, int _resultCode,
            String _resultData, Bundle _resultExtras, boolean _serialized, boolean _sticky,
            boolean _initialSticky, int _userId, int originalStickyCallingUid,
            @NonNull BackgroundStartPrivileges backgroundStartPrivileges,
            boolean timeoutExempt,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            int callerAppProcessState) {
        if (_intent == null) {
            throw new NullPointerException("Can't construct with a null intent");
        }
        queue = _queue;
        intent = Objects.requireNonNull(_intent);
        targetComp = _intent.getComponent();
        callerApp = _callerApp;
        callerPackage = _callerPackage;
        callerFeatureId = _callerFeatureId;
        callingPid = _callingPid;
        callingUid = _callingUid;
        callerProcState = callerAppProcessState;
        callerInstantApp = _callerInstantApp;
        callerInstrumented = isCallerInstrumented(_callerApp, _callingUid);
        resolvedType = _resolvedType;
        requiredPermissions = _requiredPermissions;
        excludedPermissions = _excludedPermissions;
        excludedPackages = _excludedPackages;
        appOp = _appOp;
        options = _options;
        receivers = (_receivers != null) ? _receivers : EMPTY_RECEIVERS;
        delivery = new int[_receivers != null ? _receivers.size() : 0];
        deliveryReasons = new String[delivery.length];
        urgent = calculateUrgent(_intent, _options);
        deferUntilActive = calculateDeferUntilActive(_callingUid,
                _options, _resultTo, _serialized, urgent);
        blockedUntilBeyondCount = calculateBlockedUntilBeyondCount(receivers, _serialized);
        scheduledTime = new long[delivery.length];
        terminalTime = new long[delivery.length];
        resultToApp = _resultToApp;
        resultTo = _resultTo;
        resultCode = _resultCode;
        resultData = _resultData;
        resultExtras = _resultExtras;
        ordered = _serialized;
        sticky = _sticky;
        initialSticky = _initialSticky;
        prioritized = isPrioritized(blockedUntilBeyondCount, _serialized);
        userId = _userId;
        nextReceiver = 0;
        state = IDLE;
        mBackgroundStartPrivileges = backgroundStartPrivileges;
        this.timeoutExempt = timeoutExempt;
        alarm = options != null && options.isAlarmBroadcast();
        pushMessage = options != null && options.isPushMessagingBroadcast();
        pushMessageOverQuota = options != null && options.isPushMessagingOverQuotaBroadcast();
        interactive = options != null && options.isInteractive();
        shareIdentity = options != null && options.isShareIdentityEnabled();
        this.filterExtrasForReceiver = filterExtrasForReceiver;
        this.originalStickyCallingUid = originalStickyCallingUid;
    }

    /**
     * Copy constructor which takes a different intent.
     * Only used by {@link #maybeStripForHistory}.
     */
    private BroadcastRecord(BroadcastRecord from, Intent newIntent) {
        intent = Objects.requireNonNull(newIntent);
        targetComp = newIntent.getComponent();

        callerApp = from.callerApp;
        callerPackage = from.callerPackage;
        callerFeatureId = from.callerFeatureId;
        callingPid = from.callingPid;
        callingUid = from.callingUid;
        callerProcState = from.callerProcState;
        callerInstantApp = from.callerInstantApp;
        callerInstrumented = from.callerInstrumented;
        ordered = from.ordered;
        sticky = from.sticky;
        initialSticky = from.initialSticky;
        prioritized = from.prioritized;
        userId = from.userId;
        resolvedType = from.resolvedType;
        requiredPermissions = from.requiredPermissions;
        excludedPermissions = from.excludedPermissions;
        excludedPackages = from.excludedPackages;
        appOp = from.appOp;
        options = from.options;
        receivers = from.receivers;
        delivery = from.delivery;
        deliveryReasons = from.deliveryReasons;
        deferUntilActive = from.deferUntilActive;
        blockedUntilBeyondCount = from.blockedUntilBeyondCount;
        scheduledTime = from.scheduledTime;
        terminalTime = from.terminalTime;
        resultToApp = from.resultToApp;
        resultTo = from.resultTo;
        enqueueTime = from.enqueueTime;
        enqueueRealTime = from.enqueueRealTime;
        enqueueClockTime = from.enqueueClockTime;
        dispatchTime = from.dispatchTime;
        dispatchRealTime = from.dispatchRealTime;
        dispatchClockTime = from.dispatchClockTime;
        receiverTime = from.receiverTime;
        finishTime = from.finishTime;
        resultCode = from.resultCode;
        resultData = from.resultData;
        resultExtras = from.resultExtras;
        resultAbort = from.resultAbort;
        nextReceiver = from.nextReceiver;
        state = from.state;
        anrCount = from.anrCount;
        manifestCount = from.manifestCount;
        manifestSkipCount = from.manifestSkipCount;
        queue = from.queue;
        mBackgroundStartPrivileges = from.mBackgroundStartPrivileges;
        timeoutExempt = from.timeoutExempt;
        alarm = from.alarm;
        pushMessage = from.pushMessage;
        pushMessageOverQuota = from.pushMessageOverQuota;
        interactive = from.interactive;
        shareIdentity = from.shareIdentity;
        urgent = from.urgent;
        filterExtrasForReceiver = from.filterExtrasForReceiver;
        originalStickyCallingUid = from.originalStickyCallingUid;
    }

    /**
     * Split off a new BroadcastRecord that clones this one, but contains only the
     * recipient records for the current (just-finished) receiver's app, starting
     * after the just-finished receiver [i.e. at r.nextReceiver].  Returns null
     * if there are no matching subsequent receivers in this BroadcastRecord.
     */
    BroadcastRecord splitRecipientsLocked(int slowAppUid, int startingAt) {
        // Do we actually have any matching receivers down the line...?
        ArrayList splitReceivers = null;
        for (int i = startingAt; i < receivers.size(); ) {
            Object o = receivers.get(i);
            if (getReceiverUid(o) == slowAppUid) {
                if (splitReceivers == null) {
                    splitReceivers = new ArrayList<>();
                }
                splitReceivers.add(o);
                receivers.remove(i);
            } else {
                i++;
            }
        }

        // No later receivers in the same app, so we have no more to do
        if (splitReceivers == null) {
            return null;
        }

        // build a new BroadcastRecord around that single-target list
        BroadcastRecord split = new BroadcastRecord(queue, intent, callerApp, callerPackage,
                callerFeatureId, callingPid, callingUid, callerInstantApp, resolvedType,
                requiredPermissions, excludedPermissions, excludedPackages, appOp, options,
                splitReceivers, resultToApp, resultTo, resultCode, resultData, resultExtras,
                ordered, sticky, initialSticky, userId,
                mBackgroundStartPrivileges, timeoutExempt, filterExtrasForReceiver,
                callerProcState);
        split.enqueueTime = this.enqueueTime;
        split.enqueueRealTime = this.enqueueRealTime;
        split.enqueueClockTime = this.enqueueClockTime;
        split.splitToken = this.splitToken;
        return split;
    }

    /**
     * Split a BroadcastRecord to a map of deferred receiver UID to deferred BroadcastRecord.
     *
     * The receivers that are deferred are removed from original BroadcastRecord's receivers list.
     * The receivers that are not deferred are kept in original BroadcastRecord's receivers list.
     *
     * Only used to split LOCKED_BOOT_COMPLETED or BOOT_COMPLETED BroadcastRecord.
     * LOCKED_BOOT_COMPLETED or BOOT_COMPLETED broadcast can be deferred until the first time
     * the receiver's UID has a process started.
     *
     * @param ams The ActivityManagerService object.
     * @param deferType Defer what UID?
     * @return the deferred UID to BroadcastRecord map, the BroadcastRecord has the list of
     *         receivers in that UID.
     */
    @NonNull SparseArray<BroadcastRecord> splitDeferredBootCompletedBroadcastLocked(
            ActivityManagerInternal activityManagerInternal,
            @BroadcastConstants.DeferBootCompletedBroadcastType int deferType) {
        final SparseArray<BroadcastRecord> ret = new SparseArray<>();
        if (deferType == DEFER_BOOT_COMPLETED_BROADCAST_NONE) {
            return ret;
        }

        if (receivers == null) {
            return ret;
        }

        final String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return ret;
        }

        final SparseArray<List<Object>> uid2receiverList = new SparseArray<>();
        for (int i = receivers.size() - 1; i >= 0; i--) {
            final Object receiver = receivers.get(i);
            final int uid = getReceiverUid(receiver);
            if (deferType != DEFER_BOOT_COMPLETED_BROADCAST_ALL) {
                if ((deferType & DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY) != 0) {
                    if (activityManagerInternal.getRestrictionLevel(uid)
                            < RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                        // skip if the UID is not background restricted.
                        continue;
                    }
                }
                if ((deferType & DEFER_BOOT_COMPLETED_BROADCAST_TARGET_T_ONLY) != 0) {
                    if (!CompatChanges.isChangeEnabled(DEFER_BOOT_COMPLETED_BROADCAST_CHANGE_ID,
                            uid)) {
                        // skip if the UID is not targetSdkVersion T+.
                        continue;
                    }
                }
            }
            // Remove receiver from original BroadcastRecord's receivers list.
            receivers.remove(i);
            final List<Object> receiverList = uid2receiverList.get(uid);
            if (receiverList != null) {
                receiverList.add(0, receiver);
            } else {
                ArrayList<Object> splitReceivers = new ArrayList<>();
                splitReceivers.add(0, receiver);
                uid2receiverList.put(uid, splitReceivers);
            }
        }
        final int uidSize = uid2receiverList.size();
        for (int i = 0; i < uidSize; i++) {
            final BroadcastRecord br = new BroadcastRecord(queue, intent, callerApp, callerPackage,
                    callerFeatureId, callingPid, callingUid, callerInstantApp, resolvedType,
                    requiredPermissions, excludedPermissions, excludedPackages, appOp, options,
                    uid2receiverList.valueAt(i), null /* _resultToApp */, null /* _resultTo */,
                    resultCode, resultData, resultExtras, ordered, sticky, initialSticky, userId,
                    mBackgroundStartPrivileges, timeoutExempt,
                    filterExtrasForReceiver, callerProcState);
            br.enqueueTime = this.enqueueTime;
            br.enqueueRealTime = this.enqueueRealTime;
            br.enqueueClockTime = this.enqueueClockTime;
            ret.put(uid2receiverList.keyAt(i), br);
        }
        return ret;
    }

    /**
     * Update the delivery state of the given {@link #receivers} index.
     * Automatically updates any time measurements related to state changes.
     *
     * @return if {@link #beyondCount} changed due to this state transition,
     *         indicating that other events may be unblocked.
     */
    @CheckResult
    boolean setDeliveryState(int index, @DeliveryState int newDeliveryState,
            @NonNull String reason) {
        final int oldDeliveryState = delivery[index];
        if (isDeliveryStateTerminal(oldDeliveryState)
                || newDeliveryState == oldDeliveryState) {
            // We've already arrived in terminal or requested state, so leave
            // any statistics and reasons intact from the first transition
            return false;
        }

        switch (oldDeliveryState) {
            case DELIVERY_DEFERRED:
                deferredCount--;
                break;
        }
        switch (newDeliveryState) {
            case DELIVERY_PENDING:
                scheduledTime[index] = 0;
                break;
            case DELIVERY_SCHEDULED:
                scheduledTime[index] = SystemClock.uptimeMillis();
                break;
            case DELIVERY_DEFERRED:
                deferredCount++;
                break;
            case DELIVERY_DELIVERED:
            case DELIVERY_SKIPPED:
            case DELIVERY_TIMEOUT:
            case DELIVERY_FAILURE:
                terminalTime[index] = SystemClock.uptimeMillis();
                terminalCount++;
                break;
        }

        delivery[index] = newDeliveryState;
        deliveryReasons[index] = reason;

        // If this state change might bring us to a new high-water mark, bring
        // ourselves as high as we possibly can
        final int oldBeyondCount = beyondCount;
        if (index >= beyondCount) {
            for (int i = beyondCount; i < delivery.length; i++) {
                if (isDeliveryStateBeyond(getDeliveryState(i))) {
                    beyondCount = i + 1;
                } else {
                    break;
                }
            }
        }
        return (beyondCount != oldBeyondCount);
    }

    @DeliveryState int getDeliveryState(int index) {
        return delivery[index];
    }

    /**
     * @return if the given {@link #receivers} index should be considered
     *         blocked based on the current status of the overall broadcast.
     */
    boolean isBlocked(int index) {
        return (beyondCount < blockedUntilBeyondCount[index]);
    }

    boolean wasDeliveryAttempted(int index) {
        final int deliveryState = getDeliveryState(index);
        switch (deliveryState) {
            case DELIVERY_DELIVERED:
            case DELIVERY_TIMEOUT:
            case DELIVERY_FAILURE:
                return true;
            default:
                return false;
        }
    }

    void copyEnqueueTimeFrom(@NonNull BroadcastRecord replacedBroadcast) {
        originalEnqueueClockTime = enqueueClockTime;
        enqueueTime = replacedBroadcast.enqueueTime;
        enqueueRealTime = replacedBroadcast.enqueueRealTime;
        enqueueClockTime = replacedBroadcast.enqueueClockTime;
    }

    boolean isForeground() {
        return (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0;
    }

    boolean isReplacePending() {
        return (intent.getFlags() & Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;
    }

    boolean isNoAbort() {
        return (intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) != 0;
    }

    boolean isOffload() {
        return (intent.getFlags() & Intent.FLAG_RECEIVER_OFFLOAD) != 0;
    }

    boolean isDeferUntilActive() {
        return deferUntilActive;
    }

    boolean isUrgent() {
        return urgent;
    }

    @NonNull String getHostingRecordTriggerType() {
        if (alarm) {
            return HostingRecord.TRIGGER_TYPE_ALARM;
        } else if (pushMessage) {
            return HostingRecord.TRIGGER_TYPE_PUSH_MESSAGE;
        } else if (pushMessageOverQuota) {
            return HostingRecord.TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA;
        }
        return HostingRecord.TRIGGER_TYPE_UNKNOWN;
    }

    /**
     * Return an instance of {@link #intent} specialized for the given receiver.
     * For example, this returns a new specialized instance if the extras need
     * to be filtered, or a {@link ResolveInfo} needs to be configured.
     *
     * @return a specialized intent, otherwise {@code null} to indicate that the
     *         broadcast should not be delivered to this receiver, typically due
     *         to it being filtered away by {@link #filterExtrasForReceiver}.
     */
    @Nullable Intent getReceiverIntent(@NonNull Object receiver) {
        Intent newIntent = null;
        if (filterExtrasForReceiver != null) {
            final Bundle extras = intent.getExtras();
            if (extras != null) {
                final int receiverUid = getReceiverUid(receiver);
                final Bundle filteredExtras = filterExtrasForReceiver.apply(receiverUid, extras);
                if (filteredExtras == null) {
                    // Completely filtered; skip the broadcast!
                    return null;
                } else {
                    newIntent = new Intent(intent);
                    newIntent.replaceExtras(filteredExtras);
                }
            }
        }
        if (receiver instanceof ResolveInfo) {
            if (newIntent == null) {
                newIntent = new Intent(intent);
            }
            newIntent.setComponent(((ResolveInfo) receiver).activityInfo.getComponentName());
        }
        return (newIntent != null) ? newIntent : intent;
    }

    static boolean isCallerInstrumented(@Nullable ProcessRecord callerApp, int callingUid) {
        switch (UserHandle.getAppId(callingUid)) {
            case android.os.Process.ROOT_UID:
            case android.os.Process.SHELL_UID:
                // Broadcasts sent via "shell" are typically invoked by test
                // suites, so we treat them as if the caller was instrumented
                return true;
        }
        return (callerApp != null) ? (callerApp.getActiveInstrumentation() != null) : false;
    }

    /**
     * Determine if the result of {@link #calculateBlockedUntilTerminalCount}
     * has prioritized tranches of receivers.
     */
    @VisibleForTesting
    static boolean isPrioritized(@NonNull int[] blockedUntilBeyondCount,
            boolean ordered) {
        return !ordered && (blockedUntilBeyondCount.length > 0)
                && (blockedUntilBeyondCount[0] != -1);
    }

    /**
     * Calculate the {@link #beyondCount} that each receiver should be
     * considered blocked until.
     * <p>
     * For example, in an ordered broadcast, receiver {@code N} is blocked until
     * receiver {@code N-1} reaches a terminal or deferred state. Similarly, in
     * a prioritized broadcast, receiver {@code N} is blocked until all
     * receivers of a higher priority reach a terminal or deferred state.
     * <p>
     * When there are no beyond count constraints, the blocked value for each
     * receiver is {@code -1}.
     */
    @VisibleForTesting
    static @NonNull int[] calculateBlockedUntilBeyondCount(
            @NonNull List<Object> receivers, boolean ordered) {
        final int N = receivers.size();
        final int[] blockedUntilBeyondCount = new int[N];
        int lastPriority = 0;
        int lastPriorityIndex = 0;
        for (int i = 0; i < N; i++) {
            if (ordered) {
                // When sending an ordered broadcast, we need to block this
                // receiver until all previous receivers have terminated
                blockedUntilBeyondCount[i] = i;
            } else {
                // When sending a prioritized broadcast, we only need to wait
                // for the previous tranche of receivers to be terminated
                final int thisPriority = getReceiverPriority(receivers.get(i));
                if ((i == 0) || (thisPriority != lastPriority)) {
                    lastPriority = thisPriority;
                    lastPriorityIndex = i;
                    blockedUntilBeyondCount[i] = i;
                } else {
                    blockedUntilBeyondCount[i] = lastPriorityIndex;
                }
            }
        }
        // If the entire list is in the same priority tranche, mark as -1 to
        // indicate that none of them need to wait
        if (N > 0 && blockedUntilBeyondCount[N - 1] == 0) {
            Arrays.fill(blockedUntilBeyondCount, -1);
        }
        return blockedUntilBeyondCount;
    }

    static int getReceiverUid(@NonNull Object receiver) {
        if (receiver instanceof BroadcastFilter) {
            return ((BroadcastFilter) receiver).owningUid;
        } else /* if (receiver instanceof ResolveInfo) */ {
            return ((ResolveInfo) receiver).activityInfo.applicationInfo.uid;
        }
    }

    static @NonNull String getReceiverProcessName(@NonNull Object receiver) {
        if (receiver instanceof BroadcastFilter) {
            return ((BroadcastFilter) receiver).receiverList.app.processName;
        } else /* if (receiver instanceof ResolveInfo) */ {
            return ((ResolveInfo) receiver).activityInfo.processName;
        }
    }

    static @NonNull String getReceiverPackageName(@NonNull Object receiver) {
        if (receiver instanceof BroadcastFilter) {
            return ((BroadcastFilter) receiver).receiverList.app.info.packageName;
        } else /* if (receiver instanceof ResolveInfo) */ {
            return ((ResolveInfo) receiver).activityInfo.packageName;
        }
    }

    static @Nullable String getReceiverClassName(@NonNull Object receiver) {
        if (receiver instanceof BroadcastFilter) {
            return ((BroadcastFilter) receiver).getReceiverClassName();
        } else /* if (receiver instanceof ResolveInfo) */ {
            return ((ResolveInfo) receiver).activityInfo.name;
        }
    }

    static int getReceiverPriority(@NonNull Object receiver) {
        if (receiver instanceof BroadcastFilter) {
            return ((BroadcastFilter) receiver).getPriority();
        } else /* if (receiver instanceof ResolveInfo) */ {
            return ((ResolveInfo) receiver).priority;
        }
    }

    static boolean isReceiverEquals(@NonNull Object a, @NonNull Object b) {
        if (a == b) {
            return true;
        } else if (a instanceof ResolveInfo && b instanceof ResolveInfo) {
            final ResolveInfo infoA = (ResolveInfo) a;
            final ResolveInfo infoB = (ResolveInfo) b;
            return Objects.equals(infoA.activityInfo.packageName, infoB.activityInfo.packageName)
                    && Objects.equals(infoA.activityInfo.name, infoB.activityInfo.name);
        } else {
            return false;
        }
    }

    /**
     * Core policy determination about this broadcast's delivery prioritization
     */
    @VisibleForTesting
    static boolean calculateUrgent(@NonNull Intent intent, @Nullable BroadcastOptions options) {
        // TODO: flags for controlling policy
        // TODO: migrate alarm-prioritization flag to BroadcastConstants
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0) {
            return true;
        }
        if (options != null) {
            if (options.isInteractive()) {
                return true;
            }
            if (options.isAlarmBroadcast()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the requested {@link BroadcastOptions#setDeferralPolicy(int)}
     * against this broadcast state to determine if it should be marked as
     * "defer until active".
     */
    @VisibleForTesting
    static boolean calculateDeferUntilActive(int callingUid, @Nullable BroadcastOptions options,
            @Nullable IIntentReceiver resultTo, boolean ordered, boolean urgent) {
        // Ordered broadcasts can never be deferred until active
        if (ordered) {
            return false;
        }

        // Unordered resultTo broadcasts are always deferred until active
        if (!ordered && resultTo != null) {
            return true;
        }

        // Determine if a strong preference in either direction was expressed;
        // a preference here overrides all remaining policies
        if (options != null) {
            switch (options.getDeferralPolicy()) {
                case BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE:
                    return true;
                case BroadcastOptions.DEFERRAL_POLICY_NONE:
                    return false;
            }
        }

        // Urgent broadcasts aren't deferred until active
        if (urgent) {
            return false;
        }

        // Otherwise, choose a reasonable default
        if (CORE_DEFER_UNTIL_ACTIVE && UserHandle.isCore(callingUid)) {
            return true;
        } else {
            return false;
        }
    }

    int calculateTypeForLogging() {
        int type = BROADCAST_TYPE_NONE;
        if (isForeground()) {
            type |= BROADCAST_TYPE_FOREGROUND;
        } else {
            type |= BROADCAST_TYPE_BACKGROUND;
        }
        if (alarm) {
            type |= BROADCAST_TYPE_ALARM;
        }
        if (interactive) {
            type |= BROADCAST_TYPE_INTERACTIVE;
        }
        if (ordered) {
            type |= BROADCAST_TYPE_ORDERED;
        }
        if (prioritized) {
            type |= BROADCAST_TYPE_PRIORITIZED;
        }
        if (resultTo != null) {
            type |= BROADCAST_TYPE_RESULT_TO;
        }
        if (deferUntilActive) {
            type |= BROADCAST_TYPE_DEFERRABLE_UNTIL_ACTIVE;
        }
        if (pushMessage) {
            type |= BROADCAST_TYPE_PUSH_MESSAGE;
        }
        if (pushMessageOverQuota) {
            type |= BROADCAST_TYPE_PUSH_MESSAGE_OVER_QUOTA;
        }
        if (sticky) {
            type |= BROADCAST_TYPE_STICKY;
        }
        if (initialSticky) {
            type |= BROADCAST_TYPE_INITIAL_STICKY;
        }
        return type;
    }

    public BroadcastRecord maybeStripForHistory() {
        if (!intent.canStripForHistory()) {
            return this;
        }
        return new BroadcastRecord(this, intent.maybeStripForHistory());
    }

    @VisibleForTesting
    boolean cleanupDisabledPackageReceiversLocked(
            String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        if (receivers == null) {
            return false;
        }

        final boolean cleanupAllUsers = userId == UserHandle.USER_ALL;
        final boolean sendToAllUsers = this.userId == UserHandle.USER_ALL;
        if (this.userId != userId && !cleanupAllUsers && !sendToAllUsers) {
            return false;
        }

        boolean didSomething = false;
        Object o;
        for (int i = receivers.size() - 1; i >= 0; i--) {
            o = receivers.get(i);
            if (!(o instanceof ResolveInfo)) {
                continue;
            }
            ActivityInfo info = ((ResolveInfo)o).activityInfo;

            final boolean sameComponent = packageName == null
                    || (info.applicationInfo.packageName.equals(packageName)
                    && (filterByClasses == null || filterByClasses.contains(info.name)));
            if (sameComponent && (cleanupAllUsers
                    || UserHandle.getUserId(info.applicationInfo.uid) == userId)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                receivers.remove(i);
                if (i < nextReceiver) {
                    nextReceiver--;
                }
            }
        }
        nextReceiver = Math.min(nextReceiver, receivers.size());

        return didSomething;
    }

    /**
     * Apply special treatment to manifest receivers hosted by a singleton
     * process, by re-targeting them at {@link UserHandle#USER_SYSTEM}.
     */
    void applySingletonPolicy(@NonNull ActivityManagerService service) {
        if (receivers == null) return;
        for (int i = 0; i < receivers.size(); i++) {
            final Object receiver = receivers.get(i);
            if (receiver instanceof ResolveInfo) {
                final ResolveInfo info = (ResolveInfo) receiver;
                boolean isSingleton = false;
                try {
                    isSingleton = service.isSingleton(info.activityInfo.processName,
                            info.activityInfo.applicationInfo,
                            info.activityInfo.name, info.activityInfo.flags);
                } catch (SecurityException e) {
                    BroadcastQueue.logw(e.getMessage());
                }
                final int receiverUid = info.activityInfo.applicationInfo.uid;
                if (callingUid != android.os.Process.SYSTEM_UID && isSingleton
                        && service.isValidSingletonCall(callingUid, receiverUid)) {
                    info.activityInfo = service.getActivityInfoForUser(info.activityInfo,
                            UserHandle.USER_SYSTEM);
                }
            }
        }
    }

    boolean containsReceiver(@NonNull Object receiver) {
        for (int i = receivers.size() - 1; i >= 0; --i) {
            if (isReceiverEquals(receiver, receivers.get(i))) {
                return true;
            }
        }
        return false;
    }

    boolean containsAllReceivers(@NonNull List<Object> otherReceivers) {
        for (int i = otherReceivers.size() - 1; i >= 0; --i) {
            if (!containsReceiver(otherReceivers.get(i))) {
                return false;
            }
        }
        return true;
    }

    @DeliveryGroupPolicy
    int getDeliveryGroupPolicy() {
        return (options != null) ? options.getDeliveryGroupPolicy()
                : BroadcastOptions.DELIVERY_GROUP_POLICY_ALL;
    }

    boolean matchesDeliveryGroup(@NonNull BroadcastRecord other) {
        return matchesDeliveryGroup(this, other);
    }

    private static boolean matchesDeliveryGroup(@NonNull BroadcastRecord newRecord,
            @NonNull BroadcastRecord oldRecord) {
        final IntentFilter newMatchingFilter = getDeliveryGroupMatchingFilter(newRecord);
        // If neither delivery group key nor matching filter is specified, then use
        // Intent.filterEquals() to identify the delivery group.
        if (isMatchingKeyNull(newRecord) && isMatchingKeyNull(oldRecord)
                && newMatchingFilter == null) {
            return newRecord.intent.filterEquals(oldRecord.intent);
        }
        if (newMatchingFilter != null && !newMatchingFilter.asPredicate().test(oldRecord.intent)) {
            return false;
        }
        return areMatchingKeysEqual(newRecord, oldRecord);
    }

    private static boolean isMatchingKeyNull(@NonNull BroadcastRecord record) {
        final String namespace = getDeliveryGroupMatchingNamespaceFragment(record);
        final String key = getDeliveryGroupMatchingKeyFragment(record);
        // If either namespace or key part is null, then treat the entire matching key as null.
        return namespace == null || key == null;
    }

    private static boolean areMatchingKeysEqual(@NonNull BroadcastRecord newRecord,
            @NonNull BroadcastRecord oldRecord) {
        final String newNamespaceFragment = getDeliveryGroupMatchingNamespaceFragment(newRecord);
        final String oldNamespaceFragment = getDeliveryGroupMatchingNamespaceFragment(oldRecord);
        if (!Objects.equals(newNamespaceFragment, oldNamespaceFragment)) {
            return false;
        }

        final String newKeyFragment = getDeliveryGroupMatchingKeyFragment(newRecord);
        final String oldKeyFragment = getDeliveryGroupMatchingKeyFragment(oldRecord);
        return Objects.equals(newKeyFragment, oldKeyFragment);
    }

    @Nullable
    private static String getDeliveryGroupMatchingNamespaceFragment(
            @NonNull BroadcastRecord record) {
        return record.options == null
                ? null : record.options.getDeliveryGroupMatchingNamespaceFragment();
    }

    @Nullable
    private static String getDeliveryGroupMatchingKeyFragment(@NonNull BroadcastRecord record) {
        return record.options == null
                ? null : record.options.getDeliveryGroupMatchingKeyFragment();
    }

    @Nullable
    private static IntentFilter getDeliveryGroupMatchingFilter(@NonNull BroadcastRecord record) {
        return record.options == null ? null : record.options.getDeliveryGroupMatchingFilter();
    }

    /**
     * Returns {@code true} if all the receivers are still waiting to receive the broadcast.
     * Otherwise {@code false}.
     */
    boolean allReceiversPending() {
        // We could also count the number of receivers with deliver state DELIVERY_PENDING, but
        // checking how many receivers have finished (either skipped or cancelled) and whether or
        // not the dispatch has been started should be sufficient.
        return (terminalCount == 0 && dispatchTime <= 0);
    }

    boolean isMatchingRecord(@NonNull BroadcastRecord record) {
        final int idx = mMatchingRecordsCache.indexOfKey(record);
        if (idx > 0) {
            return mMatchingRecordsCache.valueAt(idx);
        }
        // Consider a record to be matching if has the same receivers in the same order.
        boolean matches = (receivers.size() == record.receivers.size());
        if (matches) {
            for (int i = receivers.size() - 1; i >= 0; --i) {
                if (!isReceiverEquals(receivers.get(i), record.receivers.get(i))) {
                    matches = false;
                    break;
                }
            }
        }
        mMatchingRecordsCache.put(record, matches);
        return matches;
    }

    void setMatchingRecordsCache(@NonNull ArrayMap<BroadcastRecord, Boolean> matchingRecordsCache) {
        mMatchingRecordsCache = matchingRecordsCache;
    }

    void clearMatchingRecordsCache() {
        mMatchingRecordsCache = null;
    }

    @Override
    public String toString() {
        if (mCachedToString == null) {
            String label = intent.getAction();
            if (label == null) {
                label = intent.toString();
            }
            mCachedToString = "BroadcastRecord{" + toShortString() + "}";
        }
        return mCachedToString;
    }

    public String toShortString() {
        if (mCachedToShortString == null) {
            String label = intent.getAction();
            if (label == null) {
                label = intent.toString();
            }
            mCachedToShortString = Integer.toHexString(System.identityHashCode(this))
                    + " " + label + "/u" + userId;
        }
        return mCachedToShortString;
    }

    @NeverCompile
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(BroadcastRecordProto.USER_ID, userId);
        proto.write(BroadcastRecordProto.INTENT_ACTION, intent.getAction());
        proto.end(token);
    }
}
