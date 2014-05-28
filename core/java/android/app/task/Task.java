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

package android.app.task;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container of data passed to the {@link android.app.task.TaskManager} fully encapsulating the
 * parameters required to schedule work against the calling application. These are constructed
 * using the {@link Task.Builder}.
 */
public class Task implements Parcelable {
    public interface NetworkType {
        /** Default. */
        public final int NONE = 0;
        /** This task requires network connectivity. */
        public final int ANY = 1;
        /** This task requires network connectivity that is unmetered. */
        public final int UNMETERED = 2;
    }

    /**
     * Linear: retry_time(failure_time, t) = failure_time + initial_retry_delay * t, t >= 1
     * Expon: retry_time(failure_time, t) = failure_time + initial_retry_delay ^ t, t >= 1
     */
    public interface BackoffPolicy {
        public final int LINEAR = 0;
        public final int EXPONENTIAL = 1;
    }

    private final int taskId;
    // TODO: Change this to use PersistableBundle when that lands in master.
    private final Bundle extras;
    private final ComponentName service;
    private final boolean requireCharging;
    private final boolean requireDeviceIdle;
    private final boolean hasEarlyConstraint;
    private final boolean hasLateConstraint;
    private final int networkCapabilities;
    private final long minLatencyMillis;
    private final long maxExecutionDelayMillis;
    private final boolean isPeriodic;
    private final long intervalMillis;
    private final long initialBackoffMillis;
    private final int backoffPolicy;

    /**
     * Unique task id associated with this class. This is assigned to your task by the scheduler.
     */
    public int getId() {
        return taskId;
    }

    /**
     * Bundle of extras which are returned to your application at execution time.
     */
    public Bundle getExtras() {
        return extras;
    }

    /**
     * Name of the service endpoint that will be called back into by the TaskManager.
     */
    public ComponentName getService() {
        return service;
    }

    /**
     * Whether this task needs the device to be plugged in.
     */
    public boolean isRequireCharging() {
        return requireCharging;
    }

    /**
     * Whether this task needs the device to be in an Idle maintenance window.
     */
    public boolean isRequireDeviceIdle() {
        return requireDeviceIdle;
    }

    /**
     * See {@link android.app.task.Task.NetworkType} for a description of this value.
     */
    public int getNetworkCapabilities() {
        return networkCapabilities;
    }

    /**
     * Set for a task that does not recur periodically, to specify a delay after which the task
     * will be eligible for execution. This value is not set if the task recurs periodically.
     */
    public long getMinLatencyMillis() {
        return minLatencyMillis;
    }

    /**
     * See {@link Builder#setOverrideDeadline(long)}. This value is not set if the task recurs
     * periodically.
     */
    public long getMaxExecutionDelayMillis() {
        return maxExecutionDelayMillis;
    }

    /**
     * Track whether this task will repeat with a given period.
     */
    public boolean isPeriodic() {
        return isPeriodic;
    }

    /**
     * Set to the interval between occurrences of this task. This value is <b>not</b> set if the
     * task does not recur periodically.
     */
    public long getIntervalMillis() {
        return intervalMillis;
    }

    /**
     * The amount of time the TaskManager will wait before rescheduling a failed task. This value
     * will be increased depending on the backoff policy specified at task creation time. Defaults
     * to 5 seconds.
     */
    public long getInitialBackoffMillis() {
        return initialBackoffMillis;
    }

