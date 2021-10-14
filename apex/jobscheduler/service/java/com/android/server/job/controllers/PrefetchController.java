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
import static com.android.server.job.controllers.Package.packageToString;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.EstimatedLaunchTimeChangedListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
import com.android.internal.os.SomeArgs;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.utils.AlarmQueue;

import java.util.function.Predicate;

/**
 * Controller to delay prefetch jobs until we get close to an expected app launch.
 */
public class PrefetchController extends StateController {
    private static final String TAG = "JobScheduler.Prefetch";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final PcConstants mPcConstants;
    private final PcHandler mHandler;

    @GuardedBy("mLock")
    private final UsageStatsManagerInternal mUsageStatsManagerInternal;

    @GuardedBy("mLock")
    private final SparseArrayMap<String, ArraySet<JobStatus>> mTrackedJobs = new SparseArrayMap<>();
    /**
     * Cached set of the estimated next launch times of each app. Time are in the current time
     * millis ({@link CurrentTimeMillisLong}) timebase.
     */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, Long> mEstimatedLaunchTimes = new SparseArrayMap<>();
    private final ThresholdAlarmListener mThresholdAlarmListener;

    /**
     * The cutoff point to decide if a prefetch job is worth running or not. If the app is expected
     * to launch within this amount of time into the future, then we will let a prefetch job run.
     */
    @GuardedBy("mLock")
    @CurrentTimeMillisLong
    private long mLaunchTimeThresholdMs = PcConstants.DEFAULT_LAUNCH_TIME_THRESHOLD_MS;

    @SuppressWarnings("FieldCanBeLocal")
    private final EstimatedLaunchTimeChangedListener mEstimatedLaunchTimeChangedListener =
            new EstimatedLaunchTimeChangedListener() {
                @Override
                public void onEstimatedLaunchTimeChanged(int userId, @NonNull String packageName,
                        @CurrentTimeMillisLong long newEstimatedLaunchTime) {
                    final SomeArgs args = SomeArgs.obtain();
                    args.arg1 = packageName;
                    args.argi1 = userId;
                    args.argl1 = newEstimatedLaunchTime;
                    mHandler.obtainMessage(MSG_PROCESS_UPDATED_ESTIMATED_LAUNCH_TIME, args)
                            .sendToTarget();
                }
            };

    private static final int MSG_RETRIEVE_ESTIMATED_LAUNCH_TIME = 0;
    private static final int MSG_PROCESS_UPDATED_ESTIMATED_LAUNCH_TIME = 1;

    public PrefetchController(JobSchedulerService service) {
        super(service);
        mPcConstants = new PcConstants();
        mHandler = new PcHandler(mContext.getMainLooper());
        mThresholdAlarmListener = new ThresholdAlarmListener(
                mContext, JobSchedulerBackgroundThread.get().getLooper());
        mUsageStatsManagerInternal = LocalServices.getService(UsageStatsManagerInternal.class);

        mUsageStatsManagerInternal
                .registerLaunchTimeChangedListener(mEstimatedLaunchTimeChangedListener);
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
            final long now = sSystemClock.millis();
            final long nowElapsed = sElapsedRealtimeClock.millis();
            if (jobs.add(jobStatus) && jobs.size() == 1
                    && !willBeLaunchedSoonLocked(userId, pkgName, now)) {
                updateThresholdAlarmLocked(userId, pkgName, now, nowElapsed);
            }
            updateConstraintLocked(jobStatus, now, nowElapsed);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        final ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
        if (jobs != null && jobs.remove(jobStatus) && jobs.size() == 0) {
            mThresholdAlarmListener.removeAlarmForKey(new Package(userId, pkgName));
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
        mThresholdAlarmListener.removeAlarmForKey(new Package(userId, packageName));
    }

    @Override
    @GuardedBy("mLock")
    public void onUserRemovedLocked(int userId) {
        mTrackedJobs.delete(userId);
        mEstimatedLaunchTimes.delete(userId);
        mThresholdAlarmListener.removeAlarmsForUserId(userId);
    }

    /** Return the app's next estimated launch time. */
    @GuardedBy("mLock")
    @CurrentTimeMillisLong
    public long getNextEstimatedLaunchTimeLocked(@NonNull JobStatus jobStatus) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        return getNextEstimatedLaunchTimeLocked(userId, pkgName, sSystemClock.millis());
    }

