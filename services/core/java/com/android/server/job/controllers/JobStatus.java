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
public final class JobStatus {
    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final long NO_EARLIEST_RUNTIME = 0L;

    static final int CONSTRAINT_CHARGING = 1<<0;
    static final int CONSTRAINT_TIMING_DELAY = 1<<1;
    static final int CONSTRAINT_DEADLINE = 1<<2;
    static final int CONSTRAINT_IDLE = 1<<3;
    static final int CONSTRAINT_UNMETERED = 1<<4;
    static final int CONSTRAINT_CONNECTIVITY = 1<<5;
    static final int CONSTRAINT_APP_NOT_IDLE = 1<<6;
    static final int CONSTRAINT_CONTENT_TRIGGER = 1<<7;
    static final int CONSTRAINT_DEVICE_NOT_DOZING = 1<<8;
    static final int CONSTRAINT_NOT_ROAMING = 1<<9;

    // Soft override: ignore constraints like time that don't affect API availability
    public static final int OVERRIDE_SOFT = 1;
    // Full override: ignore all constraints including API-affecting like connectivity
    public static final int OVERRIDE_FULL = 2;

    /** If not specified, trigger update delay is 10 seconds. */
    public static final long DEFAULT_TRIGGER_UPDATE_DELAY = 10*1000;

    /** The minimum possible update delay is 1/2 second. */
    public static final long MIN_TRIGGER_UPDATE_DELAY = 500;

    /** If not specified, trigger maxumum delay is 2 minutes. */
    public static final long DEFAULT_TRIGGER_MAX_DELAY = 2*60*1000;

    /** The minimum possible update delay is 1 second. */
    public static final long MIN_TRIGGER_MAX_DELAY = 1000;

    final JobInfo job;
    /** Uid of the package requesting this job. */
    final int callingUid;
    final String batteryName;

    final String sourcePackageName;
    final int sourceUserId;
    final int sourceUid;
    final String sourceTag;

    final String tag;

    /**
     * Earliest point in the future at which this job will be eligible to run. A value of 0
     * indicates there is no delay constraint. See {@link #hasTimingDelayConstraint()}.
     */
    private final long earliestRunTimeElapsedMillis;
    /**
     * Latest point in the future at which this job must be run. A value of {@link Long#MAX_VALUE}
     * indicates there is no deadline constraint. See {@link #hasDeadlineConstraint()}.
     */
    private final long latestRunTimeElapsedMillis;

    /** How many times this job has failed, used to compute back-off. */
    private final int numFailures;

    // Constraints.
    final int requiredConstraints;
    int satisfiedConstraints = 0;

    // Set to true if doze constraint was satisfied due to app being whitelisted.
    public boolean dozeWhitelisted;

    // These are filled in by controllers when preparing for execution.
    public ArraySet<Uri> changedUris;
    public ArraySet<String> changedAuthorities;

    public int lastEvaluatedPriority;

    // Used by shell commands
    public int overrideState = 0;

    /**
     * For use only by ContentObserverController: state it is maintaining about content URIs
     * being observed.
     */
    ContentObserverController.JobInstance contentObserverJobInstance;

    /** Provide a handle to the service that this job will be run on. */
    public int getServiceToken() {
        return callingUid;
    }

