/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

/**
 * This service observes the device state and when applicable sends
 * broadcasts at the beginning and at the end of a period during which
 * observers can perform idle maintenance tasks. Typical use of the
 * idle maintenance is to perform somehow expensive tasks that can be
 * postponed to a moment when they will not degrade user experience.
 *
 * The current implementation is very simple. The start of a maintenance
 * window is announced if: the screen is off or showing a dream AND the
 * battery level is more than twenty percent AND at least one hour passed
 * activity).
 *
 * The end of a maintenance window is announced only if: a start was
 * announced AND the screen turned on or a dream was stopped.
 */
public class IdleMaintenanceService extends BroadcastReceiver {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = IdleMaintenanceService.class.getSimpleName();

    private static final int LAST_USER_ACTIVITY_TIME_INVALID = -1;

    private static final long MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS = 24 * 60 * 60 * 1000; // 1 day

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_CHARGING = 30; // percent

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_NOT_CHARGING = 80; // percent

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_RUNNING = 20; // percent

    private static final long MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START = 71 * 60 * 1000; // 71 min

    private static final long MAX_IDLE_MAINTENANCE_DURATION = 71 * 60 * 1000; // 71 min

    private static final String ACTION_UPDATE_IDLE_MAINTENANCE_STATE =
        "com.android.server.IdleMaintenanceService.action.UPDATE_IDLE_MAINTENANCE_STATE";

    private static final Intent sIdleMaintenanceStartIntent;
    static {
        sIdleMaintenanceStartIntent = new Intent(Intent.ACTION_IDLE_MAINTENANCE_START);
        sIdleMaintenanceStartIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    };

    private static final Intent sIdleMaintenanceEndIntent;
    static {
        sIdleMaintenanceEndIntent = new Intent(Intent.ACTION_IDLE_MAINTENANCE_END);
        sIdleMaintenanceEndIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    }

    private final AlarmManager mAlarmService;

    private final BatteryService mBatteryService;

    private final PendingIntent mUpdateIdleMaintenanceStatePendingIntent;

    private final Context mContext;

    private final WakeLock mWakeLock;

    private final Handler mHandler;

    private long mLastIdleMaintenanceStartTimeMillis;

    private long mLastUserActivityElapsedTimeMillis = LAST_USER_ACTIVITY_TIME_INVALID;

    private boolean mIdleMaintenanceStarted;

    public IdleMaintenanceService(Context context, BatteryService batteryService) {
        mContext = context;
        mBatteryService = batteryService;

        mAlarmService = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);

        mHandler = new Handler(mContext.getMainLooper());

        Intent intent = new Intent(ACTION_UPDATE_IDLE_MAINTENANCE_STATE);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mUpdateIdleMaintenanceStatePendingIntent = PendingIntent.getBroadcast(mContext, 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);

