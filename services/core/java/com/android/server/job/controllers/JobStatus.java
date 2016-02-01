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

import android.app.AppGlobals;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uniquely identifies a job internally.
 * Created from the public {@link android.app.job.JobInfo} object when it lands on the scheduler.
 * Contains current state of the requirements of the job, as well as a function to evaluate
 * whether it's ready to run.
 * This object is shared among the various controllers - hence why the different fields are atomic.
 * This isn't strictly necessary because each controller is only interested in a specific field,
 * and the receivers that are listening for global state change will all run on the main looper,
 * but we don't enforce that so this is safer.
 * @hide
 */
public class JobStatus {
    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final long NO_EARLIEST_RUNTIME = 0L;

    final JobInfo job;
    /** Uid of the package requesting this job. */
    final int uId;
    final String name;
    final String tag;

    String sourcePackageName;
    int sourceUserId = -1;
    int sourceUid = -1;

    // Constraints.
    final AtomicBoolean chargingConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean timeDelayConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean deadlineConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean idleConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean unmeteredConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean connectivityConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean appNotIdleConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean contentTriggerConstraintSatisfied = new AtomicBoolean();

    // These are filled in by controllers when preparing for execution.
    public ArraySet<Uri> changedUris;
    public ArraySet<String> changedAuthorities;

    /**
     * For use only by ContentObserverController: state it is maintaining about content URIs
     * being observed.
     */
    ContentObserverController.JobInstance contentObserverJobInstance;

    /**
     * Earliest point in the future at which this job will be eligible to run. A value of 0
     * indicates there is no delay constraint. See {@link #hasTimingDelayConstraint()}.
     */
    private long earliestRunTimeElapsedMillis;
    /**
     * Latest point in the future at which this job must be run. A value of {@link Long#MAX_VALUE}
     * indicates there is no deadline constraint. See {@link #hasDeadlineConstraint()}.
     */
    private long latestRunTimeElapsedMillis;
    /** How many times this job has failed, used to compute back-off. */
    private final int numFailures;

    /** Provide a handle to the service that this job will be run on. */
    public int getServiceToken() {
        return uId;
    }

    private JobStatus(JobInfo job, int uId, int numFailures) {
        this.job = job;
        this.uId = uId;
        this.sourceUid = uId;
        this.name = job.getService().flattenToShortString();
        this.tag = "*job*/" + this.name;
        this.numFailures = numFailures;
    }

    /** Copy constructor. */
    public JobStatus(JobStatus jobStatus) {
        this(jobStatus.getJob(), jobStatus.getUid(), jobStatus.getNumFailures());
        this.sourceUserId = jobStatus.sourceUserId;
        this.sourcePackageName = jobStatus.sourcePackageName;
        this.earliestRunTimeElapsedMillis = jobStatus.getEarliestRunTime();
        this.latestRunTimeElapsedMillis = jobStatus.getLatestRunTimeElapsed();
    }

    /** Create a newly scheduled job. */
    public JobStatus(JobInfo job, int uId) {
        this(job, uId, 0);

        final long elapsedNow = SystemClock.elapsedRealtime();

        if (job.isPeriodic()) {
            latestRunTimeElapsedMillis = elapsedNow + job.getIntervalMillis();
            earliestRunTimeElapsedMillis = latestRunTimeElapsedMillis - job.getFlexMillis();
        } else {
            earliestRunTimeElapsedMillis = job.hasEarlyConstraint() ?
                    elapsedNow + job.getMinLatencyMillis() : NO_EARLIEST_RUNTIME;
            latestRunTimeElapsedMillis = job.hasLateConstraint() ?
                    elapsedNow + job.getMaxExecutionDelayMillis() : NO_LATEST_RUNTIME;
        }
    }

