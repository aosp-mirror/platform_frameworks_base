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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.ClipData;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

/**
 * This is an API for scheduling various types of jobs against the framework that will be executed
 * in your application's own process.
 * <p>
 * See {@link android.app.job.JobInfo} for more description of the types of jobs that can be run
 * and how to construct them. You will construct these JobInfo objects and pass them to the
 * JobScheduler with {@link #schedule(JobInfo)}. When the criteria declared are met, the
 * system will execute this job on your application's {@link android.app.job.JobService}.
 * You identify the service component that implements the logic for your job when you
 * construct the JobInfo using
 * {@link android.app.job.JobInfo.Builder#Builder(int,android.content.ComponentName)}.
 * </p>
 * <p>
 * The framework will be intelligent about when it executes jobs, and attempt to batch
 * and defer them as much as possible. Typically, if you don't specify a deadline on a job, it
 * can be run at any moment depending on the current state of the JobScheduler's internal queue.
 * </p>
 * <p>
 * Starting in Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
 * JobScheduler may try to optimize job execution by shifting execution to times with more available
 * system resources in order to lower user impact. Factors in system health include sufficient
 * battery, idle, charging, and access to an un-metered network. Jobs will initially be treated as
 * if they have all these requirements, but as their deadlines approach, restrictions will become
 * less strict. Requested requirements will not be affected by this change.
 * </p>
 *
 * {@see android.app.job.JobInfo.Builder#setRequiresBatteryNotLow(boolean)}
 * {@see android.app.job.JobInfo.Builder#setRequiresDeviceIdle(boolean)}
 * {@see android.app.job.JobInfo.Builder#setRequiresCharging(boolean)}
 * {@see android.app.job.JobInfo.Builder#setRequiredNetworkType(int)}
 *
 * <p>
 * While a job is running, the system holds a wakelock on behalf of your app.  For this reason,
 * you do not need to take any action to guarantee that the device stays awake for the
 * duration of the job.
 * </p>
 * <p>You do not
 * instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.JOB_SCHEDULER_SERVICE)}.
 *
 * <p> Prior to Android version {@link android.os.Build.VERSION_CODES#S}, jobs could only have
 * a maximum of 100 jobs scheduled at a time. Starting with Android version
 * {@link android.os.Build.VERSION_CODES#S}, that limit has been increased to 150.
 * Expedited jobs also count towards the limit.
 *
 * <p> In Android version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, jobs had a maximum
 * execution time of one minute. Starting with Android version
 * {@link android.os.Build.VERSION_CODES#M} and ending with Android version
 * {@link android.os.Build.VERSION_CODES#R}, jobs had a maximum execution time of 10 minutes.
 * Starting from Android version {@link android.os.Build.VERSION_CODES#S}, jobs will still be
 * stopped after 10 minutes if the system is busy or needs the resources, but if not, jobs
 * may continue running longer than 10 minutes.
 *
 * <p class="caution"><strong>Note:</strong> Beginning with API 30
 * ({@link android.os.Build.VERSION_CODES#R}), JobScheduler will throttle runaway applications.
 * Calling {@link #schedule(JobInfo)} and other such methods with very high frequency can have a
 * high cost and so, to make sure the system doesn't get overwhelmed, JobScheduler will begin
 * to throttle apps, regardless of target SDK version.
 */
@SystemService(Context.JOB_SCHEDULER_SERVICE)
public abstract class JobScheduler {
    /**
     * Whether to throw an exception when an app doesn't properly implement all the necessary
     * data transfer APIs.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION = 255371817L;

    /** @hide */
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_FAILURE,
            RESULT_SUCCESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {}

    /**
     * Returned from {@link #schedule(JobInfo)} if a job wasn't scheduled successfully. Scheduling
     * can fail for a variety of reasons, including, but not limited to:
     * <ul>
     * <li>an invalid parameter was supplied (eg. the run-time for your job is too short, or the
     * system can't resolve the requisite {@link JobService} in your package)</li>
     * <li>the app has too many jobs scheduled</li>
     * <li>the app has tried to schedule too many jobs in a short amount of time</li>
     * </ul>
     * Attempting to schedule the job again immediately after receiving this result will not
     * guarantee a successful schedule.
     */
    public static final int RESULT_FAILURE = 0;
    /**
     * Returned from {@link #schedule(JobInfo)} if this job has been successfully scheduled.
     */
    public static final int RESULT_SUCCESS = 1;