        register(mContext.getMainLooper());
    }

    public void register(Looper looper) {
        IntentFilter intentFilter = new IntentFilter();

        // Alarm actions.
        intentFilter.addAction(ACTION_UPDATE_IDLE_MAINTENANCE_STATE);

        // Battery actions.
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);

        // Screen actions.
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);

        // Dream actions.
        intentFilter.addAction(Intent.ACTION_DREAMING_STARTED);
        intentFilter.addAction(Intent.ACTION_DREAMING_STOPPED);

        mContext.registerReceiverAsUser(this, UserHandle.ALL,
                intentFilter, null, new Handler(looper));
    }

    private void scheduleUpdateIdleMaintenanceState(long delayMillis) {
        final long triggetRealTimeMillis = SystemClock.elapsedRealtime() + delayMillis;
        mAlarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggetRealTimeMillis,
                mUpdateIdleMaintenanceStatePendingIntent);
    }

    private void unscheduleUpdateIdleMaintenanceState() {
        mAlarmService.cancel(mUpdateIdleMaintenanceStatePendingIntent);
    }

    private void updateIdleMaintenanceState() {
        if (mIdleMaintenanceStarted) {
            // Idle maintenance can be interrupted by user activity, or duration
            // time out, or low battery.
            if (!lastUserActivityPermitsIdleMaintenanceRunning()
                    || !batteryLevelAndMaintenanceTimeoutPermitsIdleMaintenanceRunning()) {
                unscheduleUpdateIdleMaintenanceState();
                mIdleMaintenanceStarted = false;
                EventLogTags.writeIdleMaintenanceWindowFinish(SystemClock.elapsedRealtime(),
                        mLastUserActivityElapsedTimeMillis, mBatteryService.getBatteryLevel(),
                        isBatteryCharging() ? 1 : 0);
                sendIdleMaintenanceEndIntent();
                // We stopped since we don't have enough battery or timed out but the
                // user is not using the device, so we should be able to run maintenance
                // in the next maintenance window since the battery may be charged
                // without interaction and the min interval between maintenances passed.
                if (!batteryLevelAndMaintenanceTimeoutPermitsIdleMaintenanceRunning()) {
                    scheduleUpdateIdleMaintenanceState(
                            getNextIdleMaintenanceIntervalStartFromNow());
                }
            }
        } else if (deviceStatePermitsIdleMaintenanceStart()
                && lastUserActivityPermitsIdleMaintenanceStart()
                && lastRunPermitsIdleMaintenanceStart()) {
            // Now that we started idle maintenance, we should schedule another
            // update for the moment when the idle maintenance times out.
            scheduleUpdateIdleMaintenanceState(MAX_IDLE_MAINTENANCE_DURATION);
            mIdleMaintenanceStarted = true;
            EventLogTags.writeIdleMaintenanceWindowStart(SystemClock.elapsedRealtime(),
                    mLastUserActivityElapsedTimeMillis, mBatteryService.getBatteryLevel(),
                    isBatteryCharging() ? 1 : 0);
            mLastIdleMaintenanceStartTimeMillis = SystemClock.elapsedRealtime();
            sendIdleMaintenanceStartIntent();
        } else if (lastUserActivityPermitsIdleMaintenanceStart()) {
             if (lastRunPermitsIdleMaintenanceStart()) {
                // The user does not use the device and we did not run maintenance in more
                // than the min interval between runs, so schedule an update - maybe the
                // battery will be charged latter.
                scheduleUpdateIdleMaintenanceState(MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
             } else {
                 // The user does not use the device but we have run maintenance in the min
                 // interval between runs, so schedule an update after the min interval ends.
                 scheduleUpdateIdleMaintenanceState(
                         getNextIdleMaintenanceIntervalStartFromNow());
             }
        }
    }

    private long getNextIdleMaintenanceIntervalStartFromNow() {
        return mLastIdleMaintenanceStartTimeMillis + MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS
                - SystemClock.elapsedRealtime();
    }

    private void sendIdleMaintenanceStartIntent() {
        mWakeLock.acquire();
        mContext.sendOrderedBroadcastAsUser(sIdleMaintenanceStartIntent, UserHandle.ALL,
                null, this, mHandler, Activity.RESULT_OK, null, null);
    }

    private void sendIdleMaintenanceEndIntent() {
        mWakeLock.acquire();
        mContext.sendOrderedBroadcastAsUser(sIdleMaintenanceEndIntent, UserHandle.ALL,
                null, this, mHandler, Activity.RESULT_OK, null, null);
    }

    private boolean deviceStatePermitsIdleMaintenanceStart() {
        final int minBatteryLevel = isBatteryCharging()
                ? MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_CHARGING
                : MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_NOT_CHARGING;
        return (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID
                && mBatteryService.getBatteryLevel() > minBatteryLevel);
    }

    private boolean lastUserActivityPermitsIdleMaintenanceStart() {
        // The last time the user poked the device is above the threshold.
        return (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID
                && SystemClock.elapsedRealtime() - mLastUserActivityElapsedTimeMillis
                    > MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
    }

    private boolean lastRunPermitsIdleMaintenanceStart() {
        // Enough time passed since the last maintenance run.
        return SystemClock.elapsedRealtime() - mLastIdleMaintenanceStartTimeMillis
                > MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS;
    }

    private boolean lastUserActivityPermitsIdleMaintenanceRunning() {
        // The user is not using the device.
        return (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID);
    }

    private boolean batteryLevelAndMaintenanceTimeoutPermitsIdleMaintenanceRunning() {
        // Battery not too low and the maintenance duration did not timeout.
        return (mBatteryService.getBatteryLevel() > MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_RUNNING
                && mLastIdleMaintenanceStartTimeMillis + MAX_IDLE_MAINTENANCE_DURATION
                        > SystemClock.elapsedRealtime());
    }

    private boolean isBatteryCharging() {
        return mBatteryService.getPlugType() > 0
                && mBatteryService.getInvalidCharger() == 0;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) {
            Log.i(LOG_TAG, intent.getAction());
        }
        String action = intent.getAction();
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            // We care about battery only if maintenance is in progress so we can
            // stop it if battery is too low. Note that here we assume that the
            // maintenance clients are properly holding a wake lock. We will
            // refactor the maintenance to use services instead of intents for the
            // next release. The only client for this for now is internal an holds
            // a wake lock correctly.
            if (mIdleMaintenanceStarted) {
                updateIdleMaintenanceState();
            }
        } else if (Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_DREAMING_STOPPED.equals(action)) {
            mLastUserActivityElapsedTimeMillis = LAST_USER_ACTIVITY_TIME_INVALID;
            // Unschedule any future updates since we already know that maintenance
            // cannot be performed since the user is back.
            unscheduleUpdateIdleMaintenanceState();
            // If the screen went on/stopped dreaming, we know the user is using the
            // device which means that idle maintenance should be stopped if running.
            updateIdleMaintenanceState();
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)
                || Intent.ACTION_DREAMING_STARTED.equals(action)) {
            mLastUserActivityElapsedTimeMillis = SystemClock.elapsedRealtime();
            // If screen went off/started dreaming, we may be able to start idle maintenance
            // after the minimal user inactivity elapses. We schedule an alarm for when
            // this timeout elapses since the device may go to sleep by then.
            scheduleUpdateIdleMaintenanceState(MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
        } else if (ACTION_UPDATE_IDLE_MAINTENANCE_STATE.equals(action)) {
            updateIdleMaintenanceState();
        } else if (Intent.ACTION_IDLE_MAINTENANCE_START.equals(action)
                || Intent.ACTION_IDLE_MAINTENANCE_END.equals(action)) {
            // We were holding a wake lock while broadcasting the idle maintenance
            // intents but now that we finished the broadcast release the wake lock.
            mWakeLock.release();
        }
    }
}