    /**
     * See {@link android.app.task.Task.BackoffPolicy} for an explanation of the values this field
     * can take. This defaults to exponential.
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

    private Task(Parcel in) {
        taskId = in.readInt();
        extras = in.readBundle();
        service = ComponentName.readFromParcel(in);
        requireCharging = in.readInt() == 1;
        requireDeviceIdle = in.readInt() == 1;
        networkCapabilities = in.readInt();
        minLatencyMillis = in.readLong();
        maxExecutionDelayMillis = in.readLong();
        isPeriodic = in.readInt() == 1;
        intervalMillis = in.readLong();
        initialBackoffMillis = in.readLong();
        backoffPolicy = in.readInt();
        hasEarlyConstraint = in.readInt() == 1;
        hasLateConstraint = in.readInt() == 1;
    }

    private Task(Task.Builder b) {
        taskId = b.mTaskId;
        extras = new Bundle(b.mExtras);
        service = b.mTaskService;
        requireCharging = b.mRequiresCharging;
        requireDeviceIdle = b.mRequiresDeviceIdle;
        networkCapabilities = b.mNetworkCapabilities;
        minLatencyMillis = b.mMinLatencyMillis;
        maxExecutionDelayMillis = b.mMaxExecutionDelayMillis;
        isPeriodic = b.mIsPeriodic;
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
        out.writeInt(taskId);
        out.writeBundle(extras);
        ComponentName.writeToParcel(service, out);
        out.writeInt(requireCharging ? 1 : 0);
        out.writeInt(requireDeviceIdle ? 1 : 0);
        out.writeInt(networkCapabilities);
        out.writeLong(minLatencyMillis);
        out.writeLong(maxExecutionDelayMillis);
        out.writeInt(isPeriodic ? 1 : 0);
        out.writeLong(intervalMillis);
        out.writeLong(initialBackoffMillis);
        out.writeInt(backoffPolicy);
        out.writeInt(hasEarlyConstraint ? 1 : 0);
        out.writeInt(hasLateConstraint ? 1 : 0);
    }

    public static final Creator<Task> CREATOR = new Creator<Task>() {
        @Override
        public Task createFromParcel(Parcel in) {
            return new Task(in);
        }

        @Override
        public Task[] newArray(int size) {
            return new Task[size];
        }
    };

    /**
     * Builder class for constructing {@link Task} objects.
     */
    public static final class Builder {
        private int mTaskId;
        private Bundle mExtras;
        private ComponentName mTaskService;
        // Requirements.
        private boolean mRequiresCharging;
        private boolean mRequiresDeviceIdle;
        private int mNetworkCapabilities;
        // One-off parameters.
        private long mMinLatencyMillis;
        private long mMaxExecutionDelayMillis;
        // Periodic parameters.
        private boolean mIsPeriodic;
        private boolean mHasEarlyConstraint;
        private boolean mHasLateConstraint;
        private long mIntervalMillis;
        // Back-off parameters.
        private long mInitialBackoffMillis = 5000L;
        private int mBackoffPolicy = BackoffPolicy.EXPONENTIAL;
        /** Easy way to track whether the client has tried to set a back-off policy. */
        private boolean mBackoffPolicySet = false;

        /**
         * @param taskId Application-provided id for this task. Subsequent calls to cancel, or
         *               tasks created with the same taskId, will update the pre-existing task with
         *               the same id.
         * @param taskService The endpoint that you implement that will receive the callback from the
         *            TaskManager.
         */
        public Builder(int taskId, ComponentName taskService) {
            mTaskService = taskService;
            mTaskId = taskId;
        }

