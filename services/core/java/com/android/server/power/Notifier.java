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

package com.android.server.power;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.trust.TrustManager;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.hardware.display.DisplayManagerInternal;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.WorkSource;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends broadcasts about important power state changes.
 * <p>
 * This methods of this class may be called by the power manager service while
 * its lock is being held.  Internally it takes care of sending broadcasts to
 * notify other components of the system or applications asynchronously.
 * </p><p>
 * The notifier is designed to collapse unnecessary broadcasts when it is not
 * possible for the system to have observed an intermediate state.
 * </p><p>
 * For example, if the device wakes up, goes to sleep, wakes up again and goes to
 * sleep again before the wake up notification is sent, then the system will
 * be told about only one wake up and sleep.  However, we always notify the
 * fact that at least one transition occurred.  It is especially important to
 * tell the system when we go to sleep so that it can lock the keyguard if needed.
 * </p>
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
public class Notifier {
    private static final String TAG = "PowerManagerNotifier";

    private static final boolean DEBUG = false;

    private static final int INTERACTIVE_STATE_UNKNOWN = 0;
    private static final int INTERACTIVE_STATE_AWAKE = 1;
    private static final int INTERACTIVE_STATE_ASLEEP = 2;

    private static final int MSG_USER_ACTIVITY = 1;
    private static final int MSG_BROADCAST = 2;
    private static final int MSG_WIRELESS_CHARGING_STARTED = 3;
    private static final int MSG_BROADCAST_ENHANCED_PREDICTION = 4;
    private static final int MSG_PROFILE_TIMED_OUT = 5;
    private static final int MSG_WIRED_CHARGING_STARTED = 6;
    private static final int MSG_SCREEN_POLICY = 7;

    private static final long[] CHARGING_VIBRATION_TIME = {
            40, 40, 40, 40, 40, 40, 40, 40, 40, // ramp-up sampling rate = 40ms
            40, 40, 40, 40, 40, 40, 40 // ramp-down sampling rate = 40ms
    };
    private static final int[] CHARGING_VIBRATION_AMPLITUDE = {
            1, 4, 11, 25, 44, 67, 91, 114, 123, // ramp-up amplitude (from 0 to 50%)
            103, 79, 55, 34, 17, 7, 2 // ramp-up amplitude
    };
    private static final VibrationEffect CHARGING_VIBRATION_EFFECT =
            VibrationEffect.createWaveform(CHARGING_VIBRATION_TIME, CHARGING_VIBRATION_AMPLITUDE,
                    -1);
    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private final Object mLock = new Object();

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final AppOpsManager mAppOps;
    private final SuspendBlocker mSuspendBlocker;
    private final WindowManagerPolicy mPolicy;
    private final FaceDownDetector mFaceDownDetector;
    private final ScreenUndimDetector mScreenUndimDetector;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final InputManagerInternal mInputManagerInternal;
    private final InputMethodManagerInternal mInputMethodManagerInternal;
    @Nullable private final StatusBarManagerInternal mStatusBarManagerInternal;
    private final TrustManager mTrustManager;
    private final Vibrator mVibrator;
    private final WakeLockLog mWakeLockLog;
    private final DisplayManagerInternal mDisplayManagerInternal;

    private final NotifierHandler mHandler;
    private final Executor mBackgroundExecutor;
    private final Intent mScreenOnIntent;
    private final Intent mScreenOffIntent;
    private final Bundle mScreenOnOffOptions;

    // True if the device should suspend when the screen is off due to proximity.
    private final boolean mSuspendWhenScreenOffDueToProximityConfig;

    // True if the device should show the wireless charging animation when the device
    // begins charging wirelessly
    private final boolean mShowWirelessChargingAnimationConfig;

    // Encapsulates interactivity information about a particular display group.
    private static class Interactivity {
        public boolean isInteractive = true;
        public int changeReason;
        public long changeStartTime; // In SystemClock.uptimeMillis()
        public boolean isChanging;
    }

    private final SparseArray<Interactivity> mInteractivityByGroupId = new SparseArray<>();

    // The current global interactive state.  This is set as soon as an interactive state
    // transition begins so as to capture the reason that it happened.  At some point
    // this state will propagate to the pending state then eventually to the
    // broadcasted state over the course of reporting the transition asynchronously.
    private Interactivity mGlobalInteractivity = new Interactivity();

    // The pending interactive state that we will eventually want to broadcast.
    // This is designed so that we can collapse redundant sequences of awake/sleep
    // transition pairs while still guaranteeing that at least one transition is observed
    // whenever this happens.
    private int mPendingInteractiveState;
    private boolean mPendingWakeUpBroadcast;
    private boolean mPendingGoToSleepBroadcast;

