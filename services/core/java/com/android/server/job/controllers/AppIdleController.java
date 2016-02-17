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
                sController = new AppIdleController(service, service.getContext());
            }
            return sController;
        }
    }

    private AppIdleController(StateChangedListener stateChangedListener, Context context) {
        super(stateChangedListener, context);
        mUsageStatsInternal = LocalServices.getService(UsageStatsManagerInternal.class);
        mAppIdleParoleOn = mUsageStatsInternal.isAppIdleParoleOn();
        mUsageStatsInternal.addAppIdleStateChangeListener(new AppIdleStateChangeListener());
    }

    @Override
    public void maybeStartTrackingJob(JobStatus jobStatus) {
        synchronized (mTrackedTasks) {
            mTrackedTasks.add(jobStatus);
            String packageName = jobStatus.job.getService().getPackageName();
            final boolean appIdle = !mAppIdleParoleOn && mUsageStatsInternal.isAppIdle(packageName,
                    jobStatus.uId, jobStatus.getUserId());
            if (DEBUG) {
                Slog.d(LOG_TAG, "Start tracking, setting idle state of "
                        + packageName + " to " + appIdle);
            }
            jobStatus.appNotIdleConstraintSatisfied.set(!appIdle);
        }
    }

    @Override
    public void maybeStopTrackingJob(JobStatus jobStatus) {
        synchronized (mTrackedTasks) {
            mTrackedTasks.remove(jobStatus);
        }
    }

    @Override
    public void dumpControllerState(PrintWriter pw) {
        pw.println("AppIdle");
        pw.println("Parole On: " + mAppIdleParoleOn);
        synchronized (mTrackedTasks) {
            for (JobStatus task : mTrackedTasks) {
                pw.print(task.job.getService().getPackageName());
                pw.print(":idle=" + !task.appNotIdleConstraintSatisfied.get());
                pw.print(", ");
            }
            pw.println();
        }
    }

    void setAppIdleParoleOn(boolean isAppIdleParoleOn) {
        // Flag if any app's idle state has changed
        boolean changed = false;
        synchronized (mTrackedTasks) {
            if (mAppIdleParoleOn == isAppIdleParoleOn) {
                return;
            }
            mAppIdleParoleOn = isAppIdleParoleOn;
            for (JobStatus task : mTrackedTasks) {
                String packageName = task.job.getService().getPackageName();
                final boolean appIdle = !mAppIdleParoleOn && mUsageStatsInternal.isAppIdle(packageName,
                        task.uId, task.getUserId());
                if (DEBUG) {
                    Slog.d(LOG_TAG, "Setting idle state of " + packageName + " to " + appIdle);
                }
                if (task.appNotIdleConstraintSatisfied.get() == appIdle) {
                    task.appNotIdleConstraintSatisfied.set(!appIdle);
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
            synchronized (mTrackedTasks) {
                if (mAppIdleParoleOn) {
                    return;
                }
                for (JobStatus task : mTrackedTasks) {
                    if (task.job.getService().getPackageName().equals(packageName)
                            && task.getUserId() == userId) {
                        if (task.appNotIdleConstraintSatisfied.get() != !idle) {
                            if (DEBUG) {
                                Slog.d(LOG_TAG, "App Idle state changed, setting idle state of "
                                        + packageName + " to " + idle);
                            }
                            task.appNotIdleConstraintSatisfied.set(!idle);
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
