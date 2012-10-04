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

import com.android.internal.app.IBatteryStats;
import com.android.server.EventLogTags;

import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
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

    private final Object mLock = new Object();

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final SuspendBlocker mSuspendBlocker;
    private final ScreenOnBlocker mScreenOnBlocker;
    private final WindowManagerPolicy mPolicy;

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

    // True if the screen on blocker has been acquired.
    private boolean mScreenOnBlockerAcquired;

    public Notifier(Looper looper, Context context, IBatteryStats batteryStats,
            SuspendBlocker suspendBlocker, ScreenOnBlocker screenOnBlocker,
            WindowManagerPolicy policy) {
        mContext = context;
        mBatteryStats = batteryStats;
        mSuspendBlocker = suspendBlocker;
        mScreenOnBlocker = screenOnBlocker;
        mPolicy = policy;

        mHandler = new NotifierHandler(looper);
        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
    }

    /**
     * Called when a wake lock is acquired.
     */
    public void onWakeLockAcquired(int flags, String tag, int ownerUid, int ownerPid,
            WorkSource workSource) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeLockAcquired: flags=" + flags + ", tag=\"" + tag
                    + "\", ownerUid=" + ownerUid + ", ownerPid=" + ownerPid
                    + ", workSource=" + workSource);
        }

        try {
            final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
            if (workSource != null) {
                mBatteryStats.noteStartWakelockFromSource(workSource, ownerPid, tag, monitorType);
            } else {
                mBatteryStats.noteStartWakelock(ownerUid, ownerPid, tag, monitorType);
            }
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    /**
     * Called when a wake lock is released.
     */
    public void onWakeLockReleased(int flags, String tag, int ownerUid, int ownerPid,
            WorkSource workSource) {
        if (DEBUG) {
            Slog.d(TAG, "onWakeLockReleased: flags=" + flags + ", tag=\"" + tag
                    + "\", ownerUid=" + ownerUid + ", ownerPid=" + ownerPid
                    + ", workSource=" + workSource);
        }

        try {
            final int monitorType = getBatteryStatsWakeLockMonitorType(flags);
            if (workSource != null) {
                mBatteryStats.noteStopWakelockFromSource(workSource, ownerPid, tag, monitorType);
            } else {
                mBatteryStats.noteStopWakelock(ownerUid, ownerPid, tag, monitorType);
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
     * Called when the screen is turned on.
     */
    public void onScreenOn() {
        if (DEBUG) {
            Slog.d(TAG, "onScreenOn");
        }

        try {
            mBatteryStats.noteScreenOn();
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    /**
     * Called when the screen is turned off.
     */
    public void onScreenOff() {
        if (DEBUG) {
            Slog.d(TAG, "onScreenOff");
        }

        try {
            mBatteryStats.noteScreenOff();
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    /**
     * Called when the screen changes brightness.
     */
    public void onScreenBrightness(int brightness) {
        if (DEBUG) {
            Slog.d(TAG, "onScreenBrightness: brightness=" + brightness);
        }

        try {
            mBatteryStats.noteScreenBrightness(brightness);
        } catch (RemoteException ex) {
            // Ignore
        }
    }

    /**
     * Called when the device is waking up from sleep and the
     * display is about to be turned on.
     */
    public void onWakeUpStarted() {
        if (DEBUG) {
            Slog.d(TAG, "onWakeUpStarted");
        }

        synchronized (mLock) {
            if (mActualPowerState != POWER_STATE_AWAKE) {
                mActualPowerState = POWER_STATE_AWAKE;
                mPendingWakeUpBroadcast = true;
                if (!mScreenOnBlockerAcquired) {
                    mScreenOnBlockerAcquired = true;
                    mScreenOnBlocker.acquire();
                }
                updatePendingBroadcastLocked();
            }
        }
    }

    /**
     * Called when the device has finished waking up from sleep
     * and the display has been turned on.
     */
    public void onWakeUpFinished() {
        if (DEBUG) {
            Slog.d(TAG, "onWakeUpFinished");
        }
    }

    /**
     * Called when the device is going to sleep.
     */
    public void onGoToSleepStarted(int reason) {
        if (DEBUG) {
            Slog.d(TAG, "onGoToSleepStarted");
        }

        synchronized (mLock) {
            mLastGoToSleepReason = reason;
        }
    }

    /**
     * Called when the device has finished going to sleep and the
     * display has been turned off.
     *
     * This is a good time to make transitions that we don't want the user to see,
     * such as bringing the key guard to focus.  There's no guarantee for this,
     * however because the user could turn the device on again at any time.
     * Some things may need to be protected by other mechanisms that defer screen on.
     */
    public void onGoToSleepFinished() {
        if (DEBUG) {
            Slog.d(TAG, "onGoToSleepFinished");
        }

        synchronized (mLock) {
            if (mActualPowerState != POWER_STATE_ASLEEP) {
                mActualPowerState = POWER_STATE_ASLEEP;
                mPendingGoToSleepBroadcast = true;
                if (mUserActivityPending) {
                    mUserActivityPending = false;
                    mHandler.removeMessages(MSG_USER_ACTIVITY);
                }
                updatePendingBroadcastLocked();
            }
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
        final int goToSleepReason;
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
            goToSleepReason = mLastGoToSleepReason;
        }

        EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_SEND, 1);

        if (powerState == POWER_STATE_AWAKE) {
            sendWakeUpBroadcast();
        } else {
            sendGoToSleepBroadcast(goToSleepReason);
        }
    }

    private void sendWakeUpBroadcast() {
        if (DEBUG) {
            Slog.d(TAG, "Sending wake up broadcast.");
        }

        EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 1, 0, 0, 0);

        mPolicy.screenTurningOn(mScreenOnListener);

        try {
            ActivityManagerNative.getDefault().wakingUp();
        } catch (RemoteException e) {
            // ignore it
        }

        if (ActivityManagerNative.isSystemReady()) {
            mContext.sendOrderedBroadcastAsUser(mScreenOnIntent, UserHandle.ALL, null,
                    mWakeUpBroadcastDone, mHandler, 0, null, null);
        } else {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2, 1);
            sendNextBroadcast();
        }
    }

    private final WindowManagerPolicy.ScreenOnListener mScreenOnListener =
            new WindowManagerPolicy.ScreenOnListener() {
        @Override
        public void onScreenOn() {
            synchronized (mLock) {
                if (mScreenOnBlockerAcquired && !mPendingWakeUpBroadcast) {
                    mScreenOnBlockerAcquired = false;
                    mScreenOnBlocker.release();
                }
            }
        }
    };

    private final BroadcastReceiver mWakeUpBroadcastDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 1,
                    SystemClock.uptimeMillis() - mBroadcastStartTime, 1);
            sendNextBroadcast();
        }
    };

    private void sendGoToSleepBroadcast(int reason) {
        if (DEBUG) {
            Slog.d(TAG, "Sending go to sleep broadcast.");
        }

        int why = WindowManagerPolicy.OFF_BECAUSE_OF_USER;
        switch (reason) {
            case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                why = WindowManagerPolicy.OFF_BECAUSE_OF_ADMIN;
                break;
            case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                why = WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT;
                break;
        }

        EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 0, why, 0, 0);

        mPolicy.screenTurnedOff(why);
        try {
            ActivityManagerNative.getDefault().goingToSleep();
        } catch (RemoteException e) {
            // ignore it.
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
            }
        }
    }
}