    // The currently broadcasted interactive state.  This reflects what other parts of the
    // system have observed.
    private int mBroadcastedInteractiveState;
    private boolean mBroadcastInProgress;
    private long mBroadcastStartTime;

    // True if a user activity message should be sent.
    private boolean mUserActivityPending;

    private final AtomicBoolean mIsPlayingChargingStartedFeedback = new AtomicBoolean(false);

    public Notifier(Looper looper, Context context, IBatteryStats batteryStats,
            SuspendBlocker suspendBlocker, WindowManagerPolicy policy,
            FaceDownDetector faceDownDetector, ScreenUndimDetector screenUndimDetector,
            Executor backgroundExecutor) {
        mContext = context;
        mBatteryStats = batteryStats;
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mSuspendBlocker = suspendBlocker;
        mPolicy = policy;
        mFaceDownDetector = faceDownDetector;
        mScreenUndimDetector = screenUndimDetector;
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mInputMethodManagerInternal = LocalServices.getService(InputMethodManagerInternal.class);
        mStatusBarManagerInternal = LocalServices.getService(StatusBarManagerInternal.class);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mTrustManager = mContext.getSystemService(TrustManager.class);
        mVibrator = mContext.getSystemService(Vibrator.class);

        mHandler = new NotifierHandler(looper);
        mBackgroundExecutor = backgroundExecutor;
        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND
                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND
                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mScreenOnOffOptions = createScreenOnOffBroadcastOptions();

        mSuspendWhenScreenOffDueToProximityConfig = context.getResources().getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity);
        mShowWirelessChargingAnimationConfig = context.getResources().getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim);

        mWakeLockLog = new WakeLockLog(context);

        // Initialize interactive state for battery stats.
        try {
            mBatteryStats.noteInteractive(true);
        } catch (RemoteException ex) { }
        FrameworkStatsLog.write(FrameworkStatsLog.INTERACTIVE_STATE_CHANGED,
                FrameworkStatsLog.INTERACTIVE_STATE_CHANGED__STATE__ON);
    }

    /**
     * Create the {@link BroadcastOptions} bundle that will be used with sending the
     * {@link Intent#ACTION_SCREEN_ON} and {@link Intent#ACTION_SCREEN_OFF} broadcasts.
     */
    private Bundle createScreenOnOffBroadcastOptions() {
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        // This allows the broadcasting system to discard any older broadcasts
        // waiting to be delivered to a process.
        options.setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
        // Set namespace and key to identify which older broadcasts can be discarded.
        // We could use any strings here but namespace needs to be unlikely to be reused with in
        // the system_server process, as that could result in potentially discarding some
        // non-screen on/off related broadcast.
        options.setDeliveryGroupMatchingKey(
                UUID.randomUUID().toString(),
                Intent.ACTION_SCREEN_ON);
        // This allows the broadcast delivery to be delayed to apps in the Cached state.
        options.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
        return options.toBundle();
    }

    /**
     * Called when a wake lock is acquired.
     */
    public void onWakeLockAcquired(int flags, String tag, String packageName,
            int ownerUid, int ownerPid, WorkSource workSource, String historyTag,
            IWakeLockCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeLockAcquired: flags=" + flags + ", tag=\"" + tag
                    + "\", packageName=" + packageName
                    + ", ownerUid=" + ownerUid + ", ownerPid=" + ownerPid
                    + ", workSource=" + workSource);
        }
        notifyWakeLockListener(callback, tag, true);
        final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
        if (monitorType >= 0) {
            try {
                final boolean unimportantForLogging = ownerUid == Process.SYSTEM_UID
                        && (flags & PowerManager.UNIMPORTANT_FOR_LOGGING) != 0;
                if (workSource != null) {
                    mBatteryStats.noteStartWakelockFromSource(workSource, ownerPid, tag,
                            historyTag, monitorType, unimportantForLogging);
                } else {
                    mBatteryStats.noteStartWakelock(ownerUid, ownerPid, tag, historyTag,
                            monitorType, unimportantForLogging);
                    // XXX need to deal with disabled operations.
                    mAppOps.startOpNoThrow(AppOpsManager.OP_WAKE_LOCK, ownerUid, packageName);
                }
            } catch (RemoteException ex) {
                // Ignore
            }
        }

        mWakeLockLog.onWakeLockAcquired(tag, ownerUid, flags);
    }

    public void onLongPartialWakeLockStart(String tag, int ownerUid, WorkSource workSource,
            String historyTag) {
        if (DEBUG) {
            Slog.d(TAG, "onLongPartialWakeLockStart: ownerUid=" + ownerUid
                    + ", workSource=" + workSource);
        }

        try {
            if (workSource != null) {
                mBatteryStats.noteLongPartialWakelockStartFromSource(tag, historyTag, workSource);
                FrameworkStatsLog.write(FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED,
                        workSource, tag, historyTag,
                        FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED__STATE__ON);
            } else {
                mBatteryStats.noteLongPartialWakelockStart(tag, historyTag, ownerUid);
                FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED, ownerUid, null, tag,
                        historyTag,
                        FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED__STATE__ON);
            }
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    public void onLongPartialWakeLockFinish(String tag, int ownerUid, WorkSource workSource,
            String historyTag) {
        if (DEBUG) {
            Slog.d(TAG, "onLongPartialWakeLockFinish: ownerUid=" + ownerUid
                    + ", workSource=" + workSource);
        }

        try {
            if (workSource != null) {
                mBatteryStats.noteLongPartialWakelockFinishFromSource(tag, historyTag, workSource);
                FrameworkStatsLog.write(FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED,
                        workSource, tag, historyTag,
                        FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED__STATE__OFF);
            } else {
                mBatteryStats.noteLongPartialWakelockFinish(tag, historyTag, ownerUid);
                FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED, ownerUid, null, tag,
                        historyTag,
                        FrameworkStatsLog.LONG_PARTIAL_WAKELOCK_STATE_CHANGED__STATE__OFF);
            }
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    /**
     * Called when a wake lock is changing.
     */
    public void onWakeLockChanging(int flags, String tag, String packageName,
            int ownerUid, int ownerPid, WorkSource workSource, String historyTag,
            IWakeLockCallback callback, int newFlags, String newTag, String newPackageName,
            int newOwnerUid, int newOwnerPid, WorkSource newWorkSource, String newHistoryTag,
            IWakeLockCallback newCallback) {

        final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
        final int newMonitorType = getBatteryStatsWakeLockMonitorType(newFlags);
        if (workSource != null && newWorkSource != null
                && monitorType >= 0 && newMonitorType >= 0) {
            if (DEBUG) {
                Slog.d(TAG, "onWakeLockChanging: flags=" + newFlags + ", tag=\"" + newTag
                        + "\", packageName=" + newPackageName
                        + ", ownerUid=" + newOwnerUid + ", ownerPid=" + newOwnerPid
                        + ", workSource=" + newWorkSource);
            }

            final boolean unimportantForLogging = newOwnerUid == Process.SYSTEM_UID
                    && (newFlags & PowerManager.UNIMPORTANT_FOR_LOGGING) != 0;
            try {
                mBatteryStats.noteChangeWakelockFromSource(workSource, ownerPid, tag, historyTag,
                        monitorType, newWorkSource, newOwnerPid, newTag, newHistoryTag,
                        newMonitorType, unimportantForLogging);
            } catch (RemoteException ex) {
                // Ignore
            }
        } else if (!PowerManagerService.isSameCallback(callback, newCallback)) {
            onWakeLockReleased(flags, tag, packageName, ownerUid, ownerPid, workSource, historyTag,
                    null /* Do not notify the old callback */);
            onWakeLockAcquired(newFlags, newTag, newPackageName, newOwnerUid, newOwnerPid,
                    newWorkSource, newHistoryTag, newCallback /* notify the new callback */);
        } else {
            onWakeLockReleased(flags, tag, packageName, ownerUid, ownerPid, workSource, historyTag,
                    callback);
            onWakeLockAcquired(newFlags, newTag, newPackageName, newOwnerUid, newOwnerPid,
                    newWorkSource, newHistoryTag, newCallback);
        }
    }

    /**
     * Called when a wake lock is released.
     */
    public void onWakeLockReleased(int flags, String tag, String packageName,
            int ownerUid, int ownerPid, WorkSource workSource, String historyTag,
            IWakeLockCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeLockReleased: flags=" + flags + ", tag=\"" + tag
                    + "\", packageName=" + packageName
                    + ", ownerUid=" + ownerUid + ", ownerPid=" + ownerPid
                    + ", workSource=" + workSource);
        }
        notifyWakeLockListener(callback, tag, false);
        final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
        if (monitorType >= 0) {
            try {
                if (workSource != null) {
                    mBatteryStats.noteStopWakelockFromSource(workSource, ownerPid, tag,
                            historyTag, monitorType);
                } else {
                    mBatteryStats.noteStopWakelock(ownerUid, ownerPid, tag,
                            historyTag, monitorType);
                    mAppOps.finishOp(AppOpsManager.OP_WAKE_LOCK, ownerUid, packageName);
                }
            } catch (RemoteException ex) {
                // Ignore
            }
        }
        mWakeLockLog.onWakeLockReleased(tag, ownerUid);
    }

    private int getBatteryStatsWakeLockMonitorType(int flags) {
        switch (flags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.PARTIAL_WAKE_LOCK:
                return BatteryStats.WAKE_TYPE_PARTIAL;

            case PowerManager.SCREEN_DIM_WAKE_LOCK:
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                return BatteryStats.WAKE_TYPE_FULL;

            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                if (mSuspendWhenScreenOffDueToProximityConfig) {
                    return -1;
                }
                return BatteryStats.WAKE_TYPE_PARTIAL;

            case PowerManager.DRAW_WAKE_LOCK:
                return BatteryStats.WAKE_TYPE_DRAW;

            case PowerManager.DOZE_WAKE_LOCK:
                // Doze wake locks are an internal implementation detail of the
                // communication between dream manager service and power manager
                // service.  They have no additive battery impact.
                return -1;

            default:
                return -1;
        }
    }

    /**
     * Notifies that the device is changing wakefulness.
     * This function may be called even if the previous change hasn't finished in
     * which case it will assume that the state did not fully converge before the
     * next transition began and will recover accordingly.
     */
    public void onGlobalWakefulnessChangeStarted(final int wakefulness, int reason,
            long eventTime) {
        final boolean interactive = PowerManagerInternal.isInteractive(wakefulness);
        if (DEBUG) {
            Slog.d(TAG, "onWakefulnessChangeStarted: wakefulness=" + wakefulness
                    + ", reason=" + reason + ", interactive=" + interactive);
        }

        // Tell the activity manager about changes in wakefulness, not just interactivity.
        // It needs more granularity than other components.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mActivityManagerInternal.onWakefulnessChanged(wakefulness);
            }
        });

        // Handle any early interactive state changes.
        // Finish pending incomplete ones from a previous cycle.
        if (mGlobalInteractivity.isInteractive != interactive) {
            // Finish up late behaviors if needed.
            if (mGlobalInteractivity.isChanging) {
                handleLateGlobalInteractiveChange();
            }

            // Start input as soon as we start waking up or going to sleep.
            mInputManagerInternal.setInteractive(interactive);
            mInputMethodManagerInternal.setInteractive(interactive);

            // Notify battery stats.
            try {
                mBatteryStats.noteInteractive(interactive);
            } catch (RemoteException ex) { }
            FrameworkStatsLog.write(FrameworkStatsLog.INTERACTIVE_STATE_CHANGED,
                    interactive ? FrameworkStatsLog.INTERACTIVE_STATE_CHANGED__STATE__ON :
                            FrameworkStatsLog.INTERACTIVE_STATE_CHANGED__STATE__OFF);

            // Handle early behaviors.
            mGlobalInteractivity.isInteractive = interactive;
            mGlobalInteractivity.isChanging = true;
            mGlobalInteractivity.changeReason = reason;
            mGlobalInteractivity.changeStartTime = eventTime;
            handleEarlyGlobalInteractiveChange();
        }
    }

    /**
     * Notifies that the device has finished changing wakefulness.
     */
    public void onWakefulnessChangeFinished() {
        if (DEBUG) {
            Slog.d(TAG, "onWakefulnessChangeFinished");
        }
        for (int i = 0; i < mInteractivityByGroupId.size(); i++) {
            int groupId = mInteractivityByGroupId.keyAt(i);
            Interactivity interactivity = mInteractivityByGroupId.valueAt(i);
            if (interactivity.isChanging) {
                interactivity.isChanging = false;
                handleLateInteractiveChange(groupId);
            }
        }
        if (mGlobalInteractivity.isChanging) {
            mGlobalInteractivity.isChanging = false;
            handleLateGlobalInteractiveChange();
        }
    }


    private void handleEarlyInteractiveChange(int groupId) {
        synchronized (mLock) {
            Interactivity interactivity = mInteractivityByGroupId.get(groupId);
            if (interactivity == null) {
                Slog.e(TAG, "no Interactivity entry for groupId:" + groupId);
                return;
            }
            final int changeReason = interactivity.changeReason;
            if (interactivity.isInteractive) {
                mHandler.post(() -> mPolicy.startedWakingUp(groupId, changeReason));
            } else {
                mHandler.post(() -> mPolicy.startedGoingToSleep(groupId, changeReason));
            }
        }
    }

    /**
     * Handle early interactive state changes such as getting applications or the lock
     * screen running and ready for the user to see (such as when turning on the screen).
     */
    private void handleEarlyGlobalInteractiveChange() {
        synchronized (mLock) {
            if (mGlobalInteractivity.isInteractive) {
                // Waking up...
                mHandler.post(() -> {
                    mDisplayManagerInternal.onEarlyInteractivityChange(true /*isInteractive*/);
                    mPolicy.startedWakingUpGlobal(mGlobalInteractivity.changeReason);
                });

                // Send interactive broadcast.
                mPendingInteractiveState = INTERACTIVE_STATE_AWAKE;
                mPendingWakeUpBroadcast = true;
                updatePendingBroadcastLocked();
            } else {
                // Going to sleep...
                mHandler.post(() -> {
                    mDisplayManagerInternal.onEarlyInteractivityChange(false /*isInteractive*/);
                    mPolicy.startedGoingToSleepGlobal(mGlobalInteractivity.changeReason);
                });
            }
        }
    }

    /**
     * Handle late global interactive state changes. Also see
     * {@link #handleLateInteractiveChange(int)}.
     */
    private void handleLateGlobalInteractiveChange() {
        synchronized (mLock) {
            final int interactiveChangeLatency =
                    (int) (SystemClock.uptimeMillis() - mGlobalInteractivity.changeStartTime);
            if (mGlobalInteractivity.isInteractive) {
                // Finished waking up...
                mHandler.post(() -> {
                    LogMaker log = new LogMaker(MetricsEvent.SCREEN);
                    log.setType(MetricsEvent.TYPE_OPEN);
                    log.setSubtype(WindowManagerPolicyConstants.translateWakeReasonToOnReason(
                            mGlobalInteractivity.changeReason));
                    log.setLatency(interactiveChangeLatency);
                    log.addTaggedData(MetricsEvent.FIELD_SCREEN_WAKE_REASON,
                            mGlobalInteractivity.changeReason);
                    MetricsLogger.action(log);
                    EventLogTags.writePowerScreenState(1, 0, 0, 0, interactiveChangeLatency);

                    mPolicy.finishedWakingUpGlobal(mGlobalInteractivity.changeReason);
                });
            } else {
                // Finished going to sleep...
                // This is a good time to make transitions that we don't want the user to see,
                // such as bringing the key guard to focus.  There's no guarantee for this
                // however because the user could turn the device on again at any time.
                // Some things may need to be protected by other mechanisms that defer screen on.

                // Cancel pending user activity.
                if (mUserActivityPending) {
                    mUserActivityPending = false;
                    mHandler.removeMessages(MSG_USER_ACTIVITY);
                }

                // Tell the policy we finished going to sleep.
                final int offReason = WindowManagerPolicyConstants.translateSleepReasonToOffReason(
                        mGlobalInteractivity.changeReason);
                mHandler.post(() -> {
                    LogMaker log = new LogMaker(MetricsEvent.SCREEN);
                    log.setType(MetricsEvent.TYPE_CLOSE);
                    log.setSubtype(offReason);
                    log.setLatency(interactiveChangeLatency);
                    log.addTaggedData(MetricsEvent.FIELD_SCREEN_SLEEP_REASON,
                            mGlobalInteractivity.changeReason);
                    MetricsLogger.action(log);
                    EventLogTags.writePowerScreenState(
                            0, offReason, 0, 0, interactiveChangeLatency);

                    mPolicy.finishedGoingToSleepGlobal(mGlobalInteractivity.changeReason);
                });

                // Send non-interactive broadcast.
                mPendingInteractiveState = INTERACTIVE_STATE_ASLEEP;
                mPendingGoToSleepBroadcast = true;
                updatePendingBroadcastLocked();
            }
        }
    }

    /**
     * Handle late interactive state changes once they are finished so that the system can
     * finish pending transitions (such as turning the screen off) before causing
     * applications to change state visibly.
     */
    private void handleLateInteractiveChange(int groupId) {
        synchronized (mLock) {
            Interactivity interactivity = mInteractivityByGroupId.get(groupId);
            if (interactivity == null) {
                Slog.e(TAG, "no Interactivity entry for groupId:" + groupId);
                return;
            }
            final int changeReason = interactivity.changeReason;
            if (interactivity.isInteractive) {
                mHandler.post(() -> mPolicy.finishedWakingUp(groupId, changeReason));
            } else {
                mHandler.post(() -> mPolicy.finishedGoingToSleep(groupId, changeReason));
            }
        }
    }

    /**
     * Called when an individual PowerGroup changes wakefulness.
     */
    public void onGroupWakefulnessChangeStarted(int groupId, int wakefulness, int changeReason,
            long eventTime) {
        final boolean isInteractive = PowerManagerInternal.isInteractive(wakefulness);

        boolean isNewGroup = false;
        Interactivity interactivity = mInteractivityByGroupId.get(groupId);
        if (interactivity == null) {
            isNewGroup = true;
            interactivity = new Interactivity();
            mInteractivityByGroupId.put(groupId, interactivity);
        }
        if (isNewGroup || interactivity.isInteractive != isInteractive) {
            // Finish up late behaviors if needed.
            if (interactivity.isChanging) {
                handleLateInteractiveChange(groupId);
            }

            // Handle early behaviors.
            interactivity.isInteractive = isInteractive;
            interactivity.changeReason = changeReason;
            interactivity.changeStartTime = eventTime;
            interactivity.isChanging = true;
            handleEarlyInteractiveChange(groupId);
        }
    }

    /**
     * Called when a PowerGroup has been removed.
     *
     * @param groupId which group was removed
     */
    public void onGroupRemoved(int groupId) {
        mInteractivityByGroupId.remove(groupId);
    }

    /**
     * Called when there has been user activity.
     */
    public void onUserActivity(int displayGroupId, @PowerManager.UserActivityEvent int event,
            int uid) {
        if (DEBUG) {
            Slog.d(TAG, "onUserActivity: event=" + event + ", uid=" + uid);
        }

        try {
            mBatteryStats.noteUserActivity(uid, event);
        } catch (RemoteException ex) {
            // Ignore
        }

        synchronized (mLock) {
            if (!mUserActivityPending) {
                mUserActivityPending = true;
                Message msg = mHandler.obtainMessage(MSG_USER_ACTIVITY);
                msg.arg1 = displayGroupId;
                msg.arg2 = event;
                msg.setAsynchronous(true);
                mHandler.sendMessage(msg);
            }
        }
    }

    /**
     * Called when the screen has turned on.
     */
    public void onWakeUp(int reason, String details, int reasonUid, String opPackageName,
            int opUid) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeUp: reason=" + PowerManager.wakeReasonToString(reason)
                    + ", details=" + details + ", reasonUid=" + reasonUid
                    + " opPackageName=" + opPackageName + " opUid=" + opUid);
        }

        try {
            mBatteryStats.noteWakeUp(details, reasonUid);
            if (opPackageName != null) {
                mAppOps.noteOpNoThrow(AppOpsManager.OP_TURN_SCREEN_ON, opUid, opPackageName);
            }
        } catch (RemoteException ex) {
            // Ignore
        }
        FrameworkStatsLog.write(FrameworkStatsLog.DISPLAY_WAKE_REPORTED, reason, reasonUid);
    }

    /**
     * Called when profile screen lock timeout has expired.
     */
    public void onProfileTimeout(@UserIdInt int userId) {
        final Message msg = mHandler.obtainMessage(MSG_PROFILE_TIMED_OUT);
        msg.setAsynchronous(true);
        msg.arg1 = userId;
        mHandler.sendMessage(msg);
    }

    /**
     * Called when wireless charging has started - to provide user feedback (sound and visual).
     */
    public void onWirelessChargingStarted(int batteryLevel, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onWirelessChargingStarted");
        }

        mSuspendBlocker.acquire();
        Message msg = mHandler.obtainMessage(MSG_WIRELESS_CHARGING_STARTED);
        msg.setAsynchronous(true);
        msg.arg1 = batteryLevel;
        msg.arg2 = userId;
        mHandler.sendMessage(msg);
    }

    /**
     * Called when wired charging has started - to provide user feedback
     */
    public void onWiredChargingStarted(@UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onWiredChargingStarted");
        }

        mSuspendBlocker.acquire();
        Message msg = mHandler.obtainMessage(MSG_WIRED_CHARGING_STARTED);
        msg.setAsynchronous(true);
        msg.arg1 = userId;
        mHandler.sendMessage(msg);
    }

    /**
     * Called when the screen policy changes.
     */
    public void onScreenPolicyUpdate(int displayGroupId, int newPolicy) {
        if (DEBUG) {
            Slog.d(TAG, "onScreenPolicyUpdate: newPolicy=" + newPolicy);
        }

        synchronized (mLock) {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_POLICY);
            msg.arg1 = displayGroupId;
            msg.arg2 = newPolicy;
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Dumps data for bugreports.
     *
     * @param pw The stream to print to.
     */
    public void dump(PrintWriter pw) {
        if (mWakeLockLog != null) {
            mWakeLockLog.dump(pw);
        }
    }

    private void updatePendingBroadcastLocked() {
        if (!mBroadcastInProgress
                && mPendingInteractiveState != INTERACTIVE_STATE_UNKNOWN
                && (mPendingWakeUpBroadcast || mPendingGoToSleepBroadcast
                        || mPendingInteractiveState != mBroadcastedInteractiveState)) {
            mBroadcastInProgress = true;
            mSuspendBlocker.acquire();
            Message msg = mHandler.obtainMessage(MSG_BROADCAST);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    private void finishPendingBroadcastLocked() {
        mBroadcastInProgress = false;
        mSuspendBlocker.release();
    }

    private void sendUserActivity(int displayGroupId, int event) {
        synchronized (mLock) {
            if (!mUserActivityPending) {
                return;
            }
            mUserActivityPending = false;
        }
        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        tm.notifyUserActivity();
        mInputManagerInternal.notifyUserActivity();
        mPolicy.userActivity(displayGroupId, event);
        mFaceDownDetector.userActivity(event);
        mScreenUndimDetector.userActivity(displayGroupId);
    }

    void postEnhancedDischargePredictionBroadcast(long delayMs) {
        mHandler.sendEmptyMessageDelayed(MSG_BROADCAST_ENHANCED_PREDICTION, delayMs);
    }

    private void sendEnhancedDischargePredictionBroadcast() {
        Intent intent = new Intent(PowerManager.ACTION_ENHANCED_DISCHARGE_PREDICTION_CHANGED)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNextBroadcast() {
        final int powerState;
        synchronized (mLock) {
            if (mBroadcastedInteractiveState == INTERACTIVE_STATE_UNKNOWN) {
                // Broadcasted power state is unknown.
                // Send wake up or go to sleep.
                switch (mPendingInteractiveState) {
                    case INTERACTIVE_STATE_ASLEEP:
                        mPendingGoToSleepBroadcast = false;
                        mBroadcastedInteractiveState = INTERACTIVE_STATE_ASLEEP;
                        break;

                    case INTERACTIVE_STATE_AWAKE:
                    default:
                        mPendingWakeUpBroadcast = false;
                        mBroadcastedInteractiveState = INTERACTIVE_STATE_AWAKE;
                        break;
                }
            } else if (mBroadcastedInteractiveState == INTERACTIVE_STATE_AWAKE) {
                // Broadcasted power state is awake.  Send asleep if needed.
                if (mPendingWakeUpBroadcast || mPendingGoToSleepBroadcast
                        || mPendingInteractiveState == INTERACTIVE_STATE_ASLEEP) {
                    mPendingGoToSleepBroadcast = false;
                    mBroadcastedInteractiveState = INTERACTIVE_STATE_ASLEEP;
                } else {
                    finishPendingBroadcastLocked();
                    return;
                }
            } else {
                // Broadcasted power state is asleep.  Send awake if needed.
                if (mPendingWakeUpBroadcast || mPendingGoToSleepBroadcast
                        || mPendingInteractiveState == INTERACTIVE_STATE_AWAKE) {
                    mPendingWakeUpBroadcast = false;
                    mBroadcastedInteractiveState = INTERACTIVE_STATE_AWAKE;
                } else {
                    finishPendingBroadcastLocked();
                    return;
                }
            }

            mBroadcastStartTime = SystemClock.uptimeMillis();
            powerState = mBroadcastedInteractiveState;
        }

        EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_SEND, 1);

        if (powerState == INTERACTIVE_STATE_AWAKE) {
            sendWakeUpBroadcast();
        } else {
            sendGoToSleepBroadcast();
        }
    }

    private void sendWakeUpBroadcast() {
        if (DEBUG) {
            Slog.d(TAG, "Sending wake up broadcast.");
        }

        if (mActivityManagerInternal.isSystemReady()) {
            final boolean ordered = !mActivityManagerInternal.isModernQueueEnabled();
            mActivityManagerInternal.broadcastIntent(mScreenOnIntent, mWakeUpBroadcastDone,
                    null, ordered, UserHandle.USER_ALL, null, null, mScreenOnOffOptions);
        } else {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2, 1);
            sendNextBroadcast();
        }
    }

    private final IIntentReceiver mWakeUpBroadcastDone = new IIntentReceiver.Stub() {
        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 1,
                    SystemClock.uptimeMillis() - mBroadcastStartTime, 1);
            sendNextBroadcast();
        }
    };

    private void sendGoToSleepBroadcast() {
        if (DEBUG) {
            Slog.d(TAG, "Sending go to sleep broadcast.");
        }

        if (mActivityManagerInternal.isSystemReady()) {
            final boolean ordered = !mActivityManagerInternal.isModernQueueEnabled();
            mActivityManagerInternal.broadcastIntent(mScreenOffIntent, mGoToSleepBroadcastDone,
                    null, ordered, UserHandle.USER_ALL, null, null, mScreenOnOffOptions);
        } else {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 3, 1);
            sendNextBroadcast();
        }
    }

    private final IIntentReceiver mGoToSleepBroadcastDone = new IIntentReceiver.Stub() {
        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 0,
                    SystemClock.uptimeMillis() - mBroadcastStartTime, 1);
            sendNextBroadcast();
        }
    };

    private void playChargingStartedFeedback(@UserIdInt int userId, boolean wireless) {
        if (!isChargingFeedbackEnabled(userId)) {
            return;
        }

        if (!mIsPlayingChargingStartedFeedback.compareAndSet(false, true)) {
            // there's already a charging started feedback Runnable scheduled to run on the
            // background thread, so let's not execute another
            return;
        }

        // vibrate & play sound on a background thread
        mBackgroundExecutor.execute(() -> {
            // vibrate
            final boolean vibrate = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.CHARGING_VIBRATION_ENABLED, 1, userId) != 0;
            if (vibrate) {
                mVibrator.vibrate(Process.SYSTEM_UID, mContext.getOpPackageName(),
                        CHARGING_VIBRATION_EFFECT, /* reason= */ "Charging started",
                        HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES);
            }

            // play sound
            final String soundPath = Settings.Global.getString(mContext.getContentResolver(),
                    wireless ? Settings.Global.WIRELESS_CHARGING_STARTED_SOUND
                            : Settings.Global.CHARGING_STARTED_SOUND);
            final Uri soundUri = Uri.parse("file://" + soundPath);
            if (soundUri != null) {
                final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                if (sfx != null) {
                    sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                    sfx.play();
                }
            }
            mIsPlayingChargingStartedFeedback.set(false);
        });
    }

    private void showWirelessChargingStarted(int batteryLevel, @UserIdInt int userId) {
        // play sounds + haptics
        playChargingStartedFeedback(userId, true /* wireless */);

        // show animation
        if (mShowWirelessChargingAnimationConfig && mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.showChargingAnimation(batteryLevel);
        }
        mSuspendBlocker.release();
    }

    private void showWiredChargingStarted(@UserIdInt int userId) {
        playChargingStartedFeedback(userId, false /* wireless */);
        mSuspendBlocker.release();
    }

    private void screenPolicyChanging(int displayGroupId, int screenPolicy) {
        mScreenUndimDetector.recordScreenPolicy(displayGroupId, screenPolicy);
    }

    private void lockProfile(@UserIdInt int userId) {
        mTrustManager.setDeviceLockedForUser(userId, true /*locked*/);
    }

    private boolean isChargingFeedbackEnabled(@UserIdInt int userId) {
        final boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.CHARGING_SOUNDS_ENABLED, 1, userId) != 0;
        final boolean dndOff = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                == Settings.Global.ZEN_MODE_OFF;
        return enabled && dndOff;
    }

    private void notifyWakeLockListener(IWakeLockCallback callback, String tag, boolean isEnabled) {
        if (callback != null) {
            mHandler.post(() -> {
                try {
                    callback.onStateChanged(isEnabled);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Wakelock.mCallback [" + tag + "] is already dead.", e);
                }
            });
        }
    }

    private final class NotifierHandler extends Handler {

        public NotifierHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY:
                    sendUserActivity(msg.arg1, msg.arg2);
                    break;
                case MSG_BROADCAST:
                    sendNextBroadcast();
                    break;
                case MSG_WIRELESS_CHARGING_STARTED:
                    showWirelessChargingStarted(msg.arg1, msg.arg2);
                    break;
                case MSG_BROADCAST_ENHANCED_PREDICTION:
                    removeMessages(MSG_BROADCAST_ENHANCED_PREDICTION);
                    sendEnhancedDischargePredictionBroadcast();
                    break;
                case MSG_PROFILE_TIMED_OUT:
                    lockProfile(msg.arg1);
                    break;
                case MSG_WIRED_CHARGING_STARTED:
                    showWiredChargingStarted(msg.arg1);
                    break;
                case MSG_SCREEN_POLICY:
                    screenPolicyChanging(msg.arg1, msg.arg2);
                    break;
            }
        }
    }
}
