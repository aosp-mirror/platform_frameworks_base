/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.job.controllers;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.JobSchedulerService.sSystemClock;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.job.JobSchedulerService;

import java.util.function.Predicate;

/**
 * Controller to delay prefetch jobs until we get close to an expected app launch.
 */
public class PrefetchController extends StateController {
    private static final String TAG = "JobScheduler.Prefetch";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final PcConstants mPcConstants;

    @GuardedBy("mLock")
    private final SparseArrayMap<String, ArraySet<JobStatus>> mTrackedJobs = new SparseArrayMap<>();
    /**
     * Cached set of the estimated next launch times of each app. Time are in the current time
     * millis ({@link CurrentTimeMillisLong}) timebase.
     */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, Long> mEstimatedLaunchTimes = new SparseArrayMap<>();

    /**
     * The cutoff point to decide if a prefetch job is worth running or not. If the app is expected
     * to launch within this amount of time into the future, then we will let a prefetch job run.
     */
    @GuardedBy("mLock")
    @CurrentTimeMillisLong
    private long mLaunchTimeThresholdMs = PcConstants.DEFAULT_LAUNCH_TIME_THRESHOLD_MS;

    public PrefetchController(JobSchedulerService service) {
        super(service);
        mPcConstants = new PcConstants();
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.getJob().isPrefetch()) {
            final int userId = jobStatus.getSourceUserId();
            final String pkgName = jobStatus.getSourcePackageName();
            ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
            if (jobs == null) {
                jobs = new ArraySet<>();
                mTrackedJobs.add(userId, pkgName, jobs);
            }
            jobs.add(jobStatus);
            updateConstraintLocked(jobStatus,
                    sSystemClock.millis(), sElapsedRealtimeClock.millis());
        }
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        final ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUserId(),
                jobStatus.getSourcePackageName());
        if (jobs != null) {
            jobs.remove(jobStatus);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onAppRemovedLocked(String packageName, int uid) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }
        final int userId = UserHandle.getUserId(uid);
        mTrackedJobs.delete(userId, packageName);
        mEstimatedLaunchTimes.delete(userId, packageName);
    }

    @Override
    @GuardedBy("mLock")
    public void onUserRemovedLocked(int userId) {
        mTrackedJobs.delete(userId);
        mEstimatedLaunchTimes.delete(userId);
    }

    /** Return the app's next estimated launch time. */
    @GuardedBy("mLock")
    @CurrentTimeMillisLong
    public long getNextEstimatedLaunchTimeLocked(@NonNull JobStatus jobStatus) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        Long nextEstimatedLaunchTime = mEstimatedLaunchTimes.get(userId, pkgName);
        final long now = sSystemClock.millis();
        if (nextEstimatedLaunchTime == null || nextEstimatedLaunchTime < now) {
            // TODO(194532703): get estimated time from UsageStats
            nextEstimatedLaunchTime = now + 2 * HOUR_IN_MILLIS;
            mEstimatedLaunchTimes.add(userId, pkgName, nextEstimatedLaunchTime);
        }
        return nextEstimatedLaunchTime;
    }

    @GuardedBy("mLock")
    private boolean maybeUpdateConstraintForPkgLocked(long now, long nowElapsed, int userId,
            String pkgName) {
        final ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
        if (jobs == null) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < jobs.size(); i++) {
            final JobStatus js = jobs.valueAt(i);
            changed |= updateConstraintLocked(js, now, nowElapsed);
        }
        return changed;
    }

    @GuardedBy("mLock")
    private boolean updateConstraintLocked(@NonNull JobStatus jobStatus, long now,
            long nowElapsed) {
        return jobStatus.setPrefetchConstraintSatisfied(nowElapsed,
                getNextEstimatedLaunchTimeLocked(jobStatus) <= now + mLaunchTimeThresholdMs);
    }

    @Override
    @GuardedBy("mLock")
    public void prepareForUpdatedConstantsLocked() {
        mPcConstants.mShouldReevaluateConstraints = false;
    }

    @Override
    @GuardedBy("mLock")
    public void processConstantLocked(DeviceConfig.Properties properties, String key) {
        mPcConstants.processConstantLocked(properties, key);
    }

    @Override
    @GuardedBy("mLock")
    public void onConstantsUpdatedLocked() {
        if (mPcConstants.mShouldReevaluateConstraints) {
            // Update job bookkeeping out of band.
            JobSchedulerBackgroundThread.getHandler().post(() -> {
                final ArraySet<JobStatus> changedJobs = new ArraySet<>();
                synchronized (mLock) {
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    final long now = sSystemClock.millis();
                    for (int u = 0; u < mTrackedJobs.numMaps(); ++u) {
                        final int userId = mTrackedJobs.keyAt(u);
                        for (int p = 0; p < mTrackedJobs.numElementsForKey(userId); ++p) {
                            final String packageName = mTrackedJobs.keyAt(u, p);
                            if (maybeUpdateConstraintForPkgLocked(
                                    now, nowElapsed, userId, packageName)) {
                                changedJobs.addAll(mTrackedJobs.valueAt(u, p));
                            }
                        }
                    }
                }
                if (changedJobs.size() > 0) {
                    mStateChangedListener.onControllerStateChanged(changedJobs);
                }
            });
        }
    }

    @VisibleForTesting
    class PcConstants {
        private boolean mShouldReevaluateConstraints = false;

        /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
        private static final String PC_CONSTANT_PREFIX = "pc_";

        @VisibleForTesting
        static final String KEY_LAUNCH_TIME_THRESHOLD_MS =
                PC_CONSTANT_PREFIX + "launch_time_threshold_ms";

        private static final long DEFAULT_LAUNCH_TIME_THRESHOLD_MS = 7 * HOUR_IN_MILLIS;

        /** How much time each app will have to run jobs within their standby bucket window. */
        public long LAUNCH_TIME_THRESHOLD_MS = DEFAULT_LAUNCH_TIME_THRESHOLD_MS;

        @GuardedBy("mLock")
        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_LAUNCH_TIME_THRESHOLD_MS:
                    LAUNCH_TIME_THRESHOLD_MS =
                            properties.getLong(key, DEFAULT_LAUNCH_TIME_THRESHOLD_MS);
                    // Limit the threshold to the range [1, 24] hours.
                    long newLaunchTimeThresholdMs = Math.min(24 * HOUR_IN_MILLIS,
                            Math.max(HOUR_IN_MILLIS, LAUNCH_TIME_THRESHOLD_MS));
                    if (mLaunchTimeThresholdMs != newLaunchTimeThresholdMs) {
                        mLaunchTimeThresholdMs = newLaunchTimeThresholdMs;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
            }
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.print(PrefetchController.class.getSimpleName());
            pw.println(":");
            pw.increaseIndent();

            pw.print(KEY_LAUNCH_TIME_THRESHOLD_MS, LAUNCH_TIME_THRESHOLD_MS).println();

            pw.decreaseIndent();
        }
    }

    //////////////////////// TESTING HELPERS /////////////////////////////

    @VisibleForTesting
    long getLaunchTimeThresholdMs() {
        return mLaunchTimeThresholdMs;
    }

    @VisibleForTesting
    @NonNull
    PcConstants getPcConstants() {
        return mPcConstants;
    }

    //////////////////////////// DATA DUMP //////////////////////////////

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        final long now = sSystemClock.millis();

        pw.println("Cached launch times:");
        pw.increaseIndent();
        for (int u = 0; u < mEstimatedLaunchTimes.numMaps(); ++u) {
            final int userId = mEstimatedLaunchTimes.keyAt(u);
            for (int p = 0; p < mEstimatedLaunchTimes.numElementsForKey(userId); ++p) {
                final String pkgName = mEstimatedLaunchTimes.keyAt(u, p);
                final long estimatedLaunchTime = mEstimatedLaunchTimes.valueAt(u, p);

                pw.print("<" + userId + ">" + pkgName + ": ");
                pw.print(estimatedLaunchTime);
                pw.print(" (");
                TimeUtils.formatDuration(estimatedLaunchTime - now, pw,
                        TimeUtils.HUNDRED_DAY_FIELD_LEN);
                pw.println(" from now)");
            }
        }
        pw.decreaseIndent();

        pw.println();
        mTrackedJobs.forEach((jobs) -> {
            for (int j = 0; j < jobs.size(); j++) {
                final JobStatus js = jobs.valueAt(j);
                if (!predicate.test(js)) {
                    continue;
                }
                pw.print("#");
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                pw.println();
            }
        });
    }

    @Override
    public void dumpConstants(IndentingPrintWriter pw) {
        mPcConstants.dump(pw);
    }
}
