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

import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.EXEMPTED_INDEX;
import static com.android.server.job.JobSchedulerService.NEVER_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.ElapsedRealtimeLong;
import android.app.AppGlobals;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobWorkItem;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.pm.ServiceInfo;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Range;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.job.GrantedUriPermissions;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobServerProtoEnums;
import com.android.server.job.JobStatusDumpProto;
import com.android.server.job.JobStatusShortInfoProto;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private static final String TAG = "JobScheduler.JobStatus";
    static final boolean DEBUG = JobSchedulerService.DEBUG;

    private static final int NUM_CONSTRAINT_CHANGE_HISTORY = 10;

    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final long NO_EARLIEST_RUNTIME = 0L;

    static final int CONSTRAINT_CHARGING = JobInfo.CONSTRAINT_FLAG_CHARGING; // 1 < 0
    static final int CONSTRAINT_IDLE = JobInfo.CONSTRAINT_FLAG_DEVICE_IDLE;  // 1 << 2
    static final int CONSTRAINT_BATTERY_NOT_LOW = JobInfo.CONSTRAINT_FLAG_BATTERY_NOT_LOW; // 1 << 1
    static final int CONSTRAINT_STORAGE_NOT_LOW = JobInfo.CONSTRAINT_FLAG_STORAGE_NOT_LOW; // 1 << 3
    static final int CONSTRAINT_TIMING_DELAY = 1<<31;
    static final int CONSTRAINT_DEADLINE = 1<<30;
    static final int CONSTRAINT_CONNECTIVITY = 1 << 28;
    static final int CONSTRAINT_TARE_WEALTH = 1 << 27; // Implicit constraint
    static final int CONSTRAINT_CONTENT_TRIGGER = 1<<26;
    static final int CONSTRAINT_DEVICE_NOT_DOZING = 1 << 25; // Implicit constraint
    static final int CONSTRAINT_WITHIN_QUOTA = 1 << 24;      // Implicit constraint
    static final int CONSTRAINT_PREFETCH = 1 << 23;
    static final int CONSTRAINT_BACKGROUND_NOT_RESTRICTED = 1 << 22; // Implicit constraint

    // The following set of dynamic constraints are for specific use cases (as explained in their
    // relative naming and comments). Right now, they apply different constraints, which is fine,
    // but if in the future, we have overlapping dynamic constraint sets, removing one constraint
    // set may accidentally remove a constraint applied by another dynamic set.
    // TODO: properly handle overlapping dynamic constraint sets

    /**
     * The additional set of dynamic constraints that must be met if the job's effective bucket is
     * {@link JobSchedulerService#RESTRICTED_INDEX}. Connectivity can be ignored if the job doesn't
     * need network.
     */
    private static final int DYNAMIC_RESTRICTED_CONSTRAINTS =
            CONSTRAINT_BATTERY_NOT_LOW
                    | CONSTRAINT_CHARGING
                    | CONSTRAINT_CONNECTIVITY
                    | CONSTRAINT_IDLE;

    /**
     * The additional set of dynamic constraints that must be met if this is an expedited job that
     * had a long enough run while the device was Dozing or in battery saver.
     */
    private static final int DYNAMIC_EXPEDITED_DEFERRAL_CONSTRAINTS =
            CONSTRAINT_DEVICE_NOT_DOZING | CONSTRAINT_BACKGROUND_NOT_RESTRICTED;

    /**
     * Standard media URIs that contain the media files that might be important to the user.
     * @see #mHasMediaBackupExemption
     */
    private static final Uri[] MEDIA_URIS_FOR_STANDBY_EXEMPTION = {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    };

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
     * * CONSTRAINT_STORAGE_NOT_LOW can be inferred with LowStorageStateChanged (Atom #130)
     */
    private static final int STATSD_CONSTRAINTS_TO_LOG = CONSTRAINT_CONTENT_TRIGGER
            | CONSTRAINT_DEADLINE
            | CONSTRAINT_IDLE
            | CONSTRAINT_PREFETCH
            | CONSTRAINT_TARE_WEALTH
            | CONSTRAINT_TIMING_DELAY
            | CONSTRAINT_WITHIN_QUOTA;

    // TODO(b/129954980)
    private static final boolean STATS_LOG_ENABLED = false;

    // No override.
    public static final int OVERRIDE_NONE = 0;
    // Override to improve sorting order. Does not affect constraint evaluation.
    public static final int OVERRIDE_SORTING = 1;
    // Soft override: ignore constraints like time that don't affect API availability
    public static final int OVERRIDE_SOFT = 2;
    // Full override: ignore all constraints including API-affecting like connectivity
    public static final int OVERRIDE_FULL = 3;

    /** If not specified, trigger update delay is 10 seconds. */
    public static final long DEFAULT_TRIGGER_UPDATE_DELAY = 10*1000;

    /** The minimum possible update delay is 1/2 second. */
    public static final long MIN_TRIGGER_UPDATE_DELAY = 500;

    /** If not specified, trigger maximum delay is 2 minutes. */
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

    /**
     * Valid only for periodic jobs. The original latest point in the future at which this
     * job was expected to run.
     */
    private long mOriginalLatestRunTimeElapsedMillis;

    /** How many times this job has failed, used to compute back-off. */
    private final int numFailures;

    /**
     * Which app standby bucket this job's app is in.  Updated when the app is moved to a
     * different bucket.
     */
    private int standbyBucket;

    /**
     * Whether we've logged an error due to standby bucket mismatch with active uid state.
     */
    private boolean mLoggedBucketMismatch;

    /**
     * Debugging: timestamp if we ever defer this job based on standby bucketing, this
     * is when we did so.
     */
    private long whenStandbyDeferred;

    /** The first time this job was force batched. */
    private long mFirstForceBatchedTimeElapsed;

    // Constraints.
    final int requiredConstraints;
    private final int mRequiredConstraintsOfInterest;
    int satisfiedConstraints = 0;
    private int mSatisfiedConstraintsOfInterest = 0;
    /**
     * Set of constraints that must be satisfied for the job if/because it's in the RESTRICTED
     * bucket.
     */
    private int mDynamicConstraints = 0;

    /**
     * Indicates whether the job is responsible for backing up media, so we can be lenient in
     * applying standby throttling.
     *
     * Doesn't exempt jobs with a deadline constraint, as they can be started without any content or
     * network changes, in which case this exemption does not make sense.
     */
    private boolean mHasMediaBackupExemption;
    private final boolean mHasExemptedMediaUrisOnly;

    // Set to true if doze constraint was satisfied due to app being whitelisted.
    boolean appHasDozeExemption;

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
    public ServiceInfo serviceInfo;

    /** The evaluated bias of the job when it started running. */
    public int lastEvaluatedBias;

    /**
     * Whether or not this particular JobStatus instance was treated as an EJ when it started
     * running. This isn't copied over when a job is rescheduled.
     */
    public boolean startedAsExpeditedJob = false;

    // If non-null, this is work that has been enqueued for the job.
    public ArrayList<JobWorkItem> pendingWork;

    // If non-null, this is work that is currently being executed.
    public ArrayList<JobWorkItem> executingWork;

    public int nextPendingWorkId = 1;

    // Used by shell commands
    public int overrideState = JobStatus.OVERRIDE_NONE;

    // When this job was enqueued, for ordering.  (in elapsedRealtimeMillis)
    @ElapsedRealtimeLong
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

    /** Whether or not the app is background restricted by the user (FAS). */
    private boolean mIsUserBgRestricted;

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

    private int mConstraintChangeHistoryIndex = 0;
    private final long[] mConstraintUpdatedTimesElapsed = new long[NUM_CONSTRAINT_CHANGE_HISTORY];
    private final int[] mConstraintStatusHistory = new int[NUM_CONSTRAINT_CHANGE_HISTORY];

    /**
     * For use only by ContentObserverController: state it is maintaining about content URIs
     * being observed.
     */
    ContentObserverController.JobInstance contentObserverJobInstance;

    private long mTotalNetworkDownloadBytes = JobInfo.NETWORK_BYTES_UNKNOWN;
    private long mTotalNetworkUploadBytes = JobInfo.NETWORK_BYTES_UNKNOWN;
    private long mMinimumNetworkChunkBytes = JobInfo.NETWORK_BYTES_UNKNOWN;

    /**
     * Whether or not this job is approved to be treated as expedited per quota policy.
     */
    private boolean mExpeditedQuotaApproved;
    /**
     * Whether or not this job is approved to be treated as expedited per TARE policy.
     */
    private boolean mExpeditedTareApproved;

    /////// Booleans that track if a job is ready to run. They should be updated whenever dependent
    /////// states change.

    /**
     * The deadline for the job has passed. This is only good for non-periodic jobs. A periodic job
     * should only run if its constraints are satisfied.
     * Computed as: NOT periodic AND has deadline constraint AND deadline constraint satisfied.
     */
    private boolean mReadyDeadlineSatisfied;

    /**
     * The device isn't Dozing or this job is exempt from Dozing (eg. it will be in the foreground
     * or will run as an expedited job). This implicit constraint must be satisfied.
     */
    private boolean mReadyNotDozing;

    /**
     * The job is not restricted from running in the background (due to Battery Saver). This
     * implicit constraint must be satisfied.
     */
    private boolean mReadyNotRestrictedInBg;

    /** The job is within its quota based on its standby bucket. */
    private boolean mReadyWithinQuota;

    /** The job has enough credits to run based on TARE. */
    private boolean mReadyTareWealth;

    /** The job's dynamic requirements have been satisfied. */
    private boolean mReadyDynamicSatisfied;

    /** The reason a job most recently went from ready to not ready. */
    private int mReasonReadyToUnready = JobParameters.STOP_REASON_UNDEFINED;

    /**
     * Core constructor for JobStatus instances.  All other ctors funnel down to this one.
     *
     * @param job The actual requested parameters for the job
     * @param callingUid Identity of the app that is scheduling the job.  This may not be the
     *     app in which the job is implemented; such as with sync jobs.
     * @param sourcePackageName The package name of the app in which the job will run.
     * @param sourceUserId The user in which the job will run
     * @param standbyBucket The standby bucket that the source package is currently assigned to,
     *     cached here for speed of handling during runnability evaluations (and updated when bucket
     *     assignments are changed)
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
    private JobStatus(JobInfo job, int callingUid, String sourcePackageName,
            int sourceUserId, int standbyBucket, String tag, int numFailures,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis,
            long lastSuccessfulRunTime, long lastFailedRunTime, int internalFlags,
            int dynamicConstraints) {
        this.job = job;
        this.callingUid = callingUid;
        this.standbyBucket = standbyBucket;

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
        this.mOriginalLatestRunTimeElapsedMillis = latestRunTimeElapsedMillis;
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
        boolean exemptedMediaUrisOnly = false;
        if (job.getTriggerContentUris() != null) {
            requiredConstraints |= CONSTRAINT_CONTENT_TRIGGER;
            exemptedMediaUrisOnly = true;
            for (JobInfo.TriggerContentUri uri : job.getTriggerContentUris()) {
                if (!ArrayUtils.contains(MEDIA_URIS_FOR_STANDBY_EXEMPTION, uri.getUri())) {
                    exemptedMediaUrisOnly = false;
                    break;
                }
            }
        }
        mHasExemptedMediaUrisOnly = exemptedMediaUrisOnly;
        this.requiredConstraints = requiredConstraints;
        mRequiredConstraintsOfInterest = requiredConstraints & CONSTRAINTS_OF_INTEREST;
        addDynamicConstraints(dynamicConstraints);
        mReadyNotDozing = canRunInDoze();
        if (standbyBucket == RESTRICTED_INDEX) {
            addDynamicConstraints(DYNAMIC_RESTRICTED_CONSTRAINTS);
        } else {
            mReadyDynamicSatisfied = false;
        }

        mLastSuccessfulRunTime = lastSuccessfulRunTime;
        mLastFailedRunTime = lastFailedRunTime;

        mInternalFlags = internalFlags;

        updateNetworkBytesLocked();

        if (job.getRequiredNetwork() != null) {
            // Later, when we check if a given network satisfies the required
            // network, we need to know the UID that is requesting it, so push
            // our source UID into place.
            final JobInfo.Builder builder = new JobInfo.Builder(job);
            final NetworkRequest.Builder requestBuilder =
                    new NetworkRequest.Builder(job.getRequiredNetwork());
            requestBuilder.setUids(
                    Collections.singleton(new Range<Integer>(this.sourceUid, this.sourceUid)));
            builder.setRequiredNetwork(requestBuilder.build());
            // Don't perform prefetch-deadline check at this point. We've already passed the
            // initial validation check.
            job = builder.build(false);
        }

        updateMediaBackupExemptionStatus();
    }

    /** Copy constructor: used specifically when cloning JobStatus objects for persistence,
     *   so we preserve RTC window bounds if the source object has them. */
    public JobStatus(JobStatus jobStatus) {
        this(jobStatus.getJob(), jobStatus.getUid(),
                jobStatus.getSourcePackageName(), jobStatus.getSourceUserId(),
                jobStatus.getStandbyBucket(),
                jobStatus.getSourceTag(), jobStatus.getNumFailures(),
                jobStatus.getEarliestRunTime(), jobStatus.getLatestRunTimeElapsed(),
                jobStatus.getLastSuccessfulRunTime(), jobStatus.getLastFailedRunTime(),
                jobStatus.getInternalFlags(), jobStatus.mDynamicConstraints);
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
            int standbyBucket, String sourceTag,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis,
            long lastSuccessfulRunTime, long lastFailedRunTime,
            Pair<Long, Long> persistedExecutionTimesUTC,
            int innerFlags, int dynamicConstraints) {
        this(job, callingUid, sourcePkgName, sourceUserId,
                standbyBucket,
                sourceTag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime, innerFlags, dynamicConstraints);

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
    public JobStatus(JobStatus rescheduling,
            long newEarliestRuntimeElapsedMillis,
            long newLatestRuntimeElapsedMillis, int backoffAttempt,
            long lastSuccessfulRunTime, long lastFailedRunTime) {
        this(rescheduling.job, rescheduling.getUid(),
                rescheduling.getSourcePackageName(), rescheduling.getSourceUserId(),
                rescheduling.getStandbyBucket(),
                rescheduling.getSourceTag(), backoffAttempt, newEarliestRuntimeElapsedMillis,
                newLatestRuntimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime, rescheduling.getInternalFlags(),
                rescheduling.mDynamicConstraints);
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
            // Make sure period is in the interval [min_possible_period, max_possible_period].
            final long period = Math.max(JobInfo.getMinPeriodMillis(),
                    Math.min(JobSchedulerService.MAX_ALLOWED_PERIOD_MS, job.getIntervalMillis()));
            latestRunTimeElapsedMillis = elapsedNow + period;
            earliestRunTimeElapsedMillis = latestRunTimeElapsedMillis
                    // Make sure flex is in the interval [min_possible_flex, period].
                    - Math.max(JobInfo.getMinFlexMillis(), Math.min(period, job.getFlexMillis()));
        } else {
            earliestRunTimeElapsedMillis = job.hasEarlyConstraint() ?
                    elapsedNow + job.getMinLatencyMillis() : NO_EARLIEST_RUNTIME;
            latestRunTimeElapsedMillis = job.hasLateConstraint() ?
                    elapsedNow + job.getMaxExecutionDelayMillis() : NO_LATEST_RUNTIME;
        }
        String jobPackage = (sourcePkg != null) ? sourcePkg : job.getService().getPackageName();

        int standbyBucket = JobSchedulerService.standbyBucketForPackage(jobPackage,
                sourceUserId, elapsedNow);
        return new JobStatus(job, callingUid, sourcePkg, sourceUserId,
                standbyBucket, tag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                0 /* lastSuccessfulRunTime */, 0 /* lastFailedRunTime */,
                /*innerFlags=*/ 0, /* dynamicConstraints */ 0);
    }

    public void enqueueWorkLocked(JobWorkItem work) {
        if (pendingWork == null) {
            pendingWork = new ArrayList<>();
        }
        work.setWorkId(nextPendingWorkId);
        nextPendingWorkId++;
        if (work.getIntent() != null
                && GrantedUriPermissions.checkGrantFlags(work.getIntent().getFlags())) {
            work.setGrants(GrantedUriPermissions.createFromIntent(work.getIntent(), sourceUid,
                    sourcePackageName, sourceUserId, toShortString()));
        }
        pendingWork.add(work);
        updateNetworkBytesLocked();
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
            updateNetworkBytesLocked();
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

    private static void ungrantWorkItem(JobWorkItem work) {
        if (work.getGrants() != null) {
            ((GrantedUriPermissions)work.getGrants()).revoke();
        }
    }

    public boolean completeWorkLocked(int workId) {
        if (executingWork != null) {
            final int N = executingWork.size();
            for (int i = 0; i < N; i++) {
                JobWorkItem work = executingWork.get(i);
                if (work.getWorkId() == workId) {
                    executingWork.remove(i);
                    ungrantWorkItem(work);
                    return true;
                }
            }
        }
        return false;
    }

    private static void ungrantWorkList(ArrayList<JobWorkItem> list) {
        if (list != null) {
            final int N = list.size();
            for (int i = 0; i < N; i++) {
                ungrantWorkItem(list.get(i));
            }
        }
    }

    public void stopTrackingJobLocked(JobStatus incomingJob) {
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
            incomingJob.updateNetworkBytesLocked();
        } else {
            // We are completely stopping the job...  need to clean up work.
            ungrantWorkList(pendingWork);
            pendingWork = null;
            ungrantWorkList(executingWork);
            executingWork = null;
        }
        updateNetworkBytesLocked();
    }

    public void prepareLocked() {
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
            uriPerms = GrantedUriPermissions.createFromClip(clip, sourceUid, sourcePackageName,
                    sourceUserId, job.getClipGrantFlags(), toShortString());
        }
    }

    public void unprepareLocked() {
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
            uriPerms.revoke();
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

    /**
     * Returns an appropriate standby bucket for the job, taking into account any standby
     * exemptions.
     */
    public int getEffectiveStandbyBucket() {
        final int actualBucket = getStandbyBucket();
        if (actualBucket == EXEMPTED_INDEX) {
            return actualBucket;
        }
        if (uidActive || getJob().isExemptedFromAppStandby()) {
            // Treat these cases as if they're in the ACTIVE bucket so that they get throttled
            // like other ACTIVE apps.
            return ACTIVE_INDEX;
        }
        if (actualBucket != RESTRICTED_INDEX && actualBucket != NEVER_INDEX
                && mHasMediaBackupExemption) {
            // Cap it at WORKING_INDEX as media back up jobs are important to the user, and the
            // source package may not have been used directly in a while.
            return Math.min(WORKING_INDEX, actualBucket);
        }
        return actualBucket;
    }

    /** Returns the real standby bucket of the job. */
    public int getStandbyBucket() {
        return standbyBucket;
    }

    public void setStandbyBucket(int newBucket) {
        if (newBucket == RESTRICTED_INDEX) {
            // Adding to the bucket.
            addDynamicConstraints(DYNAMIC_RESTRICTED_CONSTRAINTS);
        } else if (standbyBucket == RESTRICTED_INDEX) {
            // Removing from the RESTRICTED bucket.
            removeDynamicConstraints(DYNAMIC_RESTRICTED_CONSTRAINTS);
        }

        standbyBucket = newBucket;
        mLoggedBucketMismatch = false;
    }

    /**
     * Log a bucket mismatch if this is the first time for this job.
     */
    public void maybeLogBucketMismatch() {
        if (!mLoggedBucketMismatch) {
            Slog.wtf(TAG,
                    "App " + getSourcePackageName() + " became active but still in NEVER bucket");
            mLoggedBucketMismatch = true;
        }
    }

    // Called only by the standby monitoring code
    public long getWhenStandbyDeferred() {
        return whenStandbyDeferred;
    }

    // Called only by the standby monitoring code
    public void setWhenStandbyDeferred(long now) {
        whenStandbyDeferred = now;
    }

    /**
     * Returns the first time this job was force batched, in the elapsed realtime timebase. Will be
     * 0 if this job was never force batched.
     */
    public long getFirstForceBatchedTimeElapsed() {
        return mFirstForceBatchedTimeElapsed;
    }

    public void setFirstForceBatchedTimeElapsed(long now) {
        mFirstForceBatchedTimeElapsed = now;
    }

    /**
     * Re-evaluates the media backup exemption status.
     *
     * @return true if the exemption status changed
     */
    public boolean updateMediaBackupExemptionStatus() {
        final JobSchedulerInternal jsi = LocalServices.getService(JobSchedulerInternal.class);
        boolean hasMediaExemption = mHasExemptedMediaUrisOnly
                && !job.hasLateConstraint()
                && job.getRequiredNetwork() != null
                && getEffectivePriority() >= JobInfo.PRIORITY_DEFAULT
                && sourcePackageName.equals(jsi.getCloudMediaProviderPackage(sourceUserId));
        if (mHasMediaBackupExemption == hasMediaExemption) {
            return false;
        }
        mHasMediaBackupExemption = hasMediaExemption;
        return true;
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

    public int getBias() {
        return job.getBias();
    }

    /**
     * Returns the priority of the job, which may be adjusted due to various factors.
     * @see JobInfo.Builder#setPriority(int)
     */
    @JobInfo.Priority
    public int getEffectivePriority() {
        final int rawPriority = job.getPriority();
        if (numFailures < 2) {
            return rawPriority;
        }
        // Slowly decay priority of jobs to prevent starvation of other jobs.
        if (isRequestedExpeditedJob()) {
            // EJs can't fall below HIGH priority.
            return JobInfo.PRIORITY_HIGH;
        }
        // Set a maximum priority based on the number of failures.
        final int dropPower = numFailures / 2;
        switch (dropPower) {
            case 1: return Math.min(JobInfo.PRIORITY_DEFAULT, rawPriority);
            case 2: return Math.min(JobInfo.PRIORITY_LOW, rawPriority);
            default: return JobInfo.PRIORITY_MIN;
        }
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

    private void updateNetworkBytesLocked() {
        mTotalNetworkDownloadBytes = job.getEstimatedNetworkDownloadBytes();
        mTotalNetworkUploadBytes = job.getEstimatedNetworkUploadBytes();
        mMinimumNetworkChunkBytes = job.getMinimumNetworkChunkBytes();

        if (pendingWork != null) {
            for (int i = 0; i < pendingWork.size(); i++) {
                if (mTotalNetworkDownloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                    // If any component of the job has unknown usage, we don't have a
                    // complete picture of what data will be used, and we have to treat the
                    // entire up/download as unknown.
                    long downloadBytes = pendingWork.get(i).getEstimatedNetworkDownloadBytes();
                    if (downloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                        mTotalNetworkDownloadBytes += downloadBytes;
                    }
                }
                if (mTotalNetworkUploadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                    // If any component of the job has unknown usage, we don't have a
                    // complete picture of what data will be used, and we have to treat the
                    // entire up/download as unknown.
                    long uploadBytes = pendingWork.get(i).getEstimatedNetworkUploadBytes();
                    if (uploadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                        mTotalNetworkUploadBytes += uploadBytes;
                    }
                }
                final long chunkBytes = pendingWork.get(i).getMinimumNetworkChunkBytes();
                if (mMinimumNetworkChunkBytes == JobInfo.NETWORK_BYTES_UNKNOWN) {
                    mMinimumNetworkChunkBytes = chunkBytes;
                } else if (chunkBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                    mMinimumNetworkChunkBytes = Math.min(mMinimumNetworkChunkBytes, chunkBytes);
                }
            }
        }
    }

    public long getEstimatedNetworkDownloadBytes() {
        return mTotalNetworkDownloadBytes;
    }

    public long getEstimatedNetworkUploadBytes() {
        return mTotalNetworkUploadBytes;
    }

    public long getMinimumNetworkChunkBytes() {
        return mMinimumNetworkChunkBytes;
    }

    /** Does this job have any sort of networking constraint? */
    public boolean hasConnectivityConstraint() {
        // No need to check mDynamicConstraints since connectivity will only be in that list if
        // it's already in the requiredConstraints list.
        return (requiredConstraints&CONSTRAINT_CONNECTIVITY) != 0;
    }

    public boolean hasChargingConstraint() {
        return hasConstraint(CONSTRAINT_CHARGING);
    }

    public boolean hasBatteryNotLowConstraint() {
        return hasConstraint(CONSTRAINT_BATTERY_NOT_LOW);
    }

    /** Returns true if the job requires charging OR battery not low. */
    boolean hasPowerConstraint() {
        return hasConstraint(CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW);
    }

    public boolean hasStorageNotLowConstraint() {
        return hasConstraint(CONSTRAINT_STORAGE_NOT_LOW);
    }

    public boolean hasTimingDelayConstraint() {
        return hasConstraint(CONSTRAINT_TIMING_DELAY);
    }

    public boolean hasDeadlineConstraint() {
        return hasConstraint(CONSTRAINT_DEADLINE);
    }

    public boolean hasIdleConstraint() {
        return hasConstraint(CONSTRAINT_IDLE);
    }

    public boolean hasContentTriggerConstraint() {
        // No need to check mDynamicConstraints since content trigger will only be in that list if
        // it's already in the requiredConstraints list.
        return (requiredConstraints&CONSTRAINT_CONTENT_TRIGGER) != 0;
    }

    /**
     * Checks both {@link #requiredConstraints} and {@link #mDynamicConstraints} to see if this job
     * requires the specified constraint.
     */
    private boolean hasConstraint(int constraint) {
        return (requiredConstraints & constraint) != 0 || (mDynamicConstraints & constraint) != 0;
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

    public long getOriginalLatestRunTimeElapsed() {
        return mOriginalLatestRunTimeElapsedMillis;
    }

    public void setOriginalLatestRunTimeElapsed(long latestRunTimeElapsed) {
        mOriginalLatestRunTimeElapsedMillis = latestRunTimeElapsed;
    }

    @JobParameters.StopReason
    public int getStopReason() {
        return mReasonReadyToUnready;
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
        if (earliestRunTimeElapsedMillis == NO_EARLIEST_RUNTIME
                && latestRunTimeElapsedMillis == NO_LATEST_RUNTIME) {
            return 1;
        } else if (earliestRunTimeElapsedMillis == NO_EARLIEST_RUNTIME) {
            return now >= latestRunTimeElapsedMillis ? 1 : 0;
        } else if (latestRunTimeElapsedMillis == NO_LATEST_RUNTIME) {
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

    /** @return true if the app has requested that this run as an expedited job. */
    public boolean isRequestedExpeditedJob() {
        return (getFlags() & JobInfo.FLAG_EXPEDITED) != 0;
    }

    /**
     * @return true if all expedited job requirements are satisfied and therefore this should be
     * treated as an expedited job.
     */
    public boolean shouldTreatAsExpeditedJob() {
        return mExpeditedQuotaApproved && mExpeditedTareApproved && isRequestedExpeditedJob();
    }

    /**
     * @return true if the job is exempted from Doze restrictions and therefore allowed to run
     * in Doze.
     */
    public boolean canRunInDoze() {
        return appHasDozeExemption
                || (getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0
                || ((shouldTreatAsExpeditedJob() || startedAsExpeditedJob)
                && (mDynamicConstraints & CONSTRAINT_DEVICE_NOT_DOZING) == 0);
    }

    boolean canRunInBatterySaver() {
        return (getInternalFlags() & INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION) != 0
                || ((shouldTreatAsExpeditedJob() || startedAsExpeditedJob)
                && (mDynamicConstraints & CONSTRAINT_BACKGROUND_NOT_RESTRICTED) == 0);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setChargingConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CHARGING, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setBatteryNotLowConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_BATTERY_NOT_LOW, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setStorageNotLowConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_STORAGE_NOT_LOW, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setPrefetchConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_PREFETCH, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setTimingDelayConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_TIMING_DELAY, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setDeadlineConstraintSatisfied(final long nowElapsed, boolean state) {
        if (setConstraintSatisfied(CONSTRAINT_DEADLINE, nowElapsed, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyDeadlineSatisfied = !job.isPeriodic() && hasDeadlineConstraint() && state;
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setIdleConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_IDLE, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setConnectivityConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONNECTIVITY, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setContentTriggerConstraintSatisfied(final long nowElapsed, boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONTENT_TRIGGER, nowElapsed, state);
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setDeviceNotDozingConstraintSatisfied(final long nowElapsed,
            boolean state, boolean whitelisted) {
        appHasDozeExemption = whitelisted;
        if (setConstraintSatisfied(CONSTRAINT_DEVICE_NOT_DOZING, nowElapsed, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyNotDozing = state || canRunInDoze();
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setBackgroundNotRestrictedConstraintSatisfied(final long nowElapsed, boolean state,
            boolean isUserBgRestricted) {
        mIsUserBgRestricted = isUserBgRestricted;
        if (setConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED, nowElapsed, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyNotRestrictedInBg = state;
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setQuotaConstraintSatisfied(final long nowElapsed, boolean state) {
        if (setConstraintSatisfied(CONSTRAINT_WITHIN_QUOTA, nowElapsed, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyWithinQuota = state;
            return true;
        }
        return false;
    }

    /** @return true if the constraint was changed, false otherwise. */
    boolean setTareWealthConstraintSatisfied(final long nowElapsed, boolean state) {
        if (setConstraintSatisfied(CONSTRAINT_TARE_WEALTH, nowElapsed, state)) {
            // The constraint was changed. Update the ready flag.
            mReadyTareWealth = state;
            return true;
        }
        return false;
    }

    /**
     * Sets whether or not this job is approved to be treated as an expedited job based on quota
     * policy.
     *
     * @return true if the approval bit was changed, false otherwise.
     */
    boolean setExpeditedJobQuotaApproved(final long nowElapsed, boolean state) {
        if (mExpeditedQuotaApproved == state) {
            return false;
        }
        final boolean wasReady = !state && isReady();
        mExpeditedQuotaApproved = state;
        updateExpeditedDependencies();
        final boolean isReady = isReady();
        if (wasReady && !isReady) {
            mReasonReadyToUnready = JobParameters.STOP_REASON_QUOTA;
        } else if (!wasReady && isReady) {
            mReasonReadyToUnready = JobParameters.STOP_REASON_UNDEFINED;
        }
        return true;
    }

    /**
     * Sets whether or not this job is approved to be treated as an expedited job based on TARE
     * policy.
     *
     * @return true if the approval bit was changed, false otherwise.
     */
    boolean setExpeditedJobTareApproved(final long nowElapsed, boolean state) {
        if (mExpeditedTareApproved == state) {
            return false;
        }
        final boolean wasReady = !state && isReady();
        mExpeditedTareApproved = state;
        updateExpeditedDependencies();
        final boolean isReady = isReady();
        if (wasReady && !isReady) {
            mReasonReadyToUnready = JobParameters.STOP_REASON_QUOTA;
        } else if (!wasReady && isReady) {
            mReasonReadyToUnready = JobParameters.STOP_REASON_UNDEFINED;
        }
        return true;
    }

    private void updateExpeditedDependencies() {
        // DeviceIdleJobsController currently only tracks jobs with the WILL_BE_FOREGROUND flag.
        // Making it also track requested-expedited jobs would add unnecessary hops since the
        // controller would then defer to canRunInDoze. Avoid the hops and just update
        // mReadyNotDozing directly.
        mReadyNotDozing = isConstraintSatisfied(CONSTRAINT_DEVICE_NOT_DOZING) || canRunInDoze();
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
    boolean setConstraintSatisfied(int constraint, final long nowElapsed, boolean state) {
        boolean old = (satisfiedConstraints&constraint) != 0;
        if (old == state) {
            return false;
        }
        if (DEBUG) {
            Slog.v(TAG,
                    "Constraint " + constraint + " is " + (!state ? "NOT " : "") + "satisfied for "
                            + toShortString());
        }
        final boolean wasReady = !state && isReady();
        satisfiedConstraints = (satisfiedConstraints&~constraint) | (state ? constraint : 0);
        mSatisfiedConstraintsOfInterest = satisfiedConstraints & CONSTRAINTS_OF_INTEREST;
        mReadyDynamicSatisfied = mDynamicConstraints != 0
                && mDynamicConstraints == (satisfiedConstraints & mDynamicConstraints);
        if (STATS_LOG_ENABLED && (STATSD_CONSTRAINTS_TO_LOG & constraint) != 0) {
            FrameworkStatsLog.write_non_chained(
                    FrameworkStatsLog.SCHEDULED_JOB_CONSTRAINT_CHANGED,
                    sourceUid, null, getBatteryName(), getProtoConstraint(constraint),
                    state ? FrameworkStatsLog.SCHEDULED_JOB_CONSTRAINT_CHANGED__STATE__SATISFIED
                            : FrameworkStatsLog
                                    .SCHEDULED_JOB_CONSTRAINT_CHANGED__STATE__UNSATISFIED);
        }

        mConstraintUpdatedTimesElapsed[mConstraintChangeHistoryIndex] = nowElapsed;
        mConstraintStatusHistory[mConstraintChangeHistoryIndex] = satisfiedConstraints;
        mConstraintChangeHistoryIndex =
                (mConstraintChangeHistoryIndex + 1) % NUM_CONSTRAINT_CHANGE_HISTORY;

        // Can't use isReady() directly since "cache booleans" haven't updated yet.
        final boolean isReady = readinessStatusWithConstraint(constraint, state);
        if (wasReady && !isReady) {
            mReasonReadyToUnready = constraintToStopReason(constraint);
        } else if (!wasReady && isReady) {
            mReasonReadyToUnready = JobParameters.STOP_REASON_UNDEFINED;
        }

        return true;
    }

    @JobParameters.StopReason
    private int constraintToStopReason(int constraint) {
        switch (constraint) {
            case CONSTRAINT_BATTERY_NOT_LOW:
                if ((requiredConstraints & constraint) != 0) {
                    // The developer requested this constraint, so it makes sense to return the
                    // explicit constraint reason.
                    return JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW;
                }
                // Hard-coding right now since the current dynamic constraint sets don't overlap
                // TODO: return based on active dynamic constraint sets when they start overlapping
                return JobParameters.STOP_REASON_APP_STANDBY;
            case CONSTRAINT_CHARGING:
                if ((requiredConstraints & constraint) != 0) {
                    // The developer requested this constraint, so it makes sense to return the
                    // explicit constraint reason.
                    return JobParameters.STOP_REASON_CONSTRAINT_CHARGING;
                }
                // Hard-coding right now since the current dynamic constraint sets don't overlap
                // TODO: return based on active dynamic constraint sets when they start overlapping
                return JobParameters.STOP_REASON_APP_STANDBY;
            case CONSTRAINT_CONNECTIVITY:
                return JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY;
            case CONSTRAINT_IDLE:
                if ((requiredConstraints & constraint) != 0) {
                    // The developer requested this constraint, so it makes sense to return the
                    // explicit constraint reason.
                    return JobParameters.STOP_REASON_CONSTRAINT_DEVICE_IDLE;
                }
                // Hard-coding right now since the current dynamic constraint sets don't overlap
                // TODO: return based on active dynamic constraint sets when they start overlapping
                return JobParameters.STOP_REASON_APP_STANDBY;
            case CONSTRAINT_STORAGE_NOT_LOW:
                return JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW;

            case CONSTRAINT_BACKGROUND_NOT_RESTRICTED:
                // The BACKGROUND_NOT_RESTRICTED constraint could be dissatisfied either because
                // the app is background restricted, or because we're restricting background work
                // in battery saver. Assume that background restriction is the reason apps that
                // are background restricted have their jobs stopped, and battery saver otherwise.
                // This has the benefit of being consistent for background restricted apps
                // (they'll always get BACKGROUND_RESTRICTION) as the reason, regardless of
                // battery saver state.
                if (mIsUserBgRestricted) {
                    return JobParameters.STOP_REASON_BACKGROUND_RESTRICTION;
                }
                return JobParameters.STOP_REASON_DEVICE_STATE;
            case CONSTRAINT_DEVICE_NOT_DOZING:
                return JobParameters.STOP_REASON_DEVICE_STATE;

            case CONSTRAINT_PREFETCH:
                return JobParameters.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED;

            case CONSTRAINT_TARE_WEALTH:
            case CONSTRAINT_WITHIN_QUOTA:
                return JobParameters.STOP_REASON_QUOTA;

            // These should never be stop reasons since they can never go from true to false.
            case CONSTRAINT_CONTENT_TRIGGER:
            case CONSTRAINT_DEADLINE:
            case CONSTRAINT_TIMING_DELAY:
            default:
                Slog.wtf(TAG, "Unsupported constraint (" + constraint + ") --stop reason mapping");
                return JobParameters.STOP_REASON_UNDEFINED;
        }
    }

    boolean isConstraintSatisfied(int constraint) {
        return (satisfiedConstraints&constraint) != 0;
    }

    boolean isExpeditedQuotaApproved() {
        return mExpeditedQuotaApproved;
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

    /**
     * Add additional constraints to prevent this job from running when doze or battery saver are
     * active.
     */
    public void disallowRunInBatterySaverAndDoze() {
        addDynamicConstraints(DYNAMIC_EXPEDITED_DEFERRAL_CONSTRAINTS);
    }

    /**
     * Indicates that this job cannot run without the specified constraints. This is evaluated
     * separately from the job's explicitly requested constraints and MUST be satisfied before
     * the job can run if the app doesn't have quota.
     */
    private void addDynamicConstraints(int constraints) {
        if ((constraints & CONSTRAINT_WITHIN_QUOTA) != 0) {
            // Quota should never be used as a dynamic constraint.
            Slog.wtf(TAG, "Tried to set quota as a dynamic constraint");
            constraints &= ~CONSTRAINT_WITHIN_QUOTA;
        }
        if ((constraints & CONSTRAINT_TARE_WEALTH) != 0) {
            // Quota should never be used as a dynamic constraint.
            Slog.wtf(TAG, "Tried to set TARE as a dynamic constraint");
            constraints &= ~CONSTRAINT_TARE_WEALTH;
        }

        // Connectivity and content trigger are special since they're only valid to add if the
        // job has requested network or specific content URIs. Adding these constraints to jobs
        // that don't need them doesn't make sense.
        if (!hasConnectivityConstraint()) {
            constraints &= ~CONSTRAINT_CONNECTIVITY;
        }
        if (!hasContentTriggerConstraint()) {
            constraints &= ~CONSTRAINT_CONTENT_TRIGGER;
        }

        mDynamicConstraints |= constraints;
        mReadyDynamicSatisfied = mDynamicConstraints != 0
                && mDynamicConstraints == (satisfiedConstraints & mDynamicConstraints);
    }

    /**
     * Removes dynamic constraints from a job, meaning that the requirements are not required for
     * the job to run (if the job itself hasn't requested the constraint. This is separate from
     * the job's explicitly requested constraints and does not remove those requested constraints.
     *
     */
    private void removeDynamicConstraints(int constraints) {
        mDynamicConstraints &= ~constraints;
        mReadyDynamicSatisfied = mDynamicConstraints != 0
                && mDynamicConstraints == (satisfiedConstraints & mDynamicConstraints);
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
        return readinessStatusWithConstraint(constraint, true);
    }

    private boolean readinessStatusWithConstraint(int constraint, boolean value) {
        boolean oldValue = false;
        int satisfied = mSatisfiedConstraintsOfInterest;
        switch (constraint) {
            case CONSTRAINT_BACKGROUND_NOT_RESTRICTED:
                oldValue = mReadyNotRestrictedInBg;
                mReadyNotRestrictedInBg = value;
                break;
            case CONSTRAINT_DEADLINE:
                oldValue = mReadyDeadlineSatisfied;
                mReadyDeadlineSatisfied = value;
                break;
            case CONSTRAINT_DEVICE_NOT_DOZING:
                oldValue = mReadyNotDozing;
                mReadyNotDozing = value;
                break;
            case CONSTRAINT_TARE_WEALTH:
                oldValue = mReadyTareWealth;
                mReadyTareWealth = value;
                break;
            case CONSTRAINT_WITHIN_QUOTA:
                oldValue = mReadyWithinQuota;
                mReadyWithinQuota = value;
                break;
            default:
                if (value) {
                    satisfied |= constraint;
                } else {
                    satisfied &= ~constraint;
                }
                mReadyDynamicSatisfied = mDynamicConstraints != 0
                        && mDynamicConstraints == (satisfied & mDynamicConstraints);

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
            case CONSTRAINT_TARE_WEALTH:
                mReadyTareWealth = oldValue;
                break;
            case CONSTRAINT_WITHIN_QUOTA:
                mReadyWithinQuota = oldValue;
                break;
            default:
                mReadyDynamicSatisfied = mDynamicConstraints != 0
                        && mDynamicConstraints == (satisfiedConstraints & mDynamicConstraints);
                break;
        }
        return toReturn;
    }

    private boolean isReady(int satisfiedConstraints) {
        // Quota and dynamic constraints trump all other constraints.
        // NEVER jobs are not supposed to run at all. Since we're using quota to allow parole
        // sessions (exempt from dynamic restrictions), we need the additional check to ensure
        // that NEVER jobs don't run.
        // TODO: cleanup quota and standby bucket management so we don't need the additional checks
        if (((!mReadyWithinQuota || !mReadyTareWealth)
                && !mReadyDynamicSatisfied && !shouldTreatAsExpeditedJob())
                || getEffectiveStandbyBucket() == NEVER_INDEX) {
            return false;
        }
        // Deadline constraint trumps other constraints besides quota and dynamic (except for
        // periodic jobs where deadline is an implementation detail. A periodic job should only
        // run if its constraints are satisfied).
        // DeviceNotDozing implicit constraint must be satisfied
        // NotRestrictedInBackground implicit constraint must be satisfied
        return mReadyNotDozing && mReadyNotRestrictedInBg && (serviceInfo != null)
                && (mReadyDeadlineSatisfied || isConstraintsSatisfied(satisfiedConstraints));
    }

    /** All constraints besides implicit and deadline. */
    static final int CONSTRAINTS_OF_INTEREST = CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW
            | CONSTRAINT_STORAGE_NOT_LOW | CONSTRAINT_TIMING_DELAY | CONSTRAINT_CONNECTIVITY
            | CONSTRAINT_IDLE | CONSTRAINT_CONTENT_TRIGGER | CONSTRAINT_PREFETCH;

    // Soft override covers all non-"functional" constraints
    static final int SOFT_OVERRIDE_CONSTRAINTS =
            CONSTRAINT_CHARGING | CONSTRAINT_BATTERY_NOT_LOW | CONSTRAINT_STORAGE_NOT_LOW
                    | CONSTRAINT_TIMING_DELAY | CONSTRAINT_IDLE | CONSTRAINT_PREFETCH;

    /** Returns true whenever all dynamically set constraints are satisfied. */
    public boolean areDynamicConstraintsSatisfied() {
        return mReadyDynamicSatisfied;
    }

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
        } else {
            sb.append(" satisfied:0x").append(Integer.toHexString(satisfiedConstraints));
            sb.append(" unsatisfied:0x").append(Integer.toHexString(
                    (satisfiedConstraints & mRequiredConstraintsOfInterest)
                            ^ mRequiredConstraintsOfInterest));
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
        if ((constraints & CONSTRAINT_PREFETCH) != 0) {
            pw.print(" PREFETCH");
        }
        if ((constraints & CONSTRAINT_TARE_WEALTH) != 0) {
            pw.print(" TARE_WEALTH");
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

    private void dumpJobWorkItem(IndentingPrintWriter pw, JobWorkItem work, int index) {
        pw.increaseIndent();
        pw.print("#"); pw.print(index); pw.print(": #");
        pw.print(work.getWorkId()); pw.print(" "); pw.print(work.getDeliveryCount());
        pw.print("x "); pw.println(work.getIntent());
        if (work.getGrants() != null) {
            pw.println("URI grants:");
            pw.increaseIndent();
            ((GrantedUriPermissions) work.getGrants()).dump(pw);
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    private void dumpJobWorkItem(ProtoOutputStream proto, long fieldId, JobWorkItem work) {
        final long token = proto.start(fieldId);

        proto.write(JobStatusDumpProto.JobWorkItem.WORK_ID, work.getWorkId());
        proto.write(JobStatusDumpProto.JobWorkItem.DELIVERY_COUNT, work.getDeliveryCount());
        if (work.getIntent() != null) {
            work.getIntent().dumpDebug(proto, JobStatusDumpProto.JobWorkItem.INTENT);
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
            case 5: return "RESTRICTED";
            case 6: return "EXEMPTED";
            default:
                return "Unknown: " + standbyBucket;
        }
    }

    // Dumpsys infrastructure
    @NeverCompile // Avoid size overhead of debugging code.
    public void dump(IndentingPrintWriter pw,  boolean full, long nowElapsed) {
        UserHandle.formatUid(pw, callingUid);
        pw.print(" tag="); pw.println(tag);

        pw.print("Source: uid="); UserHandle.formatUid(pw, getSourceUid());
        pw.print(" user="); pw.print(getSourceUserId());
        pw.print(" pkg="); pw.println(getSourcePackageName());
        if (full) {
            pw.println("JobInfo:");
            pw.increaseIndent();

            pw.print("Service: ");
            pw.println(job.getService().flattenToShortString());
            if (job.isPeriodic()) {
                pw.print("PERIODIC: interval=");
                TimeUtils.formatDuration(job.getIntervalMillis(), pw);
                pw.print(" flex="); TimeUtils.formatDuration(job.getFlexMillis(), pw);
                pw.println();
            }
            if (job.isPersisted()) {
                pw.println("PERSISTED");
            }
            if (job.getBias() != 0) {
                pw.print("Bias: ");
                pw.println(JobInfo.getBiasString(job.getBias()));
            }
            pw.print("Priority: ");
            pw.print(JobInfo.getPriorityString(job.getPriority()));
            final int effectivePriority = getEffectivePriority();
            if (effectivePriority != job.getPriority()) {
                pw.print(" effective=");
                pw.print(JobInfo.getPriorityString(effectivePriority));
            }
            pw.println();
            if (job.getFlags() != 0) {
                pw.print("Flags: ");
                pw.println(Integer.toHexString(job.getFlags()));
            }
            if (getInternalFlags() != 0) {
                pw.print("Internal flags: ");
                pw.print(Integer.toHexString(getInternalFlags()));

                if ((getInternalFlags()&INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION) != 0) {
                    pw.print(" HAS_FOREGROUND_EXEMPTION");
                }
                pw.println();
            }
            pw.print("Requires: charging=");
            pw.print(job.isRequireCharging()); pw.print(" batteryNotLow=");
            pw.print(job.isRequireBatteryNotLow()); pw.print(" deviceIdle=");
            pw.println(job.isRequireDeviceIdle());
            if (job.getTriggerContentUris() != null) {
                pw.println("Trigger content URIs:");
                pw.increaseIndent();
                for (int i = 0; i < job.getTriggerContentUris().length; i++) {
                    JobInfo.TriggerContentUri trig = job.getTriggerContentUris()[i];
                    pw.print(Integer.toHexString(trig.getFlags()));
                    pw.print(' '); pw.println(trig.getUri());
                }
                pw.decreaseIndent();
                if (job.getTriggerContentUpdateDelay() >= 0) {
                    pw.print("Trigger update delay: ");
                    TimeUtils.formatDuration(job.getTriggerContentUpdateDelay(), pw);
                    pw.println();
                }
                if (job.getTriggerContentMaxDelay() >= 0) {
                    pw.print("Trigger max delay: ");
                    TimeUtils.formatDuration(job.getTriggerContentMaxDelay(), pw);
                    pw.println();
                }
                pw.print("Has media backup exemption", mHasMediaBackupExemption).println();
            }
            if (job.getExtras() != null && !job.getExtras().isDefinitelyEmpty()) {
                pw.print("Extras: ");
                pw.println(job.getExtras().toShortString());
            }
            if (job.getTransientExtras() != null && !job.getTransientExtras().isDefinitelyEmpty()) {
                pw.print("Transient extras: ");
                pw.println(job.getTransientExtras().toShortString());
            }
            if (job.getClipData() != null) {
                pw.print("Clip data: ");
                StringBuilder b = new StringBuilder(128);
                b.append(job.getClipData());
                pw.println(b);
            }
            if (uriPerms != null) {
                pw.println("Granted URI permissions:");
                uriPerms.dump(pw);
            }
            if (job.getRequiredNetwork() != null) {
                pw.print("Network type: ");
                pw.println(job.getRequiredNetwork());
            }
            if (mTotalNetworkDownloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                pw.print("Network download bytes: ");
                pw.println(mTotalNetworkDownloadBytes);
            }
            if (mTotalNetworkUploadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                pw.print("Network upload bytes: ");
                pw.println(mTotalNetworkUploadBytes);
            }
            if (mMinimumNetworkChunkBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                pw.print("Minimum network chunk bytes: ");
                pw.println(mMinimumNetworkChunkBytes);
            }
            if (job.getMinLatencyMillis() != 0) {
                pw.print("Minimum latency: ");
                TimeUtils.formatDuration(job.getMinLatencyMillis(), pw);
                pw.println();
            }
            if (job.getMaxExecutionDelayMillis() != 0) {
                pw.print("Max execution delay: ");
                TimeUtils.formatDuration(job.getMaxExecutionDelayMillis(), pw);
                pw.println();
            }
            pw.print("Backoff: policy="); pw.print(job.getBackoffPolicy());
            pw.print(" initial="); TimeUtils.formatDuration(job.getInitialBackoffMillis(), pw);
            pw.println();
            if (job.hasEarlyConstraint()) {
                pw.println("Has early constraint");
            }
            if (job.hasLateConstraint()) {
                pw.println("Has late constraint");
            }

            pw.decreaseIndent();
        }

        pw.print("Required constraints:");
        dumpConstraints(pw, requiredConstraints);
        pw.println();
        pw.print("Dynamic constraints:");
        dumpConstraints(pw, mDynamicConstraints);
        pw.println();
        if (full) {
            pw.print("Satisfied constraints:");
            dumpConstraints(pw, satisfiedConstraints);
            pw.println();
            pw.print("Unsatisfied constraints:");
            dumpConstraints(pw,
                    ((requiredConstraints | CONSTRAINT_WITHIN_QUOTA | CONSTRAINT_TARE_WEALTH)
                            & ~satisfiedConstraints));
            pw.println();

            pw.println("Constraint history:");
            pw.increaseIndent();
            for (int h = 0; h < NUM_CONSTRAINT_CHANGE_HISTORY; ++h) {
                final int idx = (h + mConstraintChangeHistoryIndex) % NUM_CONSTRAINT_CHANGE_HISTORY;
                if (mConstraintUpdatedTimesElapsed[idx] == 0) {
                    continue;
                }
                TimeUtils.formatDuration(mConstraintUpdatedTimesElapsed[idx], nowElapsed, pw);
                // dumpConstraints prepends with a space, so no need to add a space after the =
                pw.print(" =");
                dumpConstraints(pw, mConstraintStatusHistory[idx]);
                pw.println();
            }
            pw.decreaseIndent();

            if (appHasDozeExemption) {
                pw.println("Doze whitelisted: true");
            }
            if (uidActive) {
                pw.println("Uid: active");
            }
            if (job.isExemptedFromAppStandby()) {
                pw.println("Is exempted from app standby");
            }
        }
        if (trackingControllers != 0) {
            pw.print("Tracking:");
            if ((trackingControllers&TRACKING_BATTERY) != 0) pw.print(" BATTERY");
            if ((trackingControllers&TRACKING_CONNECTIVITY) != 0) pw.print(" CONNECTIVITY");
            if ((trackingControllers&TRACKING_CONTENT) != 0) pw.print(" CONTENT");
            if ((trackingControllers&TRACKING_IDLE) != 0) pw.print(" IDLE");
            if ((trackingControllers&TRACKING_STORAGE) != 0) pw.print(" STORAGE");
            if ((trackingControllers&TRACKING_TIME) != 0) pw.print(" TIME");
            if ((trackingControllers & TRACKING_QUOTA) != 0) pw.print(" QUOTA");
            pw.println();
        }

        pw.println("Implicit constraints:");
        pw.increaseIndent();
        pw.print("readyNotDozing: ");
        pw.println(mReadyNotDozing);
        pw.print("readyNotRestrictedInBg: ");
        pw.println(mReadyNotRestrictedInBg);
        if (!job.isPeriodic() && hasDeadlineConstraint()) {
            pw.print("readyDeadlineSatisfied: ");
            pw.println(mReadyDeadlineSatisfied);
        }
        if (mDynamicConstraints != 0) {
            pw.print("readyDynamicSatisfied: ");
            pw.println(mReadyDynamicSatisfied);
        }
        pw.print("readyComponentEnabled: ");
        pw.println(serviceInfo != null);
        if ((getFlags() & JobInfo.FLAG_EXPEDITED) != 0) {
            pw.print("expeditedQuotaApproved: ");
            pw.print(mExpeditedQuotaApproved);
            pw.print(" expeditedTareApproved: ");
            pw.print(mExpeditedTareApproved);
            pw.print(" (started as EJ: ");
            pw.print(startedAsExpeditedJob);
            pw.println(")");
        }
        pw.decreaseIndent();

        if (changedAuthorities != null) {
            pw.println("Changed authorities:");
            pw.increaseIndent();
            for (int i=0; i<changedAuthorities.size(); i++) {
                pw.println(changedAuthorities.valueAt(i));
            }
            pw.decreaseIndent();
        }
        if (changedUris != null) {
            pw.println("Changed URIs:");
            pw.increaseIndent();
            for (int i = 0; i < changedUris.size(); i++) {
                pw.println(changedUris.valueAt(i));
            }
            pw.decreaseIndent();
        }
        if (network != null) {
            pw.print("Network: "); pw.println(network);
        }
        if (pendingWork != null && pendingWork.size() > 0) {
            pw.println("Pending work:");
            for (int i = 0; i < pendingWork.size(); i++) {
                dumpJobWorkItem(pw, pendingWork.get(i), i);
            }
        }
        if (executingWork != null && executingWork.size() > 0) {
            pw.println("Executing work:");
            for (int i = 0; i < executingWork.size(); i++) {
                dumpJobWorkItem(pw, executingWork.get(i), i);
            }
        }
        pw.print("Standby bucket: ");
        pw.println(getBucketName());
        pw.increaseIndent();
        if (whenStandbyDeferred != 0) {
            pw.print("Deferred since: ");
            TimeUtils.formatDuration(whenStandbyDeferred, nowElapsed, pw);
            pw.println();
        }
        if (mFirstForceBatchedTimeElapsed != 0) {
            pw.print("Time since first force batch attempt: ");
            TimeUtils.formatDuration(mFirstForceBatchedTimeElapsed, nowElapsed, pw);
            pw.println();
        }
        pw.decreaseIndent();

        pw.print("Enqueue time: ");
        TimeUtils.formatDuration(enqueueTime, nowElapsed, pw);
        pw.println();
        pw.print("Run time: earliest=");
        formatRunTime(pw, earliestRunTimeElapsedMillis, NO_EARLIEST_RUNTIME, nowElapsed);
        pw.print(", latest=");
        formatRunTime(pw, latestRunTimeElapsedMillis, NO_LATEST_RUNTIME, nowElapsed);
        pw.print(", original latest=");
        formatRunTime(pw, mOriginalLatestRunTimeElapsedMillis, NO_LATEST_RUNTIME, nowElapsed);
        pw.println();
        if (numFailures != 0) {
            pw.print("Num failures: "); pw.println(numFailures);
        }
        if (mLastSuccessfulRunTime != 0) {
            pw.print("Last successful run: ");
            pw.println(formatTime(mLastSuccessfulRunTime));
        }
        if (mLastFailedRunTime != 0) {
            pw.print("Last failed run: ");
            pw.println(formatTime(mLastFailedRunTime));
        }
    }

    private static CharSequence formatTime(long time) {
        return DateFormat.format("yyyy-MM-dd HH:mm:ss", time);
    }

    public void dump(ProtoOutputStream proto, long fieldId, boolean full, long elapsedRealtimeMillis) {
        final long token = proto.start(fieldId);

        proto.write(JobStatusDumpProto.CALLING_UID, callingUid);
        proto.write(JobStatusDumpProto.TAG, tag);
        proto.write(JobStatusDumpProto.SOURCE_UID, getSourceUid());
        proto.write(JobStatusDumpProto.SOURCE_USER_ID, getSourceUserId());
        proto.write(JobStatusDumpProto.SOURCE_PACKAGE_NAME, getSourcePackageName());

        if (full) {
            final long jiToken = proto.start(JobStatusDumpProto.JOB_INFO);

            job.getService().dumpDebug(proto, JobStatusDumpProto.JobInfo.SERVICE);

            proto.write(JobStatusDumpProto.JobInfo.IS_PERIODIC, job.isPeriodic());
            proto.write(JobStatusDumpProto.JobInfo.PERIOD_INTERVAL_MS, job.getIntervalMillis());
            proto.write(JobStatusDumpProto.JobInfo.PERIOD_FLEX_MS, job.getFlexMillis());

            proto.write(JobStatusDumpProto.JobInfo.IS_PERSISTED, job.isPersisted());
            proto.write(JobStatusDumpProto.JobInfo.PRIORITY, job.getBias());
            proto.write(JobStatusDumpProto.JobInfo.FLAGS, job.getFlags());
            proto.write(JobStatusDumpProto.INTERNAL_FLAGS, getInternalFlags());
            // Foreground exemption can be determined from internal flags value.

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
            if (job.getExtras() != null && !job.getExtras().isDefinitelyEmpty()) {
                job.getExtras().dumpDebug(proto, JobStatusDumpProto.JobInfo.EXTRAS);
            }
            if (job.getTransientExtras() != null && !job.getTransientExtras().isDefinitelyEmpty()) {
                job.getTransientExtras().dumpDebug(proto, JobStatusDumpProto.JobInfo.TRANSIENT_EXTRAS);
            }
            if (job.getClipData() != null) {
                job.getClipData().dumpDebug(proto, JobStatusDumpProto.JobInfo.CLIP_DATA);
            }
            if (uriPerms != null) {
                uriPerms.dump(proto, JobStatusDumpProto.JobInfo.GRANTED_URI_PERMISSIONS);
            }
            if (mTotalNetworkDownloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                proto.write(JobStatusDumpProto.JobInfo.TOTAL_NETWORK_DOWNLOAD_BYTES,
                        mTotalNetworkDownloadBytes);
            }
            if (mTotalNetworkUploadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
                proto.write(JobStatusDumpProto.JobInfo.TOTAL_NETWORK_UPLOAD_BYTES,
                        mTotalNetworkUploadBytes);
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
        dumpConstraints(proto, JobStatusDumpProto.DYNAMIC_CONSTRAINTS, mDynamicConstraints);
        if (full) {
            dumpConstraints(proto, JobStatusDumpProto.SATISFIED_CONSTRAINTS, satisfiedConstraints);
            dumpConstraints(proto, JobStatusDumpProto.UNSATISFIED_CONSTRAINTS,
                    ((requiredConstraints | CONSTRAINT_WITHIN_QUOTA) & ~satisfiedConstraints));
            proto.write(JobStatusDumpProto.IS_DOZE_WHITELISTED, appHasDozeExemption);
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
        // mReadyDeadlineSatisfied isn't an implicit constraint...and can be determined from other
        // field values.
        proto.write(JobStatusDumpProto.ImplicitConstraints.IS_DYNAMIC_SATISFIED,
                mReadyDynamicSatisfied);
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

        if (pendingWork != null) {
            for (int i = 0; i < pendingWork.size(); i++) {
                dumpJobWorkItem(proto, JobStatusDumpProto.PENDING_WORK, pendingWork.get(i));
            }
        }
        if (executingWork != null) {
            for (int i = 0; i < executingWork.size(); i++) {
                dumpJobWorkItem(proto, JobStatusDumpProto.EXECUTING_WORK, executingWork.get(i));
            }
        }

        proto.write(JobStatusDumpProto.STANDBY_BUCKET, standbyBucket);
        proto.write(JobStatusDumpProto.ENQUEUE_DURATION_MS, elapsedRealtimeMillis - enqueueTime);
        proto.write(JobStatusDumpProto.TIME_SINCE_FIRST_DEFERRAL_MS,
                whenStandbyDeferred == 0 ? 0 : elapsedRealtimeMillis - whenStandbyDeferred);
        proto.write(JobStatusDumpProto.TIME_SINCE_FIRST_FORCE_BATCH_ATTEMPT_MS,
                mFirstForceBatchedTimeElapsed == 0
                        ? 0 : elapsedRealtimeMillis - mFirstForceBatchedTimeElapsed);
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
        proto.write(JobStatusDumpProto.ORIGINAL_LATEST_RUNTIME_ELAPSED,
                mOriginalLatestRunTimeElapsedMillis);

        proto.write(JobStatusDumpProto.NUM_FAILURES, numFailures);
        proto.write(JobStatusDumpProto.LAST_SUCCESSFUL_RUN_TIME, mLastSuccessfulRunTime);
        proto.write(JobStatusDumpProto.LAST_FAILED_RUN_TIME, mLastFailedRunTime);

        proto.end(token);
    }
}
