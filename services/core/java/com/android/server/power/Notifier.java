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
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManagerInternal;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.view.inputmethod.InputMethodManagerInternal;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.policy.WindowManagerPolicy;

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
final class Notifier {
    private static final String TAG = "PowerManagerNotifier";

    private static final boolean DEBUG = false;

    private static final int INTERACTIVE_STATE_UNKNOWN = 0;
    private static final int INTERACTIVE_STATE_AWAKE = 1;
    private static final int INTERACTIVE_STATE_ASLEEP = 2;

    private static final int MSG_USER_ACTIVITY = 1;
    private static final int MSG_BROADCAST = 2;
    private static final int MSG_WIRELESS_CHARGING_STARTED = 3;
    private static final int MSG_SCREEN_BRIGHTNESS_BOOST_CHANGED = 4;
    private static final int MSG_PROFILE_TIMED_OUT = 5;
    private static final int MSG_WIRED_CHARGING_STARTED = 6;

    private final Object mLock = new Object();

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final AppOpsManager mAppOps;
    private final SuspendBlocker mSuspendBlocker;
    private final WindowManagerPolicy mPolicy;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final InputManagerInternal mInputManagerInternal;
    private final InputMethodManagerInternal mInputMethodManagerInternal;
    @Nullable private final StatusBarManagerInternal mStatusBarManagerInternal;
    private final TrustManager mTrustManager;

    private final NotifierHandler mHandler;
    private final Intent mScreenOnIntent;
    private final Intent mScreenOffIntent;
    private final Intent mScreenBrightnessBoostIntent;

    // True if the device should suspend when the screen is off due to proximity.
    private final boolean mSuspendWhenScreenOffDueToProximityConfig;

    // The current interactive state.  This is set as soon as an interactive state
    // transition begins so as to capture the reason that it happened.  At some point
    // this state will propagate to the pending state then eventually to the
    // broadcasted state over the course of reporting the transition asynchronously.
    private boolean mInteractive = true;
    private int mInteractiveChangeReason;
    private boolean mInteractiveChanging;

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

    public Notifier(Looper looper, Context context, IBatteryStats batteryStats,
            SuspendBlocker suspendBlocker, WindowManagerPolicy policy) {
        mContext = context;
        mBatteryStats = batteryStats;
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mSuspendBlocker = suspendBlocker;
        mPolicy = policy;
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mInputMethodManagerInternal = LocalServices.getService(InputMethodManagerInternal.class);
        mStatusBarManagerInternal = LocalServices.getService(StatusBarManagerInternal.class);
        mTrustManager = mContext.getSystemService(TrustManager.class);

        mHandler = new NotifierHandler(looper);
        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND
                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND
                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mScreenBrightnessBoostIntent =
                new Intent(PowerManager.ACTION_SCREEN_BRIGHTNESS_BOOST_CHANGED);
        mScreenBrightnessBoostIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);