    @GuardedBy("mLock")
    @CurrentTimeMillisLong
    private long getNextEstimatedLaunchTimeLocked(int userId, @NonNull String pkgName,
            @CurrentTimeMillisLong long now) {
        final Long nextEstimatedLaunchTime = mEstimatedLaunchTimes.get(userId, pkgName);
        if (nextEstimatedLaunchTime == null || nextEstimatedLaunchTime < now) {
            // Don't query usage stats here because it may have to read from disk.
            mHandler.obtainMessage(MSG_RETRIEVE_ESTIMATED_LAUNCH_TIME, userId, 0, pkgName)
                    .sendToTarget();
            // Store something in the cache so we don't keep posting retrieval messages.
            mEstimatedLaunchTimes.add(userId, pkgName, Long.MAX_VALUE);
            return Long.MAX_VALUE;
        }
        return nextEstimatedLaunchTime;
    }

    @GuardedBy("mLock")
    private boolean maybeUpdateConstraintForPkgLocked(@CurrentTimeMillisLong long now,
            @ElapsedRealtimeLong long nowElapsed, int userId, String pkgName) {
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

    private void processUpdatedEstimatedLaunchTime(int userId, @NonNull String pkgName,
            @CurrentTimeMillisLong long newEstimatedLaunchTime) {
        if (DEBUG) {
            Slog.d(TAG, "Estimated launch time for " + packageToString(userId, pkgName)
                    + " changed to " + newEstimatedLaunchTime
                    + " ("
                    + TimeUtils.formatDuration(newEstimatedLaunchTime - sSystemClock.millis())
                    + " from now)");
        }

        synchronized (mLock) {
            final ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
            if (jobs == null) {
                if (DEBUG) {
                    Slog.i(TAG,
                            "Not caching launch time since we haven't seen any prefetch"
                                    + " jobs for " + packageToString(userId, pkgName));
                }
            } else {
                // Don't bother caching the value unless the app has scheduled prefetch jobs
                // before. This is based on the assumption that if an app has scheduled a
                // prefetch job before, then it will probably schedule another one again.
                mEstimatedLaunchTimes.add(userId, pkgName, newEstimatedLaunchTime);

                if (!jobs.isEmpty()) {
                    final long now = sSystemClock.millis();
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    updateThresholdAlarmLocked(userId, pkgName, now, nowElapsed);
                    if (maybeUpdateConstraintForPkgLocked(now, nowElapsed, userId, pkgName)) {
                        mStateChangedListener.onControllerStateChanged(jobs);
                    }
                }
            }
        }
    }

    @GuardedBy("mLock")
    private boolean updateConstraintLocked(@NonNull JobStatus jobStatus,
            @CurrentTimeMillisLong long now, @ElapsedRealtimeLong long nowElapsed) {
        return jobStatus.setPrefetchConstraintSatisfied(nowElapsed,
                willBeLaunchedSoonLocked(
                        jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), now));
    }

    @GuardedBy("mLock")
    private void updateThresholdAlarmLocked(int userId, @NonNull String pkgName,
            @CurrentTimeMillisLong long now, @ElapsedRealtimeLong long nowElapsed) {
        final ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
        if (jobs == null || jobs.size() == 0) {
            mThresholdAlarmListener.removeAlarmForKey(new Package(userId, pkgName));
            return;
        }

        final long nextEstimatedLaunchTime = getNextEstimatedLaunchTimeLocked(userId, pkgName, now);
        if (nextEstimatedLaunchTime - now > mLaunchTimeThresholdMs) {
            // Set alarm to be notified when this crosses the threshold.
            final long timeToCrossThresholdMs =
                    nextEstimatedLaunchTime - (now + mLaunchTimeThresholdMs);
            mThresholdAlarmListener.addAlarm(new Package(userId, pkgName),
                    nowElapsed + timeToCrossThresholdMs);
        } else {
            mThresholdAlarmListener.removeAlarmForKey(new Package(userId, pkgName));
        }
    }

