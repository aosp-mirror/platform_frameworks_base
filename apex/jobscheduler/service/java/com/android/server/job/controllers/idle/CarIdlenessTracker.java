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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.server.am.ActivityManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;

import java.io.PrintWriter;

public final class CarIdlenessTracker extends BroadcastReceiver implements IdlenessTracker {
    private static final String TAG = "JobScheduler.CarIdlenessTracker";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    public static final String ACTION_GARAGE_MODE_ON =
            "com.android.server.jobscheduler.GARAGE_MODE_ON";
    public static final String ACTION_GARAGE_MODE_OFF =
            "com.android.server.jobscheduler.GARAGE_MODE_OFF";

    public static final String ACTION_FORCE_IDLE = "com.android.server.jobscheduler.FORCE_IDLE";
    public static final String ACTION_UNFORCE_IDLE = "com.android.server.jobscheduler.UNFORCE_IDLE";

    // After construction, mutations of idle/screen-on state will only happen
    // on the main looper thread, either in onReceive() or in an alarm callback.
    private boolean mIdle;
    private boolean mGarageModeOn;
    private boolean mForced;
    private IdlenessListener mIdleListener;

    public CarIdlenessTracker() {
        // At boot we presume that the user has just "interacted" with the
        // device in some meaningful way.
        mIdle = false;
        mGarageModeOn = false;
        mForced = false;
    }

    @Override
    public boolean isIdle() {
        return mIdle;
    }

    @Override
    public void startTracking(Context context, IdlenessListener listener) {
        mIdleListener = listener;

        IntentFilter filter = new IntentFilter();

        // Screen state
        filter.addAction(Intent.ACTION_SCREEN_ON);

        // State of GarageMode
        filter.addAction(ACTION_GARAGE_MODE_ON);
        filter.addAction(ACTION_GARAGE_MODE_OFF);

        // Debugging/instrumentation
        filter.addAction(ACTION_FORCE_IDLE);
        filter.addAction(ACTION_UNFORCE_IDLE);
        filter.addAction(ActivityManagerService.ACTION_TRIGGER_IDLE);

        context.registerReceiver(this, filter);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("  mIdle: "); pw.println(mIdle);
        pw.print("  mGarageModeOn: "); pw.println(mGarageModeOn);
    }

    @Override
    public void dump(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        final long ciToken = proto.start(
                StateControllerProto.IdleController.IdlenessTracker.CAR_IDLENESS_TRACKER);

        proto.write(StateControllerProto.IdleController.IdlenessTracker.CarIdlenessTracker.IS_IDLE,
                mIdle);
        proto.write(
                StateControllerProto.IdleController.IdlenessTracker.CarIdlenessTracker.IS_GARAGE_MODE_ON,
                mGarageModeOn);

        proto.end(ciToken);
        proto.end(token);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        logIfDebug("Received action: " + action);

        // Check for forced actions
        if (action.equals(ACTION_FORCE_IDLE)) {
            logIfDebug("Forcing idle...");
            setForceIdleState(true);
        } else if (action.equals(ACTION_UNFORCE_IDLE)) {
            logIfDebug("Unforcing idle...");
            setForceIdleState(false);
        } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
            logIfDebug("Screen is on...");
            handleScreenOn();
        } else if (action.equals(ACTION_GARAGE_MODE_ON)) {
            logIfDebug("GarageMode is on...");
            mGarageModeOn = true;
            updateIdlenessState();
        } else if (action.equals(ACTION_GARAGE_MODE_OFF)) {
            logIfDebug("GarageMode is off...");
            mGarageModeOn = false;
            updateIdlenessState();
        } else if (action.equals(ActivityManagerService.ACTION_TRIGGER_IDLE)) {
            if (!mGarageModeOn) {
                logIfDebug("Idle trigger fired...");
                triggerIdlenessOnce();
            } else {
                logIfDebug("TRIGGER_IDLE received but not changing state; idle="
                        + mIdle + " screen=" + mGarageModeOn);
            }
        }
    }

    private void setForceIdleState(boolean forced) {
        mForced = forced;
        updateIdlenessState();
    }

    private void updateIdlenessState() {
        final boolean newState = (mForced || mGarageModeOn);
        if (mIdle != newState) {
            // State of idleness changed. Notifying idleness controller
            logIfDebug("Device idleness changed. New idle=" + newState);
            mIdle = newState;
            mIdleListener.reportNewIdleState(mIdle);
        } else {
            // Nothing changed, device idleness is in the same state as new state
            logIfDebug("Device idleness is the same. Current idle=" + newState);
        }
    }

    private void triggerIdlenessOnce() {
        // This is simply triggering idleness once until some constraint will switch it back off
        if (mIdle) {
            // Already in idle state. Nothing to do
            logIfDebug("Device is already idle");
        } else {
            // Going idle once
            logIfDebug("Device is going idle once");
            mIdle = true;
            mIdleListener.reportNewIdleState(mIdle);
        }
    }

    private void handleScreenOn() {
        if (mForced || mGarageModeOn) {
            // Even though screen is on, the device remains idle
            logIfDebug("Screen is on, but device cannot exit idle");
        } else if (mIdle) {
            // Exiting idle
            logIfDebug("Device is exiting idle");
            mIdle = false;
        } else {
            // Already in non-idle state. Nothing to do
            logIfDebug("Device is already non-idle");
        }
    }

    private static void logIfDebug(String msg) {
        if (DEBUG) {
            Slog.v(TAG, msg);
        }
    }
}
