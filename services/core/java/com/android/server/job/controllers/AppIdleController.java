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
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Controls when apps are considered idle and if jobs pertaining to those apps should
 * be executed. Apps that haven't been actively launched or accessed from a foreground app
 * for a certain amount of time (maybe hours or days) are considered idle. When the app comes
 * out of idle state, it will be allowed to run scheduled jobs.
 */
public final class AppIdleController extends StateController {
    private static final String TAG = "JobScheduler.AppIdle";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    // Singleton factory
    private static Object sCreationLock = new Object();
    private static volatile AppIdleController sController;
    private final JobSchedulerService mJobSchedulerService;
    private final UsageStatsManagerInternal mUsageStatsInternal;
    private boolean mInitializedParoleOn;
    boolean mAppIdleParoleOn;

    final class GlobalUpdateFunc implements Consumer<JobStatus> {
        boolean mChanged;

        @Override
        public void accept(JobStatus jobStatus) {
            String packageName = jobStatus.getSourcePackageName();
            final boolean appIdle = !mAppIdleParoleOn && mUsageStatsInternal.isAppIdle(packageName,
                    jobStatus.getSourceUid(), jobStatus.getSourceUserId());
            if (DEBUG) {
                Slog.d(TAG, "Setting idle state of " + packageName + " to " + appIdle);
            }
            if (jobStatus.setAppNotIdleConstraintSatisfied(!appIdle)) {
                mChanged = true;
            }
        }
    }

    final static class PackageUpdateFunc implements Consumer<JobStatus> {
        final int mUserId;
        final String mPackage;
        final boolean mIdle;
        boolean mChanged;

        PackageUpdateFunc(int userId, String pkg, boolean idle) {
            mUserId = userId;
            mPackage = pkg;
            mIdle = idle;
        }

        @Override
        public void accept(JobStatus jobStatus) {
            if (jobStatus.getSourcePackageName().equals(mPackage)
                    && jobStatus.getSourceUserId() == mUserId) {
                if (jobStatus.setAppNotIdleConstraintSatisfied(!mIdle)) {
                    if (DEBUG) {
                        Slog.d(TAG, "App Idle state changed, setting idle state of "
                                + mPackage + " to " + mIdle);
                    }
                    mChanged = true;
                }
            }
        }
    }

    public static AppIdleController get(JobSchedulerService service) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new AppIdleController(service, service.getContext(),
                        service.getLock());
            }
            return sController;
        }
    }

    private AppIdleController(JobSchedulerService service, Context context, Object lock) {
        super(service, context, lock);
        mJobSchedulerService = service;
        mUsageStatsInternal = LocalServices.getService(UsageStatsManagerInternal.class);
        mAppIdleParoleOn = true;
        mUsageStatsInternal.addAppIdleStateChangeListener(new AppIdleStateChangeListener());
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (!mInitializedParoleOn) {
            mInitializedParoleOn = true;
            mAppIdleParoleOn = mUsageStatsInternal.isAppIdleParoleOn();
        }
        String packageName = jobStatus.getSourcePackageName();
        final boolean appIdle = !mAppIdleParoleOn && mUsageStatsInternal.isAppIdle(packageName,
                jobStatus.getSourceUid(), jobStatus.getSourceUserId());
        if (DEBUG) {
            Slog.d(TAG, "Start tracking, setting idle state of "
                    + packageName + " to " + appIdle);
        }
        jobStatus.setAppNotIdleConstraintSatisfied(!appIdle);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
    }

    @Override
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        pw.println("Parole on: " + mAppIdleParoleOn);
        pw.println();

        mJobSchedulerService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            pw.print("#");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, jobStatus.getSourceUid());
            pw.print(": ");
            pw.print(jobStatus.getSourcePackageName());
            if ((jobStatus.satisfiedConstraints&JobStatus.CONSTRAINT_APP_NOT_IDLE) != 0) {
                pw.println(" RUNNABLE");
            } else {
                pw.println(" WAITING");
            }
        });
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.APP_IDLE);

        proto.write(StateControllerProto.AppIdleController.IS_PAROLE_ON, mAppIdleParoleOn);

        mJobSchedulerService.getJobStore().forEachJob(predicate, (js) -> {
            final long jsToken =
                    proto.start(StateControllerProto.AppIdleController.TRACKED_JOBS);
            js.writeToShortProto(proto, StateControllerProto.AppIdleController.TrackedJob.INFO);
            proto.write(StateControllerProto.AppIdleController.TrackedJob.SOURCE_UID,
                    js.getSourceUid());
            proto.write(StateControllerProto.AppIdleController.TrackedJob.SOURCE_PACKAGE_NAME,
                    js.getSourcePackageName());
            proto.write(
                    StateControllerProto.AppIdleController.TrackedJob.ARE_CONSTRAINTS_SATISFIED,
                    (js.satisfiedConstraints & JobStatus.CONSTRAINT_APP_NOT_IDLE) != 0);
            proto.end(jsToken);
        });

        proto.end(mToken);
        proto.end(token);
    }

    void setAppIdleParoleOn(boolean isAppIdleParoleOn) {
        // Flag if any app's idle state has changed
        boolean changed = false;
        synchronized (mLock) {
            if (mAppIdleParoleOn == isAppIdleParoleOn) {
                return;
            }
            mAppIdleParoleOn = isAppIdleParoleOn;
            GlobalUpdateFunc update = new GlobalUpdateFunc();
            mJobSchedulerService.getJobStore().forEachJob(update);
            if (update.mChanged) {
                changed = true;
            }
        }
        if (changed) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    private final class AppIdleStateChangeListener
            extends UsageStatsManagerInternal.AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle, int bucket) {
            boolean changed = false;
            synchronized (mLock) {
                if (mAppIdleParoleOn) {
                    return;
                }

                PackageUpdateFunc update = new PackageUpdateFunc(userId, packageName, idle);
                mJobSchedulerService.getJobStore().forEachJob(update);
                if (update.mChanged) {
                    changed = true;
                }
            }
            if (changed) {
                mStateChangedListener.onControllerStateChanged();
            }
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            if (DEBUG) {
                Slog.d(TAG, "Parole on: " + isParoleOn);
            }
            setAppIdleParoleOn(isParoleOn);
        }
    }
}