    /**
     * Create a new JobStatus that was loaded from disk. We ignore the provided
     * {@link android.app.job.JobInfo} time criteria because we can load a persisted periodic job
     * from the {@link com.android.server.job.JobStore} and still want to respect its
     * wallclock runtime rather than resetting it on every boot.
     * We consider a freshly loaded job to no longer be in back-off.
     */
    public JobStatus(JobInfo job, int uId, long earliestRunTimeElapsedMillis,
                      long latestRunTimeElapsedMillis) {
        this(job, uId, 0);

        this.earliestRunTimeElapsedMillis = earliestRunTimeElapsedMillis;
        this.latestRunTimeElapsedMillis = latestRunTimeElapsedMillis;
    }

    /** Create a new job to be rescheduled with the provided parameters. */
    public JobStatus(JobStatus rescheduling, long newEarliestRuntimeElapsedMillis,
                      long newLatestRuntimeElapsedMillis, int backoffAttempt) {
        this(rescheduling.job, rescheduling.getUid(), backoffAttempt);
        this.sourceUserId = rescheduling.sourceUserId;
        this.sourcePackageName = rescheduling.sourcePackageName;

        earliestRunTimeElapsedMillis = newEarliestRuntimeElapsedMillis;
        latestRunTimeElapsedMillis = newLatestRuntimeElapsedMillis;
    }

    public JobInfo getJob() {
        return job;
    }

    public int getJobId() {
        return job.getId();
    }

    public int getNumFailures() {
        return numFailures;
    }

    public ComponentName getServiceComponent() {
        return job.getService();
    }

    public String getSourcePackageName() {
        return sourcePackageName != null ? sourcePackageName : job.getService().getPackageName();
    }

    public int getSourceUid() {
        return sourceUid;
    }

    public int getSourceUserId() {
        if (sourceUserId == -1) {
            sourceUserId = getUserId();
        }
        return sourceUserId;
    }

    public int getUserId() {
        return UserHandle.getUserId(uId);
    }

    public int getUid() {
        return uId;
    }

    public String getName() {
        return name;
    }

    public String getTag() {
        return tag;
    }

    public PersistableBundle getExtras() {
        return job.getExtras();
    }

    public int getPriority() {
        return job.getPriority();
    }

    public boolean hasConnectivityConstraint() {
        return job.getNetworkType() == JobInfo.NETWORK_TYPE_ANY;
    }

    public boolean hasUnmeteredConstraint() {
        return job.getNetworkType() == JobInfo.NETWORK_TYPE_UNMETERED;
    }

    public boolean hasChargingConstraint() {
        return job.isRequireCharging();
    }

    public boolean hasTimingDelayConstraint() {
        return earliestRunTimeElapsedMillis != NO_EARLIEST_RUNTIME;
    }

    public boolean hasDeadlineConstraint() {
        return latestRunTimeElapsedMillis != NO_LATEST_RUNTIME;
    }

    public boolean hasIdleConstraint() {
        return job.isRequireDeviceIdle();
    }

    public boolean hasContentTriggerConstraint() {
        return job.getTriggerContentUris() != null;
    }

    public boolean isPersisted() {
        return job.isPersisted();
    }

    public long getEarliestRunTime() {
        return earliestRunTimeElapsedMillis;
    }

    public long getLatestRunTimeElapsed() {
        return latestRunTimeElapsedMillis;
    }

    /**
     * @return Whether or not this job is ready to run, based on its requirements. This is true if
     * the constraints are satisfied <strong>or</strong> the deadline on the job has expired.
     */
    public synchronized boolean isReady() {
        // Deadline constraint trumps other constraints (except for periodic jobs where deadline
        // (is an implementation detail. A periodic job should only run if it's constraints are
        // satisfied).
        // AppNotIdle implicit constraint trumps all!
        return (isConstraintsSatisfied()
                    || (!job.isPeriodic()
                            && hasDeadlineConstraint() && deadlineConstraintSatisfied.get()))
                && appNotIdleConstraintSatisfied.get();
    }

