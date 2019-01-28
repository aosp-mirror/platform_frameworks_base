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

import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.net.Network;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.format.Time;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.server.LocalServices;
import com.android.server.job.GrantedUriPermissions;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobServerProtoEnums;
import com.android.server.job.JobStatusDumpProto;
import com.android.server.job.JobStatusShortInfoProto;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Uniquely identifies a job internally.
 * Created from the public {@link android.app.job.JobInfo} object when it lands on the scheduler.
 * Contains current state of the requirements of the job, as well as a function to evaluate
 * whether it's ready to run.
 * This object is shared among the various controllers - hence why the different fields are atomic.
 * This isn't strictly necessary because each controller is only interested in a specific field,
 * and the receivers that are listening for global state change will all run on the main looper,
 * but we don't enforce that so this is safer.
 *
 * Test: atest com.android.server.job.controllers.JobStatusTest
 * @hide
 */
public final class JobStatus {
    static final String TAG = "JobSchedulerService";
    static final boolean DEBUG = JobSchedulerService.DEBUG;

    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final long NO_EARLIEST_RUNTIME = 0L;

    static final int CONSTRAINT_CHARGING = JobInfo.CONSTRAINT_FLAG_CHARGING; // 1 < 0
    static final int CONSTRAINT_IDLE = JobInfo.CONSTRAINT_FLAG_DEVICE_IDLE;  // 1 << 2
    static final int CONSTRAINT_BATTERY_NOT_LOW = JobInfo.CONSTRAINT_FLAG_BATTERY_NOT_LOW; // 1 << 1
    static final int CONSTRAINT_STORAGE_NOT_LOW = JobInfo.CONSTRAINT_FLAG_STORAGE_NOT_LOW; // 1 << 3
    static final int CONSTRAINT_TIMING_DELAY = 1<<31;
    static final int CONSTRAINT_DEADLINE = 1<<30;
    static final int CONSTRAINT_CONNECTIVITY = 1<<28;
    static final int CONSTRAINT_CONTENT_TRIGGER = 1<<26;
    static final int CONSTRAINT_DEVICE_NOT_DOZING = 1 << 25; // Implicit constraint
    static final int CONSTRAINT_WITHIN_QUOTA = 1 << 24;      // Implicit constraint
    static final int CONSTRAINT_BACKGROUND_NOT_RESTRICTED = 1 << 22; // Implicit constraint

    /**
     * The constraints that we want to log to statsd.
     *
     * Constraints that can be inferred from other atoms have been excluded to avoid logging too
     * much information and to reduce redundancy:
     *
     * * CONSTRAINT_CHARGING can be inferred with PluggedStateChanged (Atom #32)
     * * CONSTRAINT_BATTERY_NOT_LOW can be inferred with BatteryLevelChanged (Atom #30)
     * * CONSTRAINT_CONNECTIVITY can be partially inferred with ConnectivityStateChanged
     * (Atom #98) and BatterySaverModeStateChanged (Atom #20).
     * * CONSTRAINT_DEVICE_NOT_DOZING can be mostly inferred with DeviceIdleModeStateChanged
     * (Atom #21)
     * * CONSTRAINT_BACKGROUND_NOT_RESTRICTED can be inferred with BatterySaverModeStateChanged
     * (Atom #20)
     */
    private static final int STATSD_CONSTRAINTS_TO_LOG = CONSTRAINT_CONTENT_TRIGGER
            | CONSTRAINT_DEADLINE
            | CONSTRAINT_IDLE
            | CONSTRAINT_STORAGE_NOT_LOW
            | CONSTRAINT_TIMING_DELAY
            | CONSTRAINT_WITHIN_QUOTA;

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
    /**
     * Uid of the package requesting this job.  This can differ from the "source"
     * uid when the job was scheduled on the app's behalf, such as with the jobs
     * that underly Sync Manager operation.
     */
    final int callingUid;
    final int targetSdkVersion;
    final String batteryName;

    /**
     * Identity of the app in which the job is hosted.
     */
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

    /**
     * Current standby heartbeat when this job was scheduled or last ran.  Used to
     * pin the runnability check regardless of the job's app moving between buckets.
     */
    private final long baseHeartbeat;

    /**
     * Which app standby bucket this job's app is in.  Updated when the app is moved to a
     * different bucket.
     */
    private int standbyBucket;

    /**
     * Debugging: timestamp if we ever defer this job based on standby bucketing, this
     * is when we did so.
     */
    private long whenStandbyDeferred;

    // Constraints.
    final int requiredConstraints;
    private final int mRequiredConstraintsOfInterest;
    int satisfiedConstraints = 0;
    private int mSatisfiedConstraintsOfInterest = 0;

    // Set to true if doze constraint was satisfied due to app being whitelisted.
    public boolean dozeWhitelisted;