        /**
         * Set optional extras. This is persisted, so we only allow primitive types.
         * @param extras Bundle containing extras you want the scheduler to hold on to for you.
         */
        public Builder setExtras(Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Set some description of the kind of network capabilities you would like to have. This
         * will be a parameter defined in {@link android.app.task.Task.NetworkType}.
         * Not calling this function means the network is not necessary.
         * Bear in mind that calling this function defines network as a strict requirement for your
         * task if the network requested is not available your task will never run. See
         * {@link #setOverrideDeadline(long)} to change this behaviour.
         */
        public Builder setRequiredNetworkCapabilities(int networkCapabilities) {
            mNetworkCapabilities = networkCapabilities;
            return this;
        }

        /*
         * Specify that to run this task, the device needs to be plugged in. This defaults to
         * false.
         * @param requireCharging Whether or not the device is plugged in.
         */
        public Builder setRequiresCharging(boolean requiresCharging) {
            mRequiresCharging = requiresCharging;
            return this;
        }

        /**
         * Specify that to run, the task needs the device to be in idle mode. This defaults to
         * false.
         * <p>Idle mode is a loose definition provided by the system, which means that the device
         * is not in use, and has not been in use for some time. As such, it is a good time to
         * perform resource heavy tasks. Bear in mind that battery usage will still be attributed
         * to your application, and surfaced to the user in battery stats.</p>
         * @param requiresDeviceIdle Whether or not the device need be within an idle maintenance
         *                           window.
         */
        public Builder setRequiresDeviceIdle(boolean requiresDeviceIdle) {
            mRequiresDeviceIdle = requiresDeviceIdle;
            return this;
        }

        /**
         * Specify that this task should recur with the provided interval, not more than once per
         * period. You have no control over when within this interval this task will be executed,
         * only the guarantee that it will be executed at most once within this interval.
         * A periodic task will be repeated until the phone is turned off, however it will only be
         * persisted beyond boot if the client app has declared the
         * {@link android.Manifest.permission#RECEIVE_BOOT_COMPLETED} permission. You can schedule
         * periodic tasks without this permission, they simply will cease to exist after the phone
         * restarts.
         * Setting this function on the builder with {@link #setMinimumLatency(long)} or
         * {@link #setOverrideDeadline(long)} will result in an error.
         * @param intervalMillis Millisecond interval for which this task will repeat.
         */
        public Builder setPeriodic(long intervalMillis) {
            mIsPeriodic = true;
            mIntervalMillis = intervalMillis;
            mHasEarlyConstraint = mHasLateConstraint = true;
            return this;
        }

        /**
         * Specify that this task should be delayed by the provided amount of time.
         * Because it doesn't make sense setting this property on a periodic task, doing so will
         * throw an {@link java.lang.IllegalArgumentException} when
         * {@link android.app.task.Task.Builder#build()} is called.
         * @param minLatencyMillis Milliseconds before which this task will not be considered for
         *                         execution.
         */
        public Builder setMinimumLatency(long minLatencyMillis) {
            mMinLatencyMillis = minLatencyMillis;
            mHasEarlyConstraint = true;
            return this;
        }

        /**
         * Set deadline which is the maximum scheduling latency. The task will be run by this
         * deadline even if other requirements are not met. Because it doesn't make sense setting
         * this property on a periodic task, doing so will throw an
         * {@link java.lang.IllegalArgumentException} when
         * {@link android.app.task.Task.Builder#build()} is called.
         */
        public Builder setOverrideDeadline(long maxExecutionDelayMillis) {
            mMaxExecutionDelayMillis = maxExecutionDelayMillis;
            mHasLateConstraint = true;
            return this;
        }

        /**
         * Set up the back-off/retry policy.
         * This defaults to some respectable values: {5 seconds, Exponential}. We cap back-off at
         * 1hr.
         * Note that trying to set a backoff criteria for a task with
         * {@link #setRequiresDeviceIdle(boolean)} will throw an exception when you call build().
         * This is because back-off typically does not make sense for these types of tasks. See
         * {@link android.app.task.TaskService#taskFinished(android.app.task.TaskParams, boolean)}
         * for more description of the return value for the case of a task executing while in idle
         * mode.
         * @param initialBackoffMillis Millisecond time interval to wait initially when task has
         *                             failed.
         * @param backoffPolicy is one of {@link BackoffPolicy}
         */
        public Builder setBackoffCriteria(long initialBackoffMillis, int backoffPolicy) {
            mBackoffPolicySet = true;
            mInitialBackoffMillis = initialBackoffMillis;
            mBackoffPolicy = backoffPolicy;
            return this;
        }

        /**
         * @return The task object to hand to the TaskManager. This object is immutable.
         */
        public Task build() {
            if (mExtras == null) {
                mExtras = Bundle.EMPTY;
            }
            if (mTaskId < 0) {
                throw new IllegalArgumentException("Task id must be greater than 0.");
            }
            // Check that a deadline was not set on a periodic task.
            if (mIsPeriodic && mHasLateConstraint) {
                throw new IllegalArgumentException("Can't call setOverrideDeadline() on a " +
                        "periodic task.");
            }
            if (mIsPeriodic && mHasEarlyConstraint) {
                throw new IllegalArgumentException("Can't call setMinimumLatency() on a " +
                        "periodic task");
            }
            if (mBackoffPolicySet && mRequiresDeviceIdle) {
                throw new IllegalArgumentException("An idle mode task will not respect any" +
                        " back-off policy, so calling setBackoffCriteria with" +
                        " setRequiresDeviceIdle is an error.");
            }
            return new Task(this);
        }
    }

}