    private JobStatus(JobInfo job, int callingUid, String sourcePackageName,
            int sourceUserId, String tag, int numFailures, long earliestRunTimeElapsedMillis,
            long latestRunTimeElapsedMillis) {
        this.job = job;
        this.callingUid = callingUid;

        int tempSourceUid = -1;
        if (sourceUserId != -1 && sourcePackageName != null) {
            try {
                tempSourceUid = AppGlobals.getPackageManager().getPackageUid(sourcePackageName, 0,
                        sourceUserId);
            } catch (RemoteException ex) {
                // Can't happen, PackageManager runs in the same process.
            }
        }
        if (tempSourceUid == -1) {
            this.sourceUid = callingUid;
            this.sourceUserId = UserHandle.getUserId(callingUid);
            this.sourcePackageName = job.getService().getPackageName();
            this.sourceTag = null;
        } else {
            this.sourceUid = tempSourceUid;
            this.sourceUserId = sourceUserId;
            this.sourcePackageName = sourcePackageName;
            this.sourceTag = tag;
        }

        this.batteryName = this.sourceTag != null
                ? this.sourceTag + ":" + job.getService().getPackageName()
                : job.getService().flattenToShortString();
        this.tag = "*job*/" + this.batteryName;

        this.earliestRunTimeElapsedMillis = earliestRunTimeElapsedMillis;
        this.latestRunTimeElapsedMillis = latestRunTimeElapsedMillis;
        this.numFailures = numFailures;

        int requiredConstraints = 0;
        if (job.getNetworkType() == JobInfo.NETWORK_TYPE_ANY) {
            requiredConstraints |= CONSTRAINT_CONNECTIVITY;
        }
        if (job.getNetworkType() == JobInfo.NETWORK_TYPE_UNMETERED) {
            requiredConstraints |= CONSTRAINT_UNMETERED;
        }
        if (job.getNetworkType() == JobInfo.NETWORK_TYPE_NOT_ROAMING) {
            requiredConstraints |= CONSTRAINT_NOT_ROAMING;
        }
        if (job.isRequireCharging()) {
            requiredConstraints |= CONSTRAINT_CHARGING;
        }
        if (earliestRunTimeElapsedMillis != NO_EARLIEST_RUNTIME) {
            requiredConstraints |= CONSTRAINT_TIMING_DELAY;
        }
        if (latestRunTimeElapsedMillis != NO_LATEST_RUNTIME) {
            requiredConstraints |= CONSTRAINT_DEADLINE;
        }
        if (job.isRequireDeviceIdle()) {
            requiredConstraints |= CONSTRAINT_IDLE;
        }
        if (job.getTriggerContentUris() != null) {
            requiredConstraints |= CONSTRAINT_CONTENT_TRIGGER;
        }
        this.requiredConstraints = requiredConstraints;
    }

    /** Copy constructor. */
    public JobStatus(JobStatus jobStatus) {
        this(jobStatus.getJob(), jobStatus.getUid(),
                jobStatus.getSourcePackageName(), jobStatus.getSourceUserId(),
                jobStatus.getSourceTag(), jobStatus.getNumFailures(),
                jobStatus.getEarliestRunTime(), jobStatus.getLatestRunTimeElapsed());
    }

    /**
     * Create a new JobStatus that was loaded from disk. We ignore the provided
     * {@link android.app.job.JobInfo} time criteria because we can load a persisted periodic job
     * from the {@link com.android.server.job.JobStore} and still want to respect its
     * wallclock runtime rather than resetting it on every boot.
     * We consider a freshly loaded job to no longer be in back-off.
     */
    public JobStatus(JobInfo job, int callingUid, String sourcePackageName, int sourceUserId,
            String sourceTag, long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis) {
        this(job, callingUid, sourcePackageName, sourceUserId, sourceTag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis);
    }

    /** Create a new job to be rescheduled with the provided parameters. */
    public JobStatus(JobStatus rescheduling, long newEarliestRuntimeElapsedMillis,
                      long newLatestRuntimeElapsedMillis, int backoffAttempt) {
        this(rescheduling.job, rescheduling.getUid(),
                rescheduling.getSourcePackageName(), rescheduling.getSourceUserId(),
                rescheduling.getSourceTag(), backoffAttempt, newEarliestRuntimeElapsedMillis,
                newLatestRuntimeElapsedMillis);
    }

    /**
     * Create a newly scheduled job.
     * @param callingUid Uid of the package that scheduled this job.
     * @param sourcePackageName Package name on whose behalf this job is scheduled. Null indicates
     *                          the calling package is the source.
     * @param sourceUserId User id for whom this job is scheduled. -1 indicates this is same as the
     */
    public static JobStatus createFromJobInfo(JobInfo job, int callingUid, String sourcePackageName,
            int sourceUserId, String tag) {
        final long elapsedNow = SystemClock.elapsedRealtime();
        final long earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis;
        if (job.isPeriodic()) {
            latestRunTimeElapsedMillis = elapsedNow + job.getIntervalMillis();
            earliestRunTimeElapsedMillis = latestRunTimeElapsedMillis - job.getFlexMillis();
        } else {
            earliestRunTimeElapsedMillis = job.hasEarlyConstraint() ?
                    elapsedNow + job.getMinLatencyMillis() : NO_EARLIEST_RUNTIME;
            latestRunTimeElapsedMillis = job.hasLateConstraint() ?
                    elapsedNow + job.getMaxExecutionDelayMillis() : NO_LATEST_RUNTIME;
        }
        return new JobStatus(job, callingUid, sourcePackageName, sourceUserId, tag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis);
    }