    /** The job doesn't exist. */
    public static final int PENDING_JOB_REASON_INVALID_JOB_ID = -2;
    /** The job is currently running and is therefore not pending. */
    public static final int PENDING_JOB_REASON_EXECUTING = -1;
    /**
     * There is no known reason why the job is pending.
     * If additional reasons are added on newer Android versions, the system may return this reason
     * to apps whose target SDK is not high enough to expect that reason.
     */
    public static final int PENDING_JOB_REASON_UNDEFINED = 0;
    /**
     * The app is in a state that prevents the job from running
     * (eg. the {@link JobService} component is disabled).
     */
    public static final int PENDING_JOB_REASON_APP = 1;
    /**
     * The current standby bucket prevents the job from running.
     *
     * @see UsageStatsManager#STANDBY_BUCKET_RESTRICTED
     */
    public static final int PENDING_JOB_REASON_APP_STANDBY = 2;
    /**
     * The app is restricted from running in the background.
     *
     * @see ActivityManager#isBackgroundRestricted()
     * @see PackageManager#isInstantApp()
     */
    public static final int PENDING_JOB_REASON_BACKGROUND_RESTRICTION = 3;
    /**
     * The requested battery-not-low constraint is not satisfied.
     *
     * @see JobInfo.Builder#setRequiresBatteryNotLow(boolean)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW = 4;
    /**
     * The requested charging constraint is not satisfied.
     *
     * @see JobInfo.Builder#setRequiresCharging(boolean)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_CHARGING = 5;
    /**
     * The requested connectivity constraint is not satisfied.
     *
     * @see JobInfo.Builder#setRequiredNetwork(NetworkRequest)
     * @see JobInfo.Builder#setRequiredNetworkType(int)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY = 6;
    /**
     * The requested content trigger constraint is not satisfied.
     *
     * @see JobInfo.Builder#addTriggerContentUri(JobInfo.TriggerContentUri)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER = 7;
    /**
     * The requested idle constraint is not satisfied.
     *
     * @see JobInfo.Builder#setRequiresDeviceIdle(boolean)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE = 8;
    /**
     * The minimum latency has not transpired.
     *
     * @see JobInfo.Builder#setMinimumLatency(long)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY = 9;
    /**
     * The system's estimate of when the app will be launched is far away enough to warrant delaying
     * this job.
     *
     * @see JobInfo#isPrefetch()
     * @see JobInfo.Builder#setPrefetch(boolean)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_PREFETCH = 10;
    /**
     * The requested storage-not-low constraint is not satisfied.
     *
     * @see JobInfo.Builder#setRequiresStorageNotLow(boolean)
     */
    public static final int PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW = 11;
    /**
     * The job is being deferred due to the device state (eg. Doze, battery saver, memory usage,
     * thermal status, etc.).
     */
    public static final int PENDING_JOB_REASON_DEVICE_STATE = 12;
    /**
     * JobScheduler thinks it can defer this job to a more optimal running time.
     */
    public static final int PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION = 13;
    /**
     * The app has consumed all of its current quota.
     *
     * @see UsageStatsManager#getAppStandbyBucket()
     * @see JobParameters#STOP_REASON_QUOTA
     */
    public static final int PENDING_JOB_REASON_QUOTA = 14;
    /**
     * JobScheduler is respecting one of the user's actions (eg. force stop or adb shell commands)
     * to defer this job.
     */
    public static final int PENDING_JOB_REASON_USER = 15;

