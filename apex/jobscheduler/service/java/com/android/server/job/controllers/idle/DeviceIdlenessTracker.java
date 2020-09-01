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
    private boolean mInCarMode;
    private IdlenessListener mIdleListener;

    private AlarmManager.OnAlarmListener mIdleAlarmListener = () -> {
        handleIdleTrigger();
    };

    public DeviceIdlenessTracker() {
        // At boot we presume that the user has just "interacted" with the
        // device in some meaningful way.
        mIdle = false;
        mScreenOn = true;
        mDockIdle = false;
        mInCarMode = false;
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

        // Car mode
        filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED);
        filter.addAction(UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED);

        context.registerReceiver(this, filter);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("  mIdle: "); pw.println(mIdle);
        pw.print("  mScreenOn: "); pw.println(mScreenOn);
        pw.print("  mDockIdle: "); pw.println(mDockIdle);
        pw.print("  mInCarMode: ");
        pw.println(mInCarMode);
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
                StateControllerProto.IdleController.IdlenessTracker.DeviceIdlenessTracker.IN_CAR_MODE,
                mInCarMode);

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
            case UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED:
                mInCarMode = true;
                cancelIdlenessCheck();
                if (mIdle) {
                    mIdle = false;
                    mIdleListener.reportNewIdleState(mIdle);
                }
                break;
            case UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED:
                mInCarMode = false;
                maybeScheduleIdlenessCheck(action);
                break;
            case ActivityManagerService.ACTION_TRIGGER_IDLE:
                handleIdleTrigger();
                break;
        }
    }

    private void maybeScheduleIdlenessCheck(String reason) {
        if ((!mScreenOn || mDockIdle) && !mInCarMode) {
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
        if (!mIdle && (!mScreenOn || mDockIdle) && !mInCarMode) {
            if (DEBUG) {
                Slog.v(TAG, "Idle trigger fired @ " + sElapsedRealtimeClock.millis());
            }
            mIdle = true;
            mIdleListener.reportNewIdleState(mIdle);
        } else {
            if (DEBUG) {
                Slog.v(TAG, "TRIGGER_IDLE received but not changing state; idle="
                        + mIdle + " screen=" + mScreenOn + " car=" + mInCarMode);
            }
        }
    }
}