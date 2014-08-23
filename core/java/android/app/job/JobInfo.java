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

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * Container of data passed to the {@link android.app.job.JobScheduler} fully encapsulating the
 * parameters required to schedule work against the calling application. These are constructed
 * using the {@link JobInfo.Builder}.
 * You must specify at least one sort of constraint on the JobInfo object that you are creating.
 * The goal here is to provide the scheduler with high-level semantics about the work you want to
 * accomplish. Doing otherwise with throw an exception in your app.
 */
public class JobInfo implements Parcelable {
    /** Default. */
    public static final int NETWORK_TYPE_NONE = 0;
    /** This job requires network connectivity. */
    public static final int NETWORK_TYPE_ANY = 1;
    /** This job requires network connectivity that is unmetered. */
    public static final int NETWORK_TYPE_UNMETERED = 2;

    /**
     * Amount of backoff a job has initially by default, in milliseconds.
     */
    public static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 30000L;  // 30 seconds.

    /**
     * Maximum backoff we allow for a job, in milliseconds.
     */
    public static final long MAX_BACKOFF_DELAY_MILLIS = 5 * 60 * 60 * 1000;  // 5 hours.

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

    /**
     * Default type of backoff.
     * @hide
     */
    public static final int DEFAULT_BACKOFF_POLICY = BACKOFF_POLICY_EXPONENTIAL;

    private final int jobId;
    private final PersistableBundle extras;
    private final ComponentName service;
    private final boolean requireCharging;
    private final boolean requireDeviceIdle;
    private final boolean hasEarlyConstraint;
    private final boolean hasLateConstraint;
    private final int networkType;
    private final long minLatencyMillis;
    private final long maxExecutionDelayMillis;
    private final boolean isPeriodic;
    private final boolean isPersisted;
    private final long intervalMillis;
    private final long initialBackoffMillis;
    private final int backoffPolicy;

    /**
     * Unique job id associated with this class. This is assigned to your job by the scheduler.
     */
    public int getId() {
        return jobId;
    }

    /**
     * Bundle of extras which are returned to your application at execution time.
     */
    public PersistableBundle getExtras() {
        return extras;
    }

    /**
     * Name of the service endpoint that will be called back into by the JobScheduler.
     */
    public ComponentName getService() {
        return service;
    }

    /**
     * Whether this job needs the device to be plugged in.
     */
    public boolean isRequireCharging() {
        return requireCharging;
    }

    /**
     * Whether this job needs the device to be in an Idle maintenance window.
     */
    public boolean isRequireDeviceIdle() {
        return requireDeviceIdle;
    }

    /**
     * One of {@link android.app.job.JobInfo#NETWORK_TYPE_ANY},
     * {@link android.app.job.JobInfo#NETWORK_TYPE_NONE}, or
     * {@link android.app.job.JobInfo#NETWORK_TYPE_UNMETERED}.
     */
    public int getNetworkType() {
        return networkType;
    }

    /**
     * Set for a job that does not recur periodically, to specify a delay after which the job
     * will be eligible for execution. This value is not set if the job recurs periodically.
     */
    public long getMinLatencyMillis() {
        return minLatencyMillis;
    }

    /**
     * See {@link Builder#setOverrideDeadline(long)}. This value is not set if the job recurs
     * periodically.
     */
    public long getMaxExecutionDelayMillis() {
        return maxExecutionDelayMillis;
    }

    /**
     * Track whether this job will repeat with a given period.
     */
    public boolean isPeriodic() {
        return isPeriodic;
    }

    /**
     * @return Whether or not this job should be persisted across device reboots.
     */
    public boolean isPersisted() {
        return isPersisted;
    }

    /**
     * Set to the interval between occurrences of this job. This value is <b>not</b> set if the
     * job does not recur periodically.
     */
    public long getIntervalMillis() {
        return intervalMillis;
    }

    /**
     * The amount of time the JobScheduler will wait before rescheduling a failed job. This value
     * will be increased depending on the backoff policy specified at job creation time. Defaults
     * to 5 seconds.
     */
    public long getInitialBackoffMillis() {
        return initialBackoffMillis;
    }