    /** @hide */
    @IntDef(prefix = {"PENDING_JOB_REASON_"}, value = {
            PENDING_JOB_REASON_UNDEFINED,
            PENDING_JOB_REASON_APP,
            PENDING_JOB_REASON_APP_STANDBY,
            PENDING_JOB_REASON_BACKGROUND_RESTRICTION,
            PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            PENDING_JOB_REASON_CONSTRAINT_CHARGING,
            PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY,
            PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER,
            PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE,
            PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY,
            PENDING_JOB_REASON_CONSTRAINT_PREFETCH,
            PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW,
            PENDING_JOB_REASON_DEVICE_STATE,
            PENDING_JOB_REASON_EXECUTING,
            PENDING_JOB_REASON_INVALID_JOB_ID,
            PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION,
            PENDING_JOB_REASON_QUOTA,
            PENDING_JOB_REASON_USER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PendingJobReason {
    }

    /**
     * Get a JobScheduler instance that is dedicated to a specific namespace. Any API calls using
     * this instance will interact with jobs in that namespace, unless the API documentation says
     * otherwise. Attempting to update a job scheduled in another namespace will not be possible
     * but will instead create or update the job inside the current namespace. A JobScheduler
     * instance dedicated to a namespace must be used to schedule or update jobs in that namespace.
     *
     * <p class="note">Since leading and trailing whitespace can lead to hard-to-debug issues,
     * they will be {@link String#trim() trimmed}. An empty String (after trimming) is not allowed.
     * @see #getNamespace()
     */
    @NonNull
    public JobScheduler forNamespace(@NonNull String namespace) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Get the namespace this JobScheduler instance is operating in. A {@code null} value means
     * that the app has not specified a namespace for this instance, and it is therefore using the
     * default namespace.
     */
    @Nullable
    public String getNamespace() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /** @hide */
    @Nullable
    public static String sanitizeNamespace(@Nullable String namespace) {
        if (namespace == null) {
            return null;
        }
        return namespace.trim().intern();
    }

    /**
     * Schedule a job to be executed.  Will replace any currently scheduled job with the same
     * ID with the new information in the {@link JobInfo}.  If a job with the given ID is currently
     * running, it will be stopped.
     *
     * <p class="caution"><strong>Note:</strong> Scheduling a job can have a high cost, even if it's
     * rescheduling the same job and the job didn't execute, especially on platform versions before
     * version {@link android.os.Build.VERSION_CODES#Q}. As such, the system may throttle calls to
     * this API if calls are made too frequently in a short amount of time.
     *
     * <p>Note: The JobService component needs to be enabled in order to successfully schedule a
     * job.
     *
     * @param job The job you wish scheduled. See
     * {@link android.app.job.JobInfo.Builder JobInfo.Builder} for more detail on the sorts of jobs
     * you can schedule.
     * @return the result of the schedule request.
     * @throws IllegalArgumentException if the specified {@link JobService} doesn't exist or is
     * disabled.
     */
    public abstract @Result int schedule(@NonNull JobInfo job);

    /**
     * Similar to {@link #schedule}, but allows you to enqueue work for a new <em>or existing</em>
     * job.  If a job with the same ID is already scheduled, it will be replaced with the
     * new {@link JobInfo}, but any previously enqueued work will remain and be dispatched the
     * next time it runs.  If a job with the same ID is already running, the new work will be
     * enqueued for it without stopping the job.
     *
     * <p>The work you enqueue is later retrieved through
     * {@link JobParameters#dequeueWork() JobParameters.dequeueWork}.  Be sure to see there
     * about how to process work; the act of enqueueing work changes how you should handle the
     * overall lifecycle of an executing job.</p>
     *
     * <p>It is strongly encouraged that you use the same {@link JobInfo} for all work you
     * enqueue.  This will allow the system to optimally schedule work along with any pending
     * and/or currently running work.  If the JobInfo changes from the last time the job was
     * enqueued, the system will need to update the associated JobInfo, which can cause a disruption
     * in execution.  In particular, this can result in any currently running job that is processing
     * previous work to be stopped and restarted with the new JobInfo.</p>
     *
     * <p>It is recommended that you avoid using
     * {@link JobInfo.Builder#setExtras(PersistableBundle)} or
     * {@link JobInfo.Builder#setTransientExtras(Bundle)} with a JobInfo you are using to
     * enqueue work.  The system will try to compare these extras with the previous JobInfo,
     * but there are situations where it may get this wrong and count the JobInfo as changing.
     * (That said, you should be relatively safe with a simple set of consistent data in these
     * fields.)  You should never use {@link JobInfo.Builder#setClipData(ClipData, int)} with
     * work you are enqueuing, since currently this will always be treated as a different JobInfo,
     * even if the ClipData contents are exactly the same.</p>
     *
     * <p class="caution"><strong>Note:</strong> Scheduling a job can have a high cost, even if it's
     * rescheduling the same job and the job didn't execute, especially on platform versions before
     * version {@link android.os.Build.VERSION_CODES#Q}. As such, the system may throttle calls to
     * this API if calls are made too frequently in a short amount of time.
     *
     * <p class="caution"><strong>Note:</strong> Prior to Android version
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, JobWorkItems could not be persisted.
     * Apps were not allowed to enqueue JobWorkItems with persisted jobs and the system would throw
     * an {@link IllegalArgumentException} if they attempted to do so. Starting with
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * JobWorkItems can be persisted alongside the hosting job.
     * However, Intents cannot be persisted. Set a {@link PersistableBundle} using
     * {@link JobWorkItem.Builder#setExtras(PersistableBundle)} for any information that needs
     * to be persisted.
     *
     * <p>Note: The JobService component needs to be enabled in order to successfully schedule a
     * job.
     *
     * @param job The job you wish to enqueue work for. See
     * {@link android.app.job.JobInfo.Builder JobInfo.Builder} for more detail on the sorts of jobs
     * you can schedule.
     * @param work New work to enqueue.  This will be available later when the job starts running.
     * @return the result of the enqueue request.
     * @throws IllegalArgumentException if the specified {@link JobService} doesn't exist or is
     * disabled.
     */
    public abstract @Result int enqueue(@NonNull JobInfo job, @NonNull JobWorkItem work);

    /**
     *
     * @param job The job to be scheduled.
     * @param packageName The package on behalf of which the job is to be scheduled. This will be
     *                    used to track battery usage and appIdleState.
     * @param userId    User on behalf of whom this job is to be scheduled.
     * @param tag Debugging tag for dumps associated with this job (instead of the service class)
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public abstract @Result int scheduleAsPackage(@NonNull JobInfo job, @NonNull String packageName,
            int userId, String tag);

    /**
     * Cancel the specified job.  If the job is currently executing, it is stopped
     * immediately and the return value from its {@link JobService#onStopJob(JobParameters)}
     * method is ignored.
     *
     * @param jobId unique identifier for the job to be canceled, as supplied to
     *     {@link JobInfo.Builder#Builder(int, android.content.ComponentName)
     *     JobInfo.Builder(int, android.content.ComponentName)}.
     */
    public abstract void cancel(int jobId);

    /**
     * Cancel all jobs that have been scheduled in the current namespace by the
     * calling application.
     *
     * <p>
     * Starting with Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, this
     * will only cancel within the current namespace. If a namespace hasn't been explicitly set
     * with {@link #forNamespace(String)}, then this will cancel jobs in the default namespace.
     * To cancel all jobs scheduled by the application,
     * use {@link #cancelInAllNamespaces()} instead.
     */
    public abstract void cancelAll();

    /**
     * Cancel <em>all</em> jobs that have been scheduled by the calling application, regardless of
     * namespace.
     */
    public void cancelInAllNamespaces() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Retrieve all jobs that have been scheduled by the calling application.
     *
     * @return a list of all of the app's scheduled jobs.  This includes jobs that are
     *     currently started as well as those that are still waiting to run.
     */
    public abstract @NonNull List<JobInfo> getAllPendingJobs();

    /**
     * Retrieve all jobs that have been scheduled by the calling application within the current
     * namespace.
     *
     * @return a list of all of the app's scheduled jobs scheduled with the current namespace.
     * If a namespace hasn't been explicitly set with {@link #forNamespace(String)},
     * then this will return jobs in the default namespace.
     * This includes jobs that are currently started as well as those that are still waiting to run.
     */
    @NonNull
    public Map<String, List<JobInfo>> getPendingJobsInAllNamespaces() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Look up the description of a scheduled job.
     *
     * @return The {@link JobInfo} description of the given scheduled job, or {@code null}
     *     if the supplied job ID does not correspond to any job.
     */
    public abstract @Nullable JobInfo getPendingJob(int jobId);

    /**
     * Returns a reason why the job is pending and not currently executing. If there are multiple
     * reasons why a job may be pending, this will only return one of them.
     */
    @PendingJobReason
    public int getPendingJobReason(int jobId) {
        return PENDING_JOB_REASON_UNDEFINED;
    }

    /**
     * Returns {@code true} if the calling app currently holds the
     * {@link android.Manifest.permission#RUN_USER_INITIATED_JOBS} permission, allowing it to run
     * user-initiated jobs.
     */
    public boolean canRunUserInitiatedJobs() {
        return false;
    }

    /**
     * Returns {@code true} if the app currently holds the
     * {@link android.Manifest.permission#RUN_USER_INITIATED_JOBS} permission, allowing it to run
     * user-initiated jobs.
     * @hide
     * TODO(255371817): consider exposing this to apps who could call
     * {@link #scheduleAsPackage(JobInfo, String, int, String)}
     */
    public boolean hasRunUserInitiatedJobsPermission(@NonNull String packageName,
            @UserIdInt int userId) {
        return false;
    }

    /**
     * <b>For internal system callers only!</b>
     * Returns a list of all currently-executing jobs.
     * @hide
     */
    @Nullable
    public List<JobInfo> getStartedJobs() {
        return null;
    }

    /**
     * <b>For internal system callers only!</b>
     * Returns a snapshot of the state of all jobs known to the system.
     *
     * <p class="note">This is a slow operation, so it should be called sparingly.
     * @hide
     */
    @Nullable
    public List<JobSnapshot> getAllJobSnapshots() {
        return null;
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public void registerUserVisibleJobObserver(@NonNull IUserVisibleJobObserver observer) {
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public void unregisterUserVisibleJobObserver(
            @NonNull IUserVisibleJobObserver observer) {

    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public void notePendingUserRequestedAppStop(@NonNull String packageName, int userId,
            @Nullable String debugReason) {
    }
}
