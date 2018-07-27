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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.util.Log;
import android.util.Slog;
import com.android.server.am.ActivityManagerService;
import com.android.server.job.JobSchedulerService;

import java.io.PrintWriter;

public final class CarIdlenessTracker extends BroadcastReceiver implements IdlenessTracker {
    private static final String TAG = "JobScheduler.CarIdlenessTracker";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    public static final String ACTION_FORCE_IDLE = "com.android.server.ACTION_FORCE_IDLE";
    public static final String ACTION_UNFORCE_IDLE = "com.android.server.ACTION_UNFORCE_IDLE";

    // After construction, mutations of idle/screen-on state will only happen
    // on the main looper thread, either in onReceive() or in an alarm callback.
    private boolean mIdle;
    private boolean mScreenOn;
    private IdlenessListener mIdleListener;

    public CarIdlenessTracker() {
        // At boot we presume that the user has just "interacted" with the
        // device in some meaningful way.
        mIdle = false;
        mScreenOn = true;
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
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        // Debugging/instrumentation
        filter.addAction(ACTION_FORCE_IDLE);
        filter.addAction(ACTION_UNFORCE_IDLE);
        filter.addAction(ActivityManagerService.ACTION_TRIGGER_IDLE);

        context.registerReceiver(this, filter);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("  mIdle: "); pw.println(mIdle);
        pw.print("  mScreenOn: "); pw.println(mScreenOn);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        logIfDebug("Received action: " + action);

        // Check for forced actions
        if (action.equals(ACTION_FORCE_IDLE)) {
            logIfDebug("Forcing idle...");
            enterIdleState(true);
        } else if (action.equals(ACTION_UNFORCE_IDLE)) {
            logIfDebug("Unforcing idle...");
            exitIdleState(true);
        } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
            logIfDebug("Going idle...");
            mScreenOn = false;
            enterIdleState(false);
        } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
            logIfDebug("exiting idle...");
            mScreenOn = true;
            exitIdleState(true);
        } else if (action.equals(ActivityManagerService.ACTION_TRIGGER_IDLE)) {
            if (!mScreenOn) {
                logIfDebug("Idle trigger fired...");
                enterIdleState(false);
            } else {
                logIfDebug("TRIGGER_IDLE received but not changing state; idle="
                        + mIdle + " screen=" + mScreenOn);
            }
        }
    }

    private void enterIdleState(boolean forced) {
        if (!forced && mIdle) {
            // Already idle and don't need to trigger callbacks since not forced
            logIfDebug("Device is already considered idle");
            return;
        }
        mIdle = true;
        mIdleListener.reportNewIdleState(mIdle);
    }

    private void exitIdleState(boolean forced) {
        if (!forced && !mIdle) {
            // Already out of idle and don't need to trigger callbacks since not forced
            logIfDebug("Device is already considered not idle");
            return;
        }
        mIdle = false;
        mIdleListener.reportNewIdleState(mIdle);
    }

    private void logIfDebug(String msg) {
        if (DEBUG) {
            Slog.v(TAG, msg);
        }
    }
}