    /**
     * One of either {@link android.app.job.JobInfo#BACKOFF_POLICY_EXPONENTIAL}, or
     * {@link android.app.job.JobInfo#BACKOFF_POLICY_LINEAR}, depending on which criteria you set
     * when creating this job.
     */
    public int getBackoffPolicy() {
        return backoffPolicy;
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

    private JobInfo(Parcel in) {
        jobId = in.readInt();
        extras = in.readPersistableBundle();
        service = in.readParcelable(null);
        requireCharging = in.readInt() == 1;
        requireDeviceIdle = in.readInt() == 1;
        networkType = in.readInt();
        minLatencyMillis = in.readLong();
        maxExecutionDelayMillis = in.readLong();
        isPeriodic = in.readInt() == 1;
        isPersisted = in.readInt() == 1;
        intervalMillis = in.readLong();
        initialBackoffMillis = in.readLong();
        backoffPolicy = in.readInt();
        hasEarlyConstraint = in.readInt() == 1;
        hasLateConstraint = in.readInt() == 1;
    }

    private JobInfo(JobInfo.Builder b) {
        jobId = b.mJobId;
        extras = b.mExtras;
        service = b.mJobService;
        requireCharging = b.mRequiresCharging;
        requireDeviceIdle = b.mRequiresDeviceIdle;
        networkType = b.mNetworkType;
        minLatencyMillis = b.mMinLatencyMillis;
        maxExecutionDelayMillis = b.mMaxExecutionDelayMillis;
        isPeriodic = b.mIsPeriodic;
        isPersisted = b.mIsPersisted;
        intervalMillis = b.mIntervalMillis;
        initialBackoffMillis = b.mInitialBackoffMillis;
        backoffPolicy = b.mBackoffPolicy;
        hasEarlyConstraint = b.mHasEarlyConstraint;
        hasLateConstraint = b.mHasLateConstraint;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(jobId);
        out.writePersistableBundle(extras);
        out.writeParcelable(service, flags);
        out.writeInt(requireCharging ? 1 : 0);
        out.writeInt(requireDeviceIdle ? 1 : 0);
        out.writeInt(networkType);
        out.writeLong(minLatencyMillis);
        out.writeLong(maxExecutionDelayMillis);
        out.writeInt(isPeriodic ? 1 : 0);
        out.writeInt(isPersisted ? 1 : 0);
        out.writeLong(intervalMillis);
        out.writeLong(initialBackoffMillis);
        out.writeInt(backoffPolicy);
        out.writeInt(hasEarlyConstraint ? 1 : 0);
        out.writeInt(hasLateConstraint ? 1 : 0);
    }

    public static final Creator<JobInfo> CREATOR = new Creator<JobInfo>() {
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

    /** Builder class for constructing {@link JobInfo} objects. */
    public static final class Builder {
        private int mJobId;
        private PersistableBundle mExtras = PersistableBundle.EMPTY;
        private ComponentName mJobService;
        // Requirements.
        private boolean mRequiresCharging;
        private boolean mRequiresDeviceIdle;
        private int mNetworkType;
        private boolean mIsPersisted;
        // One-off parameters.
        private long mMinLatencyMillis;
        private long mMaxExecutionDelayMillis;
        // Periodic parameters.
        private boolean mIsPeriodic;
        private boolean mHasEarlyConstraint;
        private boolean mHasLateConstraint;
        private long mIntervalMillis;
        // Back-off parameters.
        private long mInitialBackoffMillis = DEFAULT_INITIAL_BACKOFF_MILLIS;
        private int mBackoffPolicy = DEFAULT_BACKOFF_POLICY;
        /** Easy way to track whether the client has tried to set a back-off policy. */
        private boolean mBackoffPolicySet = false;

        /**
         * @param jobId Application-provided id for this job. Subsequent calls to cancel, or
         *               jobs created with the same jobId, will update the pre-existing job with
         *               the same id.
         * @param jobService The endpoint that you implement that will receive the callback from the
         *            JobScheduler.
         */
        public Builder(int jobId, ComponentName jobService) {
            mJobService = jobService;
            mJobId = jobId;
        }

        /**
         * Set optional extras. This is persisted, so we only allow primitive types.
         * @param extras Bundle containing extras you want the scheduler to hold on to for you.
         */
        public Builder setExtras(PersistableBundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Set some description of the kind of network type your job needs to have.
         * Not calling this function means the network is not necessary, as the default is
         * {@link #NETWORK_TYPE_NONE}.
         * Bear in mind that calling this function defines network as a strict requirement for your
         * job. If the network requested is not available your job will never run. See
         * {@link #setOverrideDeadline(long)} to change this behaviour.
         */
        public Builder setRequiredNetworkType(int networkType) {
            mNetworkType = networkType;
            return this;
        }

        /**
         * Specify that to run this job, the device needs to be plugged in. This defaults to
         * false.
         * @param requiresCharging Whether or not the device is plugged in.
         */
        public Builder setRequiresCharging(boolean requiresCharging) {
            mRequiresCharging = requiresCharging;
            return this;
        }

        /**
         * Specify that to run, the job needs the device to be in idle mode. This defaults to
         * false.
         * <p>Idle mode is a loose definition provided by the system, which means that the device
         * is not in use, and has not been in use for some time. As such, it is a good time to
         * perform resource heavy jobs. Bear in mind that battery usage will still be attributed
         * to your application, and surfaced to the user in battery stats.</p>
         * @param requiresDeviceIdle Whether or not the device need be within an idle maintenance
         *                           window.
         */
        public Builder setRequiresDeviceIdle(boolean requiresDeviceIdle) {
            mRequiresDeviceIdle = requiresDeviceIdle;
            return this;
        }

        /**
         * Specify that this job should recur with the provided interval, not more than once per
         * period. You have no control over when within this interval this job will be executed,
         * only the guarantee that it will be executed at most once within this interval.
         * Setting this function on the builder with {@link #setMinimumLatency(long)} or
         * {@link #setOverrideDeadline(long)} will result in an error.
         * @param intervalMillis Millisecond interval for which this job will repeat.
         */
        public Builder setPeriodic(long intervalMillis) {
            mIsPeriodic = true;
            mIntervalMillis = intervalMillis;
            mHasEarlyConstraint = mHasLateConstraint = true;
            return this;
        }

        /**
         * Specify that this job should be delayed by the provided amount of time.
         * Because it doesn't make sense setting this property on a periodic job, doing so will
         * throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.
         * @param minLatencyMillis Milliseconds before which this job will not be considered for
         *                         execution.
         */
        public Builder setMinimumLatency(long minLatencyMillis) {
            mMinLatencyMillis = minLatencyMillis;
            mHasEarlyConstraint = true;
            return this;
        }

        /**
         * Set deadline which is the maximum scheduling latency. The job will be run by this
         * deadline even if other requirements are not met. Because it doesn't make sense setting
         * this property on a periodic job, doing so will throw an
         * {@link java.lang.IllegalArgumentException} when
         * {@link android.app.job.JobInfo.Builder#build()} is called.
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
         * Note that trying to set a backoff criteria for a job with
         * {@link #setRequiresDeviceIdle(boolean)} will throw an exception when you call build().
         * This is because back-off typically does not make sense for these types of jobs. See
         * {@link android.app.job.JobService#jobFinished(android.app.job.JobParameters, boolean)}
         * for more description of the return value for the case of a job executing while in idle
         * mode.
         * @param initialBackoffMillis Millisecond time interval to wait initially when job has
         *                             failed.
         * @param backoffPolicy is one of {@link #BACKOFF_POLICY_LINEAR} or
         * {@link #BACKOFF_POLICY_EXPONENTIAL}
         */
        public Builder setBackoffCriteria(long initialBackoffMillis, int backoffPolicy) {
            mBackoffPolicySet = true;
            mInitialBackoffMillis = initialBackoffMillis;
            mBackoffPolicy = backoffPolicy;
            return this;
        }

        /**
         * Set whether or not to persist this job across device reboots. This will only have an
         * effect if your application holds the permission
         * {@link android.Manifest.permission#RECEIVE_BOOT_COMPLETED}. Otherwise an exception will
         * be thrown.
         * @param isPersisted True to indicate that the job will be written to disk and loaded at
         *                    boot.
         */
        public Builder setPersisted(boolean isPersisted) {
            mIsPersisted = isPersisted;
            return this;
        }

        /**
         * @return The job object to hand to the JobScheduler. This object is immutable.
         */
        public JobInfo build() {
            // Allow jobs with no constraints - What am I, a database?
            if (!mHasEarlyConstraint && !mHasLateConstraint && !mRequiresCharging &&
                    !mRequiresDeviceIdle && mNetworkType == NETWORK_TYPE_NONE) {
                throw new IllegalArgumentException("You're trying to build a job with no " +
                        "constraints, this is not allowed.");
            }
            mExtras = new PersistableBundle(mExtras);  // Make our own copy.
            // Check that a deadline was not set on a periodic job.
            if (mIsPeriodic && (mMaxExecutionDelayMillis != 0L)) {
                throw new IllegalArgumentException("Can't call setOverrideDeadline() on a " +
                        "periodic job.");
            }
            if (mIsPeriodic && (mMinLatencyMillis != 0L)) {
                throw new IllegalArgumentException("Can't call setMinimumLatency() on a " +
                        "periodic job");
            }
            if (mBackoffPolicySet && mRequiresDeviceIdle) {
                throw new IllegalArgumentException("An idle mode job will not respect any" +
                        " back-off policy, so calling setBackoffCriteria with" +
                        " setRequiresDeviceIdle is an error.");
            }
            return new JobInfo(this);
        }
    }

}