        mSuspendWhenScreenOffDueToProximityConfig = context.getResources().getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity);

        // Initialize interactive state for battery stats.
        try {
            mBatteryStats.noteInteractive(true);
        } catch (RemoteException ex) { }
    }

    /**
     * Called when a wake lock is acquired.
     */
    public void onWakeLockAcquired(int flags, String tag, String packageName,
            int ownerUid, int ownerPid, WorkSource workSource, String historyTag) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeLockAcquired: flags=" + flags + ", tag=\"" + tag
                    + "\", packageName=" + packageName
                    + ", ownerUid=" + ownerUid + ", ownerPid=" + ownerPid
                    + ", workSource=" + workSource);
        }

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
            } else {
                mBatteryStats.noteLongPartialWakelockStart(tag, historyTag, ownerUid);
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
            } else {
                mBatteryStats.noteLongPartialWakelockFinish(tag, historyTag, ownerUid);
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
            int newFlags, String newTag, String newPackageName, int newOwnerUid,
            int newOwnerPid, WorkSource newWorkSource, String newHistoryTag) {

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
        } else {
            onWakeLockReleased(flags, tag, packageName, ownerUid, ownerPid, workSource, historyTag);
            onWakeLockAcquired(newFlags, newTag, newPackageName, newOwnerUid, newOwnerPid,
                    newWorkSource, newHistoryTag);
        }
    }

    /**
     * Called when a wake lock is released.
     */
    public void onWakeLockReleased(int flags, String tag, String packageName,
            int ownerUid, int ownerPid, WorkSource workSource, String historyTag) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeLockReleased: flags=" + flags + ", tag=\"" + tag
                    + "\", packageName=" + packageName
                    + ", ownerUid=" + ownerUid + ", ownerPid=" + ownerPid
                    + ", workSource=" + workSource);
        }

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
    public void onWakefulnessChangeStarted(final int wakefulness, int reason) {
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
        if (mInteractive != interactive) {
            // Finish up late behaviors if needed.
            if (mInteractiveChanging) {
                handleLateInteractiveChange();
            }

            // Start input as soon as we start waking up or going to sleep.
            mInputManagerInternal.setInteractive(interactive);
            mInputMethodManagerInternal.setInteractive(interactive);

            // Notify battery stats.
            try {
                mBatteryStats.noteInteractive(interactive);
            } catch (RemoteException ex) { }

            // Handle early behaviors.
            mInteractive = interactive;
            mInteractiveChangeReason = reason;
            mInteractiveChanging = true;
            handleEarlyInteractiveChange();
        }
    }

    /**
     * Notifies that the device has finished changing wakefulness.
     */
    public void onWakefulnessChangeFinished() {
        if (DEBUG) {
            Slog.d(TAG, "onWakefulnessChangeFinished");
        }

        if (mInteractiveChanging) {
            mInteractiveChanging = false;
            handleLateInteractiveChange();
        }
    }

    /**
     * Handle early interactive state changes such as getting applications or the lock
     * screen running and ready for the user to see (such as when turning on the screen).
     */
    private void handleEarlyInteractiveChange() {
        synchronized (mLock) {
            if (mInteractive) {
                // Waking up...
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Note a SCREEN tron event is logged in PowerManagerService.
                        mPolicy.startedWakingUp();
                    }
                });

                // Send interactive broadcast.
                mPendingInteractiveState = INTERACTIVE_STATE_AWAKE;
                mPendingWakeUpBroadcast = true;
                updatePendingBroadcastLocked();
            } else {
                // Going to sleep...
                // Tell the policy that we started going to sleep.
                final int why = translateOffReason(mInteractiveChangeReason);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mPolicy.startedGoingToSleep(why);
                    }
                });
            }
        }
    }

    /**
     * Handle late interactive state changes once they are finished so that the system can
     * finish pending transitions (such as turning the screen off) before causing
     * applications to change state visibly.
     */
    private void handleLateInteractiveChange() {
        synchronized (mLock) {
            if (mInteractive) {
                // Finished waking up...
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mPolicy.finishedWakingUp();
                    }
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
                final int why = translateOffReason(mInteractiveChangeReason);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        LogMaker log = new LogMaker(MetricsEvent.SCREEN);
                        log.setType(MetricsEvent.TYPE_CLOSE);
                        log.setSubtype(why);
                        MetricsLogger.action(log);
                        EventLogTags.writePowerScreenState(0, why, 0, 0, 0);
                        mPolicy.finishedGoingToSleep(why);
                    }
                });

                // Send non-interactive broadcast.
                mPendingInteractiveState = INTERACTIVE_STATE_ASLEEP;
                mPendingGoToSleepBroadcast = true;
                updatePendingBroadcastLocked();
            }
        }
    }

    private static int translateOffReason(int reason) {
        switch (reason) {
            case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                return WindowManagerPolicy.OFF_BECAUSE_OF_ADMIN;
            case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                return WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT;
            default:
                return WindowManagerPolicy.OFF_BECAUSE_OF_USER;
        }
    }

    /**
     * Called when screen brightness boost begins or ends.
     */
    public void onScreenBrightnessBoostChanged() {
        if (DEBUG) {
            Slog.d(TAG, "onScreenBrightnessBoostChanged");
        }

        mSuspendBlocker.acquire();
        Message msg = mHandler.obtainMessage(MSG_SCREEN_BRIGHTNESS_BOOST_CHANGED);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    /**
     * Called when there has been user activity.
     */
    public void onUserActivity(int event, int uid) {
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
                msg.setAsynchronous(true);
                mHandler.sendMessage(msg);
            }
        }
    }

    /**
     * Called when the screen has turned on.
     */
    public void onWakeUp(String reason, int reasonUid, String opPackageName, int opUid) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeUp: event=" + reason + ", reasonUid=" + reasonUid
                    + " opPackageName=" + opPackageName + " opUid=" + opUid);
        }

        try {
            mBatteryStats.noteWakeUp(reason, reasonUid);
            if (opPackageName != null) {
                mAppOps.noteOpNoThrow(AppOpsManager.OP_TURN_SCREEN_ON, opUid, opPackageName);
            }
        } catch (RemoteException ex) {
            // Ignore
        }
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
     * Called when wireless charging has started so as to provide user feedback (sound and visual).
     */
    public void onWirelessChargingStarted(int batteryLevel) {
        if (DEBUG) {
            Slog.d(TAG, "onWirelessChargingStarted");
        }

        mSuspendBlocker.acquire();
        Message msg = mHandler.obtainMessage(MSG_WIRELESS_CHARGING_STARTED);
        msg.setAsynchronous(true);
        msg.arg1 = batteryLevel;
        mHandler.sendMessage(msg);
    }

    /**
     * Called when wired charging has started so as to provide user feedback
     */
    public void onWiredChargingStarted() {
        if (DEBUG) {
            Slog.d(TAG, "onWiredChargingStarted");
        }

        mSuspendBlocker.acquire();
        Message msg = mHandler.obtainMessage(MSG_WIRED_CHARGING_STARTED);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
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

    private void sendUserActivity() {
        synchronized (mLock) {
            if (!mUserActivityPending) {
                return;
            }
            mUserActivityPending = false;
        }
        mPolicy.userActivity();
    }

    private void sendNextBroadcast() {
        final int powerState;
        synchronized (mLock) {
            if (mBroadcastedInteractiveState == INTERACTIVE_STATE_UNKNOWN) {
                // Broadcasted power state is unknown.  Send wake up.
                mPendingWakeUpBroadcast = false;
                mBroadcastedInteractiveState = INTERACTIVE_STATE_AWAKE;
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

    private void sendBrightnessBoostChangedBroadcast() {
        if (DEBUG) {
            Slog.d(TAG, "Sending brightness boost changed broadcast.");
        }

        mContext.sendOrderedBroadcastAsUser(mScreenBrightnessBoostIntent, UserHandle.ALL, null,
                mScreeBrightnessBoostChangedDone, mHandler, 0, null, null);
    }

    private final BroadcastReceiver mScreeBrightnessBoostChangedDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mSuspendBlocker.release();
        }
    };

    private void sendWakeUpBroadcast() {
        if (DEBUG) {
            Slog.d(TAG, "Sending wake up broadcast.");
        }

        if (mActivityManagerInternal.isSystemReady()) {
            mContext.sendOrderedBroadcastAsUser(mScreenOnIntent, UserHandle.ALL, null,
                    mWakeUpBroadcastDone, mHandler, 0, null, null);
        } else {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2, 1);
            sendNextBroadcast();
        }
    }

    private final BroadcastReceiver mWakeUpBroadcastDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
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
            mContext.sendOrderedBroadcastAsUser(mScreenOffIntent, UserHandle.ALL, null,
                    mGoToSleepBroadcastDone, mHandler, 0, null, null);
        } else {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 3, 1);
            sendNextBroadcast();
        }
    }

    private final BroadcastReceiver mGoToSleepBroadcastDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 0,
                    SystemClock.uptimeMillis() - mBroadcastStartTime, 1);
            sendNextBroadcast();
        }
    };

    /**
     * Plays the wireless charging sound for both wireless and non-wireless charging
     */
    private void playChargingStartedSound() {
        final boolean enabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.CHARGING_SOUNDS_ENABLED, 1) != 0;
        final boolean dndOff = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                == Settings.Global.ZEN_MODE_OFF;
        final String soundPath = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.CHARGING_STARTED_SOUND);
        if (enabled && dndOff && soundPath != null) {
            final Uri soundUri = Uri.parse("file://" + soundPath);
            if (soundUri != null) {
                final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                if (sfx != null) {
                    sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                    sfx.play();
                }
            }
        }
    }

    private void showWirelessChargingStarted(int batteryLevel) {
        playChargingStartedSound();
        if (mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.showChargingAnimation(batteryLevel);
        }
        mSuspendBlocker.release();
    }

    private void showWiredChargingStarted() {
        playChargingStartedSound();
        mSuspendBlocker.release();
    }

    private void lockProfile(@UserIdInt int userId) {
        mTrustManager.setDeviceLockedForUser(userId, true /*locked*/);
    }

    private final class NotifierHandler extends Handler {

        public NotifierHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY:
                    sendUserActivity();
                    break;
                case MSG_BROADCAST:
                    sendNextBroadcast();
                    break;
                case MSG_WIRELESS_CHARGING_STARTED:
                    showWirelessChargingStarted(msg.arg1);
                    break;
                case MSG_SCREEN_BRIGHTNESS_BOOST_CHANGED:
                    sendBrightnessBoostChangedBroadcast();
                    break;
                case MSG_PROFILE_TIMED_OUT:
                    lockProfile(msg.arg1);
                    break;
                case MSG_WIRED_CHARGING_STARTED:
                    showWiredChargingStarted();
            }
        }
    }
}