    public JobInfo getJob() {
        return job;
    }

    public int getJobId() {
        return job.getId();
    }

    public void printUniqueId(PrintWriter pw) {
        UserHandle.formatUid(pw, callingUid);
        pw.print("/");
        pw.print(job.getId());
    }

    public int getNumFailures() {
        return numFailures;
    }

    public ComponentName getServiceComponent() {
        return job.getService();
    }

    public String getSourcePackageName() {
        return sourcePackageName;
    }

    public int getSourceUid() {
        return sourceUid;
    }

    public int getSourceUserId() {
        return sourceUserId;
    }

    public int getUserId() {
        return UserHandle.getUserId(callingUid);
    }

    public String getSourceTag() {
        return sourceTag;
    }

    public int getUid() {
        return callingUid;
    }

    public String getBatteryName() {
        return batteryName;
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

    public int getFlags() {
        return job.getFlags();
    }

    public boolean hasConnectivityConstraint() {
        return (requiredConstraints&CONSTRAINT_CONNECTIVITY) != 0;
    }

    public boolean hasUnmeteredConstraint() {
        return (requiredConstraints&CONSTRAINT_UNMETERED) != 0;
    }

    public boolean hasNotRoamingConstraint() {
        return (requiredConstraints&CONSTRAINT_NOT_ROAMING) != 0;
    }

    public boolean hasChargingConstraint() {
        return (requiredConstraints&CONSTRAINT_CHARGING) != 0;
    }

    public boolean hasTimingDelayConstraint() {
        return (requiredConstraints&CONSTRAINT_TIMING_DELAY) != 0;
    }

    public boolean hasDeadlineConstraint() {
        return (requiredConstraints&CONSTRAINT_DEADLINE) != 0;
    }

    public boolean hasIdleConstraint() {
        return (requiredConstraints&CONSTRAINT_IDLE) != 0;
    }

    public boolean hasContentTriggerConstraint() {
        return (requiredConstraints&CONSTRAINT_CONTENT_TRIGGER) != 0;
    }

    public long getTriggerContentUpdateDelay() {
        long time = job.getTriggerContentUpdateDelay();
        if (time < 0) {
            return DEFAULT_TRIGGER_UPDATE_DELAY;
        }
        return Math.max(time, MIN_TRIGGER_UPDATE_DELAY);
    }

    public long getTriggerContentMaxDelay() {
        long time = job.getTriggerContentMaxDelay();
        if (time < 0) {
            return DEFAULT_TRIGGER_MAX_DELAY;
        }
        return Math.max(time, MIN_TRIGGER_MAX_DELAY);
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

    boolean setChargingConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CHARGING, state);
    }

