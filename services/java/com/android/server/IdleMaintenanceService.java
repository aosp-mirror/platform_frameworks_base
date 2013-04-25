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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
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
 * since the screen went off or a dream started (i.e. since the last user
 * activity).
 *
 * The end of a maintenance window is announced only if: a start was
 * announced AND the screen turned on or a dream was stopped.
 */
public class IdleMaintenanceService extends BroadcastReceiver {

    private final boolean DEBUG = false;

    private static final String LOG_TAG = IdleMaintenanceService.class.getSimpleName();

    private static final int LAST_USER_ACTIVITY_TIME_INVALID = -1;

    private static final long MILLIS_IN_DAY = 24 * 60 * 60 * 1000;

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_CHARGING = 30; // percent

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_NOT_CHARGING = 80; // percent

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_RUNNING = 10; // percent

    private static final long MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START = 60 * 60 * 1000; // 1 hour

    private final Intent mIdleMaintenanceStartIntent =
            new Intent(Intent.ACTION_IDLE_MAINTENANCE_START);

    private final Intent mIdleMaintenanceEndIntent =
            new Intent(Intent.ACTION_IDLE_MAINTENANCE_END);

    private final Context mContext;

    private final WakeLock mWakeLock;

    private final Handler mHandler;

    private long mLastIdleMaintenanceStartTimeMillis = SystemClock.elapsedRealtime();

    private long mLastUserActivityElapsedTimeMillis = LAST_USER_ACTIVITY_TIME_INVALID;

    private int mBatteryLevel;

    private boolean mBatteryCharging;

    private boolean mIdleMaintenanceStarted;

    public IdleMaintenanceService(Context context) {
        mContext = context;

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);

        mHandler = new Handler(mContext.getMainLooper());

        register(mContext.getMainLooper());
    }

    public void register(Looper looper) {
        IntentFilter intentFilter = new IntentFilter();

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

    private void updateIdleMaintenanceState() {
        if (mIdleMaintenanceStarted) {
            // Idle maintenance can be interrupted only by
            // a change of the device state.
            if (!deviceStatePermitsIdleMaintenanceRunning()) {
                mIdleMaintenanceStarted = false;
                EventLogTags.writeIdleMaintenanceWindowFinish(SystemClock.elapsedRealtime(),
                        mLastUserActivityElapsedTimeMillis, mBatteryLevel,
                        mBatteryCharging ? 1 : 0);
                sendIdleMaintenanceEndIntent();
            }
        } else if (deviceStatePermitsIdleMaintenanceStart()
                && lastUserActivityPermitsIdleMaintenanceStart()
                && lastRunPermitsIdleMaintenanceStart()) {
            mIdleMaintenanceStarted = true;
            EventLogTags.writeIdleMaintenanceWindowStart(SystemClock.elapsedRealtime(),
                    mLastUserActivityElapsedTimeMillis, mBatteryLevel,
                    mBatteryCharging ? 1 : 0);
            mLastIdleMaintenanceStartTimeMillis = SystemClock.elapsedRealtime();
            sendIdleMaintenanceStartIntent();
        }
    }

    private void sendIdleMaintenanceStartIntent() {
        if (DEBUG) {
            Log.i(LOG_TAG, "Broadcasting " + Intent.ACTION_IDLE_MAINTENANCE_START);
        }
        mWakeLock.acquire();
        mContext.sendOrderedBroadcastAsUser(mIdleMaintenanceStartIntent, UserHandle.ALL,
                null, this, mHandler, Activity.RESULT_OK, null, null);
    }

    private void sendIdleMaintenanceEndIntent() {
        if (DEBUG) {
            Log.i(LOG_TAG, "Broadcasting " + Intent.ACTION_IDLE_MAINTENANCE_END);
        }
        mWakeLock.acquire();
        mContext.sendOrderedBroadcastAsUser(mIdleMaintenanceEndIntent, UserHandle.ALL,
                null, this, mHandler, Activity.RESULT_OK, null, null);
    }

    private boolean deviceStatePermitsIdleMaintenanceStart() {
        final int minBatteryLevel = mBatteryCharging
                ? MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_CHARGING
                : MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_NOT_CHARGING;
        return (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID
                && mBatteryLevel > minBatteryLevel);
    }

    private boolean deviceStatePermitsIdleMaintenanceRunning() {
        return (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID
                && mBatteryLevel > MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_RUNNING);
    }

    private boolean lastUserActivityPermitsIdleMaintenanceStart() {
        return (SystemClock.elapsedRealtime() - mLastUserActivityElapsedTimeMillis
                > MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
    }

    private boolean lastRunPermitsIdleMaintenanceStart() {
        return SystemClock.elapsedRealtime() - mLastIdleMaintenanceStartTimeMillis > MILLIS_IN_DAY;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) {
            Log.i(LOG_TAG, intent.getAction());
        }
        String action = intent.getAction();
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            final int maxBatteryLevel = intent.getExtras().getInt(BatteryManager.EXTRA_SCALE);
            final int currBatteryLevel = intent.getExtras().getInt(BatteryManager.EXTRA_LEVEL);
            mBatteryLevel = (int) (((float) maxBatteryLevel / 100) * currBatteryLevel);
            final int pluggedState = intent.getExtras().getInt(BatteryManager.EXTRA_PLUGGED);
            final int chargerState = intent.getExtras().getInt(
                    BatteryManager.EXTRA_INVALID_CHARGER, 0);
            mBatteryCharging = (pluggedState > 0 && chargerState == 0);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_DREAMING_STOPPED.equals(action)) {
            mLastUserActivityElapsedTimeMillis = LAST_USER_ACTIVITY_TIME_INVALID;
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)
                || Intent.ACTION_DREAMING_STARTED.equals(action)) {
            mLastUserActivityElapsedTimeMillis = SystemClock.elapsedRealtime();
        } else if (Intent.ACTION_IDLE_MAINTENANCE_START.equals(action)
                || Intent.ACTION_IDLE_MAINTENANCE_END.equals(action)) {
            mWakeLock.release();
            return;
        }
        updateIdleMaintenanceState();
    }
}