    // Set to true when the app is "active" per AppStateTracker
    public boolean uidActive;

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
     * Flag for {@link #trackingControllers}: the quota controller is currently tracking this job.
     */
    public static final int TRACKING_QUOTA = 1 << 6;

    /**
     * Bit mask of controllers that are currently tracking the job.
     */
    private int trackingControllers;

    /**
     * Flag for {@link #mInternalFlags}: this job was scheduled when the app that owns the job
     * service (not necessarily the caller) was in the foreground and the job has no time
     * constraints, which makes it exempted from the battery saver job restriction.
     *
     * @hide
     */
    public static final int INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION = 1 << 0;

    /**
     * Versatile, persistable flags for a job that's updated within the system server,
     * as opposed to {@link JobInfo#flags} that's set by callers.
     */
    private int mInternalFlags;

    // These are filled in by controllers when preparing for execution.
    public ArraySet<Uri> changedUris;
    public ArraySet<String> changedAuthorities;
    public Network network;

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

    private long totalNetworkBytes = JobInfo.NETWORK_BYTES_UNKNOWN;

    /////// Booleans that track if a job is ready to run. They should be updated whenever dependent
    /////// states change.

    /**
     * The deadline for the job has passed. This is only good for non-periodic jobs. A periodic job
     * should only run if its constraints are satisfied.
     * Computed as: NOT periodic AND has deadline constraint AND deadline constraint satisfied.
     */
    private boolean mReadyDeadlineSatisfied;

    /**
     * The device isn't Dozing or this job will be in the foreground. This implicit constraint must
     * be satisfied.
     */
    private boolean mReadyNotDozing;

    /**
     * The job is not restricted from running in the background (due to Battery Saver). This
     * implicit constraint must be satisfied.
     */
    private boolean mReadyNotRestrictedInBg;

    /** The job is within its quota based on its standby bucket. */
    private boolean mReadyWithinQuota;

    /** Provide a handle to the service that this job will be run on. */
    public int getServiceToken() {
        return callingUid;
    }

    /**
     * Core constructor for JobStatus instances.  All other ctors funnel down to this one.
     *
     * @param job The actual requested parameters for the job
     * @param callingUid Identity of the app that is scheduling the job.  This may not be the
     *     app in which the job is implemented; such as with sync jobs.
     * @param targetSdkVersion The targetSdkVersion of the app in which the job will run.
     * @param sourcePackageName The package name of the app in which the job will run.
     * @param sourceUserId The user in which the job will run
     * @param standbyBucket The standby bucket that the source package is currently assigned to,
     *     cached here for speed of handling during runnability evaluations (and updated when bucket
     *     assignments are changed)
     * @param heartbeat Timestamp of when the job was created, in the standby-related
     *     timebase.
     * @param tag A string associated with the job for debugging/logging purposes.
     * @param numFailures Count of how many times this job has requested a reschedule because
     *     its work was not yet finished.
     * @param earliestRunTimeElapsedMillis Milestone: earliest point in time at which the job
     *     is to be considered runnable
     * @param latestRunTimeElapsedMillis Milestone: point in time at which the job will be
     *     considered overdue
     * @param lastSuccessfulRunTime When did we last run this job to completion?
     * @param lastFailedRunTime When did we last run this job only to have it stop incomplete?
     * @param internalFlags Non-API property flags about this job
     */
    private JobStatus(JobInfo job, int callingUid, int targetSdkVersion, String sourcePackageName,
            int sourceUserId, int standbyBucket, long heartbeat, String tag, int numFailures,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis,
            long lastSuccessfulRunTime, long lastFailedRunTime, int internalFlags) {
        this.job = job;
        this.callingUid = callingUid;
        this.targetSdkVersion = targetSdkVersion;
        this.standbyBucket = standbyBucket;
        this.baseHeartbeat = heartbeat;

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
        if (job.getRequiredNetwork() != null) {
            requiredConstraints |= CONSTRAINT_CONNECTIVITY;
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
        mRequiredConstraintsOfInterest = requiredConstraints & CONSTRAINTS_OF_INTEREST;
        mReadyNotDozing = (job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0;

        mLastSuccessfulRunTime = lastSuccessfulRunTime;
        mLastFailedRunTime = lastFailedRunTime;

        mInternalFlags = internalFlags;

        updateEstimatedNetworkBytesLocked();

        if (job.getRequiredNetwork() != null) {
            // Later, when we check if a given network satisfies the required
            // network, we need to know the UID that is requesting it, so push
            // our source UID into place.
            job.getRequiredNetwork().networkCapabilities.setSingleUid(this.sourceUid);
        }
    }

    /** Copy constructor: used specifically when cloning JobStatus objects for persistence,
     *   so we preserve RTC window bounds if the source object has them. */
    public JobStatus(JobStatus jobStatus) {
        this(jobStatus.getJob(), jobStatus.getUid(), jobStatus.targetSdkVersion,
                jobStatus.getSourcePackageName(), jobStatus.getSourceUserId(),
                jobStatus.getStandbyBucket(), jobStatus.getBaseHeartbeat(),
                jobStatus.getSourceTag(), jobStatus.getNumFailures(),
                jobStatus.getEarliestRunTime(), jobStatus.getLatestRunTimeElapsed(),
                jobStatus.getLastSuccessfulRunTime(), jobStatus.getLastFailedRunTime(),
                jobStatus.getInternalFlags());
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
     * We consider a freshly loaded job to no longer be in back-off, and the associated
     * standby bucket is whatever the OS thinks it should be at this moment.
     */
    public JobStatus(JobInfo job, int callingUid, String sourcePkgName, int sourceUserId,
            int standbyBucket, long baseHeartbeat, String sourceTag,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis,
            long lastSuccessfulRunTime, long lastFailedRunTime,
            Pair<Long, Long> persistedExecutionTimesUTC,
            int innerFlags) {
        this(job, callingUid, resolveTargetSdkVersion(job), sourcePkgName, sourceUserId,
                standbyBucket, baseHeartbeat,
                sourceTag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime, innerFlags);

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
    public JobStatus(JobStatus rescheduling, long newBaseHeartbeat,
            long newEarliestRuntimeElapsedMillis,
            long newLatestRuntimeElapsedMillis, int backoffAttempt,
            long lastSuccessfulRunTime, long lastFailedRunTime) {
        this(rescheduling.job, rescheduling.getUid(), resolveTargetSdkVersion(rescheduling.job),
                rescheduling.getSourcePackageName(), rescheduling.getSourceUserId(),
                rescheduling.getStandbyBucket(), newBaseHeartbeat,
                rescheduling.getSourceTag(), backoffAttempt, newEarliestRuntimeElapsedMillis,
                newLatestRuntimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime, rescheduling.getInternalFlags());
    }

    /**
     * Create a newly scheduled job.
     * @param callingUid Uid of the package that scheduled this job.
     * @param sourcePkg Package name of the app that will actually run the job.  Null indicates
     *     that the calling package is the source.
     * @param sourceUserId User id for whom this job is scheduled. -1 indicates this is same as the
     *     caller.
     */
    public static JobStatus createFromJobInfo(JobInfo job, int callingUid, String sourcePkg,
            int sourceUserId, String tag) {
        final long elapsedNow = sElapsedRealtimeClock.millis();
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
        String jobPackage = (sourcePkg != null) ? sourcePkg : job.getService().getPackageName();

        int standbyBucket = JobSchedulerService.standbyBucketForPackage(jobPackage,
                sourceUserId, elapsedNow);
        JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        long currentHeartbeat = js != null
                ? js.baseHeartbeatForApp(jobPackage, sourceUserId, standbyBucket)
                : 0;
        return new JobStatus(job, callingUid, resolveTargetSdkVersion(job), sourcePkg, sourceUserId,
                standbyBucket, currentHeartbeat, tag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                0 /* lastSuccessfulRunTime */, 0 /* lastFailedRunTime */,
                /*innerFlags=*/ 0);
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
        updateEstimatedNetworkBytesLocked();
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
            updateEstimatedNetworkBytesLocked();
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
            incomingJob.updateEstimatedNetworkBytesLocked();
        } else {
            // We are completely stopping the job...  need to clean up work.
            ungrantWorkList(am, pendingWork);
            pendingWork = null;
            ungrantWorkList(am, executingWork);
            executingWork = null;
        }
        updateEstimatedNetworkBytesLocked();
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

    public int getTargetSdkVersion() {
        return targetSdkVersion;
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

    public int getStandbyBucket() {
        return standbyBucket;
    }

    public long getBaseHeartbeat() {
        return baseHeartbeat;
    }

    public void setStandbyBucket(int newBucket) {
        standbyBucket = newBucket;
    }

    // Called only by the standby monitoring code
    public long getWhenStandbyDeferred() {
        return whenStandbyDeferred;
    }

    // Called only by the standby monitoring code
    public void setWhenStandbyDeferred(long now) {
        whenStandbyDeferred = now;
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

    public int getInternalFlags() {
        return mInternalFlags;
    }

    public void addInternalFlags(int flags) {
        mInternalFlags |= flags;
    }

    public int getSatisfiedConstraintFlags() {
        return satisfiedConstraints;
    }

    public void maybeAddForegroundExemption(Predicate<Integer> uidForegroundChecker) {
        // Jobs with time constraints shouldn't be exempted.
        if (job.hasEarlyConstraint() || job.hasLateConstraint()) {
            return;
        }
        // Already exempted, skip the foreground check.
        if ((mInternalFlags & INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION) != 0) {
            return;
        }
        if (uidForegroundChecker.test(getSourceUid())) {
            addInternalFlags(INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION);
        }
    }

    private void updateEstimatedNetworkBytesLocked() {
        totalNetworkBytes = computeEstimatedNetworkBytesLocked();
    }

    private long computeEstimatedNetworkBytesLocked() {
        // If any component of the job has unknown usage, we don't have a
        // complete picture of what data will be used, and we have to treat the
        // entire job as unknown.
        long totalNetworkBytes = 0;
        long networkBytes = job.getEstimatedNetworkBytes();
        if (networkBytes == JobInfo.NETWORK_BYTES_UNKNOWN) {
            return JobInfo.NETWORK_BYTES_UNKNOWN;
        } else {
            totalNetworkBytes += networkBytes;
        }
        if (pendingWork != null) {
            for (int i = 0; i < pendingWork.size(); i++) {
                networkBytes = pendingWork.get(i).getEstimatedNetworkBytes();
                if (networkBytes == JobInfo.NETWORK_BYTES_UNKNOWN) {
                    return JobInfo.NETWORK_BYTES_UNKNOWN;
                } else {
                    totalNetworkBytes += networkBytes;
                }
            }
        }
        return totalNetworkBytes;
    }

    public long getEstimatedNetworkBytes() {
        return totalNetworkBytes;
    }

    /** Does this job have any sort of networking constraint? */
    public boolean hasConnectivityConstraint() {
        return (requiredConstraints&CONSTRAINT_CONNECTIVITY) != 0;
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

    /**
     * Return the fractional position of "now" within the "run time" window of
     * this job.
     * <p>
     * For example, if the earliest run time was 10 minutes ago, and the latest
     * run time is 30 minutes from now, this would return 0.25.
     * <p>
     * If the job has no window defined, returns 1. When only an earliest or
     * latest time is defined, it's treated as an infinitely small window at
     * that time.
     */
    public float getFractionRunTime() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        if (earliestRunTimeElapsedMillis == 0 && latestRunTimeElapsedMillis == Long.MAX_VALUE) {
            return 1;
        } else if (earliestRunTimeElapsedMillis == 0) {
            return now >= latestRunTimeElapsedMillis ? 1 : 0;
        } else if (latestRunTimeElapsedMillis == Long.MAX_VALUE) {
            return now >= earliestRunTimeElapsedMillis ? 1 : 0;
        } else {
            if (now <= earliestRunTimeElapsedMillis) {
                return 0;
            } else if (now >= latestRunTimeElapsedMillis) {
                return 1;
            } else {
                return (float) (now - earliestRunTimeElapsedMillis)
                        / (float) (latestRunTimeElapsedMillis - earliestRunTimeElapsedMillis);
            }
        }
    }

    public Pair<Long, Long> getPersistedUtcTimes() {
        return mPersistedUtcTimes;
    }

    public void clearPersistedUtcTimes() {
        mPersistedUtcTimes = null;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setChargingConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CHARGING, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setBatteryNotLowConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_BATTERY_NOT_LOW, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setStorageNotLowConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_STORAGE_NOT_LOW, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setTimingDelayConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_TIMING_DELAY, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setDeadlineConstraintSatisfied(boolean state) {
        if (setConstraintSatisfied(CONSTRAINT_DEADLINE, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyDeadlineSatisfied = !job.isPeriodic() && hasDeadlineConstraint() && state;
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setIdleConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_IDLE, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setConnectivityConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONNECTIVITY, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setContentTriggerConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONTENT_TRIGGER, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setDeviceNotDozingConstraintSatisfied(boolean state, boolean whitelisted) {
        dozeWhitelisted = whitelisted;
        if (setConstraintSatisfied(CONSTRAINT_DEVICE_NOT_DOZING, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyNotDozing = state || (job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0;
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setBackgroundNotRestrictedConstraintSatisfied(boolean state) {
        if (setConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyNotRestrictedInBg = state;
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setQuotaConstraintSatisfied(boolean state) {
        if (setConstraintSatisfied(CONSTRAINT_WITHIN_QUOTA, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyWithinQuota = state;
            return true;
        }
        return false;
    }

    /** @return true if the state was changed, false otherwise. */
    boolean setUidActive(final boolean newActiveState) {
        if (newActiveState != uidActive) {
            uidActive = newActiveState;
            return true;
        }
        return false; /* unchanged */
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setConstraintSatisfied(int constraint, boolean state) {
        boolean old = (satisfiedConstraints&constraint) != 0;
        if (old == state) {
            return false;
        }
        satisfiedConstraints = (satisfiedConstraints&~constraint) | (state ? constraint : 0);
        mSatisfiedConstraintsOfInterest = satisfiedConstraints & CONSTRAINTS_OF_INTEREST;
        if ((STATSD_CONSTRAINTS_TO_LOG & constraint) != 0) {
            StatsLog.write_non_chained(StatsLog.SCHEDULED_JOB_CONSTRAINT_CHANGED,
                    sourceUid, null, getBatteryName(), getProtoConstraint(constraint),
                    state ? StatsLog.SCHEDULED_JOB_CONSTRAINT_CHANGED__STATE__SATISFIED
                            : StatsLog.SCHEDULED_JOB_CONSTRAINT_CHANGED__STATE__UNSATISFIED);
        }
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

    /**
     * @return Whether or not this job is ready to run, based on its requirements.
     */
    public boolean isReady() {
        return isReady(mSatisfiedConstraintsOfInterest);
    }

    /**
     * @return Whether or not this job would be ready to run if it had the specified constraint
     * granted, based on its requirements.
     */
    boolean wouldBeReadyWithConstraint(int constraint) {
        boolean oldValue = false;
        int satisfied = mSatisfiedConstraintsOfInterest;
        switch (constraint) {
            case CONSTRAINT_BACKGROUND_NOT_RESTRICTED:
                oldValue = mReadyNotRestrictedInBg;
                mReadyNotRestrictedInBg = true;
                break;
            case CONSTRAINT_DEADLINE:
                oldValue = mReadyDeadlineSatisfied;
                mReadyDeadlineSatisfied = true;
                break;
            case CONSTRAINT_DEVICE_NOT_DOZING:
                oldValue = mReadyNotDozing;
                mReadyNotDozing = true;
                break;
            case CONSTRAINT_WITHIN_QUOTA:
                oldValue = mReadyWithinQuota;
                mReadyWithinQuota = true;
                break;
            default:
                satisfied |= constraint;
                break;
        }

        boolean toReturn = isReady(satisfied);

        switch (constraint) {
            case CONSTRAINT_BACKGROUND_NOT_RESTRICTED:
                mReadyNotRestrictedInBg = oldValue;
                break;
            case CONSTRAINT_DEADLINE:
                mReadyDeadlineSatisfied = oldValue;
                break;
            case CONSTRAINT_DEVICE_NOT_DOZING:
                mReadyNotDozing = oldValue;
                break;
            case CONSTRAINT_WITHIN_QUOTA:
                mReadyWithinQuota = oldValue;
                break;
        }
        return toReturn;
    }

    private boolean isReady(int satisfiedConstraints) {
        // Quota constraints trumps all other constraints.
        if (!mReadyWithinQuota) {
            return false;
        }
        // Deadline constraint trumps other constraints besides quota (except for periodic jobs
        // where deadline is an implementation detail. A periodic job should only run if its
        // constraints are satisfied).
        // DeviceNotDozing implicit constraint must be satisfied
        // NotRestrictedInBackground implicit constraint must be satisfied
        return mReadyNotDozing && mReadyNotRestrictedInBg && (mReadyDeadlineSatisfied
                || isConstraintsSatisfied(satisfiedConstraints));
    }

    static final int CONSTRAINTS_OF_INTEREST = CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW
            | CONSTRAINT_STORAGE_NOT_LOW | CONSTRAINT_TIMING_DELAY | CONSTRAINT_CONNECTIVITY
            | CONSTRAINT_IDLE | CONSTRAINT_CONTENT_TRIGGER;

    // Soft override covers all non-"functional" constraints
    static final int SOFT_OVERRIDE_CONSTRAINTS =
            CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW | CONSTRAINT_STORAGE_NOT_LOW
                    | CONSTRAINT_TIMING_DELAY | CONSTRAINT_IDLE;

    /**
     * @return Whether the constraints set on this job are satisfied.
     */
    public boolean isConstraintsSatisfied() {
        return isConstraintsSatisfied(mSatisfiedConstraintsOfInterest);
    }

    private boolean isConstraintsSatisfied(int satisfiedConstraints) {
        if (overrideState == OVERRIDE_FULL) {
            // force override: the job is always runnable
            return true;
        }

        int sat = satisfiedConstraints;
        if (overrideState == OVERRIDE_SOFT) {
            // override: pretend all 'soft' requirements are satisfied
            sat |= (requiredConstraints & SOFT_OVERRIDE_CONSTRAINTS);
        }

        return (sat & mRequiredConstraintsOfInterest) == mRequiredConstraintsOfInterest;
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
            long now = sElapsedRealtimeClock.millis();
            sb.append(" TIME=");
            formatRunTime(sb, earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME, now);
            sb.append(":");
            formatRunTime(sb, latestRunTimeElapsedMillis, NO_LATEST_RUNTIME, now);
        }
        if (job.getRequiredNetwork() != null) {
            sb.append(" NET");
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

    /**
     * Convenience function to dump data that identifies a job uniquely to proto. This is intended
     * to mimic {@link #toShortString}.
     */
    public void writeToShortProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(JobStatusShortInfoProto.CALLING_UID, callingUid);
        proto.write(JobStatusShortInfoProto.JOB_ID, job.getId());
        proto.write(JobStatusShortInfoProto.BATTERY_NAME, batteryName);

        proto.end(token);
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
        if ((constraints&CONSTRAINT_CONTENT_TRIGGER) != 0) {
            pw.print(" CONTENT_TRIGGER");
        }
        if ((constraints&CONSTRAINT_DEVICE_NOT_DOZING) != 0) {
            pw.print(" DEVICE_NOT_DOZING");
        }
        if ((constraints&CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0) {
            pw.print(" BACKGROUND_NOT_RESTRICTED");
        }
        if ((constraints & CONSTRAINT_WITHIN_QUOTA) != 0) {
            pw.print(" WITHIN_QUOTA");
        }
        if (constraints != 0) {
            pw.print(" [0x");
            pw.print(Integer.toHexString(constraints));
            pw.print("]");
        }
    }

    /** Returns a {@link JobServerProtoEnums.Constraint} enum value for the given constraint. */
    private int getProtoConstraint(int constraint) {
        switch (constraint) {
            case CONSTRAINT_BACKGROUND_NOT_RESTRICTED:
                return JobServerProtoEnums.CONSTRAINT_BACKGROUND_NOT_RESTRICTED;
            case CONSTRAINT_BATTERY_NOT_LOW:
                return JobServerProtoEnums.CONSTRAINT_BATTERY_NOT_LOW;
            case CONSTRAINT_CHARGING:
                return JobServerProtoEnums.CONSTRAINT_CHARGING;
            case CONSTRAINT_CONNECTIVITY:
                return JobServerProtoEnums.CONSTRAINT_CONNECTIVITY;
            case CONSTRAINT_CONTENT_TRIGGER:
                return JobServerProtoEnums.CONSTRAINT_CONTENT_TRIGGER;
            case CONSTRAINT_DEADLINE:
                return JobServerProtoEnums.CONSTRAINT_DEADLINE;
            case CONSTRAINT_DEVICE_NOT_DOZING:
                return JobServerProtoEnums.CONSTRAINT_DEVICE_NOT_DOZING;
            case CONSTRAINT_IDLE:
                return JobServerProtoEnums.CONSTRAINT_IDLE;
            case CONSTRAINT_STORAGE_NOT_LOW:
                return JobServerProtoEnums.CONSTRAINT_STORAGE_NOT_LOW;
            case CONSTRAINT_TIMING_DELAY:
                return JobServerProtoEnums.CONSTRAINT_TIMING_DELAY;
            case CONSTRAINT_WITHIN_QUOTA:
                return JobServerProtoEnums.CONSTRAINT_WITHIN_QUOTA;
            default:
                return JobServerProtoEnums.CONSTRAINT_UNKNOWN;
        }
    }

    /** Writes constraints to the given repeating proto field. */
    void dumpConstraints(ProtoOutputStream proto, long fieldId, int constraints) {
        if ((constraints & CONSTRAINT_CHARGING) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_CHARGING);
        }
        if ((constraints & CONSTRAINT_BATTERY_NOT_LOW) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_BATTERY_NOT_LOW);
        }
        if ((constraints & CONSTRAINT_STORAGE_NOT_LOW) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_STORAGE_NOT_LOW);
        }
        if ((constraints & CONSTRAINT_TIMING_DELAY) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_TIMING_DELAY);
        }
        if ((constraints & CONSTRAINT_DEADLINE) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_DEADLINE);
        }
        if ((constraints & CONSTRAINT_IDLE) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_IDLE);
        }
        if ((constraints & CONSTRAINT_CONNECTIVITY) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_CONNECTIVITY);
        }
        if ((constraints & CONSTRAINT_CONTENT_TRIGGER) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_CONTENT_TRIGGER);
        }
        if ((constraints & CONSTRAINT_DEVICE_NOT_DOZING) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_DEVICE_NOT_DOZING);
        }
        if ((constraints & CONSTRAINT_WITHIN_QUOTA) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_WITHIN_QUOTA);
        }
        if ((constraints & CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0) {
            proto.write(fieldId, JobServerProtoEnums.CONSTRAINT_BACKGROUND_NOT_RESTRICTED);
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

    private void dumpJobWorkItem(ProtoOutputStream proto, long fieldId, JobWorkItem work) {
        final long token = proto.start(fieldId);

        proto.write(JobStatusDumpProto.JobWorkItem.WORK_ID, work.getWorkId());
        proto.write(JobStatusDumpProto.JobWorkItem.DELIVERY_COUNT, work.getDeliveryCount());
        if (work.getIntent() != null) {
            work.getIntent().writeToProto(proto, JobStatusDumpProto.JobWorkItem.INTENT);
        }
        Object grants = work.getGrants();
        if (grants != null) {
            ((GrantedUriPermissions) grants).dump(proto, JobStatusDumpProto.JobWorkItem.URI_GRANTS);
        }

        proto.end(token);
    }

    /**
     * Returns a bucket name based on the normalized bucket indices, not the AppStandby constants.
     */
    String getBucketName() {
        return bucketName(standbyBucket);
    }

    /**
     * Returns a bucket name based on the normalized bucket indices, not the AppStandby constants.
     */
    static String bucketName(int standbyBucket) {
        switch (standbyBucket) {
            case 0: return "ACTIVE";
            case 1: return "WORKING_SET";
            case 2: return "FREQUENT";
            case 3: return "RARE";
            case 4: return "NEVER";
            default:
                return "Unknown: " + standbyBucket;
        }
    }

    private static int resolveTargetSdkVersion(JobInfo job) {
        return LocalServices.getService(PackageManagerInternal.class)
                .getPackageTargetSdkVersion(job.getService().getPackageName());
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
                pw.print(prefix); pw.print("  Priority: ");
                pw.println(JobInfo.getPriorityString(job.getPriority()));
            }
            if (job.getFlags() != 0) {
                pw.print(prefix); pw.print("  Flags: ");
                pw.println(Integer.toHexString(job.getFlags()));
            }
            if (getInternalFlags() != 0) {
                pw.print(prefix); pw.print("  Internal flags: ");
                pw.print(Integer.toHexString(getInternalFlags()));

                if ((getInternalFlags()&INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION) != 0) {
                    pw.print(" HAS_FOREGROUND_EXEMPTION");
                }
                pw.println();
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
            if (job.getRequiredNetwork() != null) {
                pw.print(prefix); pw.print("  Network type: ");
                pw.println(job.getRequiredNetwork());
            }
            if (totalNetworkBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                pw.print(prefix); pw.print("  Network bytes: ");
                pw.println(totalNetworkBytes);
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
            dumpConstraints(pw,
                    ((requiredConstraints | CONSTRAINT_WITHIN_QUOTA) & ~satisfiedConstraints));
            pw.println();
            if (dozeWhitelisted) {
                pw.print(prefix); pw.println("Doze whitelisted: true");
            }
            if (uidActive) {
                pw.print(prefix); pw.println("Uid: active");
            }
            if (job.isExemptedFromAppStandby()) {
                pw.print(prefix); pw.println("Is exempted from app standby");
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
            if ((trackingControllers & TRACKING_QUOTA) != 0) pw.print(" QUOTA");
            pw.println();
        }

        pw.print(prefix); pw.println("Implicit constraints:");
        pw.print(prefix); pw.print("  readyNotDozing: ");
        pw.println(mReadyNotDozing);
        pw.print(prefix); pw.print("  readyNotRestrictedInBg: ");
        pw.println(mReadyNotRestrictedInBg);
        if (!job.isPeriodic() && hasDeadlineConstraint()) {
            pw.print(prefix); pw.print("  readyDeadlineSatisfied: ");
            pw.println(mReadyDeadlineSatisfied);
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
        if (network != null) {
            pw.print(prefix); pw.print("Network: "); pw.println(network);
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
        pw.print(prefix); pw.print("Standby bucket: ");
        pw.println(getBucketName());
        if (standbyBucket > 0) {
            pw.print(prefix); pw.print("Base heartbeat: ");
            pw.println(baseHeartbeat);
        }
        if (whenStandbyDeferred != 0) {
            pw.print(prefix); pw.print("  Deferred since: ");
            TimeUtils.formatDuration(whenStandbyDeferred, elapsedRealtimeMillis, pw);
            pw.println();
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

    public void dump(ProtoOutputStream proto, long fieldId, boolean full, long elapsedRealtimeMillis) {
        final long token = proto.start(fieldId);

        proto.write(JobStatusDumpProto.CALLING_UID, callingUid);
        proto.write(JobStatusDumpProto.TAG, tag);
        proto.write(JobStatusDumpProto.SOURCE_UID, getSourceUid());
        proto.write(JobStatusDumpProto.SOURCE_USER_ID, getSourceUserId());
        proto.write(JobStatusDumpProto.SOURCE_PACKAGE_NAME, getSourcePackageName());
        proto.write(JobStatusDumpProto.INTERNAL_FLAGS, getInternalFlags());

        if (full) {
            final long jiToken = proto.start(JobStatusDumpProto.JOB_INFO);

            job.getService().writeToProto(proto, JobStatusDumpProto.JobInfo.SERVICE);

            proto.write(JobStatusDumpProto.JobInfo.IS_PERIODIC, job.isPeriodic());
            proto.write(JobStatusDumpProto.JobInfo.PERIOD_INTERVAL_MS, job.getIntervalMillis());
            proto.write(JobStatusDumpProto.JobInfo.PERIOD_FLEX_MS, job.getFlexMillis());

            proto.write(JobStatusDumpProto.JobInfo.IS_PERSISTED, job.isPersisted());
            proto.write(JobStatusDumpProto.JobInfo.PRIORITY, job.getPriority());
            proto.write(JobStatusDumpProto.JobInfo.FLAGS, job.getFlags());

            proto.write(JobStatusDumpProto.JobInfo.REQUIRES_CHARGING, job.isRequireCharging());
            proto.write(JobStatusDumpProto.JobInfo.REQUIRES_BATTERY_NOT_LOW, job.isRequireBatteryNotLow());
            proto.write(JobStatusDumpProto.JobInfo.REQUIRES_DEVICE_IDLE, job.isRequireDeviceIdle());

            if (job.getTriggerContentUris() != null) {
                for (int i = 0; i < job.getTriggerContentUris().length; i++) {
                    final long tcuToken = proto.start(JobStatusDumpProto.JobInfo.TRIGGER_CONTENT_URIS);
                    JobInfo.TriggerContentUri trig = job.getTriggerContentUris()[i];

                    proto.write(JobStatusDumpProto.JobInfo.TriggerContentUri.FLAGS, trig.getFlags());
                    Uri u = trig.getUri();
                    if (u != null) {
                        proto.write(JobStatusDumpProto.JobInfo.TriggerContentUri.URI, u.toString());
                    }

                    proto.end(tcuToken);
                }
                if (job.getTriggerContentUpdateDelay() >= 0) {
                    proto.write(JobStatusDumpProto.JobInfo.TRIGGER_CONTENT_UPDATE_DELAY_MS,
                            job.getTriggerContentUpdateDelay());
                }
                if (job.getTriggerContentMaxDelay() >= 0) {
                    proto.write(JobStatusDumpProto.JobInfo.TRIGGER_CONTENT_MAX_DELAY_MS,
                            job.getTriggerContentMaxDelay());
                }
            }
            if (job.getExtras() != null && !job.getExtras().maybeIsEmpty()) {
                job.getExtras().writeToProto(proto, JobStatusDumpProto.JobInfo.EXTRAS);
            }
            if (job.getTransientExtras() != null && !job.getTransientExtras().maybeIsEmpty()) {
                job.getTransientExtras().writeToProto(proto, JobStatusDumpProto.JobInfo.TRANSIENT_EXTRAS);
            }
            if (job.getClipData() != null) {
                job.getClipData().writeToProto(proto, JobStatusDumpProto.JobInfo.CLIP_DATA);
            }
            if (uriPerms != null) {
                uriPerms.dump(proto, JobStatusDumpProto.JobInfo.GRANTED_URI_PERMISSIONS);
            }
            if (job.getRequiredNetwork() != null) {
                job.getRequiredNetwork().writeToProto(proto, JobStatusDumpProto.JobInfo.REQUIRED_NETWORK);
            }
            if (totalNetworkBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                proto.write(JobStatusDumpProto.JobInfo.TOTAL_NETWORK_BYTES, totalNetworkBytes);
            }
            proto.write(JobStatusDumpProto.JobInfo.MIN_LATENCY_MS, job.getMinLatencyMillis());
            proto.write(JobStatusDumpProto.JobInfo.MAX_EXECUTION_DELAY_MS, job.getMaxExecutionDelayMillis());

            final long bpToken = proto.start(JobStatusDumpProto.JobInfo.BACKOFF_POLICY);
            proto.write(JobStatusDumpProto.JobInfo.Backoff.POLICY, job.getBackoffPolicy());
            proto.write(JobStatusDumpProto.JobInfo.Backoff.INITIAL_BACKOFF_MS,
                    job.getInitialBackoffMillis());
            proto.end(bpToken);

            proto.write(JobStatusDumpProto.JobInfo.HAS_EARLY_CONSTRAINT, job.hasEarlyConstraint());
            proto.write(JobStatusDumpProto.JobInfo.HAS_LATE_CONSTRAINT, job.hasLateConstraint());

            proto.end(jiToken);
        }

        dumpConstraints(proto, JobStatusDumpProto.REQUIRED_CONSTRAINTS, requiredConstraints);
        if (full) {
            dumpConstraints(proto, JobStatusDumpProto.SATISFIED_CONSTRAINTS, satisfiedConstraints);
            dumpConstraints(proto, JobStatusDumpProto.UNSATISFIED_CONSTRAINTS,
                    ((requiredConstraints | CONSTRAINT_WITHIN_QUOTA) & ~satisfiedConstraints));
            proto.write(JobStatusDumpProto.IS_DOZE_WHITELISTED, dozeWhitelisted);
            proto.write(JobStatusDumpProto.IS_UID_ACTIVE, uidActive);
            proto.write(JobStatusDumpProto.IS_EXEMPTED_FROM_APP_STANDBY,
                    job.isExemptedFromAppStandby());
        }

        // Tracking controllers
        if ((trackingControllers&TRACKING_BATTERY) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_BATTERY);
        }
        if ((trackingControllers&TRACKING_CONNECTIVITY) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_CONNECTIVITY);
        }
        if ((trackingControllers&TRACKING_CONTENT) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_CONTENT);
        }
        if ((trackingControllers&TRACKING_IDLE) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_IDLE);
        }
        if ((trackingControllers&TRACKING_STORAGE) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_STORAGE);
        }
        if ((trackingControllers&TRACKING_TIME) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_TIME);
        }
        if ((trackingControllers & TRACKING_QUOTA) != 0) {
            proto.write(JobStatusDumpProto.TRACKING_CONTROLLERS,
                    JobStatusDumpProto.TRACKING_QUOTA);
        }

        // Implicit constraints
        final long icToken = proto.start(JobStatusDumpProto.IMPLICIT_CONSTRAINTS);
        proto.write(JobStatusDumpProto.ImplicitConstraints.IS_NOT_DOZING, mReadyNotDozing);
        proto.write(JobStatusDumpProto.ImplicitConstraints.IS_NOT_RESTRICTED_IN_BG,
                mReadyNotRestrictedInBg);
        proto.end(icToken);

        if (changedAuthorities != null) {
            for (int k = 0; k < changedAuthorities.size(); k++) {
                proto.write(JobStatusDumpProto.CHANGED_AUTHORITIES, changedAuthorities.valueAt(k));
            }
        }
        if (changedUris != null) {
            for (int i = 0; i < changedUris.size(); i++) {
                Uri u = changedUris.valueAt(i);
                proto.write(JobStatusDumpProto.CHANGED_URIS, u.toString());
            }
        }

        if (network != null) {
            network.writeToProto(proto, JobStatusDumpProto.NETWORK);
        }

        if (pendingWork != null && pendingWork.size() > 0) {
            for (int i = 0; i < pendingWork.size(); i++) {
                dumpJobWorkItem(proto, JobStatusDumpProto.PENDING_WORK, pendingWork.get(i));
            }
        }
        if (executingWork != null && executingWork.size() > 0) {
            for (int i = 0; i < executingWork.size(); i++) {
                dumpJobWorkItem(proto, JobStatusDumpProto.EXECUTING_WORK, executingWork.get(i));
            }
        }

        proto.write(JobStatusDumpProto.STANDBY_BUCKET, standbyBucket);
        proto.write(JobStatusDumpProto.ENQUEUE_DURATION_MS, elapsedRealtimeMillis - enqueueTime);
        if (earliestRunTimeElapsedMillis == NO_EARLIEST_RUNTIME) {
            proto.write(JobStatusDumpProto.TIME_UNTIL_EARLIEST_RUNTIME_MS, 0);
        } else {
            proto.write(JobStatusDumpProto.TIME_UNTIL_EARLIEST_RUNTIME_MS,
                    earliestRunTimeElapsedMillis - elapsedRealtimeMillis);
        }
        if (latestRunTimeElapsedMillis == NO_LATEST_RUNTIME) {
            proto.write(JobStatusDumpProto.TIME_UNTIL_LATEST_RUNTIME_MS, 0);
        } else {
            proto.write(JobStatusDumpProto.TIME_UNTIL_LATEST_RUNTIME_MS,
                    latestRunTimeElapsedMillis - elapsedRealtimeMillis);
        }

        proto.write(JobStatusDumpProto.NUM_FAILURES, numFailures);
        proto.write(JobStatusDumpProto.LAST_SUCCESSFUL_RUN_TIME, mLastSuccessfulRunTime);
        proto.write(JobStatusDumpProto.LAST_FAILED_RUN_TIME, mLastFailedRunTime);

        proto.end(token);
    }
}