    boolean setTimingDelayConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_TIMING_DELAY, state);
    }

    boolean setDeadlineConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_DEADLINE, state);
    }

    boolean setIdleConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_IDLE, state);
    }

    boolean setConnectivityConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONNECTIVITY, state);
    }

    boolean setUnmeteredConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_UNMETERED, state);
    }

    boolean setNotRoamingConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_NOT_ROAMING, state);
    }

    boolean setAppNotIdleConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_APP_NOT_IDLE, state);
    }

    boolean setContentTriggerConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONTENT_TRIGGER, state);
    }

    boolean setDeviceNotDozingConstraintSatisfied(boolean state, boolean whitelisted) {
        dozeWhitelisted = whitelisted;
        return setConstraintSatisfied(CONSTRAINT_DEVICE_NOT_DOZING, state);
    }

    boolean setConstraintSatisfied(int constraint, boolean state) {
        boolean old = (satisfiedConstraints&constraint) != 0;
        if (old == state) {
            return false;
        }
        satisfiedConstraints = (satisfiedConstraints&~constraint) | (state ? constraint : 0);
        return true;
    }

    boolean isConstraintSatisfied(int constraint) {
        return (satisfiedConstraints&constraint) != 0;
    }

    public boolean shouldDump(int filterUid) {
        return filterUid == -1 || UserHandle.getAppId(getUid()) == filterUid
                || UserHandle.getAppId(getSourceUid()) == filterUid;
    }

    /**
     * @return Whether or not this job is ready to run, based on its requirements. This is true if
     * the constraints are satisfied <strong>or</strong> the deadline on the job has expired.
     */
    public boolean isReady() {
        // Deadline constraint trumps other constraints (except for periodic jobs where deadline
        // is an implementation detail. A periodic job should only run if its constraints are
        // satisfied).
        // AppNotIdle implicit constraint must be satisfied
        // DeviceNotDozing implicit constraint must be satisfied
        final boolean deadlineSatisfied = (!job.isPeriodic() && hasDeadlineConstraint()
                && (satisfiedConstraints & CONSTRAINT_DEADLINE) != 0);
        final boolean notIdle = (satisfiedConstraints & CONSTRAINT_APP_NOT_IDLE) != 0;
        final boolean notDozing = (satisfiedConstraints & CONSTRAINT_DEVICE_NOT_DOZING) != 0
                || (job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0;
        return (isConstraintsSatisfied() || deadlineSatisfied) && notIdle && notDozing;
    }

    static final int CONSTRAINTS_OF_INTEREST =
            CONSTRAINT_CHARGING | CONSTRAINT_TIMING_DELAY |
            CONSTRAINT_CONNECTIVITY | CONSTRAINT_UNMETERED | CONSTRAINT_NOT_ROAMING |
            CONSTRAINT_IDLE | CONSTRAINT_CONTENT_TRIGGER;

    // Soft override covers all non-"functional" constraints
    static final int SOFT_OVERRIDE_CONSTRAINTS =
            CONSTRAINT_CHARGING | CONSTRAINT_TIMING_DELAY | CONSTRAINT_IDLE;

    /**
     * @return Whether the constraints set on this job are satisfied.
     */
    public boolean isConstraintsSatisfied() {
        if (overrideState == OVERRIDE_FULL) {
            // force override: the job is always runnable
            return true;
        }

        final int req = requiredConstraints & CONSTRAINTS_OF_INTEREST;

        int sat = satisfiedConstraints & CONSTRAINTS_OF_INTEREST;
        if (overrideState == OVERRIDE_SOFT) {
            // override: pretend all 'soft' requirements are satisfied
            sat |= (requiredConstraints & SOFT_OVERRIDE_CONSTRAINTS);
        }

        return (sat & req) == req;
    }

    public boolean matches(int uid, int jobId) {
        return this.job.getId() == jobId && this.callingUid == uid;
    }

    @Override
    public String toString() {
        return String.valueOf(hashCode()).substring(0, 3) + ".."
                + ":[" + job.getService()
                + ",jId=" + job.getId()
                + ",u" + getUserId()
                + ",suid=" + getSourceUid()
                + ",R=(" + formatRunTime(earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME)
                + "," + formatRunTime(latestRunTimeElapsedMillis, NO_LATEST_RUNTIME) + ")"
                + ",N=" + job.getNetworkType() + ",C=" + job.isRequireCharging()
                + ",I=" + job.isRequireDeviceIdle()
                + ",U=" + (job.getTriggerContentUris() != null)
                + ",F=" + numFailures + ",P=" + job.isPersisted()
                + ",ANI=" + ((satisfiedConstraints&CONSTRAINT_APP_NOT_IDLE) != 0)
                + ",DND=" + ((satisfiedConstraints&CONSTRAINT_DEVICE_NOT_DOZING) != 0)
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

    /**
     * Convenience function to identify a job uniquely without pulling all the data that
     * {@link #toString()} returns.
     */
    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        UserHandle.formatUid(sb, callingUid);
        sb.append("/");
        sb.append(job.getId());
        sb.append(' ');
        sb.append(batteryName);
        return sb.toString();
    }

    /**
     * Convenience function to identify a job uniquely without pulling all the data that
     * {@link #toString()} returns.
     */
    public String toShortStringExceptUniqueId() {
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(batteryName);
        return sb.toString();
    }

    void dumpConstraints(PrintWriter pw, int constraints) {
        if ((constraints&CONSTRAINT_CHARGING) != 0) {
            pw.print(" CHARGING");
        }
        if ((constraints&CONSTRAINT_TIMING_DELAY) != 0) {
            pw.print(" TIMING_DELAY");
        }
        if ((constraints&CONSTRAINT_DEADLINE) != 0) {
            pw.print(" DEADLINE");
        }
        if ((constraints&CONSTRAINT_IDLE) != 0) {
            pw.print(" IDLE");
        }
        if ((constraints&CONSTRAINT_CONNECTIVITY) != 0) {
            pw.print(" CONNECTIVITY");
        }
        if ((constraints&CONSTRAINT_UNMETERED) != 0) {
            pw.print(" UNMETERED");
        }
        if ((constraints&CONSTRAINT_NOT_ROAMING) != 0) {
            pw.print(" NOT_ROAMING");
        }
        if ((constraints&CONSTRAINT_APP_NOT_IDLE) != 0) {
            pw.print(" APP_NOT_IDLE");
        }
        if ((constraints&CONSTRAINT_CONTENT_TRIGGER) != 0) {
            pw.print(" CONTENT_TRIGGER");
        }
        if ((constraints&CONSTRAINT_DEVICE_NOT_DOZING) != 0) {
            pw.print(" DEVICE_NOT_DOZING");
        }
    }

    // Dumpsys infrastructure
    public void dump(PrintWriter pw, String prefix, boolean full) {
        pw.print(prefix); UserHandle.formatUid(pw, callingUid);
        pw.print(" tag="); pw.println(tag);
        pw.print(prefix);
        pw.print("Source: uid="); UserHandle.formatUid(pw, getSourceUid());
        pw.print(" user="); pw.print(getSourceUserId());
        pw.print(" pkg="); pw.println(getSourcePackageName());
        if (full) {
            pw.print(prefix); pw.println("JobInfo:"); pw.print(prefix);
            pw.print("  Service: "); pw.println(job.getService().flattenToShortString());
            if (job.isPeriodic()) {
                pw.print(prefix); pw.print("  PERIODIC: interval=");
                TimeUtils.formatDuration(job.getIntervalMillis(), pw);
                pw.print(" flex="); TimeUtils.formatDuration(job.getFlexMillis(), pw);
                pw.println();
            }
            if (job.isPersisted()) {
                pw.print(prefix); pw.println("  PERSISTED");
            }
            if (job.getPriority() != 0) {
                pw.print(prefix); pw.print("  Priority: "); pw.println(job.getPriority());
            }
            if (job.getFlags() != 0) {
                pw.print(prefix); pw.print("  Flags: ");
                pw.println(Integer.toHexString(job.getFlags()));
            }
            pw.print(prefix); pw.print("  Requires: charging=");
            pw.print(job.isRequireCharging()); pw.print(" deviceIdle=");
            pw.println(job.isRequireDeviceIdle());
            if (job.getTriggerContentUris() != null) {
                pw.print(prefix); pw.println("  Trigger content URIs:");
                for (int i = 0; i < job.getTriggerContentUris().length; i++) {
                    JobInfo.TriggerContentUri trig = job.getTriggerContentUris()[i];
                    pw.print(prefix); pw.print("    ");
                    pw.print(Integer.toHexString(trig.getFlags()));
                    pw.print(' '); pw.println(trig.getUri());
                }
                if (job.getTriggerContentUpdateDelay() >= 0) {
                    pw.print(prefix); pw.print("  Trigger update delay: ");
                    TimeUtils.formatDuration(job.getTriggerContentUpdateDelay(), pw);
                    pw.println();
                }
                if (job.getTriggerContentMaxDelay() >= 0) {
                    pw.print(prefix); pw.print("  Trigger max delay: ");
                    TimeUtils.formatDuration(job.getTriggerContentMaxDelay(), pw);
                    pw.println();
                }
            }
            if (job.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
                pw.print(prefix); pw.print("  Network type: "); pw.println(job.getNetworkType());
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
            pw.print(prefix); pw.print("  Backoff: policy="); pw.print(job.getBackoffPolicy());
            pw.print(" initial="); TimeUtils.formatDuration(job.getInitialBackoffMillis(), pw);
            pw.println();
            if (job.hasEarlyConstraint()) {
                pw.print(prefix); pw.println("  Has early constraint");
            }
            if (job.hasLateConstraint()) {
                pw.print(prefix); pw.println("  Has late constraint");
            }
        }
        pw.print(prefix); pw.print("Required constraints:");
        dumpConstraints(pw, requiredConstraints);
        pw.println();
        if (full) {
            pw.print(prefix); pw.print("Satisfied constraints:");
            dumpConstraints(pw, satisfiedConstraints);
            pw.println();
            pw.print(prefix); pw.print("Unsatisfied constraints:");
            dumpConstraints(pw, (requiredConstraints & ~satisfiedConstraints));
            pw.println();
            if (dozeWhitelisted) {
                pw.print(prefix); pw.println("Doze whitelisted: true");
            }
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
