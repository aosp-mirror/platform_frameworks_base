/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.job.controllers.idle;

import static android.app.UiModeManager.PROJECTION_TYPE_NONE;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.provider.DeviceConfig;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.am.ActivityManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;

import java.io.PrintWriter;
import java.util.Set;

/** Class to track device idle state. */
public final class DeviceIdlenessTracker extends BroadcastReceiver implements IdlenessTracker {
    private static final String TAG = "JobScheduler.DeviceIdlenessTracker";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
    private static final String IC_DIT_CONSTANT_PREFIX = "ic_dit_";
    @VisibleForTesting
    static final String KEY_INACTIVITY_IDLE_THRESHOLD_MS =
            IC_DIT_CONSTANT_PREFIX + "inactivity_idle_threshold_ms";
    @VisibleForTesting
    static final String KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS =
            IC_DIT_CONSTANT_PREFIX + "inactivity_idle_stable_power_threshold_ms";
    private static final String KEY_IDLE_WINDOW_SLOP_MS =
            IC_DIT_CONSTANT_PREFIX + "idle_window_slop_ms";

    private AlarmManager mAlarm;
    private PowerManager mPowerManager;

    // After construction, mutations of idle/screen-on/projection states will only happen
    // on the JobScheduler thread, either in onReceive(), in an alarm callback, or in on.*Changed.
    private long mInactivityIdleThreshold;
    private long mInactivityStablePowerIdleThreshold;
    private long mIdleWindowSlop;
    /** Stable power is defined as "charging + battery not low." */
    private boolean mIsStablePower;
    private boolean mIdle;
    private boolean mScreenOn;
    private boolean mDockIdle;
    private boolean mProjectionActive;

    /**
     * Time (in the elapsed realtime timebase) when the idleness check was scheduled. This should
     * be a negative value if the device is not in state to be considered idle.
     */
    private long mIdlenessCheckScheduledElapsed = -1;
    /**
     * Time (in the elapsed realtime timebase) when the device can be considered idle.
     */
    private long mIdleStartElapsed = Long.MAX_VALUE;

    private IdlenessListener mIdleListener;
    private final UiModeManager.OnProjectionStateChangedListener mOnProjectionStateChangedListener =
            this::onProjectionStateChanged;

    private AlarmManager.OnAlarmListener mIdleAlarmListener = () -> {
        handleIdleTrigger();
    };

    public DeviceIdlenessTracker() {
        // At boot we presume that the user has just "interacted" with the
        // device in some meaningful way.
        mScreenOn = true;
    }

    @Override
    public boolean isIdle() {
        return mIdle;
    }

    @Override
    public void startTracking(Context context, JobSchedulerService service,
            IdlenessListener listener) {
        mIdleListener = listener;
        mInactivityIdleThreshold = context.getResources().getInteger(
                com.android.internal.R.integer.config_jobSchedulerInactivityIdleThreshold);
        mInactivityStablePowerIdleThreshold = context.getResources().getInteger(
                com.android.internal.R.integer
                        .config_jobSchedulerInactivityIdleThresholdOnStablePower);
        mIdleWindowSlop = context.getResources().getInteger(
                com.android.internal.R.integer.config_jobSchedulerIdleWindowSlop);
        mAlarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = context.getSystemService(PowerManager.class);

        IntentFilter filter = new IntentFilter();

        // Screen state
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        // Dreaming state
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);

        // Debugging/instrumentation
        filter.addAction(ActivityManagerService.ACTION_TRIGGER_IDLE);

        // Wireless charging dock state
        filter.addAction(Intent.ACTION_DOCK_IDLE);
        filter.addAction(Intent.ACTION_DOCK_ACTIVE);

        context.registerReceiver(this, filter, null, AppSchedulingModuleThread.getHandler());

        context.getSystemService(UiModeManager.class).addOnProjectionStateChangedListener(
                UiModeManager.PROJECTION_TYPE_ALL, AppSchedulingModuleThread.getExecutor(),
                mOnProjectionStateChangedListener);

