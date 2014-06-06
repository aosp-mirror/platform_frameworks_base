/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.task.controllers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryProperty;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.BatteryService;
import com.android.server.task.StateChangedListener;
import com.android.server.task.TaskManagerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple controller that tracks whether the phone is charging or not. The phone is considered to
 * be charging when it's been plugged in for more than two minutes, and the system has broadcast
 * ACTION_BATTERY_OK.
 */
public class BatteryController extends StateController {
    private static final String TAG = "BatteryController";

    private static final Object sCreationLock = new Object();
    private static volatile BatteryController sController;
    private static final String ACTION_CHARGING_STABLE =
            "com.android.server.task.controllers.BatteryController.ACTION_CHARGING_STABLE";
    /** Wait this long after phone is plugged in before doing any work. */
    private static final long STABLE_CHARGING_THRESHOLD_MILLIS = 2 * 60 * 1000; // 2 minutes.

    private List<TaskStatus> mTrackedTasks = new ArrayList<TaskStatus>();
    private ChargingTracker mChargeTracker;

    public static BatteryController get(TaskManagerService taskManagerService) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new BatteryController(taskManagerService,
                        taskManagerService.getContext());
            }
        }
        return sController;
    }

    @VisibleForTesting
    public ChargingTracker getTracker() {
        return mChargeTracker;
    }

    @VisibleForTesting
    public static BatteryController getForTesting(StateChangedListener stateChangedListener,
                                           Context context) {
        return new BatteryController(stateChangedListener, context);
    }

    private BatteryController(StateChangedListener stateChangedListener, Context context) {
        super(stateChangedListener, context);
        mChargeTracker = new ChargingTracker();
        mChargeTracker.startTracking();
    }

    @Override
    public void maybeStartTrackingTask(TaskStatus taskStatus) {
        if (taskStatus.hasChargingConstraint()) {
            synchronized (mTrackedTasks) {
                mTrackedTasks.add(taskStatus);
                taskStatus.chargingConstraintSatisfied.set(mChargeTracker.isOnStablePower());
            }
        }

    }

    @Override
    public void maybeStopTrackingTask(TaskStatus taskStatus) {
        if (taskStatus.hasChargingConstraint()) {
            synchronized (mTrackedTasks) {
                mTrackedTasks.remove(taskStatus);
            }
        }
    }

    private void maybeReportNewChargingState() {
        final boolean stablePower = mChargeTracker.isOnStablePower();
        boolean reportChange = false;
        synchronized (mTrackedTasks) {
            for (TaskStatus ts : mTrackedTasks) {
                boolean previous = ts.chargingConstraintSatisfied.getAndSet(stablePower);
                if (previous != stablePower) {
                    reportChange = true;
                }
            }
        }
        if (reportChange) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    public class ChargingTracker extends BroadcastReceiver {
        private final AlarmManager mAlarm;
        private final PendingIntent mStableChargingTriggerIntent;
        /**
         * Track whether we're "charging", where charging means that we're ready to commit to
         * doing work.
         */
        private boolean mCharging;
        /** Keep track of whether the battery is charged enough that we want to do work. */
        private boolean mBatteryHealthy;

        public ChargingTracker() {
            mAlarm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(ACTION_CHARGING_STABLE)
                    .setComponent(new ComponentName(mContext, this.getClass()));
            mStableChargingTriggerIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Battery health.
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(Intent.ACTION_BATTERY_OKAY);
            // Charging/not charging.
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            mContext.registerReceiver(this, filter);

            // Initialise tracker state.
            BatteryService batteryService = (BatteryService) ServiceManager.getService("battery");
            if (batteryService != null) {
                mBatteryHealthy = !batteryService.getBatteryLevelLow();
                mCharging = batteryService.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
            } else {
                // Unavailable for some reason, we default to false and let ACTION_BATTERY_[OK,LOW]
                // sort it out.
            }
        }

        boolean isOnStablePower() {
            return mCharging && mBatteryHealthy;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            onReceiveInternal(intent);
        }

        @VisibleForTesting
        public void onReceiveInternal(Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Battery life too low to do work. @ "
                            + SystemClock.elapsedRealtime());
                }
                // If we get this action, the battery is discharging => it isn't plugged in so
                // there's no work to cancel. We track this variable for the case where it is
                // charging, but hasn't been for long enough to be healthy.
                mBatteryHealthy = false;
            } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Battery life healthy enough to do work. @ "
                            + SystemClock.elapsedRealtime());
                }
                mBatteryHealthy = true;
                maybeReportNewChargingState();
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                // Set up an alarm for ACTION_CHARGING_STABLE - we don't want to kick off tasks
                // here if the user unplugs the phone immediately.
                mAlarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + STABLE_CHARGING_THRESHOLD_MILLIS,
                        mStableChargingTriggerIntent);
                mCharging = true;
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                // If an alarm is set, breathe a sigh of relief and cancel it - crisis averted.
                mAlarm.cancel(mStableChargingTriggerIntent);
                mCharging = false;
                maybeReportNewChargingState();
            }else if (ACTION_CHARGING_STABLE.equals(action)) {
                // Here's where we actually do the notify for a task being ready.
                if (DEBUG) {
                    Slog.d(TAG, "Battery connected fired @ " + SystemClock.elapsedRealtime());
                }
                if (mCharging) {  // Should never receive this intent if mCharging is false.
                    maybeReportNewChargingState();
                }
            }
        }
    }
}