    /**
     * @return Whether the constraints set on this job are satisfied.
     */
    public synchronized boolean isConstraintsSatisfied() {
        return (!hasChargingConstraint() || chargingConstraintSatisfied.get())
                && (!hasTimingDelayConstraint() || timeDelayConstraintSatisfied.get())
                && (!hasConnectivityConstraint() || connectivityConstraintSatisfied.get())
                && (!hasUnmeteredConstraint() || unmeteredConstraintSatisfied.get())
                && (!hasIdleConstraint() || idleConstraintSatisfied.get())
                && (!hasContentTriggerConstraint() || contentTriggerConstraintSatisfied.get());
    }

    public boolean matches(int uid, int jobId) {
        return this.job.getId() == jobId && this.uId == uid;
    }

    @Override
    public String toString() {
        return String.valueOf(hashCode()).substring(0, 3) + ".."
                + ":[" + job.getService()
                + ",jId=" + job.getId()
                + ",u" + getUserId()
                + ",R=(" + formatRunTime(earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME)
                + "," + formatRunTime(latestRunTimeElapsedMillis, NO_LATEST_RUNTIME) + ")"
                + ",N=" + job.getNetworkType() + ",C=" + job.isRequireCharging()
                + ",I=" + job.isRequireDeviceIdle()
                + ",U=" + (job.getTriggerContentUris() != null)
                + ",F=" + numFailures + ",P=" + job.isPersisted()
                + ",ANI=" + appNotIdleConstraintSatisfied.get()
                + (isReady() ? "(READY)" : "")
                + "]";
    }

    private String formatRunTime(long runtime, long  defaultValue) {
        if (runtime == defaultValue) {
            return "none";
        } else {
            long elapsedNow = SystemClock.elapsedRealtime();
            long nextRuntime = runtime - elapsedNow;
            if (nextRuntime > 0) {
                return DateUtils.formatElapsedTime(nextRuntime / 1000);
            } else {
                return "-" + DateUtils.formatElapsedTime(nextRuntime / -1000);
            }
        }
    }

    public void setSource(String sourcePackageName, int sourceUserId) {
        this.sourcePackageName = sourcePackageName;
        this.sourceUserId = sourceUserId;
        try {
            sourceUid = AppGlobals.getPackageManager().getPackageUid(sourcePackageName, 0,
                    sourceUserId);
        } catch (RemoteException ex) {
            // Can't happen, PackageManager runs in the same process.
        }
        if (sourceUid == -1) {
            sourceUid = uId;
            this.sourceUserId = getUserId();
            this.sourcePackageName = null;
        }
    }

