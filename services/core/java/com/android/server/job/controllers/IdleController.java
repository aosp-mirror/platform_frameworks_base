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

package com.android.server.job.controllers;

import java.io.PrintWriter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.am.ActivityManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

public final class IdleController extends StateController {
    private static final String TAG = "IdleController";

    // Policy: we decide that we're "idle" if the device has been unused /
    // screen off or dreaming for at least this long
    private long mInactivityIdleThreshold;
    private long mIdleWindowSlop;
    final ArraySet<JobStatus> mTrackedTasks = new ArraySet<>();
    IdlenessTracker mIdleTracker;

    // Singleton factory
    private static Object sCreationLock = new Object();
    private static volatile IdleController sController;

    public static IdleController get(JobSchedulerService service) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new IdleController(service, service.getContext(), service.getLock());
            }
            return sController;
        }
    }

    private IdleController(StateChangedListener stateChangedListener, Context context,
                Object lock) {
        super(stateChangedListener, context, lock);
        initIdleStateTracking();
    }

    /**
     * StateController interface
     */
    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasIdleConstraint()) {
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_IDLE);
            taskStatus.setIdleConstraintSatisfied(mIdleTracker.isIdle());
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_IDLE)) {
            mTrackedTasks.remove(taskStatus);
        }
    }

    /**
     * Interaction with the task manager service
     */
    void reportNewIdleState(boolean isIdle) {
        synchronized (mLock) {
            for (int i = mTrackedTasks.size()-1; i >= 0; i--) {
                mTrackedTasks.valueAt(i).setIdleConstraintSatisfied(isIdle);
            }
        }
        mStateChangedListener.onControllerStateChanged();
    }

    /**
     * Idle state tracking, and messaging with the task manager when
     * significant state changes occur
     */
    private void initIdleStateTracking() {
        mInactivityIdleThreshold = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_jobSchedulerInactivityIdleThreshold);
        mIdleWindowSlop = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_jobSchedulerIdleWindowSlop);
        mIdleTracker = new IdlenessTracker();
        mIdleTracker.startTracking();
    }

    final class IdlenessTracker extends BroadcastReceiver {
        private AlarmManager mAlarm;
        private PendingIntent mIdleTriggerIntent;
        boolean mIdle;
        boolean mScreenOn;

        public IdlenessTracker() {
            mAlarm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

            Intent intent = new Intent(ActivityManagerService.ACTION_TRIGGER_IDLE)
                    .setPackage("android")
                    .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mIdleTriggerIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

            // At boot we presume that the user has just "interacted" with the
            // device in some meaningful way.
            mIdle = false;
            mScreenOn = true;
        }

        public boolean isIdle() {
            return mIdle;
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Screen state
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);

            // Dreaming state
            filter.addAction(Intent.ACTION_DREAMING_STARTED);
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);

            // Debugging/instrumentation
            filter.addAction(ActivityManagerService.ACTION_TRIGGER_IDLE);

            mContext.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)
                    || action.equals(Intent.ACTION_DREAMING_STOPPED)) {
                if (DEBUG) {
                    Slog.v(TAG,"exiting idle : " + action);
                }
                mScreenOn = true;
                //cancel the alarm
                mAlarm.cancel(mIdleTriggerIntent);
                if (mIdle) {
                // possible transition to not-idle
                    mIdle = false;
                    reportNewIdleState(mIdle);
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)
                    || action.equals(Intent.ACTION_DREAMING_STARTED)) {
                // when the screen goes off or dreaming starts, we schedule the
                // alarm that will tell us when we have decided the device is
                // truly idle.
                final long nowElapsed = SystemClock.elapsedRealtime();
                final long when = nowElapsed + mInactivityIdleThreshold;
                if (DEBUG) {
                    Slog.v(TAG, "Scheduling idle : " + action + " now:" + nowElapsed + " when="
                            + when);
                }
                mScreenOn = false;
                mAlarm.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        when, mIdleWindowSlop, mIdleTriggerIntent);
            } else if (action.equals(ActivityManagerService.ACTION_TRIGGER_IDLE)) {
                // idle time starts now. Do not set mIdle if screen is on.
                if (!mIdle && !mScreenOn) {
                    if (DEBUG) {
                        Slog.v(TAG, "Idle trigger fired @ " + SystemClock.elapsedRealtime());
                    }
                    mIdle = true;
                    reportNewIdleState(mIdle);
                } else {
                    if (DEBUG) {
                        Slog.v(TAG, "TRIGGER_IDLE received but not changing state; idle="
                                + mIdle + " screen=" + mScreenOn);
                    }
                }
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.print("Idle: ");
        pw.println(mIdleTracker.isIdle());
        pw.print("Tracking ");
        pw.print(mTrackedTasks.size());
        pw.println(":");
        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!js.shouldDump(filterUid)) {
                continue;
            }
            pw.print("  #");
            js.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, js.getSourceUid());
            pw.println();
        }
    }
}
