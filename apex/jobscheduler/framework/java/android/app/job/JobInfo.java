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

package android.app.job;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.util.TimeUtils.formatDuration;

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.ComponentName;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.Uri;
import android.os.BaseBundle;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Container of data passed to the {@link android.app.job.JobScheduler} fully encapsulating the
 * parameters required to schedule work against the calling application. These are constructed
 * using the {@link JobInfo.Builder}.
 * The goal here is to provide the scheduler with high-level semantics about the work you want to
 * accomplish.
 * <p> Prior to Android version {@link Build.VERSION_CODES#Q}, you had to specify at least one
 * constraint on the JobInfo object that you are creating. Otherwise, the builder would throw an
 * exception when building. From Android version {@link Build.VERSION_CODES#Q} and onwards, it is
 * valid to schedule jobs with no constraints.
 */
public class JobInfo implements Parcelable {
    private static String TAG = "JobInfo";

    /**
     * Disallow setting a deadline (via {@link Builder#setOverrideDeadline(long)}) for prefetch
     * jobs ({@link Builder#setPrefetch(boolean)}. Prefetch jobs are meant to run close to the next
     * app launch, so there's no good reason to allow them to have deadlines.
     *
     * We don't drop or cancel any previously scheduled prefetch jobs with a deadline.
     * There's no way for an app to keep a perpetually scheduled prefetch job with a deadline.
     * Prefetch jobs with a deadline will run and apps under this restriction won't be able to
     * schedule new prefetch jobs with a deadline. If a job is rescheduled (by providing
     * {@code true} via {@link JobService#jobFinished(JobParameters, boolean)} or
     * {@link JobService#onStopJob(JobParameters)}'s return value),the deadline is dropped.
     * Periodic jobs require all constraints to be met, so there's no issue with their deadlines.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long DISALLOW_DEADLINES_FOR_PREFETCH_JOBS = 194532703L;

    /**
     * Whether to throw an exception when an app provides an invalid priority value via
     * {@link Builder#setPriority(int)}. Legacy apps may be incorrectly using the API and
     * so the call will silently fail for them if they continue using the API.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long THROW_ON_INVALID_PRIORITY_VALUE = 140852299L;

    /** @hide */
    @IntDef(prefix = { "NETWORK_TYPE_" }, value = {
            NETWORK_TYPE_NONE,
            NETWORK_TYPE_ANY,
            NETWORK_TYPE_UNMETERED,
            NETWORK_TYPE_NOT_ROAMING,
            NETWORK_TYPE_CELLULAR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkType {}

    /** Default. */
    public static final int NETWORK_TYPE_NONE = 0;
    /** This job requires network connectivity. */
    public static final int NETWORK_TYPE_ANY = 1;
    /** This job requires network connectivity that is unmetered. */
    public static final int NETWORK_TYPE_UNMETERED = 2;
    /** This job requires network connectivity that is not roaming. */
    public static final int NETWORK_TYPE_NOT_ROAMING = 3;
    /** This job requires network connectivity that is a cellular network. */
    public static final int NETWORK_TYPE_CELLULAR = 4;

    /**
     * This job requires metered connectivity such as most cellular data
     * networks.
     *
     * @deprecated Cellular networks may be unmetered, or Wi-Fi networks may be
     *             metered, so this isn't a good way of selecting a specific
     *             transport. Instead, use {@link #NETWORK_TYPE_CELLULAR} or
     *             {@link android.net.NetworkRequest.Builder#addTransportType(int)}
     *             if your job requires a specific network transport.
     */
    @Deprecated
    public static final int NETWORK_TYPE_METERED = NETWORK_TYPE_CELLULAR;

    /** Sentinel value indicating that bytes are unknown. */
    public static final int NETWORK_BYTES_UNKNOWN = -1;

    /**
     * Amount of backoff a job has initially by default, in milliseconds.
     */
    public static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 30000L;  // 30 seconds.

    /**
     * Maximum backoff we allow for a job, in milliseconds.
     */
    public static final long MAX_BACKOFF_DELAY_MILLIS = 5 * 60 * 60 * 1000;  // 5 hours.

    /** @hide */
    @IntDef(prefix = { "BACKOFF_POLICY_" }, value = {
            BACKOFF_POLICY_LINEAR,
            BACKOFF_POLICY_EXPONENTIAL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackoffPolicy {}

    /**
     * Linearly back-off a failed job. See
     * {@link android.app.job.JobInfo.Builder#setBackoffCriteria(long, int)}
     * retry_time(current_time, num_failures) =
     *     current_time + initial_backoff_millis * num_failures, num_failures >= 1
     */
    public static final int BACKOFF_POLICY_LINEAR = 0;

    /**
     * Exponentially back-off a failed job. See
     * {@link android.app.job.JobInfo.Builder#setBackoffCriteria(long, int)}
     *
     * retry_time(current_time, num_failures) =
     *     current_time + initial_backoff_millis * 2 ^ (num_failures - 1), num_failures >= 1
     */
    public static final int BACKOFF_POLICY_EXPONENTIAL = 1;

    /* Minimum interval for a periodic job, in milliseconds. */
    private static final long MIN_PERIOD_MILLIS = 15 * 60 * 1000L;   // 15 minutes

    /* Minimum flex for a periodic job, in milliseconds. */
    private static final long MIN_FLEX_MILLIS = 5 * 60 * 1000L; // 5 minutes

    /**
     * Minimum backoff interval for a job, in milliseconds
     * @hide
     */
    public static final long MIN_BACKOFF_MILLIS = 10 * 1000L;      // 10 seconds

    /**
     * Query the minimum interval allowed for periodic scheduled jobs.  Attempting
     * to declare a smaller period than this when scheduling a job will result in a
     * job that is still periodic, but will run with this effective period.
     *
     * @return The minimum available interval for scheduling periodic jobs, in milliseconds.
     */
    public static final long getMinPeriodMillis() {
        return MIN_PERIOD_MILLIS;
    }

    /**
     * Query the minimum flex time allowed for periodic scheduled jobs.  Attempting
     * to declare a shorter flex time than this when scheduling such a job will
     * result in this amount as the effective flex time for the job.
     *
     * @return The minimum available flex time for scheduling periodic jobs, in milliseconds.
     */
    public static final long getMinFlexMillis() {
        return MIN_FLEX_MILLIS;
    }

    /**
     * Query the minimum automatic-reschedule backoff interval permitted for jobs.
     * @hide
     */
    public static final long getMinBackoffMillis() {
        return MIN_BACKOFF_MILLIS;
    }

    /**
     * Default type of backoff.
     * @hide
     */
    public static final int DEFAULT_BACKOFF_POLICY = BACKOFF_POLICY_EXPONENTIAL;

    /**
     * Job has minimal value to the user. The user has absolutely no expectation
     * or knowledge of this task and it has no bearing on the user's perception of
     * the app whatsoever. JobScheduler <i>may</i> decide to defer these tasks while
     * there are higher priority tasks in order to ensure there is sufficient quota
     * available for the higher priority tasks.
     * A sample task of min priority: uploading analytics
     */
    public static final int PRIORITY_MIN = 100;

    /**
     * Low priority. The task provides some benefit to users, but is not critical
     * and is more of a nice-to-have. This is more important than minimum priority
     * jobs and will be prioritized ahead of them, but may still be deferred in lieu
     * of higher priority jobs. JobScheduler <i>may</i> decide to defer these tasks
     * while there are higher priority tasks in order to ensure there is sufficient
     * quota available for the higher priority tasks.
     * A sample task of low priority: prefetching data the user hasn't requested
     */
    public static final int PRIORITY_LOW = 200;

    /**
     * Default value for all regular jobs. As noted in {@link JobScheduler},
     * these jobs have a general execution time of 10 minutes.
     * Receives the standard job management policy.
     */
    public static final int PRIORITY_DEFAULT = 300;

    /**
     * This task should be ordered ahead of most other tasks. It may be
     * deferred a little, but if it doesn't run at some point, the user may think
     * something is wrong. Assuming all constraints remain satisfied
     * (including ideal system load conditions), these jobs can have an
     * execution time of at least 4 minutes. Setting all of your jobs to high
     * priority will not be beneficial to your app and in fact may hurt its
     * performance in the long run.
     */
    public static final int PRIORITY_HIGH = 400;

    /**
     * This task should be run ahead of all other tasks. Only Expedited Jobs
     * {@link Builder#setExpedited(boolean)} can have this priority and as such,
     * are subject to the same execution time details noted in
     * {@link Builder#setExpedited(boolean)}.
     * A sample task of max priority: receiving a text message and processing it to
     * show a notification
     */
    public static final int PRIORITY_MAX = 500;

    /** @hide */
    @IntDef(prefix = {"PRIORITY_"}, value = {
            PRIORITY_MIN,
            PRIORITY_LOW,
            PRIORITY_DEFAULT,
            PRIORITY_HIGH,
            PRIORITY_MAX,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Priority {
    }

    /**
     * Default of {@link #getBias}.
     * @hide
     */
    public static final int BIAS_DEFAULT = 0;

    /**
     * Value of {@link #getBias} for expedited syncs.
     * @hide
     */
    public static final int BIAS_SYNC_EXPEDITED = 10;

    /**
     * Value of {@link #getBias} for first time initialization syncs.
     * @hide
     */
    public static final int BIAS_SYNC_INITIALIZATION = 20;

    /**
     * Value of {@link #getBias} for a BFGS app (overrides the supplied
     * JobInfo bias if it is smaller).
     * @hide
     */
    public static final int BIAS_BOUND_FOREGROUND_SERVICE = 30;

    /** @hide For backward compatibility. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PRIORITY_FOREGROUND_APP = BIAS_BOUND_FOREGROUND_SERVICE;

    /**
     * Value of {@link #getBias} for a FG service app (overrides the supplied
     * JobInfo bias if it is smaller).
     * @hide
     */
    public static final int BIAS_FOREGROUND_SERVICE = 35;

    /** @hide For backward compatibility. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PRIORITY_FOREGROUND_SERVICE = BIAS_FOREGROUND_SERVICE;

    /**
     * Value of {@link #getBias} for the current top app (overrides the supplied
     * JobInfo bias if it is smaller).
     * @hide
     */
    public static final int BIAS_TOP_APP = 40;

    /**
     * Adjustment of {@link #getBias} if the app has often (50% or more of the time)
     * been running jobs.
     * @hide
     */
    public static final int BIAS_ADJ_OFTEN_RUNNING = -40;

    /**
     * Adjustment of {@link #getBias} if the app has always (90% or more of the time)
     * been running jobs.
     * @hide
     */
    public static final int BIAS_ADJ_ALWAYS_RUNNING = -80;

    /**
     * Indicates that the implementation of this job will be using
     * {@link JobService#startForeground(int, android.app.Notification)} to run
     * in the foreground.
     * <p>
     * When set, the internal scheduling of this job will ignore any background
     * network restrictions for the requesting app. Note that this flag alone
     * doesn't actually place your {@link JobService} in the foreground; you
     * still need to post the notification yourself.
     * <p>
     * To use this flag, the caller must hold the
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL} permission.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int FLAG_WILL_BE_FOREGROUND = 1 << 0;

    /**
     * Allows this job to run despite doze restrictions as long as the app is in the foreground
     * or on the temporary whitelist
     * @hide
     */
    public static final int FLAG_IMPORTANT_WHILE_FOREGROUND = 1 << 1;

    /**
     * @hide
     */
    public static final int FLAG_PREFETCH = 1 << 2;

    /**
     * This job needs to be exempted from the app standby throttling. Only the system (UID 1000)
     * can set it. Jobs with a time constraint must not have it.
     *
     * @hide
     */
    public static final int FLAG_EXEMPT_FROM_APP_STANDBY = 1 << 3;

    /**
     * Whether it's an expedited job or not.
     *
     * @hide
     */
    public static final int FLAG_EXPEDITED = 1 << 4;

    /**
     * @hide
     */
    public static final int CONSTRAINT_FLAG_CHARGING = 1 << 0;

    /**
     * @hide
     */
    public static final int CONSTRAINT_FLAG_BATTERY_NOT_LOW = 1 << 1;

    /**
     * @hide
     */
    public static final int CONSTRAINT_FLAG_DEVICE_IDLE = 1 << 2;

    /**
     * @hide
     */
    public static final int CONSTRAINT_FLAG_STORAGE_NOT_LOW = 1 << 3;

    @UnsupportedAppUsage
    private final int jobId;
    private final PersistableBundle extras;
    private final Bundle transientExtras;
    private final ClipData clipData;
    private final int clipGrantFlags;
    @UnsupportedAppUsage
    private final ComponentName service;
    private final int constraintFlags;
    private final TriggerContentUri[] triggerContentUris;
    private final long triggerContentUpdateDelay;
    private final long triggerContentMaxDelay;
    private final boolean hasEarlyConstraint;
    private final boolean hasLateConstraint;
    private final NetworkRequest networkRequest;
    private final long networkDownloadBytes;
    private final long networkUploadBytes;
    private final long minimumNetworkChunkBytes;
    private final long minLatencyMillis;
    private final long maxExecutionDelayMillis;
    private final boolean isPeriodic;
    private final boolean isPersisted;
    private final long intervalMillis;
    private final long flexMillis;
    private final long initialBackoffMillis;
    private final int backoffPolicy;
    private final int mBias;
    @Priority
    private final int mPriority;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int flags;

    /**
     * Unique job id associated with this application (uid).  This is the same job ID
     * you supplied in the {@link Builder} constructor.
     */
    public int getId() {
        return jobId;
    }

    /**
     * @see JobInfo.Builder#setExtras(PersistableBundle)
     */
    public @NonNull PersistableBundle getExtras() {
        return extras;
    }

    /**
     * @see JobInfo.Builder#setTransientExtras(Bundle)
     */
    public @NonNull Bundle getTransientExtras() {
        return transientExtras;
    }

    /**
     * @see JobInfo.Builder#setClipData(ClipData, int)
     */
    public @Nullable ClipData getClipData() {
        return clipData;
    }

    /**
     * @see JobInfo.Builder#setClipData(ClipData, int)
     */
    public int getClipGrantFlags() {
        return clipGrantFlags;
    }

    /**
     * Name of the service endpoint that will be called back into by the JobScheduler.
     */
    public @NonNull ComponentName getService() {
        return service;
    }

    /** @hide */
    public int getBias() {
        return mBias;
    }

    /**
     * @see JobInfo.Builder#setPriority(int)
     */
    @Priority
    public int getPriority() {
        return mPriority;
    }

    /** @hide */
    public int getFlags() {
        return flags;
    }

    /** @hide */
    public boolean isExemptedFromAppStandby() {
        return ((flags & FLAG_EXEMPT_FROM_APP_STANDBY) != 0) && !isPeriodic();
    }

    /**
     * @see JobInfo.Builder#setRequiresCharging(boolean)
     */
    public boolean isRequireCharging() {
        return (constraintFlags & CONSTRAINT_FLAG_CHARGING) != 0;
    }

    /**
     * @see JobInfo.Builder#setRequiresBatteryNotLow(boolean)
     */
    public boolean isRequireBatteryNotLow() {
        return (constraintFlags & CONSTRAINT_FLAG_BATTERY_NOT_LOW) != 0;
    }

    /**
     * @see JobInfo.Builder#setRequiresDeviceIdle(boolean)
     */
    public boolean isRequireDeviceIdle() {
        return (constraintFlags & CONSTRAINT_FLAG_DEVICE_IDLE) != 0;
    }

    /**
     * @see JobInfo.Builder#setRequiresStorageNotLow(boolean)
     */
    public boolean isRequireStorageNotLow() {
        return (constraintFlags & CONSTRAINT_FLAG_STORAGE_NOT_LOW) != 0;
    }

    /**
     * @hide
     */
    public int getConstraintFlags() {
        return constraintFlags;
    }

    /**
     * Which content: URIs must change for the job to be scheduled.  Returns null
     * if there are none required.
     * @see JobInfo.Builder#addTriggerContentUri(TriggerContentUri)
     */
    public @Nullable TriggerContentUri[] getTriggerContentUris() {
        return triggerContentUris;
    }

    /**
     * When triggering on content URI changes, this is the delay from when a change
     * is detected until the job is scheduled.
     * @see JobInfo.Builder#setTriggerContentUpdateDelay(long)
     */
    public long getTriggerContentUpdateDelay() {
        return triggerContentUpdateDelay;
    }

    /**
     * When triggering on content URI changes, this is the maximum delay we will
     * use before scheduling the job.
     * @see JobInfo.Builder#setTriggerContentMaxDelay(long)
     */
    public long getTriggerContentMaxDelay() {
        return triggerContentMaxDelay;
    }

    /**
     * Return the basic description of the kind of network this job requires.
     *
     * @deprecated This method attempts to map {@link #getRequiredNetwork()}
     *             into the set of simple constants, which results in a loss of
     *             fidelity. Callers should move to using
     *             {@link #getRequiredNetwork()} directly.
     * @see Builder#setRequiredNetworkType(int)
     */
    @Deprecated
    public @NetworkType int getNetworkType() {
        if (networkRequest == null) {
            return NETWORK_TYPE_NONE;
        } else if (networkRequest.hasCapability(NET_CAPABILITY_NOT_METERED)) {
            return NETWORK_TYPE_UNMETERED;
        } else if (networkRequest.hasCapability(NET_CAPABILITY_NOT_ROAMING)) {
            return NETWORK_TYPE_NOT_ROAMING;
        } else if (networkRequest.hasTransport(TRANSPORT_CELLULAR)) {
            return NETWORK_TYPE_CELLULAR;
        } else {
            return NETWORK_TYPE_ANY;
        }
    }

    /**
     * Return the detailed description of the kind of network this job requires,
     * or {@code null} if no specific kind of network is required.
     *
     * @see Builder#setRequiredNetwork(NetworkRequest)
     */
    public @Nullable NetworkRequest getRequiredNetwork() {
        return networkRequest;
    }

    /**
     * Return the estimated size of download traffic that will be performed by
     * this job, in bytes.
     *
     * @return Estimated size of download traffic, or
     *         {@link #NETWORK_BYTES_UNKNOWN} when unknown.
     * @see Builder#setEstimatedNetworkBytes(long, long)
     */
    public @BytesLong long getEstimatedNetworkDownloadBytes() {
        return networkDownloadBytes;
    }

    /**
     * Return the estimated size of upload traffic that will be performed by
     * this job, in bytes.
     *
     * @return Estimated size of upload traffic, or
     *         {@link #NETWORK_BYTES_UNKNOWN} when unknown.
     * @see Builder#setEstimatedNetworkBytes(long, long)
     */
    public @BytesLong long getEstimatedNetworkUploadBytes() {
        return networkUploadBytes;
    }

    /**
     * Return the smallest piece of data that cannot be easily paused and resumed, in bytes.
     *
     * @return Smallest piece of data that cannot be easily paused and resumed, or
     *         {@link #NETWORK_BYTES_UNKNOWN} when unknown.
     * @see Builder#setMinimumNetworkChunkBytes(long)
     */
    public @BytesLong long getMinimumNetworkChunkBytes() {
        return minimumNetworkChunkBytes;
    }

    /**
     * Set for a job that does not recur periodically, to specify a delay after which the job
     * will be eligible for execution. This value is not set if the job recurs periodically.
     * @see JobInfo.Builder#setMinimumLatency(long)
     */
    public long getMinLatencyMillis() {
        return minLatencyMillis;
    }

    /**
     * @see JobInfo.Builder#setOverrideDeadline(long)
     */
    public long getMaxExecutionDelayMillis() {
        return maxExecutionDelayMillis;
    }

    /**
     * Track whether this job will repeat with a given period.
     * @see JobInfo.Builder#setPeriodic(long)
     * @see JobInfo.Builder#setPeriodic(long, long)
     */
    public boolean isPeriodic() {
        return isPeriodic;
    }

    /**
     * @see JobInfo.Builder#setPersisted(boolean)
     */
    public boolean isPersisted() {
        return isPersisted;
    }

    /**
     * Set to the interval between occurrences of this job. This value is <b>not</b> set if the
     * job does not recur periodically.
     * @see JobInfo.Builder#setPeriodic(long)
     * @see JobInfo.Builder#setPeriodic(long, long)
     */
    public long getIntervalMillis() {
        return intervalMillis;
    }

    /**
     * Flex time for this job. Only valid if this is a periodic job.  The job can
     * execute at any time in a window of flex length at the end of the period.
     * @see JobInfo.Builder#setPeriodic(long)
     * @see JobInfo.Builder#setPeriodic(long, long)
     */
    public long getFlexMillis() {
        return flexMillis;
    }

    /**
     * The amount of time the JobScheduler will wait before rescheduling a failed job. This value
     * will be increased depending on the backoff policy specified at job creation time. Defaults
     * to 30 seconds, minimum is currently 10 seconds.
     * @see JobInfo.Builder#setBackoffCriteria(long, int)
     */
    public long getInitialBackoffMillis() {
        return initialBackoffMillis;
    }

    /**
     * Return the backoff policy of this job.
     *
     * @see JobInfo.Builder#setBackoffCriteria(long, int)
     */
    public @BackoffPolicy int getBackoffPolicy() {
        return backoffPolicy;
    }

    /**
     * @see JobInfo.Builder#setExpedited(boolean)
     */
    public boolean isExpedited() {
        return (flags & FLAG_EXPEDITED) != 0;
    }

    /**
     * @see JobInfo.Builder#setImportantWhileForeground(boolean)
     */
    public boolean isImportantWhileForeground() {
        return (flags & FLAG_IMPORTANT_WHILE_FOREGROUND) != 0;
    }

    /**
     * @see JobInfo.Builder#setPrefetch(boolean)
     */
    public boolean isPrefetch() {
        return (flags & FLAG_PREFETCH) != 0;
    }

    /**
     * User can specify an early constraint of 0L, which is valid, so we keep track of whether the
     * function was called at all.
     * @hide
     */
    public boolean hasEarlyConstraint() {
        return hasEarlyConstraint;
    }

    /**
     * User can specify a late constraint of 0L, which is valid, so we keep track of whether the
     * function was called at all.
     * @hide
     */
    public boolean hasLateConstraint() {
        return hasLateConstraint;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JobInfo)) {
            return false;
        }
        JobInfo j = (JobInfo) o;
        if (jobId != j.jobId) {
            return false;
        }
        // XXX won't be correct if one is parcelled and the other not.
        if (!BaseBundle.kindofEquals(extras, j.extras)) {
            return false;
        }
        // XXX won't be correct if one is parcelled and the other not.
        if (!BaseBundle.kindofEquals(transientExtras, j.transientExtras)) {
            return false;
        }
        // XXX for now we consider two different clip data objects to be different,
        // regardless of whether their contents are the same.
        if (clipData != j.clipData) {
            return false;
        }
        if (clipGrantFlags != j.clipGrantFlags) {
            return false;
        }
        if (!Objects.equals(service, j.service)) {
            return false;
        }
        if (constraintFlags != j.constraintFlags) {
            return false;
        }
        if (!Arrays.equals(triggerContentUris, j.triggerContentUris)) {
            return false;
        }
        if (triggerContentUpdateDelay != j.triggerContentUpdateDelay) {
            return false;
        }
        if (triggerContentMaxDelay != j.triggerContentMaxDelay) {
            return false;
        }
        if (hasEarlyConstraint != j.hasEarlyConstraint) {
            return false;
        }
        if (hasLateConstraint != j.hasLateConstraint) {
            return false;
        }
        if (!Objects.equals(networkRequest, j.networkRequest)) {
            return false;
        }
        if (networkDownloadBytes != j.networkDownloadBytes) {
            return false;
        }
        if (networkUploadBytes != j.networkUploadBytes) {
            return false;
        }
        if (minimumNetworkChunkBytes != j.minimumNetworkChunkBytes) {
            return false;
        }
        if (minLatencyMillis != j.minLatencyMillis) {
            return false;
        }
        if (maxExecutionDelayMillis != j.maxExecutionDelayMillis) {
            return false;
        }
        if (isPeriodic != j.isPeriodic) {
            return false;
        }
        if (isPersisted != j.isPersisted) {
            return false;
        }
        if (intervalMillis != j.intervalMillis) {
            return false;
        }
        if (flexMillis != j.flexMillis) {
            return false;
        }
        if (initialBackoffMillis != j.initialBackoffMillis) {
            return false;
        }
        if (backoffPolicy != j.backoffPolicy) {
            return false;
        }
        if (mBias != j.mBias) {
            return false;
        }
        if (mPriority != j.mPriority) {
            return false;
        }
        if (flags != j.flags) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hashCode = jobId;
        if (extras != null) {
            hashCode = 31 * hashCode + extras.hashCode();
        }
        if (transientExtras != null) {
            hashCode = 31 * hashCode + transientExtras.hashCode();
        }
        if (clipData != null) {
            hashCode = 31 * hashCode + clipData.hashCode();
        }
        hashCode = 31*hashCode + clipGrantFlags;
        if (service != null) {
            hashCode = 31 * hashCode + service.hashCode();
        }
        hashCode = 31 * hashCode + constraintFlags;
        if (triggerContentUris != null) {
            hashCode = 31 * hashCode + Arrays.hashCode(triggerContentUris);
        }
        hashCode = 31 * hashCode + Long.hashCode(triggerContentUpdateDelay);
        hashCode = 31 * hashCode + Long.hashCode(triggerContentMaxDelay);
        hashCode = 31 * hashCode + Boolean.hashCode(hasEarlyConstraint);
        hashCode = 31 * hashCode + Boolean.hashCode(hasLateConstraint);
        if (networkRequest != null) {
            hashCode = 31 * hashCode + networkRequest.hashCode();
        }
        hashCode = 31 * hashCode + Long.hashCode(networkDownloadBytes);
        hashCode = 31 * hashCode + Long.hashCode(networkUploadBytes);
        hashCode = 31 * hashCode + Long.hashCode(minimumNetworkChunkBytes);
        hashCode = 31 * hashCode + Long.hashCode(minLatencyMillis);
        hashCode = 31 * hashCode + Long.hashCode(maxExecutionDelayMillis);
        hashCode = 31 * hashCode + Boolean.hashCode(isPeriodic);
        hashCode = 31 * hashCode + Boolean.hashCode(isPersisted);
        hashCode = 31 * hashCode + Long.hashCode(intervalMillis);
        hashCode = 31 * hashCode + Long.hashCode(flexMillis);
        hashCode = 31 * hashCode + Long.hashCode(initialBackoffMillis);
        hashCode = 31 * hashCode + backoffPolicy;
        hashCode = 31 * hashCode + mBias;
        hashCode = 31 * hashCode + mPriority;
        hashCode = 31 * hashCode + flags;
        return hashCode;
    }

    @SuppressWarnings("UnsafeParcelApi")
    private JobInfo(Parcel in) {
        jobId = in.readInt();
        extras = in.readPersistableBundle();
        transientExtras = in.readBundle();
        if (in.readInt() != 0) {
            clipData = ClipData.CREATOR.createFromParcel(in);
            clipGrantFlags = in.readInt();
        } else {
            clipData = null;
            clipGrantFlags = 0;
        }
        service = in.readParcelable(null);
        constraintFlags = in.readInt();
        triggerContentUris = in.createTypedArray(TriggerContentUri.CREATOR);
        triggerContentUpdateDelay = in.readLong();
        triggerContentMaxDelay = in.readLong();
        if (in.readInt() != 0) {
            networkRequest = NetworkRequest.CREATOR.createFromParcel(in);
        } else {
            networkRequest = null;
        }
        networkDownloadBytes = in.readLong();
        networkUploadBytes = in.readLong();
        minimumNetworkChunkBytes = in.readLong();
        minLatencyMillis = in.readLong();
        maxExecutionDelayMillis = in.readLong();
        isPeriodic = in.readInt() == 1;
        isPersisted = in.readInt() == 1;
        intervalMillis = in.readLong();
        flexMillis = in.readLong();
        initialBackoffMillis = in.readLong();
        backoffPolicy = in.readInt();
        hasEarlyConstraint = in.readInt() == 1;
        hasLateConstraint = in.readInt() == 1;
        mBias = in.readInt();
        mPriority = in.readInt();
        flags = in.readInt();
    }

    private JobInfo(JobInfo.Builder b) {
        jobId = b.mJobId;
        extras = b.mExtras.deepCopy();
        transientExtras = b.mTransientExtras.deepCopy();
        clipData = b.mClipData;
        clipGrantFlags = b.mClipGrantFlags;
        service = b.mJobService;
        constraintFlags = b.mConstraintFlags;
        triggerContentUris = b.mTriggerContentUris != null
                ? b.mTriggerContentUris.toArray(new TriggerContentUri[b.mTriggerContentUris.size()])
                : null;
        triggerContentUpdateDelay = b.mTriggerContentUpdateDelay;
        triggerContentMaxDelay = b.mTriggerContentMaxDelay;
        networkRequest = b.mNetworkRequest;
        networkDownloadBytes = b.mNetworkDownloadBytes;
        networkUploadBytes = b.mNetworkUploadBytes;
        minimumNetworkChunkBytes = b.mMinimumNetworkChunkBytes;
        minLatencyMillis = b.mMinLatencyMillis;
        maxExecutionDelayMillis = b.mMaxExecutionDelayMillis;
        isPeriodic = b.mIsPeriodic;
        isPersisted = b.mIsPersisted;
        intervalMillis = b.mIntervalMillis;
        flexMillis = b.mFlexMillis;
        initialBackoffMillis = b.mInitialBackoffMillis;
        backoffPolicy = b.mBackoffPolicy;
        hasEarlyConstraint = b.mHasEarlyConstraint;
        hasLateConstraint = b.mHasLateConstraint;
        mBias = b.mBias;
        mPriority = b.mPriority;
        flags = b.mFlags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(jobId);
        out.writePersistableBundle(extras);
        out.writeBundle(transientExtras);
        if (clipData != null) {
            out.writeInt(1);
            clipData.writeToParcel(out, flags);
            out.writeInt(clipGrantFlags);
        } else {
            out.writeInt(0);
        }
        out.writeParcelable(service, flags);
        out.writeInt(constraintFlags);
        out.writeTypedArray(triggerContentUris, flags);
        out.writeLong(triggerContentUpdateDelay);
        out.writeLong(triggerContentMaxDelay);
        if (networkRequest != null) {
            out.writeInt(1);
            networkRequest.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeLong(networkDownloadBytes);
        out.writeLong(networkUploadBytes);
        out.writeLong(minimumNetworkChunkBytes);
        out.writeLong(minLatencyMillis);
        out.writeLong(maxExecutionDelayMillis);
        out.writeInt(isPeriodic ? 1 : 0);
        out.writeInt(isPersisted ? 1 : 0);
        out.writeLong(intervalMillis);
        out.writeLong(flexMillis);
        out.writeLong(initialBackoffMillis);
        out.writeInt(backoffPolicy);
        out.writeInt(hasEarlyConstraint ? 1 : 0);
        out.writeInt(hasLateConstraint ? 1 : 0);
        out.writeInt(mBias);
        out.writeInt(mPriority);
        out.writeInt(this.flags);
    }

    public static final @android.annotation.NonNull Creator<JobInfo> CREATOR = new Creator<JobInfo>() {
        @Override
        public JobInfo createFromParcel(Parcel in) {
            return new JobInfo(in);
        }

        @Override
        public JobInfo[] newArray(int size) {
            return new JobInfo[size];
        }
    };

    @Override
    public String toString() {
        return "(job:" + jobId + "/" + service.flattenToShortString() + ")";
    }

    /**
     * Information about a content URI modification that a job would like to
     * trigger on.
     */
    public static final class TriggerContentUri implements Parcelable {
        private final Uri mUri;
        private final int mFlags;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "FLAG_" }, value = {
                FLAG_NOTIFY_FOR_DESCENDANTS,
        })
        public @interface Flags { }

        /**
         * Flag for trigger: also trigger if any descendants of the given URI change.
         * Corresponds to the <var>notifyForDescendants</var> of
         * {@link android.content.ContentResolver#registerContentObserver}.
         */
        public static final int FLAG_NOTIFY_FOR_DESCENDANTS = 1<<0;

        /**
         * Create a new trigger description.
         * @param uri The URI to observe.  Must be non-null.
         * @param flags Flags for the observer.
         */
        public TriggerContentUri(@NonNull Uri uri, @Flags int flags) {
            mUri = Objects.requireNonNull(uri);
            mFlags = flags;
        }

        /**
         * Return the Uri this trigger was created for.
         */
        public Uri getUri() {
            return mUri;
        }

        /**
         * Return the flags supplied for the trigger.
         */
        public @Flags int getFlags() {
            return mFlags;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TriggerContentUri)) {
                return false;
            }
            TriggerContentUri t = (TriggerContentUri) o;
            return Objects.equals(t.mUri, mUri) && t.mFlags == mFlags;
        }

        @Override
        public int hashCode() {
            return (mUri == null ? 0 : mUri.hashCode()) ^ mFlags;
        }

        private TriggerContentUri(Parcel in) {
            mUri = Uri.CREATOR.createFromParcel(in);
            mFlags = in.readInt();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            mUri.writeToParcel(out, flags);
            out.writeInt(mFlags);
        }

        public static final @android.annotation.NonNull Creator<TriggerContentUri> CREATOR = new Creator<TriggerContentUri>() {
            @Override
            public TriggerContentUri createFromParcel(Parcel in) {
                return new TriggerContentUri(in);
            }

            @Override
            public TriggerContentUri[] newArray(int size) {
                return new TriggerContentUri[size];
            }
        };
    }

    /** Builder class for constructing {@link JobInfo} objects. */
    public static final class Builder {
        private final int mJobId;
        private final ComponentName mJobService;
        private PersistableBundle mExtras = PersistableBundle.EMPTY;
        private Bundle mTransientExtras = Bundle.EMPTY;
        private ClipData mClipData;
        private int mClipGrantFlags;
        private int mBias = BIAS_DEFAULT;
        @Priority
        private int mPriority = PRIORITY_DEFAULT;
        private int mFlags;
        // Requirements.
        private int mConstraintFlags;
        private NetworkRequest mNetworkRequest;
        private long mNetworkDownloadBytes = NETWORK_BYTES_UNKNOWN;
        private long mNetworkUploadBytes = NETWORK_BYTES_UNKNOWN;
        private long mMinimumNetworkChunkBytes = NETWORK_BYTES_UNKNOWN;
        private ArrayList<TriggerContentUri> mTriggerContentUris;
        private long mTriggerContentUpdateDelay = -1;
        private long mTriggerContentMaxDelay = -1;
        private boolean mIsPersisted;
        // One-off parameters.
        private long mMinLatencyMillis;
        private long mMaxExecutionDelayMillis;
        // Periodic parameters.
        private boolean mIsPeriodic;
        private boolean mHasEarlyConstraint;
        private boolean mHasLateConstraint;
        private long mIntervalMillis;
        private long mFlexMillis;
        // Back-off parameters.
        private long mInitialBackoffMillis = DEFAULT_INITIAL_BACKOFF_MILLIS;
        private int mBackoffPolicy = DEFAULT_BACKOFF_POLICY;
        /** Easy way to track whether the client has tried to set a back-off policy. */
        private boolean mBackoffPolicySet = false;

        /**
         * Initialize a new Builder to construct a {@link JobInfo}.
         *
         * @param jobId Application-provided id for this job. Subsequent calls to cancel, or
         * jobs created with the same jobId, will update the pre-existing job with
         * the same id.  This ID must be unique across all clients of the same uid
         * (not just the same package).  You will want to make sure this is a stable
         * id across app updates, so probably not based on a resource ID.
         * @param jobService The endpoint that you implement that will receive the callback from the
         * JobScheduler.
         */
        public Builder(int jobId, @NonNull ComponentName jobService) {
            mJobService = jobService;
            mJobId = jobId;
        }

        /**
         * Creates a new Builder of JobInfo from an existing instance.
         * @hide
         */
        public Builder(@NonNull JobInfo job) {
            mJobId = job.getId();
            mJobService = job.getService();
            mExtras = job.getExtras();
            mTransientExtras = job.getTransientExtras();
            mClipData = job.getClipData();
            mClipGrantFlags = job.getClipGrantFlags();
            mBias = job.getBias();
            mFlags = job.getFlags();
            mConstraintFlags = job.getConstraintFlags();
            mNetworkRequest = job.getRequiredNetwork();
            mNetworkDownloadBytes = job.getEstimatedNetworkDownloadBytes();
            mNetworkUploadBytes = job.getEstimatedNetworkUploadBytes();
            mMinimumNetworkChunkBytes = job.getMinimumNetworkChunkBytes();
            mTriggerContentUris = job.getTriggerContentUris() != null
                    ? new ArrayList<>(Arrays.asList(job.getTriggerContentUris())) : null;
            mTriggerContentUpdateDelay = job.getTriggerContentUpdateDelay();
            mTriggerContentMaxDelay = job.getTriggerContentMaxDelay();
            mIsPersisted = job.isPersisted();
            mMinLatencyMillis = job.getMinLatencyMillis();
            mMaxExecutionDelayMillis = job.getMaxExecutionDelayMillis();
            mIsPeriodic = job.isPeriodic();
            mHasEarlyConstraint = job.hasEarlyConstraint();
            mHasLateConstraint = job.hasLateConstraint();
            mIntervalMillis = job.getIntervalMillis();
            mFlexMillis = job.getFlexMillis();
            mInitialBackoffMillis = job.getInitialBackoffMillis();
            // mBackoffPolicySet isn't set but it's fine since this is copying from an already valid
            // job.
            mBackoffPolicy = job.getBackoffPolicy();
            mPriority = job.getPriority();
        }

        /** @hide */
        @NonNull
        public Builder setBias(int bias) {
            mBias = bias;
            return this;
        }

        /**
         * Indicate the priority for this job. The priority set here will be used to sort jobs
         * for the calling app and apply slightly different policies based on the priority.
         * The priority will <b>NOT</b> be used as a global sorting value to sort between
         * different app's jobs. Use this to inform the system about which jobs it should try
         * to run before other jobs. Giving the same priority to all of your jobs will result
         * in them all being treated the same. The priorities each have slightly different
         * behaviors, as noted in their relevant javadoc.
         *
         * <b>NOTE:</b> Setting all of your jobs to high priority will not be
         * beneficial to your app and in fact may hurt its performance in the
         * long run.
         *
         * In order to prevent starvation, repeatedly retried jobs (because of failures) will slowly
         * have their priorities lowered.
         *
         * @see JobInfo#getPriority()
         */
        @NonNull
        public Builder setPriority(@Priority int priority) {
            if (priority > PRIORITY_MAX || priority < PRIORITY_MIN) {
                if (Compatibility.isChangeEnabled(THROW_ON_INVALID_PRIORITY_VALUE)) {
                    throw new IllegalArgumentException("Invalid priority value");
                }
                // No-op for invalid calls of apps that are targeting S-. This was an unsupported
                // API before Tiramisu, so anyone calling this that isn't targeting T isn't
                // guaranteed a behavior change.
                return this;
            }
            mPriority = priority;
            return this;
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Builder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Set optional extras. This is persisted, so we only allow primitive types.
         * @param extras Bundle containing extras you want the scheduler to hold on to for you.
         * @see JobInfo#getExtras()
         */
        public Builder setExtras(@NonNull PersistableBundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Set optional transient extras.
         *
         * <p>Because setting this property is not compatible with persisted
         * jobs, doing so will throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.</p>
         *
         * @param extras Bundle containing extras you want the scheduler to hold on to for you.
         * @see JobInfo#getTransientExtras()
         */
        public Builder setTransientExtras(@NonNull Bundle extras) {
            mTransientExtras = extras;
            return this;
        }

        /**
         * Set a {@link ClipData} associated with this Job.
         *
         * <p>The main purpose of providing a ClipData is to allow granting of
         * URI permissions for data associated with the clip.  The exact kind
         * of permission grant to perform is specified through <var>grantFlags</var>.
         *
         * <p>If the ClipData contains items that are Intents, any
         * grant flags in those Intents will be ignored.  Only flags provided as an argument
         * to this method are respected, and will be applied to all Uri or
         * Intent items in the clip (or sub-items of the clip).
         *
         * <p>Because setting this property is not compatible with persisted
         * jobs, doing so will throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.</p>
         *
         * @param clip The new clip to set.  May be null to clear the current clip.
         * @param grantFlags The desired permissions to grant for any URIs.  This should be
         * a combination of {@link android.content.Intent#FLAG_GRANT_READ_URI_PERMISSION},
         * {@link android.content.Intent#FLAG_GRANT_WRITE_URI_PERMISSION}, and
         * {@link android.content.Intent#FLAG_GRANT_PREFIX_URI_PERMISSION}.
         * @see JobInfo#getClipData()
         * @see JobInfo#getClipGrantFlags()
         */
        public Builder setClipData(@Nullable ClipData clip, int grantFlags) {
            mClipData = clip;
            mClipGrantFlags = grantFlags;
            return this;
        }

        /**
         * Set basic description of the kind of network your job requires. If
         * you need more precise control over network capabilities, see
         * {@link #setRequiredNetwork(NetworkRequest)}.
         * <p>
         * If your job doesn't need a network connection, you don't need to call
         * this method, as the default value is {@link #NETWORK_TYPE_NONE}.
         * <p>
         * Calling this method defines network as a strict requirement for your
         * job. If the network requested is not available your job will never
         * run. See {@link #setOverrideDeadline(long)} to change this behavior.
         * Calling this method will override any requirements previously defined
         * by {@link #setRequiredNetwork(NetworkRequest)}; you typically only
         * want to call one of these methods.
         * <p class="note">
         * When your job executes in
         * {@link JobService#onStartJob(JobParameters)}, be sure to use the
         * specific network returned by {@link JobParameters#getNetwork()},
         * otherwise you'll use the default network which may not meet this
         * constraint.
         *
         * @see #setRequiredNetwork(NetworkRequest)
         * @see JobInfo#getNetworkType()
         * @see JobParameters#getNetwork()
         */
        public Builder setRequiredNetworkType(@NetworkType int networkType) {
            if (networkType == NETWORK_TYPE_NONE) {
                return setRequiredNetwork(null);
            } else {
                final NetworkRequest.Builder builder = new NetworkRequest.Builder();

                // All types require validated Internet
                builder.addCapability(NET_CAPABILITY_INTERNET);
                builder.addCapability(NET_CAPABILITY_VALIDATED);
                builder.removeCapability(NET_CAPABILITY_NOT_VPN);
                builder.removeCapability(NET_CAPABILITY_NOT_RESTRICTED);

                if (networkType == NETWORK_TYPE_ANY) {
                    // No other capabilities
                } else if (networkType == NETWORK_TYPE_UNMETERED) {
                    builder.addCapability(NET_CAPABILITY_NOT_METERED);
                } else if (networkType == NETWORK_TYPE_NOT_ROAMING) {
                    builder.addCapability(NET_CAPABILITY_NOT_ROAMING);
                } else if (networkType == NETWORK_TYPE_CELLULAR) {
                    builder.addTransportType(TRANSPORT_CELLULAR);
                }

                return setRequiredNetwork(builder.build());
            }
        }

        /**
         * Set detailed description of the kind of network your job requires.
         * <p>
         * If your job doesn't need a network connection, you don't need to call
         * this method, as the default is {@code null}.
         * <p>
         * Calling this method defines network as a strict requirement for your
         * job. If the network requested is not available your job will never
         * run. See {@link #setOverrideDeadline(long)} to change this behavior.
         * Calling this method will override any requirements previously defined
         * by {@link #setRequiredNetworkType(int)}; you typically only want to
         * call one of these methods.
         * <p class="note">
         * When your job executes in
         * {@link JobService#onStartJob(JobParameters)}, be sure to use the
         * specific network returned by {@link JobParameters#getNetwork()},
         * otherwise you'll use the default network which may not meet this
         * constraint.
         *
         * @param networkRequest The detailed description of the kind of network
         *            this job requires, or {@code null} if no specific kind of
         *            network is required. Defining a {@link NetworkSpecifier}
         *            is only supported for jobs that aren't persisted.
         * @see #setRequiredNetworkType(int)
         * @see JobInfo#getRequiredNetwork()
         * @see JobParameters#getNetwork()
         */
        public Builder setRequiredNetwork(@Nullable NetworkRequest networkRequest) {
            mNetworkRequest = networkRequest;
            return this;
        }

        /**
         * Set the estimated size of network traffic that will be performed by
         * this job, in bytes.
         * <p>
         * Apps are encouraged to provide values that are as accurate as
         * possible, but when the exact size isn't available, an
         * order-of-magnitude estimate can be provided instead. Here are some
         * specific examples:
         * <ul>
         * <li>A job that is backing up a photo knows the exact size of that
         * photo, so it should provide that size as the estimate.
         * <li>A job that refreshes top news stories wouldn't know an exact
         * size, but if the size is expected to be consistently around 100KB, it
         * can provide that order-of-magnitude value as the estimate.
         * <li>A job that synchronizes email could end up using an extreme range
         * of data, from under 1KB when nothing has changed, to dozens of MB
         * when there are new emails with attachments. Jobs that cannot provide
         * reasonable estimates should use the sentinel value
         * {@link JobInfo#NETWORK_BYTES_UNKNOWN}.
         * </ul>
         * Note that the system may choose to delay jobs with large network
         * usage estimates when the device has a poor network connection, in
         * order to save battery and possible network costs.
         * Starting from Android version {@link Build.VERSION_CODES#S}, JobScheduler may attempt
         * to run large jobs when the device is charging and on an unmetered network, even if the
         * network is slow. This gives large jobs an opportunity to make forward progress, even if
         * they risk timing out.
         * <p>
         * The values provided here only reflect the traffic that will be
         * performed by the base job; if you're using {@link JobWorkItem} then
         * you also need to define the network traffic used by each work item
         * when constructing them.
         *
         * <p class="note">
         * Prior to Android version {@link Build.VERSION_CODES#TIRAMISU}, JobScheduler used the
         * estimated transfer numbers in a similar fashion to
         * {@link #setMinimumNetworkChunkBytes(long)} (to estimate if the work would complete
         * within the time available to job). In other words, JobScheduler treated the transfer as
         * all-or-nothing. Starting from Android version {@link Build.VERSION_CODES#TIRAMISU},
         * JobScheduler will only use the estimated transfer numbers in this manner if minimum
         * chunk sizes have not been provided via {@link #setMinimumNetworkChunkBytes(long)}.
         *
         * @param downloadBytes The estimated size of network traffic that will
         *            be downloaded by this job, in bytes.
         * @param uploadBytes The estimated size of network traffic that will be
         *            uploaded by this job, in bytes.
         * @see JobInfo#getEstimatedNetworkDownloadBytes()
         * @see JobInfo#getEstimatedNetworkUploadBytes()
         * @see JobWorkItem#JobWorkItem(android.content.Intent, long, long)
         */
        public Builder setEstimatedNetworkBytes(@BytesLong long downloadBytes,
                @BytesLong long uploadBytes) {
            mNetworkDownloadBytes = downloadBytes;
            mNetworkUploadBytes = uploadBytes;
            return this;
        }

        /**
         * Set the minimum size of non-resumable network traffic this job requires, in bytes. When
         * the upload or download can be easily paused and resumed, use this to set the smallest
         * size that must be transmitted between start and stop events to be considered successful.
         * If the transfer cannot be paused and resumed, then this should be the sum of the values
         * provided to {@link JobInfo.Builder#setEstimatedNetworkBytes(long, long)}.
         *
         * <p>
         * Apps are encouraged to provide values that are as accurate as possible since JobScheduler
         * will try to run the job at a time when at least the minimum chunk can be transmitted to
         * reduce the amount of repetitive data that's transferred. Jobs that cannot provide
         * reasonable estimates should use the sentinel value {@link JobInfo#NETWORK_BYTES_UNKNOWN}.
         *
         * <p>
         * The values provided here only reflect the minimum non-resumable traffic that will be
         * performed by the base job; if you're using {@link JobWorkItem} then
         * you also need to define the network traffic used by each work item
         * when constructing them.
         *
         * @param chunkSizeBytes The smallest piece of data that cannot be easily paused and
         *                       resumed, in bytes.
         * @see JobInfo#getMinimumNetworkChunkBytes()
         * @see JobWorkItem#JobWorkItem(android.content.Intent, long, long, long)
         */
        @NonNull
        public Builder setMinimumNetworkChunkBytes(@BytesLong long chunkSizeBytes) {
            if (chunkSizeBytes != NETWORK_BYTES_UNKNOWN && chunkSizeBytes <= 0) {
                throw new IllegalArgumentException("Minimum chunk size must be positive");
            }
            mMinimumNetworkChunkBytes = chunkSizeBytes;
            return this;
        }

        /**
         * Specify that to run this job, the device must be charging (or be a
         * non-battery-powered device connected to permanent power, such as Android TV
         * devices). This defaults to {@code false}.
         *
         * <p class="note">For purposes of running jobs, a battery-powered device
         * "charging" is not quite the same as simply being connected to power.  If the
         * device is so busy that the battery is draining despite a power connection, jobs
         * with this constraint will <em>not</em> run.  This can happen during some
         * common use cases such as video chat, particularly if the device is plugged in
         * to USB rather than to wall power.
         *
         * @param requiresCharging Pass {@code true} to require that the device be
         *     charging in order to run the job.
         * @see JobInfo#isRequireCharging()
         */
        public Builder setRequiresCharging(boolean requiresCharging) {
            mConstraintFlags = (mConstraintFlags&~CONSTRAINT_FLAG_CHARGING)
                    | (requiresCharging ? CONSTRAINT_FLAG_CHARGING : 0);
            return this;
        }

        /**
         * Specify that to run this job, the device's battery level must not be low.
         * This defaults to false.  If true, the job will only run when the battery level
         * is not low, which is generally the point where the user is given a "low battery"
         * warning.
         * @param batteryNotLow Whether or not the device's battery level must not be low.
         * @see JobInfo#isRequireBatteryNotLow()
         */
        public Builder setRequiresBatteryNotLow(boolean batteryNotLow) {
            mConstraintFlags = (mConstraintFlags&~CONSTRAINT_FLAG_BATTERY_NOT_LOW)
                    | (batteryNotLow ? CONSTRAINT_FLAG_BATTERY_NOT_LOW : 0);
            return this;
        }

        /**
         * When set {@code true}, ensure that this job will not run if the device is in active use.
         * The default state is {@code false}: that is, the for the job to be runnable even when
         * someone is interacting with the device.
         *
         * <p>This state is a loose definition provided by the system. In general, it means that
         * the device is not currently being used interactively, and has not been in use for some
         * time. As such, it is a good time to perform resource heavy jobs. Bear in mind that
         * battery usage will still be attributed to your application, and surfaced to the user in
         * battery stats.</p>
         *
         * <p class="note">Despite the similar naming, this job constraint is <em>not</em>
         * related to the system's "device idle" or "doze" states.  This constraint only
         * determines whether a job is allowed to run while the device is directly in use.
         *
         * @param requiresDeviceIdle Pass {@code true} to prevent the job from running
         *     while the device is being used interactively.
         * @see JobInfo#isRequireDeviceIdle()
         */
        public Builder setRequiresDeviceIdle(boolean requiresDeviceIdle) {
            mConstraintFlags = (mConstraintFlags&~CONSTRAINT_FLAG_DEVICE_IDLE)
                    | (requiresDeviceIdle ? CONSTRAINT_FLAG_DEVICE_IDLE : 0);
            return this;
        }

        /**
         * Specify that to run this job, the device's available storage must not be low.
         * This defaults to false.  If true, the job will only run when the device is not
         * in a low storage state, which is generally the point where the user is given a
         * "low storage" warning.
         * @param storageNotLow Whether or not the device's available storage must not be low.
         * @see JobInfo#isRequireStorageNotLow()
         */
        public Builder setRequiresStorageNotLow(boolean storageNotLow) {
            mConstraintFlags = (mConstraintFlags&~CONSTRAINT_FLAG_STORAGE_NOT_LOW)
                    | (storageNotLow ? CONSTRAINT_FLAG_STORAGE_NOT_LOW : 0);
            return this;
        }

        /**
         * Add a new content: URI that will be monitored with a
         * {@link android.database.ContentObserver}, and will cause the job to execute if changed.
         * If you have any trigger content URIs associated with a job, it will not execute until
         * there has been a change report for one or more of them.
         *
         * <p>Note that trigger URIs can not be used in combination with
         * {@link #setPeriodic(long)} or {@link #setPersisted(boolean)}.  To continually monitor
         * for content changes, you need to schedule a new JobInfo using the same job ID and
         * observing the same URIs in place of calling
         * {@link JobService#jobFinished(JobParameters, boolean)}. Remember that
         * {@link JobScheduler#schedule(JobInfo)} stops a running job if it uses the same job ID,
         * so only call it after you've finished processing the most recent changes (in other words,
         * call {@link JobScheduler#schedule(JobInfo)} where you would have normally called
         * {@link JobService#jobFinished(JobParameters, boolean)}.
         * Following this pattern will ensure you do not lose any content changes: while your
         * job is running, the system will continue monitoring for content changes, and propagate
         * any changes it sees over to the next job you schedule, so you do not have to worry
         * about missing new changes. <b>Scheduling the new job
         * before or during processing will cause the current job to be stopped (as described in
         * {@link JobScheduler#schedule(JobInfo)}), meaning the wakelock will be released for the
         * current job and your app process may be killed since it will no longer be in a valid
         * component lifecycle.</b>
         * Since {@link JobScheduler#schedule(JobInfo)} stops the current job, you do not
         * need to call {@link JobService#jobFinished(JobParameters, boolean)} if you call
         * {@link JobScheduler#schedule(JobInfo)} using the same job ID as the
         * currently running job.</p>
         *
         * <p>Because setting this property is not compatible with periodic or
         * persisted jobs, doing so will throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.</p>
         *
         * <p>The following example shows how this feature can be used to monitor for changes
         * in the photos on a device.</p>
         *
         * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/PhotosContentJob.java
         *      job}
         *
         * @param uri The content: URI to monitor.
         * @see JobInfo#getTriggerContentUris()
         */
        public Builder addTriggerContentUri(@NonNull TriggerContentUri uri) {
            if (mTriggerContentUris == null) {
                mTriggerContentUris = new ArrayList<>();
            }
            mTriggerContentUris.add(uri);
            return this;
        }

        /**
         * Set the delay (in milliseconds) from when a content change is detected until
         * the job is scheduled.  If there are more changes during that time, the delay
         * will be reset to start at the time of the most recent change.
         * @param durationMs Delay after most recent content change, in milliseconds.
         * @see JobInfo#getTriggerContentUpdateDelay()
         */
        public Builder setTriggerContentUpdateDelay(long durationMs) {
            mTriggerContentUpdateDelay = durationMs;
            return this;
        }

        /**
         * Set the maximum total delay (in milliseconds) that is allowed from the first
         * time a content change is detected until the job is scheduled.
         * @param durationMs Delay after initial content change, in milliseconds.
         * @see JobInfo#getTriggerContentMaxDelay()
         */
        public Builder setTriggerContentMaxDelay(long durationMs) {
            mTriggerContentMaxDelay = durationMs;
            return this;
        }

        /**
         * Specify that this job should recur with the provided interval, not more than once per
         * period. You have no control over when within this interval this job will be executed,
         * only the guarantee that it will be executed at most once within this interval, as long
         * as the constraints are satisfied. If the constraints are not satisfied within this
         * interval, the job will wait until the constraints are satisfied.
         * Setting this function on the builder with {@link #setMinimumLatency(long)} or
         * {@link #setOverrideDeadline(long)} will result in an error.
         * @param intervalMillis Millisecond interval for which this job will repeat.
         * @see JobInfo#getIntervalMillis()
         * @see JobInfo#getFlexMillis()
         */
        public Builder setPeriodic(long intervalMillis) {
            return setPeriodic(intervalMillis, intervalMillis);
        }

        /**
         * Specify that this job should recur with the provided interval and flex. The job can
         * execute at any time in a window of flex length at the end of the period.
         * @param intervalMillis Millisecond interval for which this job will repeat. A minimum
         *                       value of {@link #getMinPeriodMillis()} is enforced.
         * @param flexMillis Millisecond flex for this job. Flex is clamped to be at least
         *                   {@link #getMinFlexMillis()} or 5 percent of the period, whichever is
         *                   higher.
         * @see JobInfo#getIntervalMillis()
         * @see JobInfo#getFlexMillis()
         */
        public Builder setPeriodic(long intervalMillis, long flexMillis) {
            final long minPeriod = getMinPeriodMillis();
            if (intervalMillis < minPeriod) {
                Log.w(TAG, "Requested interval " + formatDuration(intervalMillis) + " for job "
                        + mJobId + " is too small; raising to " + formatDuration(minPeriod));
                intervalMillis = minPeriod;
            }

            final long percentClamp = 5 * intervalMillis / 100;
            final long minFlex = Math.max(percentClamp, getMinFlexMillis());
            if (flexMillis < minFlex) {
                Log.w(TAG, "Requested flex " + formatDuration(flexMillis) + " for job " + mJobId
                        + " is too small; raising to " + formatDuration(minFlex));
                flexMillis = minFlex;
            }

            mIsPeriodic = true;
            mIntervalMillis = intervalMillis;
            mFlexMillis = flexMillis;
            mHasEarlyConstraint = mHasLateConstraint = true;
            return this;
        }

        /**
         * Specify that this job should be delayed by the provided amount of time. The job may not
         * run the instant the delay has elapsed. JobScheduler will start the job at an
         * indeterminate time after the delay has elapsed.
         * <p>
         * Because it doesn't make sense setting this property on a periodic job, doing so will
         * throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.
         * @param minLatencyMillis Milliseconds before which this job will not be considered for
         *                         execution.
         * @see JobInfo#getMinLatencyMillis()
         */
        public Builder setMinimumLatency(long minLatencyMillis) {
            mMinLatencyMillis = minLatencyMillis;
            mHasEarlyConstraint = true;
            return this;
        }

        /**
         * Set deadline which is the maximum scheduling latency. The job will be run by this
         * deadline even if other requirements (including a delay set through
         * {@link #setMinimumLatency(long)}) are not met.
         * <p>
         * Because it doesn't make sense setting this property on a periodic job, doing so will
         * throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.
         * @see JobInfo#getMaxExecutionDelayMillis()
         */
        public Builder setOverrideDeadline(long maxExecutionDelayMillis) {
            mMaxExecutionDelayMillis = maxExecutionDelayMillis;
            mHasLateConstraint = true;
            return this;
        }

        /**
         * Set up the back-off/retry policy.
         * This defaults to some respectable values: {30 seconds, Exponential}. We cap back-off at
         * 5hrs.
         * <p>
         * Note that trying to set a backoff criteria for a job with
         * {@link #setRequiresDeviceIdle(boolean)} will throw an exception when you call build().
         * This is because back-off typically does not make sense for these types of jobs. See
         * {@link android.app.job.JobService#jobFinished(android.app.job.JobParameters, boolean)}
         * for more description of the return value for the case of a job executing while in idle
         * mode.
         * @param initialBackoffMillis Millisecond time interval to wait initially when job has
         *                             failed.
         * @see JobInfo#getInitialBackoffMillis()
         * @see JobInfo#getBackoffPolicy()
         */
        public Builder setBackoffCriteria(long initialBackoffMillis,
                @BackoffPolicy int backoffPolicy) {
            final long minBackoff = getMinBackoffMillis();
            if (initialBackoffMillis < minBackoff) {
                Log.w(TAG, "Requested backoff " + formatDuration(initialBackoffMillis) + " for job "
                        + mJobId + " is too small; raising to " + formatDuration(minBackoff));
                initialBackoffMillis = minBackoff;
            }

            mBackoffPolicySet = true;
            mInitialBackoffMillis = initialBackoffMillis;
            mBackoffPolicy = backoffPolicy;
            return this;
        }

        /**
         * Setting this to true indicates that this job is important and needs to run as soon as
         * possible with stronger guarantees than regular jobs. These "expedited" jobs will:
         * <ol>
         *     <li>Run as soon as possible</li>
         *     <li>Be less restricted during Doze and battery saver</li>
         *     <li>Bypass Doze, app standby, and battery saver network restrictions</li>
         *     <li>Be less likely to be killed than regular jobs</li>
         *     <li>Be subject to background location throttling</li>
         * </ol>
         *
         * <p>
         * Since these jobs have stronger guarantees than regular jobs, they will be subject to
         * stricter quotas. As long as an app has available expedited quota, jobs scheduled with
         * this set to true will run with these guarantees. If an app has run out of available
         * expedited quota, any pending expedited jobs will run as regular jobs.
         * {@link JobParameters#isExpeditedJob()} can be used to know whether the executing job
         * has expedited guarantees or not. In addition, {@link JobScheduler#schedule(JobInfo)}
         * will immediately return {@link JobScheduler#RESULT_FAILURE} if the app does not have
         * available quota (and the job will not be successfully scheduled).
         *
         * <p>
         * Expedited job quota will replenish over time and as the user interacts with the app,
         * so you should not have to worry about running out of quota because of processing from
         * frequent user engagement.
         *
         * <p>
         * Expedited jobs may only set network, storage-not-low, and persistence constraints.
         * No other constraints are allowed.
         *
         * <p>
         * Assuming all constraints remain satisfied (including ideal system load conditions),
         * expedited jobs can have an execution time of at least 1 minute. If your
         * app has remaining expedited job quota, then the expedited job <i>may</i> potentially run
         * longer until remaining quota is used up. Just like with regular jobs, quota is not
         * consumed while the app is on top and visible to the user.
         *
         * <p class="note">
         * Note: Even though expedited jobs are meant to run as soon as possible, they may be
         * deferred if the system is under heavy load or requested constraints are not satisfied.
         * This delay may be true for expedited jobs of the foreground app on Android version
         * {@link Build.VERSION_CODES#S}, but starting from Android version
         * {@link Build.VERSION_CODES#TIRAMISU}, expedited jobs for the foreground app are
         * guaranteed to be started before {@link JobScheduler#schedule(JobInfo)} returns (assuming
         * all requested constraints are satisfied), similar to foreground services.
         *
         * @see JobInfo#isExpedited()
         */
        @NonNull
        public Builder setExpedited(boolean expedited) {
            if (expedited) {
                mFlags |= FLAG_EXPEDITED;
                if (mPriority == PRIORITY_DEFAULT) {
                    // The default priority for EJs is MAX, but only change this if .setPriority()
                    // hasn't been called yet.
                    mPriority = PRIORITY_MAX;
                }
            } else {
                if (mPriority == PRIORITY_MAX && (mFlags & FLAG_EXPEDITED) != 0) {
                    // Reset the priority for the job, but only change this if .setPriority()
                    // hasn't been called yet.
                    mPriority = PRIORITY_DEFAULT;
                }
                mFlags &= (~FLAG_EXPEDITED);
            }
            return this;
        }

        /**
         * Setting this to true indicates that this job is important while the scheduling app
         * is in the foreground or on the temporary whitelist for background restrictions.
         * This means that the system will relax doze restrictions on this job during this time.
         *
         * Apps should use this flag only for short jobs that are essential for the app to function
         * properly in the foreground.
         *
         * Note that once the scheduling app is no longer whitelisted from background restrictions
         * and in the background, or the job failed due to unsatisfied constraints,
         * this job should be expected to behave like other jobs without this flag.
         *
         * <p>
         * Jobs marked as important-while-foreground are given {@link #PRIORITY_HIGH} by default.
         *
         * @param importantWhileForeground whether to relax doze restrictions for this job when the
         *                                 app is in the foreground. False by default.
         * @see JobInfo#isImportantWhileForeground()
         * @deprecated Use {@link #setExpedited(boolean)} instead.
         */
        @Deprecated
        public Builder setImportantWhileForeground(boolean importantWhileForeground) {
            if (importantWhileForeground) {
                mFlags |= FLAG_IMPORTANT_WHILE_FOREGROUND;
                if (mPriority == PRIORITY_DEFAULT) {
                    // The default priority for important-while-foreground is HIGH, but only change
                    // this if .setPriority() hasn't been called yet.
                    mPriority = PRIORITY_HIGH;
                }
            } else {
                if (mPriority == PRIORITY_HIGH
                        && (mFlags & FLAG_IMPORTANT_WHILE_FOREGROUND) != 0) {
                    // Reset the priority for the job, but only change this if .setPriority()
                    // hasn't been called yet.
                    mPriority = PRIORITY_DEFAULT;
                }
                mFlags &= (~FLAG_IMPORTANT_WHILE_FOREGROUND);
            }
            return this;
        }

        /**
         * Setting this to true indicates that this job is designed to prefetch
         * content that will make a material improvement to the experience of
         * the specific user of this device. For example, fetching top headlines
         * of interest to the current user.
         * <p>
         * Apps targeting Android version {@link Build.VERSION_CODES#TIRAMISU} or later are
         * not allowed to have deadlines (set via {@link #setOverrideDeadline(long)} on their
         * prefetch jobs.
         * <p>
         * The system may use this signal to relax the network constraints you
         * originally requested, such as allowing a
         * {@link JobInfo#NETWORK_TYPE_UNMETERED} job to run over a metered
         * network when there is a surplus of metered data available. The system
         * may also use this signal in combination with end user usage patterns
         * to ensure data is prefetched before the user launches your app.
         * @see JobInfo#isPrefetch()
         */
        public Builder setPrefetch(boolean prefetch) {
            if (prefetch) {
                mFlags |= FLAG_PREFETCH;
            } else {
                mFlags &= (~FLAG_PREFETCH);
            }
            return this;
        }

        /**
         * Set whether or not to persist this job across device reboots.
         *
         * @param isPersisted True to indicate that the job will be written to
         *            disk and loaded at boot.
         * @see JobInfo#isPersisted()
         */
        @RequiresPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED)
        public Builder setPersisted(boolean isPersisted) {
            mIsPersisted = isPersisted;
            return this;
        }

        /**
         * @return The job object to hand to the JobScheduler. This object is immutable.
         */
        public JobInfo build() {
            return build(Compatibility.isChangeEnabled(DISALLOW_DEADLINES_FOR_PREFETCH_JOBS));
        }

        /** @hide */
        public JobInfo build(boolean disallowPrefetchDeadlines) {
            // This check doesn't need to be inside enforceValidity. It's an unnecessary legacy
            // check that would ideally be phased out instead.
            if (mBackoffPolicySet && (mConstraintFlags & CONSTRAINT_FLAG_DEVICE_IDLE) != 0) {
                throw new IllegalArgumentException("An idle mode job will not respect any" +
                        " back-off policy, so calling setBackoffCriteria with" +
                        " setRequiresDeviceIdle is an error.");
            }
            JobInfo jobInfo = new JobInfo(this);
            jobInfo.enforceValidity(disallowPrefetchDeadlines);
            return jobInfo;
        }

        /**
         * @hide
         */
        public String summarize() {
            final String service = (mJobService != null)
                    ? mJobService.flattenToShortString()
                    : "null";
            return "JobInfo.Builder{job:" + mJobId + "/" + service + "}";
        }
    }

    /**
     * @hide
     */
    public final void enforceValidity(boolean disallowPrefetchDeadlines) {
        // Check that network estimates require network type and are reasonable values.
        if ((networkDownloadBytes > 0 || networkUploadBytes > 0 || minimumNetworkChunkBytes > 0)
                && networkRequest == null) {
            throw new IllegalArgumentException(
                    "Can't provide estimated network usage without requiring a network");
        }
        final long estimatedTransfer;
        if (networkUploadBytes == NETWORK_BYTES_UNKNOWN) {
            estimatedTransfer = networkDownloadBytes;
        } else {
            estimatedTransfer = networkUploadBytes
                    + (networkDownloadBytes == NETWORK_BYTES_UNKNOWN ? 0 : networkDownloadBytes);
        }
        if (minimumNetworkChunkBytes != NETWORK_BYTES_UNKNOWN
                && estimatedTransfer != NETWORK_BYTES_UNKNOWN
                && minimumNetworkChunkBytes > estimatedTransfer) {
            throw new IllegalArgumentException(
                    "Minimum chunk size can't be greater than estimated network usage");
        }
        if (minimumNetworkChunkBytes != NETWORK_BYTES_UNKNOWN && minimumNetworkChunkBytes <= 0) {
            throw new IllegalArgumentException("Minimum chunk size must be positive");
        }

        final boolean hasDeadline = maxExecutionDelayMillis != 0L;
        // Check that a deadline was not set on a periodic job.
        if (isPeriodic) {
            if (hasDeadline) {
                throw new IllegalArgumentException(
                        "Can't call setOverrideDeadline() on a periodic job.");
            }
            if (minLatencyMillis != 0L) {
                throw new IllegalArgumentException(
                        "Can't call setMinimumLatency() on a periodic job");
            }
            if (triggerContentUris != null) {
                throw new IllegalArgumentException(
                        "Can't call addTriggerContentUri() on a periodic job");
            }
        }

        // Prefetch jobs should not have deadlines
        if (disallowPrefetchDeadlines && hasDeadline && (flags & FLAG_PREFETCH) != 0) {
            throw new IllegalArgumentException(
                    "Can't call setOverrideDeadline() on a prefetch job.");
        }

        if (isPersisted) {
            // We can't serialize network specifiers
            if (networkRequest != null
                    && networkRequest.getNetworkSpecifier() != null) {
                throw new IllegalArgumentException(
                        "Network specifiers aren't supported for persistent jobs");
            }
            if (triggerContentUris != null) {
                throw new IllegalArgumentException(
                        "Can't call addTriggerContentUri() on a persisted job");
            }
            if (!transientExtras.isEmpty()) {
                throw new IllegalArgumentException(
                        "Can't call setTransientExtras() on a persisted job");
            }
            if (clipData != null) {
                throw new IllegalArgumentException(
                        "Can't call setClipData() on a persisted job");
            }
        }

        if ((flags & FLAG_IMPORTANT_WHILE_FOREGROUND) != 0) {
            if (hasEarlyConstraint) {
                throw new IllegalArgumentException(
                        "An important while foreground job cannot have a time delay");
            }
            if (mPriority != PRIORITY_HIGH && mPriority != PRIORITY_DEFAULT) {
                throw new IllegalArgumentException(
                        "An important while foreground job must be high or default priority."
                                + " Don't mark unimportant tasks as important while foreground.");
            }
        }

        final boolean isExpedited = (flags & FLAG_EXPEDITED) != 0;
        switch (mPriority) {
            case PRIORITY_MAX:
                if (!isExpedited) {
                    throw new IllegalArgumentException("Only expedited jobs can have max priority");
                }
                break;
            case PRIORITY_HIGH:
                if ((flags & FLAG_PREFETCH) != 0) {
                    throw new IllegalArgumentException("Prefetch jobs cannot be high priority");
                }
                if (isPeriodic) {
                    throw new IllegalArgumentException("Periodic jobs cannot be high priority");
                }
                break;
            case PRIORITY_DEFAULT:
            case PRIORITY_LOW:
            case PRIORITY_MIN:
                break;
            default:
                throw new IllegalArgumentException("Invalid priority level provided: " + mPriority);
        }

        if (isExpedited) {
            if (hasEarlyConstraint) {
                throw new IllegalArgumentException("An expedited job cannot have a time delay");
            }
            if (hasLateConstraint) {
                throw new IllegalArgumentException("An expedited job cannot have a deadline");
            }
            if (isPeriodic) {
                throw new IllegalArgumentException("An expedited job cannot be periodic");
            }
            if (mPriority != PRIORITY_MAX && mPriority != PRIORITY_HIGH) {
                throw new IllegalArgumentException(
                        "An expedited job must be high or max priority. Don't use expedited jobs"
                                + " for unimportant tasks.");
            }
            if ((constraintFlags & ~CONSTRAINT_FLAG_STORAGE_NOT_LOW) != 0
                    || (flags & ~(FLAG_EXPEDITED | FLAG_EXEMPT_FROM_APP_STANDBY)) != 0) {
                throw new IllegalArgumentException(
                        "An expedited job can only have network and storage-not-low constraints");
            }
            if (triggerContentUris != null && triggerContentUris.length > 0) {
                throw new IllegalArgumentException(
                        "Can't call addTriggerContentUri() on an expedited job");
            }
        }
    }

    /**
     * Convert a bias integer into a human readable string for debugging.
     * @hide
     */
    public static String getBiasString(int bias) {
        switch (bias) {
            case BIAS_DEFAULT:
                return BIAS_DEFAULT + " [DEFAULT]";
            case BIAS_SYNC_EXPEDITED:
                return BIAS_SYNC_EXPEDITED + " [SYNC_EXPEDITED]";
            case BIAS_SYNC_INITIALIZATION:
                return BIAS_SYNC_INITIALIZATION + " [SYNC_INITIALIZATION]";
            case BIAS_BOUND_FOREGROUND_SERVICE:
                return BIAS_BOUND_FOREGROUND_SERVICE + " [BFGS_APP]";
            case BIAS_FOREGROUND_SERVICE:
                return BIAS_FOREGROUND_SERVICE + " [FGS_APP]";
            case BIAS_TOP_APP:
                return BIAS_TOP_APP + " [TOP_APP]";

                // BIAS_ADJ_* are adjustments and not used as real priorities.
                // No need to convert to strings.
        }
        return bias + " [UNKNOWN]";
    }

    /**
     * Convert a priority integer into a human readable string for debugging.
     * @hide
     */
    public static String getPriorityString(@Priority int priority) {
        switch (priority) {
            case PRIORITY_MIN:
                return priority + " [MIN]";
            case PRIORITY_LOW:
                return priority + " [LOW]";
            case PRIORITY_DEFAULT:
                return priority + " [DEFAULT]";
            case PRIORITY_HIGH:
                return priority + " [HIGH]";
            case PRIORITY_MAX:
                return priority + " [MAX]";
        }
        return priority + " [UNKNOWN]";
    }
}
