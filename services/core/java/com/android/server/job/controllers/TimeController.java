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

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.content.Context;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * This class sets an alarm for the next expiring job, and determines whether a job's minimum
 * delay has been satisfied.
 */
public class TimeController extends StateController {
    private static final String TAG = "JobScheduler.Time";

    /** Deadline alarm tag for logging purposes */
    private final String DEADLINE_TAG = "*job.deadline*";
    /** Delay alarm tag for logging purposes */
    private final String DELAY_TAG = "*job.delay*";

    private long mNextJobExpiredElapsedMillis;
    private long mNextDelayExpiredElapsedMillis;

    private AlarmManager mAlarmService = null;
    /** List of tracked jobs, sorted asc. by deadline */
    private final List<JobStatus> mTrackedJobs = new LinkedList<JobStatus>();
    /** Singleton. */
    private static TimeController mSingleton;

    public static synchronized TimeController get(JobSchedulerService jms) {
        if (mSingleton == null) {
            mSingleton = new TimeController(jms, jms.getContext(), jms.getLock());
        }
        return mSingleton;
    }

    private TimeController(StateChangedListener stateChangedListener, Context context,
                Object lock) {
        super(stateChangedListener, context, lock);

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
            maybeStopTrackingJobLocked(job, null, false);
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
            maybeUpdateAlarmsLocked(
                    job.hasTimingDelayConstraint() ? job.getEarliestRunTime() : Long.MAX_VALUE,
                    job.hasDeadlineConstraint() ? job.getLatestRunTimeElapsed() : Long.MAX_VALUE,
                    job.getSourceUid());
        }
    }

    /**
     * When we stop tracking a job, we only need to update our alarms if the job we're no longer
     * tracking was the one our alarms were based off of.
     * Really an == comparison should be enough, but why play with fate? We'll do <=.
     */
    @Override
    public void maybeStopTrackingJobLocked(JobStatus job, JobStatus incomingJob, boolean forUpdate) {
        if (mTrackedJobs.remove(job)) {
            checkExpiredDelaysAndResetAlarm();
            checkExpiredDeadlinesAndResetAlarm();
        }
    }

    /**
     * Determines whether this controller can stop tracking the given job.
     * The controller is no longer interested in a job once its time constraint is satisfied, and
     * the job's deadline is fulfilled - unlike other controllers a time constraint can't toggle
     * back and forth.
     */
    private boolean canStopTrackingJobLocked(JobStatus job) {
        return (!job.hasTimingDelayConstraint() ||
                (job.satisfiedConstraints&JobStatus.CONSTRAINT_TIMING_DELAY) != 0) &&
                (!job.hasDeadlineConstraint() ||
                        (job.satisfiedConstraints&JobStatus.CONSTRAINT_DEADLINE) != 0);
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
    private void checkExpiredDeadlinesAndResetAlarm() {
        synchronized (mLock) {
            long nextExpiryTime = Long.MAX_VALUE;
            int nextExpiryUid = 0;
            final long nowElapsedMillis = SystemClock.elapsedRealtime();

            Iterator<JobStatus> it = mTrackedJobs.iterator();
            while (it.hasNext()) {
                JobStatus job = it.next();
                if (!job.hasDeadlineConstraint()) {
                    continue;
                }
                final long jobDeadline = job.getLatestRunTimeElapsed();

                if (jobDeadline <= nowElapsedMillis) {
                    if (job.hasTimingDelayConstraint()) {
                        job.setTimingDelayConstraintSatisfied(true);
                    }
                    job.setDeadlineConstraintSatisfied(true);
                    mStateChangedListener.onRunJobNow(job);
                    it.remove();
                } else {  // Sorted by expiry time, so take the next one and stop.
                    nextExpiryTime = jobDeadline;
                    nextExpiryUid = job.getSourceUid();
                    break;
                }
            }
            setDeadlineExpiredAlarmLocked(nextExpiryTime, nextExpiryUid);
        }
    }

    /**
     * Handles alarm that notifies us that a job's delay has expired. Iterates through the list of
     * tracked jobs and marks them as ready as appropriate.
     */
    private void checkExpiredDelaysAndResetAlarm() {
        synchronized (mLock) {
            final long nowElapsedMillis = SystemClock.elapsedRealtime();
            long nextDelayTime = Long.MAX_VALUE;
            int nextDelayUid = 0;
            boolean ready = false;
            Iterator<JobStatus> it = mTrackedJobs.iterator();
            while (it.hasNext()) {
                final JobStatus job = it.next();
                if (!job.hasTimingDelayConstraint()) {
                    continue;
                }
                final long jobDelayTime = job.getEarliestRunTime();
                if (jobDelayTime <= nowElapsedMillis) {
                    job.setTimingDelayConstraintSatisfied(true);
                    if (canStopTrackingJobLocked(job)) {
                        it.remove();
                    }
                    if (job.isReady()) {
                        ready = true;
                    }
                } else if (!job.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY)) {
                    // If this job still doesn't have its delay constraint satisfied,
                    // then see if it is the next upcoming delay time for the alarm.
                    if (nextDelayTime > jobDelayTime) {
                        nextDelayTime = jobDelayTime;
                        nextDelayUid = job.getSourceUid();
                    }
                }
            }
            if (ready) {
                mStateChangedListener.onControllerStateChanged();
            }
            setDelayExpiredAlarmLocked(nextDelayTime, nextDelayUid);
        }
    }

    private void maybeUpdateAlarmsLocked(long delayExpiredElapsed, long deadlineExpiredElapsed,
            int uid) {
        if (delayExpiredElapsed < mNextDelayExpiredElapsedMillis) {
            setDelayExpiredAlarmLocked(delayExpiredElapsed, uid);
        }
        if (deadlineExpiredElapsed < mNextJobExpiredElapsedMillis) {
            setDeadlineExpiredAlarmLocked(deadlineExpiredElapsed, uid);
        }
    }

    /**
     * Set an alarm with the {@link android.app.AlarmManager} for the next time at which a job's
     * delay will expire.
     * This alarm <b>will</b> wake up the phone.
     */
    private void setDelayExpiredAlarmLocked(long alarmTimeElapsedMillis, int uid) {
        alarmTimeElapsedMillis = maybeAdjustAlarmTime(alarmTimeElapsedMillis);
        mNextDelayExpiredElapsedMillis = alarmTimeElapsedMillis;
        updateAlarmWithListenerLocked(DELAY_TAG, mNextDelayExpiredListener,
                mNextDelayExpiredElapsedMillis, uid);
    }

    /**
     * Set an alarm with the {@link android.app.AlarmManager} for the next time at which a job's
     * deadline will expire.
     * This alarm <b>will</b> wake up the phone.
     */
    private void setDeadlineExpiredAlarmLocked(long alarmTimeElapsedMillis, int uid) {
        alarmTimeElapsedMillis = maybeAdjustAlarmTime(alarmTimeElapsedMillis);
        mNextJobExpiredElapsedMillis = alarmTimeElapsedMillis;
        updateAlarmWithListenerLocked(DEADLINE_TAG, mDeadlineExpiredListener,
                mNextJobExpiredElapsedMillis, uid);
    }

    private long maybeAdjustAlarmTime(long proposedAlarmTimeElapsedMillis) {
        final long earliestWakeupTimeElapsed = SystemClock.elapsedRealtime();
        if (proposedAlarmTimeElapsedMillis < earliestWakeupTimeElapsed) {
            return earliestWakeupTimeElapsed;
        }
        return proposedAlarmTimeElapsedMillis;
    }

    private void updateAlarmWithListenerLocked(String tag, OnAlarmListener listener,
            long alarmTimeElapsed, int uid) {
        ensureAlarmServiceLocked();
        if (alarmTimeElapsed == Long.MAX_VALUE) {
            mAlarmService.cancel(listener);
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Setting " + tag + " for: " + alarmTimeElapsed);
            }
            mAlarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTimeElapsed,
                    AlarmManager.WINDOW_HEURISTIC, 0, tag, listener, null, new WorkSource(uid));
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
            checkExpiredDelaysAndResetAlarm();
        }
    };

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        final long nowElapsed = SystemClock.elapsedRealtime();
        pw.print("Alarms: now=");
        pw.print(SystemClock.elapsedRealtime());
        pw.println();
        pw.print("Next delay alarm in ");
        TimeUtils.formatDuration(mNextDelayExpiredElapsedMillis, nowElapsed, pw);
        pw.println();
        pw.print("Next deadline alarm in ");
        TimeUtils.formatDuration(mNextJobExpiredElapsedMillis, nowElapsed, pw);
        pw.println();
        pw.print("Tracking ");
        pw.print(mTrackedJobs.size());
        pw.println(":");
        for (JobStatus ts : mTrackedJobs) {
            if (!ts.shouldDump(filterUid)) {
                continue;
            }
            pw.print("  #");
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
}