    /**
     * Returns true if the app is expected to be launched soon, where "soon" is within the next
     * {@link #mLaunchTimeThresholdMs} time.
     */
    @GuardedBy("mLock")
    private boolean willBeLaunchedSoonLocked(int userId, @NonNull String pkgName,
            @CurrentTimeMillisLong long now) {
        return getNextEstimatedLaunchTimeLocked(userId, pkgName, now)
                <= now + mLaunchTimeThresholdMs;
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
                            if (!willBeLaunchedSoonLocked(userId, packageName, now)) {
                                updateThresholdAlarmLocked(userId, packageName, now, nowElapsed);
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

    /** Track when apps will cross the "will run soon" threshold. */
    private class ThresholdAlarmListener extends AlarmQueue<Package> {
        private ThresholdAlarmListener(Context context, Looper looper) {
            super(context, looper, "*job.prefetch*", "Prefetch threshold", false,
                    PcConstants.DEFAULT_LAUNCH_TIME_THRESHOLD_MS / 10);
        }

        @Override
        protected boolean isForUser(@NonNull Package key, int userId) {
            return key.userId == userId;
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<Package> expired) {
            final ArraySet<JobStatus> changedJobs = new ArraySet<>();
            synchronized (mLock) {
                final long now = sSystemClock.millis();
                final long nowElapsed = sElapsedRealtimeClock.millis();
                for (int i = 0; i < expired.size(); ++i) {
                    Package p = expired.valueAt(i);
                    if (!willBeLaunchedSoonLocked(p.userId, p.packageName, now)) {
                        Slog.e(TAG, "Alarm expired for "
                                + packageToString(p.userId, p.packageName) + " at the wrong time");
                        updateThresholdAlarmLocked(p.userId, p.packageName, now, nowElapsed);
                    } else if (maybeUpdateConstraintForPkgLocked(
                            now, nowElapsed, p.userId, p.packageName)) {
                        changedJobs.addAll(mTrackedJobs.get(p.userId, p.packageName));
                    }
                }
            }
            if (changedJobs.size() > 0) {
                mStateChangedListener.onControllerStateChanged(changedJobs);
            }
        }
    }

    private class PcHandler extends Handler {
        PcHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RETRIEVE_ESTIMATED_LAUNCH_TIME:
                    final int userId = msg.arg1;
                    final String pkgName = (String) msg.obj;
                    // It's okay to get the time without holding the lock since all updates to
                    // the local cache go through the handler (and therefore will be sequential).
                    final long nextEstimatedLaunchTime = mUsageStatsManagerInternal
                            .getEstimatedPackageLaunchTime(pkgName, userId);
                    if (DEBUG) {
                        Slog.d(TAG, "Retrieved launch time for "
                                + packageToString(userId, pkgName)
                                + " of " + nextEstimatedLaunchTime
                                + " (" + TimeUtils.formatDuration(
                                        nextEstimatedLaunchTime - sSystemClock.millis())
                                + " from now)");
                    }
                    synchronized (mLock) {
                        final Long curEstimatedLaunchTime =
                                mEstimatedLaunchTimes.get(userId, pkgName);
                        if (curEstimatedLaunchTime == null
                                || nextEstimatedLaunchTime != curEstimatedLaunchTime) {
                            processUpdatedEstimatedLaunchTime(
                                    userId, pkgName, nextEstimatedLaunchTime);
                        }
                    }
                    break;

                case MSG_PROCESS_UPDATED_ESTIMATED_LAUNCH_TIME:
                    final SomeArgs args = (SomeArgs) msg.obj;
                    processUpdatedEstimatedLaunchTime(args.argi1, (String) args.arg1, args.argl1);
                    args.recycle();
                    break;
            }
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
                        // Give a leeway of 10% of the launch time threshold between alarms.
                        mThresholdAlarmListener.setMinTimeBetweenAlarmsMs(
                                mLaunchTimeThresholdMs / 10);
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

                pw.print(packageToString(userId, pkgName));
                pw.print(": ");
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

        pw.println();
        mThresholdAlarmListener.dump(pw);
    }

    @Override
    public void dumpConstants(IndentingPrintWriter pw) {
        mPcConstants.dump(pw);
    }
}
