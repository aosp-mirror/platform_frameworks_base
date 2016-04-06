/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controls when apps are considered idle and if jobs pertaining to those apps should
 * be executed. Apps that haven't been actively launched or accessed from a foreground app
 * for a certain amount of time (maybe hours or days) are considered idle. When the app comes
 * out of idle state, it will be allowed to run scheduled jobs.
 */
public class AppIdleController extends StateController {

    private static final String LOG_TAG = "AppIdleController";
    private static final boolean DEBUG = false;

    // Singleton factory
    private static Object sCreationLock = new Object();
    private static volatile AppIdleController sController;
    final ArrayList<JobStatus> mTrackedTasks = new ArrayList<JobStatus>();
    private final UsageStatsManagerInternal mUsageStatsInternal;
    boolean mAppIdleParoleOn;

    public static AppIdleController get(JobSchedulerService service) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new AppIdleController(service, service.getContext(),
                        service.getLock());
            }
            return sController;
        }
    }

    private AppIdleController(StateChangedListener stateChangedListener, Context context,
            Object lock) {
        super(stateChangedListener, context, lock);
        mUsageStatsInternal = LocalServices.getService(UsageStatsManagerInternal.class);
        mAppIdleParoleOn = mUsageStatsInternal.isAppIdleParoleOn();
        mUsageStatsInternal.addAppIdleStateChangeListener(new AppIdleStateChangeListener());
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        mTrackedTasks.add(jobStatus);
        String packageName = jobStatus.getSourcePackageName();
        final boolean appIdle = !mAppIdleParoleOn && mUsageStatsInternal.isAppIdle(packageName,
                jobStatus.getSourceUid(), jobStatus.getSourceUserId());
        if (DEBUG) {
            Slog.d(LOG_TAG, "Start tracking, setting idle state of "
                    + packageName + " to " + appIdle);
        }
        jobStatus.setAppNotIdleConstraintSatisfied(!appIdle);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob, boolean forUpdate) {
        mTrackedTasks.remove(jobStatus);
    }

    @Override
    public void dumpControllerStateLocked(PrintWriter pw) {
        pw.println("AppIdle");
        pw.println("Parole On: " + mAppIdleParoleOn);
        for (JobStatus task : mTrackedTasks) {
            pw.print(task.getSourcePackageName());
            pw.print(":runnable="
                    + ((task.satisfiedConstraints&JobStatus.CONSTRAINT_APP_NOT_IDLE) != 0));
            pw.print(", ");
        }
        pw.println();
    }

    void setAppIdleParoleOn(boolean isAppIdleParoleOn) {
        // Flag if any app's idle state has changed
        boolean changed = false;
        synchronized (mLock) {
            if (mAppIdleParoleOn == isAppIdleParoleOn) {
                return;
            }
            mAppIdleParoleOn = isAppIdleParoleOn;
            for (JobStatus task : mTrackedTasks) {
                String packageName = task.getSourcePackageName();
                final boolean appIdle = !mAppIdleParoleOn && mUsageStatsInternal.isAppIdle(packageName,
                        task.getSourceUid(), task.getSourceUserId());
                if (DEBUG) {
                    Slog.d(LOG_TAG, "Setting idle state of " + packageName + " to " + appIdle);
                }
                if (task.setAppNotIdleConstraintSatisfied(!appIdle)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    private class AppIdleStateChangeListener
            extends UsageStatsManagerInternal.AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle) {
            boolean changed = false;
            synchronized (mLock) {
                if (mAppIdleParoleOn) {
                    return;
                }
                for (JobStatus task : mTrackedTasks) {
                    if (task.getSourcePackageName().equals(packageName)
                            && task.getSourceUserId() == userId) {
                        if (task.setAppNotIdleConstraintSatisfied(!idle)) {
                            if (DEBUG) {
                                Slog.d(LOG_TAG, "App Idle state changed, setting idle state of "
                                        + packageName + " to " + idle);
                            }
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                mStateChangedListener.onControllerStateChanged();
            }
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "Parole on: " + isParoleOn);
            }
            setAppIdleParoleOn(isParoleOn);
        }
    }
}
