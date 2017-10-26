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
import android.app.IActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.content.ClipData;
import android.content.ComponentName;
import android.net.Uri;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.Time;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.server.job.GrantedUriPermissions;
import com.android.server.job.JobSchedulerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

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
    static final String TAG = "JobSchedulerService";
    static final boolean DEBUG = JobSchedulerService.DEBUG;

    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final long NO_EARLIEST_RUNTIME = 0L;

    static final int CONSTRAINT_CHARGING = JobInfo.CONSTRAINT_FLAG_CHARGING;
    static final int CONSTRAINT_IDLE = JobInfo.CONSTRAINT_FLAG_DEVICE_IDLE;
    static final int CONSTRAINT_BATTERY_NOT_LOW = JobInfo.CONSTRAINT_FLAG_BATTERY_NOT_LOW;
    static final int CONSTRAINT_STORAGE_NOT_LOW = JobInfo.CONSTRAINT_FLAG_STORAGE_NOT_LOW;
    static final int CONSTRAINT_TIMING_DELAY = 1<<31;
    static final int CONSTRAINT_DEADLINE = 1<<30;
    static final int CONSTRAINT_UNMETERED = 1<<29;
    static final int CONSTRAINT_CONNECTIVITY = 1<<28;
    static final int CONSTRAINT_APP_NOT_IDLE = 1<<27;
    static final int CONSTRAINT_CONTENT_TRIGGER = 1<<26;
    static final int CONSTRAINT_DEVICE_NOT_DOZING = 1<<25;
    static final int CONSTRAINT_NOT_ROAMING = 1<<24;
    static final int CONSTRAINT_METERED = 1<<23;

    static final int CONNECTIVITY_MASK =
            CONSTRAINT_UNMETERED | CONSTRAINT_CONNECTIVITY |
            CONSTRAINT_NOT_ROAMING | CONSTRAINT_METERED;

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

    private GrantedUriPermissions uriPerms;
    private boolean prepared;

    static final boolean DEBUG_PREPARE = true;
    private Throwable unpreparedPoint = null;

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

    /**
     * Flag for {@link #trackingControllers}: the battery controller is currently tracking this job.
     */
    public static final int TRACKING_BATTERY = 1<<0;
    /**
     * Flag for {@link #trackingControllers}: the network connectivity controller is currently
     * tracking this job.
     */
    public static final int TRACKING_CONNECTIVITY = 1<<1;
    /**
     * Flag for {@link #trackingControllers}: the content observer controller is currently
     * tracking this job.
     */
    public static final int TRACKING_CONTENT = 1<<2;
    /**
     * Flag for {@link #trackingControllers}: the idle controller is currently tracking this job.
     */
    public static final int TRACKING_IDLE = 1<<3;
    /**
     * Flag for {@link #trackingControllers}: the storage controller is currently tracking this job.
     */
    public static final int TRACKING_STORAGE = 1<<4;
    /**
     * Flag for {@link #trackingControllers}: the time controller is currently tracking this job.
     */
    public static final int TRACKING_TIME = 1<<5;

    /**
     * Bit mask of controllers that are currently tracking the job.
     */
    private int trackingControllers;

    // These are filled in by controllers when preparing for execution.
    public ArraySet<Uri> changedUris;
    public ArraySet<String> changedAuthorities;

    public int lastEvaluatedPriority;

    // If non-null, this is work that has been enqueued for the job.
    public ArrayList<JobWorkItem> pendingWork;

    // If non-null, this is work that is currently being executed.
    public ArrayList<JobWorkItem> executingWork;

    public int nextPendingWorkId = 1;

    // Used by shell commands
    public int overrideState = 0;

    // When this job was enqueued, for ordering.  (in elapsedRealtimeMillis)
    public long enqueueTime;

    // Metrics about queue latency.  (in uptimeMillis)
    public long madePending;
    public long madeActive;

    /**
     * Last time a job finished successfully for a periodic job, in the currentTimeMillis time,
     * for dumpsys.
     */
    private long mLastSuccessfulRunTime;

    /**
     * Last time a job finished unsuccessfully, in the currentTimeMillis time, for dumpsys.
     */
    private long mLastFailedRunTime;

    /**
     * Transient: when a job is inflated from disk before we have a reliable RTC clock time,
     * we retain the canonical (delay, deadline) scheduling tuple read out of the persistent
     * store in UTC so that we can fix up the job's scheduling criteria once we get a good
     * wall-clock time.  If we have to persist the job again before the clock has been updated,
     * we record these times again rather than calculating based on the earliest/latest elapsed
     * time base figures.
     *
     * 'first' is the earliest/delay time, and 'second' is the latest/deadline time.
     */
    private Pair<Long, Long> mPersistedUtcTimes;

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
            long latestRunTimeElapsedMillis, long lastSuccessfulRunTime, long lastFailedRunTime) {
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

        int requiredConstraints = job.getConstraintFlags();

        switch (job.getNetworkType()) {
            case JobInfo.NETWORK_TYPE_NONE:
                // No constraint.
                break;
            case JobInfo.NETWORK_TYPE_ANY:
                requiredConstraints |= CONSTRAINT_CONNECTIVITY;
                break;
            case JobInfo.NETWORK_TYPE_UNMETERED:
                requiredConstraints |= CONSTRAINT_UNMETERED;
                break;
            case JobInfo.NETWORK_TYPE_NOT_ROAMING:
                requiredConstraints |= CONSTRAINT_NOT_ROAMING;
                break;
            case JobInfo.NETWORK_TYPE_METERED:
                requiredConstraints |= CONSTRAINT_METERED;
                break;
            default:
                Slog.w(TAG, "Unrecognized networking constraint " + job.getNetworkType());
                break;
        }

        if (earliestRunTimeElapsedMillis != NO_EARLIEST_RUNTIME) {
            requiredConstraints |= CONSTRAINT_TIMING_DELAY;
        }
        if (latestRunTimeElapsedMillis != NO_LATEST_RUNTIME) {
            requiredConstraints |= CONSTRAINT_DEADLINE;
        }
        if (job.getTriggerContentUris() != null) {
            requiredConstraints |= CONSTRAINT_CONTENT_TRIGGER;
        }
        this.requiredConstraints = requiredConstraints;

        mLastSuccessfulRunTime = lastSuccessfulRunTime;
        mLastFailedRunTime = lastFailedRunTime;
    }

    /** Copy constructor: used specifically when cloning JobStatus objects for persistence,
     *   so we preserve RTC window bounds if the source object has them. */
    public JobStatus(JobStatus jobStatus) {
        this(jobStatus.getJob(), jobStatus.getUid(),
                jobStatus.getSourcePackageName(), jobStatus.getSourceUserId(),
                jobStatus.getSourceTag(), jobStatus.getNumFailures(),
                jobStatus.getEarliestRunTime(), jobStatus.getLatestRunTimeElapsed(),
                jobStatus.getLastSuccessfulRunTime(), jobStatus.getLastFailedRunTime());
        mPersistedUtcTimes = jobStatus.mPersistedUtcTimes;
        if (jobStatus.mPersistedUtcTimes != null) {
            if (DEBUG) {
                Slog.i(TAG, "Cloning job with persisted run times", new RuntimeException("here"));
            }
        }
    }

    /**
     * Create a new JobStatus that was loaded from disk. We ignore the provided
     * {@link android.app.job.JobInfo} time criteria because we can load a persisted periodic job
     * from the {@link com.android.server.job.JobStore} and still want to respect its
     * wallclock runtime rather than resetting it on every boot.
     * We consider a freshly loaded job to no longer be in back-off.
     */
    public JobStatus(JobInfo job, int callingUid, String sourcePackageName, int sourceUserId,
            String sourceTag, long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis,
            long lastSuccessfulRunTime, long lastFailedRunTime,
            Pair<Long, Long> persistedExecutionTimesUTC) {
        this(job, callingUid, sourcePackageName, sourceUserId, sourceTag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime);

        // Only during initial inflation do we record the UTC-timebase execution bounds
        // read from the persistent store.  If we ever have to recreate the JobStatus on
        // the fly, it means we're rescheduling the job; and this means that the calculated
        // elapsed timebase bounds intrinsically become correct.
        this.mPersistedUtcTimes = persistedExecutionTimesUTC;
        if (persistedExecutionTimesUTC != null) {
            if (DEBUG) {
                Slog.i(TAG, "+ restored job with RTC times because of bad boot clock");
            }
        }
    }

    /** Create a new job to be rescheduled with the provided parameters. */
    public JobStatus(JobStatus rescheduling, long newEarliestRuntimeElapsedMillis,
            long newLatestRuntimeElapsedMillis, int backoffAttempt,
            long lastSuccessfulRunTime, long lastFailedRunTime) {
        this(rescheduling.job, rescheduling.getUid(),
                rescheduling.getSourcePackageName(), rescheduling.getSourceUserId(),
                rescheduling.getSourceTag(), backoffAttempt, newEarliestRuntimeElapsedMillis,
                newLatestRuntimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime);
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
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                0 /* lastSuccessfulRunTime */, 0 /* lastFailedRunTime */);
    }

    public void enqueueWorkLocked(IActivityManager am, JobWorkItem work) {
        if (pendingWork == null) {
            pendingWork = new ArrayList<>();
        }
        work.setWorkId(nextPendingWorkId);
        nextPendingWorkId++;
        if (work.getIntent() != null
                && GrantedUriPermissions.checkGrantFlags(work.getIntent().getFlags())) {
            work.setGrants(GrantedUriPermissions.createFromIntent(am, work.getIntent(), sourceUid,
                    sourcePackageName, sourceUserId, toShortString()));
        }
        pendingWork.add(work);
    }

    public JobWorkItem dequeueWorkLocked() {
        if (pendingWork != null && pendingWork.size() > 0) {
            JobWorkItem work = pendingWork.remove(0);
            if (work != null) {
                if (executingWork == null) {
                    executingWork = new ArrayList<>();
                }
                executingWork.add(work);
                work.bumpDeliveryCount();
            }
            return work;
        }
        return null;
    }

    public boolean hasWorkLocked() {
        return (pendingWork != null && pendingWork.size() > 0) || hasExecutingWorkLocked();
    }

    public boolean hasExecutingWorkLocked() {
        return executingWork != null && executingWork.size() > 0;
    }

    private static void ungrantWorkItem(IActivityManager am, JobWorkItem work) {
        if (work.getGrants() != null) {
            ((GrantedUriPermissions)work.getGrants()).revoke(am);
        }
    }

    public boolean completeWorkLocked(IActivityManager am, int workId) {
        if (executingWork != null) {
            final int N = executingWork.size();
            for (int i = 0; i < N; i++) {
                JobWorkItem work = executingWork.get(i);
                if (work.getWorkId() == workId) {
                    executingWork.remove(i);
                    ungrantWorkItem(am, work);
                    return true;
                }
            }
        }
        return false;
    }

    private static void ungrantWorkList(IActivityManager am, ArrayList<JobWorkItem> list) {
        if (list != null) {
            final int N = list.size();
            for (int i = 0; i < N; i++) {
                ungrantWorkItem(am, list.get(i));
            }
        }
    }

    public void stopTrackingJobLocked(IActivityManager am, JobStatus incomingJob) {
        if (incomingJob != null) {
            // We are replacing with a new job -- transfer the work!  We do any executing
            // work first, since that was originally at the front of the pending work.
            if (executingWork != null && executingWork.size() > 0) {
                incomingJob.pendingWork = executingWork;
            }
            if (incomingJob.pendingWork == null) {
                incomingJob.pendingWork = pendingWork;
            } else if (pendingWork != null && pendingWork.size() > 0) {
                incomingJob.pendingWork.addAll(pendingWork);
            }
            pendingWork = null;
            executingWork = null;
            incomingJob.nextPendingWorkId = nextPendingWorkId;
        } else {
            // We are completely stopping the job...  need to clean up work.
            ungrantWorkList(am, pendingWork);
            pendingWork = null;
            ungrantWorkList(am, executingWork);
            executingWork = null;
        }
    }

    public void prepareLocked(IActivityManager am) {
        if (prepared) {
            Slog.wtf(TAG, "Already prepared: " + this);
            return;
        }
        prepared = true;
        if (DEBUG_PREPARE) {
            unpreparedPoint = null;
        }
        final ClipData clip = job.getClipData();
        if (clip != null) {
            uriPerms = GrantedUriPermissions.createFromClip(am, clip, sourceUid, sourcePackageName,
                    sourceUserId, job.getClipGrantFlags(), toShortString());
        }
    }

    public void unprepareLocked(IActivityManager am) {
        if (!prepared) {
            Slog.wtf(TAG, "Hasn't been prepared: " + this);
            if (DEBUG_PREPARE && unpreparedPoint != null) {
                Slog.e(TAG, "Was already unprepared at ", unpreparedPoint);
            }
            return;
        }
        prepared = false;
        if (DEBUG_PREPARE) {
            unpreparedPoint = new Throwable().fillInStackTrace();
        }
        if (uriPerms != null) {
            uriPerms.revoke(am);
            uriPerms = null;
        }
    }

    public boolean isPreparedLocked() {
        return prepared;
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

    public int getPriority() {
        return job.getPriority();
    }

    public int getFlags() {
        return job.getFlags();
    }

    /** Does this job have any sort of networking constraint? */
    public boolean hasConnectivityConstraint() {
        return (requiredConstraints&CONNECTIVITY_MASK) != 0;
    }

    public boolean needsAnyConnectivity() {
        return (requiredConstraints&CONSTRAINT_CONNECTIVITY) != 0;
    }

    public boolean needsUnmeteredConnectivity() {
        return (requiredConstraints&CONSTRAINT_UNMETERED) != 0;
    }

    public boolean needsMeteredConnectivity() {
        return (requiredConstraints&CONSTRAINT_METERED) != 0;
    }

    public boolean needsNonRoamingConnectivity() {
        return (requiredConstraints&CONSTRAINT_NOT_ROAMING) != 0;
    }

    public boolean hasChargingConstraint() {
        return (requiredConstraints&CONSTRAINT_CHARGING) != 0;
    }

    public boolean hasBatteryNotLowConstraint() {
        return (requiredConstraints&CONSTRAINT_BATTERY_NOT_LOW) != 0;
    }

    public boolean hasPowerConstraint() {
        return (requiredConstraints&(CONSTRAINT_CHARGING|CONSTRAINT_BATTERY_NOT_LOW)) != 0;
    }

    public boolean hasStorageNotLowConstraint() {
        return (requiredConstraints&CONSTRAINT_STORAGE_NOT_LOW) != 0;
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

    public Pair<Long, Long> getPersistedUtcTimes() {
        return mPersistedUtcTimes;
    }

    public void clearPersistedUtcTimes() {
        mPersistedUtcTimes = null;
    }

    boolean setChargingConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CHARGING, state);
    }

    boolean setBatteryNotLowConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_BATTERY_NOT_LOW, state);
    }

    boolean setStorageNotLowConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_STORAGE_NOT_LOW, state);
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

    boolean setMeteredConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_METERED, state);
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

    boolean clearTrackingController(int which) {
        if ((trackingControllers&which) != 0) {
            trackingControllers &= ~which;
            return true;
        }
        return false;
    }

    void setTrackingController(int which) {
        trackingControllers |= which;
    }

    public long getLastSuccessfulRunTime() {
        return mLastSuccessfulRunTime;
    }

    public long getLastFailedRunTime() {
        return mLastFailedRunTime;
    }

    public boolean shouldDump(int filterUid) {
        return filterUid == -1 || UserHandle.getAppId(getUid()) == filterUid
                || UserHandle.getAppId(getSourceUid()) == filterUid;
    }

    /**
     * @return Whether or not this job is ready to run, based on its requirements. This is true if
     * the constraints are satisfied <strong>or</strong> the deadline on the job has expired.
     * TODO: This function is called a *lot*.  We should probably just have it check an
     * already-computed boolean, which we updated whenever we see one of the states it depends
     * on here change.
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
            CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW | CONSTRAINT_STORAGE_NOT_LOW |
            CONSTRAINT_TIMING_DELAY |
            CONSTRAINT_CONNECTIVITY | CONSTRAINT_UNMETERED |
            CONSTRAINT_NOT_ROAMING | CONSTRAINT_METERED |
            CONSTRAINT_IDLE | CONSTRAINT_CONTENT_TRIGGER;

    // Soft override covers all non-"functional" constraints
    static final int SOFT_OVERRIDE_CONSTRAINTS =
            CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW | CONSTRAINT_STORAGE_NOT_LOW
                    | CONSTRAINT_TIMING_DELAY | CONSTRAINT_IDLE;

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
        StringBuilder sb = new StringBuilder(128);
        sb.append("JobStatus{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        UserHandle.formatUid(sb, callingUid);
        sb.append("/");
        sb.append(job.getId());
        sb.append(' ');
        sb.append(batteryName);
        sb.append(" u=");
        sb.append(getUserId());
        sb.append(" s=");
        sb.append(getSourceUid());
        if (earliestRunTimeElapsedMillis != NO_EARLIEST_RUNTIME
                || latestRunTimeElapsedMillis != NO_LATEST_RUNTIME) {
            long now = SystemClock.elapsedRealtime();
            sb.append(" TIME=");
            formatRunTime(sb, earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME, now);
            sb.append(":");
            formatRunTime(sb, latestRunTimeElapsedMillis, NO_LATEST_RUNTIME, now);
        }
        if (job.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
            sb.append(" NET=");
            sb.append(job.getNetworkType());
        }
        if (job.isRequireCharging()) {
            sb.append(" CHARGING");
        }
        if (job.isRequireBatteryNotLow()) {
            sb.append(" BATNOTLOW");
        }
        if (job.isRequireStorageNotLow()) {
            sb.append(" STORENOTLOW");
        }
        if (job.isRequireDeviceIdle()) {
            sb.append(" IDLE");
        }
        if (job.isPeriodic()) {
            sb.append(" PERIODIC");
        }
        if (job.isPersisted()) {
            sb.append(" PERSISTED");
        }
        if ((satisfiedConstraints&CONSTRAINT_APP_NOT_IDLE) == 0) {
            sb.append(" WAIT:APP_NOT_IDLE");
        }
        if ((satisfiedConstraints&CONSTRAINT_DEVICE_NOT_DOZING) == 0) {
            sb.append(" WAIT:DEV_NOT_DOZING");
        }
        if (job.getTriggerContentUris() != null) {
            sb.append(" URIS=");
            sb.append(Arrays.toString(job.getTriggerContentUris()));
        }
        if (numFailures != 0) {
            sb.append(" failures=");
            sb.append(numFailures);
        }
        if (isReady()) {
            sb.append(" READY");
        }
        sb.append("}");
        return sb.toString();
    }

    private void formatRunTime(PrintWriter pw, long runtime, long  defaultValue, long now) {
        if (runtime == defaultValue) {
            pw.print("none");
        } else {
            TimeUtils.formatDuration(runtime - now, pw);
        }
    }

    private void formatRunTime(StringBuilder sb, long runtime, long  defaultValue, long now) {
        if (runtime == defaultValue) {
            sb.append("none");
        } else {
            TimeUtils.formatDuration(runtime - now, sb);
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
        if ((constraints& CONSTRAINT_BATTERY_NOT_LOW) != 0) {
            pw.print(" BATTERY_NOT_LOW");
        }
        if ((constraints& CONSTRAINT_STORAGE_NOT_LOW) != 0) {
            pw.print(" STORAGE_NOT_LOW");
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
        if ((constraints&CONSTRAINT_METERED) != 0) {
            pw.print(" METERED");
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

    private void dumpJobWorkItem(PrintWriter pw, String prefix, JobWorkItem work, int index) {
        pw.print(prefix); pw.print("  #"); pw.print(index); pw.print(": #");
        pw.print(work.getWorkId()); pw.print(" "); pw.print(work.getDeliveryCount());
        pw.print("x "); pw.println(work.getIntent());
        if (work.getGrants() != null) {
            pw.print(prefix); pw.println("  URI grants:");
            ((GrantedUriPermissions)work.getGrants()).dump(pw, prefix + "    ");
        }
    }

    // Dumpsys infrastructure
    public void dump(PrintWriter pw, String prefix, boolean full, long elapsedRealtimeMillis) {
        pw.print(prefix); UserHandle.formatUid(pw, callingUid);
        pw.print(" tag="); pw.println(tag);
        pw.print(prefix);
        pw.print("Source: uid="); UserHandle.formatUid(pw, getSourceUid());
        pw.print(" user="); pw.print(getSourceUserId());
        pw.print(" pkg="); pw.println(getSourcePackageName());
        if (full) {
            pw.print(prefix); pw.println("JobInfo:");
            pw.print(prefix); pw.print("  Service: ");
            pw.println(job.getService().flattenToShortString());
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
            pw.print(job.isRequireCharging()); pw.print(" batteryNotLow=");
            pw.print(job.isRequireBatteryNotLow()); pw.print(" deviceIdle=");
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
            if (job.getExtras() != null && !job.getExtras().maybeIsEmpty()) {
                pw.print(prefix); pw.print("  Extras: ");
                pw.println(job.getExtras().toShortString());
            }
            if (job.getTransientExtras() != null && !job.getTransientExtras().maybeIsEmpty()) {
                pw.print(prefix); pw.print("  Transient extras: ");
                pw.println(job.getTransientExtras().toShortString());
            }
            if (job.getClipData() != null) {
                pw.print(prefix); pw.print("  Clip data: ");
                StringBuilder b = new StringBuilder(128);
                job.getClipData().toShortString(b);
                pw.println(b);
            }
            if (uriPerms != null) {
                pw.print(prefix); pw.println("  Granted URI permissions:");
                uriPerms.dump(pw, prefix + "  ");
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
        if (trackingControllers != 0) {
            pw.print(prefix); pw.print("Tracking:");
            if ((trackingControllers&TRACKING_BATTERY) != 0) pw.print(" BATTERY");
            if ((trackingControllers&TRACKING_CONNECTIVITY) != 0) pw.print(" CONNECTIVITY");
            if ((trackingControllers&TRACKING_CONTENT) != 0) pw.print(" CONTENT");
            if ((trackingControllers&TRACKING_IDLE) != 0) pw.print(" IDLE");
            if ((trackingControllers&TRACKING_STORAGE) != 0) pw.print(" STORAGE");
            if ((trackingControllers&TRACKING_TIME) != 0) pw.print(" TIME");
            pw.println();
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
        if (pendingWork != null && pendingWork.size() > 0) {
            pw.print(prefix); pw.println("Pending work:");
            for (int i = 0; i < pendingWork.size(); i++) {
                dumpJobWorkItem(pw, prefix, pendingWork.get(i), i);
            }
        }
        if (executingWork != null && executingWork.size() > 0) {
            pw.print(prefix); pw.println("Executing work:");
            for (int i = 0; i < executingWork.size(); i++) {
                dumpJobWorkItem(pw, prefix, executingWork.get(i), i);
            }
        }
        pw.print(prefix); pw.print("Enqueue time: ");
        TimeUtils.formatDuration(enqueueTime, elapsedRealtimeMillis, pw);
        pw.println();
        pw.print(prefix); pw.print("Run time: earliest=");
        formatRunTime(pw, earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME, elapsedRealtimeMillis);
        pw.print(", latest=");
        formatRunTime(pw, latestRunTimeElapsedMillis, NO_LATEST_RUNTIME, elapsedRealtimeMillis);
        pw.println();
        if (numFailures != 0) {
            pw.print(prefix); pw.print("Num failures: "); pw.println(numFailures);
        }
        final Time t = new Time();
        final String format = "%Y-%m-%d %H:%M:%S";
        if (mLastSuccessfulRunTime != 0) {
            pw.print(prefix); pw.print("Last successful run: ");
            t.set(mLastSuccessfulRunTime);
            pw.println(t.format(format));
        }
        if (mLastFailedRunTime != 0) {
            pw.print(prefix); pw.print("Last failed run: ");
            t.set(mLastFailedRunTime);
            pw.println(t.format(format));
        }
    }
}