    /**
     * Convenience function to identify a job uniquely without pulling all the data that
     * {@link #toString()} returns.
     */
    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" jId=");
        sb.append(job.getId());
        sb.append(" uid=");
        UserHandle.formatUid(sb, uId);
        sb.append(' ');
        sb.append(job.getService().flattenToShortString());
        return sb.toString();
    }

    // Dumpsys infrastructure
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); UserHandle.formatUid(pw, uId);
        pw.print(" tag="); pw.println(tag);
        pw.print(prefix);
        pw.print("Source: uid="); UserHandle.formatUid(pw, sourceUid);
        pw.print(" user="); pw.print(sourceUserId);
        pw.print(" pkg="); pw.println(sourcePackageName);
        pw.print(prefix); pw.println("JobInfo:");
        pw.print(prefix); pw.print("  Service: ");
        pw.println(job.getService().flattenToShortString());
        if (job.isPeriodic()) {
            pw.print(prefix); pw.print("  PERIODIC: interval=");
            TimeUtils.formatDuration(job.getIntervalMillis(), pw);
            pw.print(" flex=");
            TimeUtils.formatDuration(job.getFlexMillis(), pw);
            pw.println();
        }
        if (job.isPersisted()) {
            pw.print(prefix); pw.println("  PERSISTED");
        }
        if (job.getPriority() != 0) {
            pw.print(prefix); pw.print("  Priority: ");
            pw.println(job.getPriority());
        }
        pw.print(prefix); pw.print("  Requires: charging=");
        pw.print(job.isRequireCharging());
        pw.print(" deviceIdle=");
        pw.println(job.isRequireDeviceIdle());
        if (job.getTriggerContentUris() != null) {
            pw.print(prefix); pw.println("  Trigger content URIs:");
            for (int i=0; i<job.getTriggerContentUris().length; i++) {
                JobInfo.TriggerContentUri trig = job.getTriggerContentUris()[i];
                pw.print(prefix); pw.print("    ");
                pw.print(Integer.toHexString(trig.getFlags()));
                pw.print(' ' );
                pw.println(trig.getUri());
            }
        }
        if (job.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
            pw.print(prefix); pw.print("  Network type: ");
            pw.println(job.getNetworkType());
        }
        if (job.getMinLatencyMillis() != 0) {
            pw.print(prefix); pw.print("  Minimum latency: ");
            TimeUtils.formatDuration(job.getMinLatencyMillis(), pw);
            pw.println();
        }
        if (job.getMaxExecutionDelayMillis() != 0) {
            pw.print(prefix); pw.print("  Max execution delay: ");
            TimeUtils.formatDuration(job.getMaxExecutionDelayMillis(), pw);
            pw.println();
        }
        pw.print(prefix); pw.print("  Backoff: policy=");
        pw.print(job.getBackoffPolicy());
        pw.print(" initial=");
        TimeUtils.formatDuration(job.getInitialBackoffMillis(), pw);
        pw.println();
        if (job.hasEarlyConstraint()) {
            pw.print(prefix); pw.println("  Has early constraint");
        }
        if (job.hasLateConstraint()) {
            pw.print(prefix); pw.println("  Has late constraint");
        }
        pw.print(prefix); pw.println("Constraints:");
        if (hasChargingConstraint()) {
            pw.print(prefix); pw.print("  Charging: ");
            pw.println(chargingConstraintSatisfied.get());
        }
        if (hasTimingDelayConstraint()) {
            pw.print(prefix); pw.print("  Time delay: ");
            pw.println(timeDelayConstraintSatisfied.get());
        }
        if (hasDeadlineConstraint()) {
            pw.print(prefix); pw.print("  Deadline: ");
            pw.println(deadlineConstraintSatisfied.get());
        }
        if (hasIdleConstraint()) {
            pw.print(prefix); pw.print("  System idle: ");
            pw.println(idleConstraintSatisfied.get());
        }
        if (hasUnmeteredConstraint()) {
            pw.print(prefix); pw.print("  Unmetered: ");
            pw.println(unmeteredConstraintSatisfied.get());
        }
        if (hasConnectivityConstraint()) {
            pw.print(prefix); pw.print("  Connectivity: ");
            pw.println(connectivityConstraintSatisfied.get());
        }
        if (hasIdleConstraint()) {
            pw.print(prefix); pw.print("  App not idle: ");
            pw.println(appNotIdleConstraintSatisfied.get());
        }
        if (hasContentTriggerConstraint()) {
            pw.print(prefix); pw.print("  Content trigger: ");
            pw.println(contentTriggerConstraintSatisfied.get());
        }
        if (changedAuthorities != null) {
            pw.print(prefix); pw.println("Changed authorities:");
            for (int i=0; i<changedAuthorities.size(); i++) {
                pw.print(prefix); pw.print("  "); pw.println(changedAuthorities.valueAt(i));
            }
            if (changedUris != null) {
                pw.print(prefix); pw.println("Changed URIs:");
                for (int i=0; i<changedUris.size(); i++) {
                    pw.print(prefix); pw.print("  "); pw.println(changedUris.valueAt(i));
                }
            }
        }
        pw.print(prefix); pw.print("Earliest run time: ");
        pw.println(formatRunTime(earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME));
        pw.print(prefix); pw.print("Latest run time: ");
        pw.println(formatRunTime(latestRunTimeElapsedMillis, NO_LATEST_RUNTIME));
        if (numFailures != 0) {
            pw.print(prefix); pw.print("Num failures: "); pw.println(numFailures);
        }
    }
}
