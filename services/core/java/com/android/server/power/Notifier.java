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

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;

import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManagerInternal;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.view.WindowManagerPolicy;

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

    private static final int POWER_STATE_UNKNOWN = 0;
    private static final int POWER_STATE_AWAKE = 1;
    private static final int POWER_STATE_ASLEEP = 2;

    private static final int MSG_USER_ACTIVITY = 1;
    private static final int MSG_BROADCAST = 2;
    private static final int MSG_WIRELESS_CHARGING_STARTED = 3;

    private final Object mLock = new Object();

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final IAppOpsService mAppOps;
    private final SuspendBlocker mSuspendBlocker;
    private final WindowManagerPolicy mPolicy;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final InputManagerInternal mInputManagerInternal;

    private final NotifierHandler mHandler;
    private final Intent mScreenOnIntent;
    private final Intent mScreenOffIntent;

    // The current power state.
    private int mActualPowerState;
    private int mLastGoToSleepReason;

    // True if there is a pending transition that needs to be reported.
    private boolean mPendingWakeUpBroadcast;
    private boolean mPendingGoToSleepBroadcast;

    // The currently broadcasted power state.  This reflects what other parts of the
    // system have observed.
    private int mBroadcastedPowerState;
    private boolean mBroadcastInProgress;
    private long mBroadcastStartTime;

    // True if a user activity message should be sent.
    private boolean mUserActivityPending;

    public Notifier(Looper looper, Context context, IBatteryStats batteryStats,
            IAppOpsService appOps, SuspendBlocker suspendBlocker,
            WindowManagerPolicy policy) {
        mContext = context;
        mBatteryStats = batteryStats;
        mAppOps = appOps;
        mSuspendBlocker = suspendBlocker;
        mPolicy = policy;
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);

        mHandler = new NotifierHandler(looper);
        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);

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

        try {
            final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
            boolean unimportantForLogging = (flags&PowerManager.UNIMPORTANT_FOR_LOGGING) != 0
                    && ownerUid == Process.SYSTEM_UID;
            if (workSource != null) {
                mBatteryStats.noteStartWakelockFromSource(workSource, ownerPid, tag, historyTag,
                        monitorType, unimportantForLogging);
            } else {
                mBatteryStats.noteStartWakelock(ownerUid, ownerPid, tag, historyTag,
                        monitorType, unimportantForLogging);
                // XXX need to deal with disabled operations.
                mAppOps.startOperation(AppOpsManager.getToken(mAppOps),
                        AppOpsManager.OP_WAKE_LOCK, ownerUid, packageName);
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

        if (workSource != null && newWorkSource != null) {
            final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
            final int newMonitorType = getBatteryStatsWakeLockMonitorType(newFlags);
            boolean unimportantForLogging = (newFlags&PowerManager.UNIMPORTANT_FOR_LOGGING) != 0
                    && newOwnerUid == Process.SYSTEM_UID;
            if (DEBUG) {
                Slog.d(TAG, "onWakeLockChanging: flags=" + newFlags + ", tag=\"" + newTag
                        + "\", packageName=" + newPackageName
                        + ", ownerUid=" + newOwnerUid + ", ownerPid=" + newOwnerPid
                        + ", workSource=" + newWorkSource);
            }
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

        try {
            final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
            if (workSource != null) {
                mBatteryStats.noteStopWakelockFromSource(workSource, ownerPid, tag, historyTag,
                        monitorType);
            } else {
                mBatteryStats.noteStopWakelock(ownerUid, ownerPid, tag, historyTag, monitorType);
                mAppOps.finishOperation(AppOpsManager.getToken(mAppOps),
                        AppOpsManager.OP_WAKE_LOCK, ownerUid, packageName);
            }
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    private static int getBatteryStatsWakeLockMonitorType(int flags) {
        switch (flags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.PARTIAL_WAKE_LOCK:
            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                return BatteryStats.WAKE_TYPE_PARTIAL;
            default:
                return BatteryStats.WAKE_TYPE_FULL;
        }
    }

    /**
     * Notifies that the device is changing interactive state.
     */
    public void onInteractiveStateChangeStarted(boolean interactive, final int reason) {
        if (DEBUG) {
            Slog.d(TAG, "onInteractiveChangeStarted: interactive=" + interactive
                    + ", reason=" + reason);
        }

        synchronized (mLock) {
            if (interactive) {
                // Waking up...
                if (mActualPowerState != POWER_STATE_AWAKE) {
                    mActualPowerState = POWER_STATE_AWAKE;
                    mPendingWakeUpBroadcast = true;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 1, 0, 0, 0);
                            mPolicy.wakingUp();
                            mActivityManagerInternal.wakingUp();
                        }
                    });
                    updatePendingBroadcastLocked();
                }
            } else {
                // Going to sleep...
                mLastGoToSleepReason = reason;
            }
        }

        mInputManagerInternal.setInteractive(interactive);

        if (interactive) {
            try {
                mBatteryStats.noteInteractive(true);
            } catch (RemoteException ex) { }
        }
    }

    /**
     * Notifies that the device has finished changing interactive state.
     */
    public void onInteractiveStateChangeFinished(boolean interactive) {
        if (DEBUG) {
            Slog.d(TAG, "onInteractiveChangeFinished");
        }

        synchronized (mLock) {
            if (!interactive) {
                // Finished going to sleep...
                // This is a good time to make transitions that we don't want the user to see,
                // such as bringing the key guard to focus.  There's no guarantee for this,
                // however because the user could turn the device on again at any time.
                // Some things may need to be protected by other mechanisms that defer screen on.
                if (mActualPowerState != POWER_STATE_ASLEEP) {
                    mActualPowerState = POWER_STATE_ASLEEP;
                    mPendingGoToSleepBroadcast = true;
                    if (mUserActivityPending) {
                        mUserActivityPending = false;
                        mHandler.removeMessages(MSG_USER_ACTIVITY);
                    }
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            int why = WindowManagerPolicy.OFF_BECAUSE_OF_USER;
                            switch (mLastGoToSleepReason) {
                                case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                                    why = WindowManagerPolicy.OFF_BECAUSE_OF_ADMIN;
                                    break;
                                case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                                    why = WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT;
                                    break;
                            }
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 0, why, 0, 0);
                            mPolicy.goingToSleep(why);
                            mActivityManagerInternal.goingToSleep();
                        }
                    });
                    updatePendingBroadcastLocked();
                }
            }
        }

        if (!interactive) {
            try {
                mBatteryStats.noteInteractive(false);
            } catch (RemoteException ex) { }
        }
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
     * Called when wireless charging has started so as to provide user feedback.
     */
    public void onWirelessChargingStarted() {
        if (DEBUG) {
            Slog.d(TAG, "onWirelessChargingStarted");
        }

        mSuspendBlocker.acquire();
        Message msg = mHandler.obtainMessage(MSG_WIRELESS_CHARGING_STARTED);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    private void updatePendingBroadcastLocked() {
        if (!mBroadcastInProgress
                && mActualPowerState != POWER_STATE_UNKNOWN
                && (mPendingWakeUpBroadcast || mPendingGoToSleepBroadcast
                        || mActualPowerState != mBroadcastedPowerState)) {
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
            if (mBroadcastedPowerState == POWER_STATE_UNKNOWN) {
                // Broadcasted power state is unknown.  Send wake up.
                mPendingWakeUpBroadcast = false;
                mBroadcastedPowerState = POWER_STATE_AWAKE;
            } else if (mBroadcastedPowerState == POWER_STATE_AWAKE) {
                // Broadcasted power state is awake.  Send asleep if needed.
                if (mPendingWakeUpBroadcast || mPendingGoToSleepBroadcast
                        || mActualPowerState == POWER_STATE_ASLEEP) {
                    mPendingGoToSleepBroadcast = false;
                    mBroadcastedPowerState = POWER_STATE_ASLEEP;
                } else {
                    finishPendingBroadcastLocked();
                    return;
                }
            } else {
                // Broadcasted power state is asleep.  Send awake if needed.
                if (mPendingWakeUpBroadcast || mPendingGoToSleepBroadcast
                        || mActualPowerState == POWER_STATE_AWAKE) {
                    mPendingWakeUpBroadcast = false;
                    mBroadcastedPowerState = POWER_STATE_AWAKE;
                } else {
                    finishPendingBroadcastLocked();
                    return;
                }
            }

            mBroadcastStartTime = SystemClock.uptimeMillis();
            powerState = mBroadcastedPowerState;
        }

        EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_SEND, 1);

        if (powerState == POWER_STATE_AWAKE) {
            sendWakeUpBroadcast();
        } else {
            sendGoToSleepBroadcast();
        }
    }

    private void sendWakeUpBroadcast() {
        if (DEBUG) {
            Slog.d(TAG, "Sending wake up broadcast.");
        }

        if (ActivityManagerNative.isSystemReady()) {
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

        if (ActivityManagerNative.isSystemReady()) {
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

    private void playWirelessChargingStartedSound() {
        final String soundPath = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.WIRELESS_CHARGING_STARTED_SOUND);
        if (soundPath != null) {
            final Uri soundUri = Uri.parse("file://" + soundPath);
            if (soundUri != null) {
                final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                if (sfx != null) {
                    sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                    sfx.play();
                }
            }
        }

        mSuspendBlocker.release();
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
                    playWirelessChargingStartedSound();
                    break;
            }
        }
    }
}