        mIsStablePower = service.isBatteryCharging() && service.isBatteryNotLow();
    }

    /** Process the specified constant and update internal constants if relevant. */
    public void processConstant(@NonNull DeviceConfig.Properties properties,
            @NonNull String key) {
        switch (key) {
            case KEY_INACTIVITY_IDLE_THRESHOLD_MS:
                // Keep the threshold in the range [1 minute, 4 hours].
                mInactivityIdleThreshold = Math.max(MINUTE_IN_MILLIS, Math.min(4 * HOUR_IN_MILLIS,
                        properties.getLong(key, mInactivityIdleThreshold)));
                // Don't bother updating any pending alarms. Just wait until the next time we
                // attempt to check for idle state to use the new value.
                break;
            case KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS:
                // Keep the threshold in the range [1 minute, 4 hours].
                mInactivityStablePowerIdleThreshold = Math.max(MINUTE_IN_MILLIS,
                        Math.min(4 * HOUR_IN_MILLIS,
                                properties.getLong(key, mInactivityStablePowerIdleThreshold)));
                // Don't bother updating any pending alarms. Just wait until the next time we
                // attempt to check for idle state to use the new value.
                break;
            case KEY_IDLE_WINDOW_SLOP_MS:
                // Keep the slop in the range [1 minute, 15 minutes].
                mIdleWindowSlop = Math.max(MINUTE_IN_MILLIS, Math.min(15 * MINUTE_IN_MILLIS,
                        properties.getLong(key, mIdleWindowSlop)));
                // Don't bother updating any pending alarms. Just wait until the next time we
                // attempt to check for idle state to use the new value.
                break;
        }
    }

    @Override
    public void onBatteryStateChanged(boolean isCharging, boolean isBatteryNotLow) {
        final boolean isStablePower = isCharging && isBatteryNotLow;
        if (mIsStablePower != isStablePower) {
            mIsStablePower = isStablePower;
            maybeScheduleIdlenessCheck("stable power changed");
        }
    }

    private void onProjectionStateChanged(@UiModeManager.ProjectionType int activeProjectionTypes,
            Set<String> projectingPackages) {
        boolean projectionActive = activeProjectionTypes != PROJECTION_TYPE_NONE;
        if (mProjectionActive == projectionActive) {
            return;
        }
        if (DEBUG) {
            Slog.v(TAG, "Projection state changed: " + projectionActive);
        }
        mProjectionActive = projectionActive;
        if (mProjectionActive) {
            exitIdle();
        } else {
            maybeScheduleIdlenessCheck("Projection ended");
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("  mIdle: "); pw.println(mIdle);
        pw.print("  mScreenOn: "); pw.println(mScreenOn);
        pw.print("  mIsStablePower: "); pw.println(mIsStablePower);
        pw.print("  mDockIdle: "); pw.println(mDockIdle);
        pw.print("  mProjectionActive: "); pw.println(mProjectionActive);
        pw.print("  mIdlenessCheckScheduledElapsed: "); pw.println(mIdlenessCheckScheduledElapsed);
        pw.print("  mIdleStartElapsed: "); pw.println(mIdleStartElapsed);
    }

    @Override
    public void dump(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        final long diToken = proto.start(
                StateControllerProto.IdleController.IdlenessTracker.DEVICE_IDLENESS_TRACKER);

        proto.write(StateControllerProto.IdleController.IdlenessTracker.DeviceIdlenessTracker.IS_IDLE,
                mIdle);
        proto.write(
                StateControllerProto.IdleController.IdlenessTracker.DeviceIdlenessTracker.IS_SCREEN_ON,
                mScreenOn);
        proto.write(
                StateControllerProto.IdleController.IdlenessTracker.DeviceIdlenessTracker.IS_DOCK_IDLE,
                mDockIdle);
        proto.write(
                StateControllerProto.IdleController.IdlenessTracker.DeviceIdlenessTracker
                        .PROJECTION_ACTIVE,
                mProjectionActive);

        proto.end(diToken);
        proto.end(token);
    }

    @Override
    public void dumpConstants(IndentingPrintWriter pw) {
        pw.println("DeviceIdlenessTracker:");
        pw.increaseIndent();
        pw.print(KEY_INACTIVITY_IDLE_THRESHOLD_MS, mInactivityIdleThreshold).println();
        pw.print(KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS, mInactivityStablePowerIdleThreshold)
                .println();
        pw.print(KEY_IDLE_WINDOW_SLOP_MS, mIdleWindowSlop).println();
        pw.decreaseIndent();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (DEBUG) {
            Slog.v(TAG, "Received action: " + action);
        }
        switch (action) {
            case Intent.ACTION_DOCK_ACTIVE:
                if (!mScreenOn) {
                    // Ignore this intent during screen off
                    return;
                }
                // Intentional fallthrough
            case Intent.ACTION_DREAMING_STOPPED:
                if (!mPowerManager.isInteractive()) {
                    // Ignore this intent if the device isn't interactive.
                    return;
                }
                // Intentional fallthrough
            case Intent.ACTION_SCREEN_ON:
                mScreenOn = true;
                mDockIdle = false;
                if (DEBUG) {
                    Slog.v(TAG, "exiting idle");
                }
                exitIdle();
                break;
            case Intent.ACTION_SCREEN_OFF:
            case Intent.ACTION_DREAMING_STARTED:
            case Intent.ACTION_DOCK_IDLE:
                // when the screen goes off or dreaming starts or wireless charging dock in idle,
                // we schedule the alarm that will tell us when we have decided the device is
                // truly idle.
                if (action.equals(Intent.ACTION_DOCK_IDLE)) {
                    if (!mScreenOn) {
                        // Ignore this intent during screen off
                        return;
                    } else {
                        mDockIdle = true;
                    }
                } else {
                    mScreenOn = false;
                    mDockIdle = false;
                }
                maybeScheduleIdlenessCheck(action);
                break;
            case ActivityManagerService.ACTION_TRIGGER_IDLE:
                handleIdleTrigger();
                break;
        }
    }

    private void maybeScheduleIdlenessCheck(String reason) {
        if (mIdle) {
            if (DEBUG) {
                Slog.w(TAG, "Already idle. Redundant reason=" + reason);
            }
            return;
        }
        if ((!mScreenOn || mDockIdle) && !mProjectionActive) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            final long inactivityThresholdMs = mIsStablePower
                    ? mInactivityStablePowerIdleThreshold : mInactivityIdleThreshold;
            if (mIdlenessCheckScheduledElapsed >= 0) {
                if (mIdlenessCheckScheduledElapsed + inactivityThresholdMs <= nowElapsed) {
                    if (DEBUG) {
                        Slog.v(TAG, "Previous idle check @ " + mIdlenessCheckScheduledElapsed
                                + " allows device to be idle now");
                    }
                    handleIdleTrigger();
                    return;
                }
            } else {
                mIdlenessCheckScheduledElapsed = nowElapsed;
            }
            final long when = mIdlenessCheckScheduledElapsed + inactivityThresholdMs;
            if (when == mIdleStartElapsed) {
                if (DEBUG) {
                    Slog.i(TAG, "No change to idle start time");
                }
                return;
            }
            mIdleStartElapsed = when;
            if (DEBUG) {
                Slog.v(TAG, "Scheduling idle : " + reason + " now:" + nowElapsed
                        + " checkElapsed=" + mIdlenessCheckScheduledElapsed
                        + " when=" + mIdleStartElapsed);
            }
            mAlarm.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mIdleStartElapsed, mIdleWindowSlop, "JS idleness",
                    AppSchedulingModuleThread.getExecutor(), mIdleAlarmListener);
        }
    }

    private void exitIdle() {
        mAlarm.cancel(mIdleAlarmListener);
        mIdlenessCheckScheduledElapsed = -1;
        mIdleStartElapsed = Long.MAX_VALUE;
        if (mIdle) {
            mIdle = false;
            mIdleListener.reportNewIdleState(false);
        }
    }

    private void handleIdleTrigger() {
        // idle time starts now. Do not set mIdle if screen is on.
        if (!mIdle && (!mScreenOn || mDockIdle) && !mProjectionActive) {
            if (DEBUG) {
                Slog.v(TAG, "Idle trigger fired @ " + sElapsedRealtimeClock.millis());
            }
            mIdle = true;
            mIdleListener.reportNewIdleState(mIdle);
        } else {
            if (DEBUG) {
                Slog.v(TAG, "TRIGGER_IDLE received but not changing state; idle="
                        + mIdle + " screen=" + mScreenOn + " projection=" + mProjectionActive);
            }
        }
    }
}