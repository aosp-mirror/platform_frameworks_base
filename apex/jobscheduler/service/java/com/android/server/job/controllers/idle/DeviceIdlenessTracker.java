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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.app.AlarmManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

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

    private AlarmManager mAlarm;
    private PowerManager mPowerManager;

    // After construction, mutations of idle/screen-on state will only happen
    // on the main looper thread, either in onReceive() or in an alarm callback.
    private long mInactivityIdleThreshold;
    private long mIdleWindowSlop;
    private boolean mIdle;
    private boolean mScreenOn;
    private boolean mDockIdle;
    private boolean mProjectionActive;
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
    public void startTracking(Context context, IdlenessListener listener) {
        mIdleListener = listener;
        mInactivityIdleThreshold = context.getResources().getInteger(
                com.android.internal.R.integer.config_jobSchedulerInactivityIdleThreshold);
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

        context.registerReceiver(this, filter);

        // TODO(b/172579710): Move the callbacks off the main executor and on to
        //  JobSchedulerBackgroundThread.getExecutor() once synchronization is fixed in this class.
        context.getSystemService(UiModeManager.class).addOnProjectionStateChangedListener(
                UiModeManager.PROJECTION_TYPE_ALL, context.getMainExecutor(),
                mOnProjectionStateChangedListener);
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
            cancelIdlenessCheck();
            if (mIdle) {
                mIdle = false;
                mIdleListener.reportNewIdleState(mIdle);
            }
        } else {
            maybeScheduleIdlenessCheck("Projection ended");
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("  mIdle: "); pw.println(mIdle);
        pw.print("  mScreenOn: "); pw.println(mScreenOn);
        pw.print("  mDockIdle: "); pw.println(mDockIdle);
        pw.print("  mProjectionActive: "); pw.println(mProjectionActive);
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
                cancelIdlenessCheck();
                if (mIdle) {
                    mIdle = false;
                    mIdleListener.reportNewIdleState(mIdle);
                }
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
        if ((!mScreenOn || mDockIdle) && !mProjectionActive) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            final long when = nowElapsed + mInactivityIdleThreshold;
            if (DEBUG) {
                Slog.v(TAG, "Scheduling idle : " + reason + " now:" + nowElapsed + " when=" + when);
            }
            mAlarm.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    when, mIdleWindowSlop, "JS idleness", mIdleAlarmListener, null);
        }
    }

    private void cancelIdlenessCheck() {
        mAlarm.cancel(mIdleAlarmListener);
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