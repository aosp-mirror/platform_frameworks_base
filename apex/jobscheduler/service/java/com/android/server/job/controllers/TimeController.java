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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.content.Context;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;

/**
 * This class sets an alarm for the next expiring job, and determines whether a job's minimum
 * delay has been satisfied.
 */
public final class TimeController extends StateController {
    private static final String TAG = "JobScheduler.Time";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final long DELAY_COALESCE_TIME_MS = 30_000L;

    /** Deadline alarm tag for logging purposes */
    private final String DEADLINE_TAG = "*job.deadline*";
    /** Delay alarm tag for logging purposes */
    private final String DELAY_TAG = "*job.delay*";

    private long mNextJobExpiredElapsedMillis;
    private long mNextDelayExpiredElapsedMillis;
    private volatile long mLastFiredDelayExpiredElapsedMillis;

    private AlarmManager mAlarmService = null;
    /** List of tracked jobs, sorted asc. by deadline */
    private final List<JobStatus> mTrackedJobs = new LinkedList<>();

    public TimeController(JobSchedulerService service) {
        super(service);

        mNextJobExpiredElapsedMillis = Long.MAX_VALUE;
        mNextDelayExpiredElapsedMillis = Long.MAX_VALUE;
    }

    /**
     * Check if the job has a timing constraint, and if so determine where to insert it in our
     * list.
     */
    @Override
    public void maybeStartTrackingJobLocked(JobStatus job, JobStatus lastJob) {
        if (job.hasTimingDelayConstraint() || job.hasDeadlineConstraint()) {
            maybeStopTrackingJobLocked(job, null);

            // First: check the constraints now, because if they are already satisfied
            // then there is no need to track it.  This gives us a fast path for a common
            // pattern of having a job with a 0 deadline constraint ("run immediately").
            // Unlike most controllers, once one of our constraints has been satisfied, it
            // will never be unsatisfied (our time base can not go backwards).
            final long nowElapsedMillis = sElapsedRealtimeClock.millis();
            if (job.hasDeadlineConstraint() && evaluateDeadlineConstraint(job, nowElapsedMillis)) {
                // We're intentionally excluding jobs whose deadlines have passed
                // from the job_scheduler.value_job_scheduler_job_deadline_expired_counter count
                // (mostly like deadlines of 0) when the job was scheduled.
                return;
            } else if (job.hasTimingDelayConstraint() && evaluateTimingDelayConstraint(job,
                    nowElapsedMillis)) {
                if (!job.hasDeadlineConstraint()) {
                    // If it doesn't have a deadline, we'll never have to touch it again.
                    return;
                }
            }

            boolean isInsert = false;
            ListIterator<JobStatus> it = mTrackedJobs.listIterator(mTrackedJobs.size());
            while (it.hasPrevious()) {
                JobStatus ts = it.previous();
                if (ts.getLatestRunTimeElapsed() < job.getLatestRunTimeElapsed()) {
                    // Insert
                    isInsert = true;
                    break;
                }
            }
            if (isInsert) {
                it.next();
            }
            it.add(job);

            job.setTrackingController(JobStatus.TRACKING_TIME);
            WorkSource ws =
                    mService.deriveWorkSource(job.getSourceUid(), job.getSourcePackageName());

            // Only update alarms if the job would be ready with the relevant timing constraint
            // satisfied.
            if (job.hasTimingDelayConstraint()
                    && wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_TIMING_DELAY)) {
                maybeUpdateDelayAlarmLocked(job.getEarliestRunTime(), ws);
            }
            if (job.hasDeadlineConstraint()
                    && wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_DEADLINE)) {
                maybeUpdateDeadlineAlarmLocked(job.getLatestRunTimeElapsed(), ws);
            }
        }
    }

    /**
     * When we stop tracking a job, we only need to update our alarms if the job we're no longer
     * tracking was the one our alarms were based off of.
     */
    @Override
    public void maybeStopTrackingJobLocked(JobStatus job, JobStatus incomingJob) {
        if (job.clearTrackingController(JobStatus.TRACKING_TIME)) {
            if (mTrackedJobs.remove(job)) {
                checkExpiredDelaysAndResetAlarm();
                checkExpiredDeadlinesAndResetAlarm();
            }
        }
    }

    @Override
    public void evaluateStateLocked(JobStatus job) {
        final long nowElapsedMillis = sElapsedRealtimeClock.millis();

        // Check deadline constraint first because if it's satisfied, we avoid a little bit of
        // unnecessary processing of the timing delay.
        if (job.hasDeadlineConstraint()
                && !job.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE)
                && job.getLatestRunTimeElapsed() <= mNextJobExpiredElapsedMillis) {
            if (evaluateDeadlineConstraint(job, nowElapsedMillis)) {
                if (job.isReady()) {
                    // If the job still isn't ready, there's no point trying to rush the
                    // Scheduler.
                    mStateChangedListener.onRunJobNow(job);
                }
                mTrackedJobs.remove(job);
                Counter.logIncrement(
                        "job_scheduler.value_job_scheduler_job_deadline_expired_counter");
            } else if (wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_DEADLINE)) {
                // This job's deadline is earlier than the current set alarm. Update the alarm.
                setDeadlineExpiredAlarmLocked(job.getLatestRunTimeElapsed(),
                        mService.deriveWorkSource(job.getSourceUid(), job.getSourcePackageName()));
            }
        }
        if (job.hasTimingDelayConstraint()
                && !job.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY)
                && job.getEarliestRunTime() <= mNextDelayExpiredElapsedMillis) {
            // Since this is just the delay, we don't need to rush the Scheduler to run the job
            // immediately if the constraint is satisfied here.
            if (evaluateTimingDelayConstraint(job, nowElapsedMillis)) {
                if (canStopTrackingJobLocked(job)) {
                    mTrackedJobs.remove(job);
                }
            } else if (wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_TIMING_DELAY)) {
                // This job's delay is earlier than the current set alarm. Update the alarm.
                setDelayExpiredAlarmLocked(job.getEarliestRunTime(),
                        mService.deriveWorkSource(job.getSourceUid(), job.getSourcePackageName()));
            }
        }
    }

    @Override
    public void reevaluateStateLocked(int uid) {
        checkExpiredDeadlinesAndResetAlarm();
        checkExpiredDelaysAndResetAlarm();
    }

    /**
     * Determines whether this controller can stop tracking the given job.
     * The controller is no longer interested in a job once its time constraint is satisfied, and
     * the job's deadline is fulfilled - unlike other controllers a time constraint can't toggle
     * back and forth.
     */
    private boolean canStopTrackingJobLocked(JobStatus job) {
        return (!job.hasTimingDelayConstraint()
                        || job.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY))
                && (!job.hasDeadlineConstraint()
                        || job.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
    }

    private void ensureAlarmServiceLocked() {
        if (mAlarmService == null) {
            mAlarmService = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        }
    }

    /**
     * Checks list of jobs for ones that have an expired deadline, sending them to the JobScheduler
     * if so, removing them from this list, and updating the alarm for the next expiry time.
     */
    @VisibleForTesting
    void checkExpiredDeadlinesAndResetAlarm() {
        synchronized (mLock) {
            long nextExpiryTime = Long.MAX_VALUE;
            int nextExpiryUid = 0;
            String nextExpiryPackageName = null;
            final long nowElapsedMillis = sElapsedRealtimeClock.millis();

            ListIterator<JobStatus> it = mTrackedJobs.listIterator();
            while (it.hasNext()) {
                JobStatus job = it.next();
                if (!job.hasDeadlineConstraint()) {
                    continue;
                }

                if (evaluateDeadlineConstraint(job, nowElapsedMillis)) {
                    if (job.isReady()) {
                        // If the job still isn't ready, there's no point trying to rush the
                        // Scheduler.
                        mStateChangedListener.onRunJobNow(job);
                    }
                    Counter.logIncrement(
                            "job_scheduler.value_job_scheduler_job_deadline_expired_counter");
                    it.remove();
                } else {  // Sorted by expiry time, so take the next one and stop.
                    if (!wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_DEADLINE)) {
                        if (DEBUG) {
                            Slog.i(TAG,
                                    "Skipping " + job + " because deadline won't make it ready.");
                        }
                        continue;
                    }
                    nextExpiryTime = job.getLatestRunTimeElapsed();
                    nextExpiryUid = job.getSourceUid();
                    nextExpiryPackageName = job.getSourcePackageName();
                    break;
                }
            }
            setDeadlineExpiredAlarmLocked(nextExpiryTime,
                    mService.deriveWorkSource(nextExpiryUid, nextExpiryPackageName));
        }
    }

    /** @return true if the job's deadline constraint is satisfied */
    private boolean evaluateDeadlineConstraint(JobStatus job, long nowElapsedMillis) {
        final long jobDeadline = job.getLatestRunTimeElapsed();

        if (jobDeadline <= nowElapsedMillis) {
            if (job.hasTimingDelayConstraint()) {
                job.setTimingDelayConstraintSatisfied(nowElapsedMillis, true);
            }
            job.setDeadlineConstraintSatisfied(nowElapsedMillis, true);
            return true;
        }
        return false;
    }

    /**
     * Handles alarm that notifies us that a job's delay has expired. Iterates through the list of
     * tracked jobs and marks them as ready as appropriate.
     */
    @VisibleForTesting
    void checkExpiredDelaysAndResetAlarm() {
        synchronized (mLock) {
            long nextDelayTime = Long.MAX_VALUE;
            int nextDelayUid = 0;
            String nextDelayPackageName = null;
            final ArraySet<JobStatus> changedJobs = new ArraySet<>();
            Iterator<JobStatus> it = mTrackedJobs.iterator();
            final long nowElapsedMillis = sElapsedRealtimeClock.millis();
            while (it.hasNext()) {
                final JobStatus job = it.next();
                if (!job.hasTimingDelayConstraint()) {
                    continue;
                }
                if (evaluateTimingDelayConstraint(job, nowElapsedMillis)) {
                    if (canStopTrackingJobLocked(job)) {
                        it.remove();
                    }
                    changedJobs.add(job);
                } else {
                    if (!wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_TIMING_DELAY)) {
                        if (DEBUG) {
                            Slog.i(TAG, "Skipping " + job + " because delay won't make it ready.");
                        }
                        continue;
                    }
                    // If this job still doesn't have its delay constraint satisfied,
                    // then see if it is the next upcoming delay time for the alarm.
                    final long jobDelayTime = job.getEarliestRunTime();
                    if (nextDelayTime > jobDelayTime) {
                        nextDelayTime = jobDelayTime;
                        nextDelayUid = job.getSourceUid();
                        nextDelayPackageName = job.getSourcePackageName();
                    }
                }
            }
            if (changedJobs.size() > 0) {
                mStateChangedListener.onControllerStateChanged(changedJobs);
            }
            setDelayExpiredAlarmLocked(nextDelayTime,
                    mService.deriveWorkSource(nextDelayUid, nextDelayPackageName));
        }
    }

    /** @return true if the job's delay constraint is satisfied */
    private boolean evaluateTimingDelayConstraint(JobStatus job, long nowElapsedMillis) {
        final long jobDelayTime = job.getEarliestRunTime();
        if (jobDelayTime <= nowElapsedMillis) {
            job.setTimingDelayConstraintSatisfied(nowElapsedMillis, true);
            return true;
        }
        return false;
    }

    private void maybeUpdateDelayAlarmLocked(long delayExpiredElapsed, WorkSource ws) {
        if (delayExpiredElapsed < mNextDelayExpiredElapsedMillis) {
            setDelayExpiredAlarmLocked(delayExpiredElapsed, ws);
        }
    }

    private void maybeUpdateDeadlineAlarmLocked(long deadlineExpiredElapsed, WorkSource ws) {
        if (deadlineExpiredElapsed < mNextJobExpiredElapsedMillis) {
            setDeadlineExpiredAlarmLocked(deadlineExpiredElapsed, ws);
        }
    }

    /**
     * Set an alarm with the {@link android.app.AlarmManager} for the next time at which a job's
     * delay will expire.
     * This alarm <b>will not</b> wake up the phone.
     */
    private void setDelayExpiredAlarmLocked(long alarmTimeElapsedMillis, WorkSource ws) {
        // To avoid spamming AlarmManager in the case where many delay times are a few milliseconds
        // apart, make sure the alarm is set no earlier than DELAY_COALESCE_TIME_MS since the last
        // time a delay alarm went off and that the alarm is not scheduled for the past.
        alarmTimeElapsedMillis = maybeAdjustAlarmTime(Math.max(alarmTimeElapsedMillis,
                mLastFiredDelayExpiredElapsedMillis + DELAY_COALESCE_TIME_MS));
        if (mNextDelayExpiredElapsedMillis == alarmTimeElapsedMillis) {
            return;
        }
        mNextDelayExpiredElapsedMillis = alarmTimeElapsedMillis;
        updateAlarmWithListenerLocked(DELAY_TAG, AlarmManager.ELAPSED_REALTIME,
                mNextDelayExpiredListener, mNextDelayExpiredElapsedMillis, ws);
    }

    /**
     * Set an alarm with the {@link android.app.AlarmManager} for the next time at which a job's
     * deadline will expire.
     * This alarm <b>will</b> wake up the phone.
     */
    private void setDeadlineExpiredAlarmLocked(long alarmTimeElapsedMillis, WorkSource ws) {
        alarmTimeElapsedMillis = maybeAdjustAlarmTime(alarmTimeElapsedMillis);
        if (mNextJobExpiredElapsedMillis == alarmTimeElapsedMillis) {
            return;
        }
        mNextJobExpiredElapsedMillis = alarmTimeElapsedMillis;
        updateAlarmWithListenerLocked(DEADLINE_TAG, AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mDeadlineExpiredListener, mNextJobExpiredElapsedMillis, ws);
    }

    private long maybeAdjustAlarmTime(long proposedAlarmTimeElapsedMillis) {
        return Math.max(proposedAlarmTimeElapsedMillis, sElapsedRealtimeClock.millis());
    }

    private void updateAlarmWithListenerLocked(String tag, @AlarmManager.AlarmType int alarmType,
            OnAlarmListener listener, long alarmTimeElapsed, WorkSource ws) {
        ensureAlarmServiceLocked();
        if (alarmTimeElapsed == Long.MAX_VALUE) {
            mAlarmService.cancel(listener);
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Setting " + tag + " for: " + alarmTimeElapsed);
            }
            mAlarmService.set(alarmType, alarmTimeElapsed,
                    AlarmManager.WINDOW_HEURISTIC, 0, tag, listener,
                    AppSchedulingModuleThread.getHandler(), ws);
        }
    }

    // Job/delay expiration alarm handling

    private final OnAlarmListener mDeadlineExpiredListener = new OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Slog.d(TAG, "Deadline-expired alarm fired");
            }
            checkExpiredDeadlinesAndResetAlarm();
        }
    };

    private final OnAlarmListener mNextDelayExpiredListener = new OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Slog.d(TAG, "Delay-expired alarm fired");
            }
            mLastFiredDelayExpiredElapsedMillis = sElapsedRealtimeClock.millis();
            checkExpiredDelaysAndResetAlarm();
        }
    };

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        pw.println("Elapsed clock: " + nowElapsed);

        pw.print("Next delay alarm in ");
        TimeUtils.formatDuration(mNextDelayExpiredElapsedMillis, nowElapsed, pw);
        pw.println();
        pw.print("Last delay alarm fired @ ");
        TimeUtils.formatDuration(nowElapsed, mLastFiredDelayExpiredElapsedMillis, pw);
        pw.println();
        pw.print("Next deadline alarm in ");
        TimeUtils.formatDuration(mNextJobExpiredElapsedMillis, nowElapsed, pw);
        pw.println();
        pw.println();

        for (JobStatus ts : mTrackedJobs) {
            if (!predicate.test(ts)) {
                continue;
            }
            pw.print("#");
            ts.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, ts.getSourceUid());
            pw.print(": Delay=");
            if (ts.hasTimingDelayConstraint()) {
                TimeUtils.formatDuration(ts.getEarliestRunTime(), nowElapsed, pw);
            } else {
                pw.print("N/A");
            }
            pw.print(", Deadline=");
            if (ts.hasDeadlineConstraint()) {
                TimeUtils.formatDuration(ts.getLatestRunTimeElapsed(), nowElapsed, pw);
            } else {
                pw.print("N/A");
            }
            pw.println();
        }
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.TIME);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        proto.write(StateControllerProto.TimeController.NOW_ELAPSED_REALTIME, nowElapsed);
        proto.write(StateControllerProto.TimeController.TIME_UNTIL_NEXT_DELAY_ALARM_MS,
                mNextDelayExpiredElapsedMillis - nowElapsed);
        proto.write(StateControllerProto.TimeController.TIME_UNTIL_NEXT_DEADLINE_ALARM_MS,
                mNextJobExpiredElapsedMillis - nowElapsed);

        for (JobStatus ts : mTrackedJobs) {
            if (!predicate.test(ts)) {
                continue;
            }
            final long tsToken = proto.start(StateControllerProto.TimeController.TRACKED_JOBS);
            ts.writeToShortProto(proto, StateControllerProto.TimeController.TrackedJob.INFO);

            proto.write(StateControllerProto.TimeController.TrackedJob.HAS_TIMING_DELAY_CONSTRAINT,
                    ts.hasTimingDelayConstraint());
            proto.write(StateControllerProto.TimeController.TrackedJob.DELAY_TIME_REMAINING_MS,
                    ts.getEarliestRunTime() - nowElapsed);

            proto.write(StateControllerProto.TimeController.TrackedJob.HAS_DEADLINE_CONSTRAINT,
                    ts.hasDeadlineConstraint());
            proto.write(StateControllerProto.TimeController.TrackedJob.TIME_REMAINING_UNTIL_DEADLINE_MS,
                    ts.getLatestRunTimeElapsed() - nowElapsed);

            proto.end(tsToken);
        }

        proto.end(mToken);
        proto.end(token);
    }
}
