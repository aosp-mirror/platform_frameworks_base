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
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains the parameters used to configure/identify your job. You do not create this object
 * yourself, instead it is handed in to your application by the System.
 */
public class JobParameters implements Parcelable {

    /** @hide */
    public static final int INTERNAL_STOP_REASON_UNKNOWN = -1;

    /** @hide */
    public static final int INTERNAL_STOP_REASON_CANCELED =
            JobProtoEnums.INTERNAL_STOP_REASON_CANCELLED; // 0.
    /** @hide */
    public static final int INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED =
            JobProtoEnums.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED; // 1.
    /** @hide */
    public static final int INTERNAL_STOP_REASON_PREEMPT =
            JobProtoEnums.INTERNAL_STOP_REASON_PREEMPT; // 2.
    /**
     * The job ran for at least its minimum execution limit.
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_TIMEOUT =
            JobProtoEnums.INTERNAL_STOP_REASON_TIMEOUT; // 3.
    /** @hide */
    public static final int INTERNAL_STOP_REASON_DEVICE_IDLE =
            JobProtoEnums.INTERNAL_STOP_REASON_DEVICE_IDLE; // 4.
    /** @hide */
    public static final int INTERNAL_STOP_REASON_DEVICE_THERMAL =
            JobProtoEnums.INTERNAL_STOP_REASON_DEVICE_THERMAL; // 5.
    /**
     * The job is in the {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED}
     * bucket.
     *
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_RESTRICTED_BUCKET =
            JobProtoEnums.INTERNAL_STOP_REASON_RESTRICTED_BUCKET; // 6.
    /**
     * The app was uninstalled.
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_UNINSTALL =
            JobProtoEnums.INTERNAL_STOP_REASON_UNINSTALL; // 7.
    /**
     * The app's data was cleared.
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_DATA_CLEARED =
            JobProtoEnums.INTERNAL_STOP_REASON_DATA_CLEARED; // 8.
    /**
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_RTC_UPDATED =
            JobProtoEnums.INTERNAL_STOP_REASON_RTC_UPDATED; // 9.
    /**
     * The app called jobFinished() on its own.
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_SUCCESSFUL_FINISH =
            JobProtoEnums.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH; // 10.
    /**
     * The user stopped the job via some UI (eg. Task Manager).
     * @hide
     */
    public static final int INTERNAL_STOP_REASON_USER_UI_STOP =
            JobProtoEnums.INTERNAL_STOP_REASON_USER_UI_STOP; // 11.

    /**
     * All the stop reason codes. This should be regarded as an immutable array at runtime.
     *
     * Note the order of these values will affect "dumpsys batterystats", and we do not want to
     * change the order of existing fields, so adding new fields is okay but do not remove or
     * change existing fields. When deprecating a field, just replace that with "-1" in this array.
     *
     * @hide
     */
    public static final int[] JOB_STOP_REASON_CODES = {
            INTERNAL_STOP_REASON_UNKNOWN,
            INTERNAL_STOP_REASON_CANCELED,
            INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
            INTERNAL_STOP_REASON_PREEMPT,
            INTERNAL_STOP_REASON_TIMEOUT,
            INTERNAL_STOP_REASON_DEVICE_IDLE,
            INTERNAL_STOP_REASON_DEVICE_THERMAL,
            INTERNAL_STOP_REASON_RESTRICTED_BUCKET,
            INTERNAL_STOP_REASON_UNINSTALL,
            INTERNAL_STOP_REASON_DATA_CLEARED,
            INTERNAL_STOP_REASON_RTC_UPDATED,
            INTERNAL_STOP_REASON_SUCCESSFUL_FINISH,
            INTERNAL_STOP_REASON_USER_UI_STOP,
    };

    /**
     * @hide
     */
    // TODO(142420609): make it @SystemApi for mainline
    @NonNull
    public static String getInternalReasonCodeDescription(int reasonCode) {
        switch (reasonCode) {
            case INTERNAL_STOP_REASON_CANCELED: return "canceled";
            case INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED: return "constraints";
            case INTERNAL_STOP_REASON_PREEMPT: return "preempt";
            case INTERNAL_STOP_REASON_TIMEOUT: return "timeout";
            case INTERNAL_STOP_REASON_DEVICE_IDLE: return "device_idle";
            case INTERNAL_STOP_REASON_DEVICE_THERMAL: return "thermal";
            case INTERNAL_STOP_REASON_RESTRICTED_BUCKET: return "restricted_bucket";
            case INTERNAL_STOP_REASON_UNINSTALL: return "uninstall";
            case INTERNAL_STOP_REASON_DATA_CLEARED: return "data_cleared";
            case INTERNAL_STOP_REASON_RTC_UPDATED: return "rtc_updated";
            case INTERNAL_STOP_REASON_SUCCESSFUL_FINISH: return "successful_finish";
            case INTERNAL_STOP_REASON_USER_UI_STOP: return "user_ui_stop";
            default: return "unknown:" + reasonCode;
        }
    }

    /** @hide */
    @NonNull
    public static int[] getJobStopReasonCodes() {
        return JOB_STOP_REASON_CODES;
    }

    /**
     * There is no reason the job is stopped. This is the value returned from the JobParameters
     * object passed to {@link JobService#onStartJob(JobParameters)}.
     */
    public static final int STOP_REASON_UNDEFINED = 0;
    /**
     * The job was cancelled directly by the app, either by calling
     * {@link JobScheduler#cancel(int)}, {@link JobScheduler#cancelAll()}, or by scheduling a
     * new job with the same job ID.
     */
    public static final int STOP_REASON_CANCELLED_BY_APP = 1;
    /** The job was stopped to run a higher priority job of the app. */
    public static final int STOP_REASON_PREEMPT = 2;
    /**
     * The job used up its maximum execution time and timed out. Each individual job has a maximum
     * execution time limit, regardless of how much total quota the app has. See the note on
     * {@link JobScheduler} and {@link JobInfo} for the execution time limits.
     */
    public static final int STOP_REASON_TIMEOUT = 3;
    /**
     * The device state (eg. Doze, battery saver, memory usage, etc) requires JobScheduler stop this
     * job.
     */
    public static final int STOP_REASON_DEVICE_STATE = 4;
    /**
     * The requested battery-not-low constraint is no longer satisfied.
     *
     * @see JobInfo.Builder#setRequiresBatteryNotLow(boolean)
     */
    public static final int STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW = 5;
    /**
     * The requested charging constraint is no longer satisfied.
     *
     * @see JobInfo.Builder#setRequiresCharging(boolean)
     */
    public static final int STOP_REASON_CONSTRAINT_CHARGING = 6;
    /**
     * The requested connectivity constraint is no longer satisfied.
     *
     * @see JobInfo.Builder#setRequiredNetwork(NetworkRequest)
     * @see JobInfo.Builder#setRequiredNetworkType(int)
     */
    public static final int STOP_REASON_CONSTRAINT_CONNECTIVITY = 7;
    /**
     * The requested idle constraint is no longer satisfied.
     *
     * @see JobInfo.Builder#setRequiresDeviceIdle(boolean)
     */
    public static final int STOP_REASON_CONSTRAINT_DEVICE_IDLE = 8;
    /**
     * The requested storage-not-low constraint is no longer satisfied.
     *
     * @see JobInfo.Builder#setRequiresStorageNotLow(boolean)
     */
    public static final int STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW = 9;
    /**
     * The app has consumed all of its current quota. Each app is assigned a quota of how much
     * it can run jobs within a certain time frame. The quota is informed, in part, by app standby
     * buckets. Once an app has used up all of its quota, it won't be able to start jobs until
     * quota is replenished, is changed, or is temporarily not applied.
     *
     * @see UsageStatsManager#getAppStandbyBucket()
     */
    public static final int STOP_REASON_QUOTA = 10;
    /**
     * The app is restricted from running in the background.
     *
     * @see ActivityManager#isBackgroundRestricted()
     * @see PackageManager#isInstantApp()
     */
    public static final int STOP_REASON_BACKGROUND_RESTRICTION = 11;
    /**
     * The current standby bucket requires that the job stop now.
     *
     * @see UsageStatsManager#STANDBY_BUCKET_RESTRICTED
     */
    public static final int STOP_REASON_APP_STANDBY = 12;
    /**
     * The user stopped the job. This can happen either through force-stop, adb shell commands,
     * uninstalling, or some other UI.
     */
    public static final int STOP_REASON_USER = 13;
    /** The system is doing some processing that requires stopping this job. */
    public static final int STOP_REASON_SYSTEM_PROCESSING = 14;
    /**
     * The system's estimate of when the app will be launched changed significantly enough to
     * decide this job shouldn't be running right now. This will mostly apply to prefetch jobs.
     *
     * @see JobInfo#isPrefetch()
     * @see JobInfo.Builder#setPrefetch(boolean)
     */
    public static final int STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED = 15;

    /** @hide */
    @IntDef(prefix = {"STOP_REASON_"}, value = {
            STOP_REASON_UNDEFINED,
            STOP_REASON_CANCELLED_BY_APP,
            STOP_REASON_PREEMPT,
            STOP_REASON_TIMEOUT,
            STOP_REASON_DEVICE_STATE,
            STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            STOP_REASON_CONSTRAINT_CHARGING,
            STOP_REASON_CONSTRAINT_CONNECTIVITY,
            STOP_REASON_CONSTRAINT_DEVICE_IDLE,
            STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW,
            STOP_REASON_QUOTA,
            STOP_REASON_BACKGROUND_RESTRICTION,
            STOP_REASON_APP_STANDBY,
            STOP_REASON_USER,
            STOP_REASON_SYSTEM_PROCESSING,
            STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StopReason {
    }

    @UnsupportedAppUsage
    private final int jobId;
    @Nullable
    private final String mJobNamespace;
    private final PersistableBundle extras;
    private final Bundle transientExtras;
    private final ClipData clipData;
    private final int clipGrantFlags;
    @UnsupportedAppUsage
    private final IBinder callback;
    private final boolean overrideDeadlineExpired;
    private final boolean mIsExpedited;
    private final boolean mIsUserInitiated;
    private final Uri[] mTriggeredContentUris;
    private final String[] mTriggeredContentAuthorities;
    private final Network network;

    private int mStopReason = STOP_REASON_UNDEFINED;
    private int mInternalStopReason = INTERNAL_STOP_REASON_UNKNOWN;
    private String debugStopReason; // Human readable stop reason for debugging.

    /** @hide */
    public JobParameters(IBinder callback, String namespace, int jobId, PersistableBundle extras,
            Bundle transientExtras, ClipData clipData, int clipGrantFlags,
            boolean overrideDeadlineExpired, boolean isExpedited,
            boolean isUserInitiated, Uri[] triggeredContentUris,
            String[] triggeredContentAuthorities, Network network) {
        this.jobId = jobId;
        this.extras = extras;
        this.transientExtras = transientExtras;
        this.clipData = clipData;
        this.clipGrantFlags = clipGrantFlags;
        this.callback = callback;
        this.overrideDeadlineExpired = overrideDeadlineExpired;
        this.mIsExpedited = isExpedited;
        this.mIsUserInitiated = isUserInitiated;
        this.mTriggeredContentUris = triggeredContentUris;
        this.mTriggeredContentAuthorities = triggeredContentAuthorities;
        this.network = network;
        this.mJobNamespace = namespace;
    }

    /**
     * @return The unique id of this job, specified at creation time.
     */
    public int getJobId() {
        return jobId;
    }

    /**
     * Get the namespace this job was placed in.
     *
     * @see JobScheduler#forNamespace(String)
     * @return The namespace this job was scheduled in. Will be {@code null} if there was no
     * explicit namespace set and this job is therefore in the default namespace.
     */
    @Nullable
    public String getJobNamespace() {
        return mJobNamespace;
    }

    /**
     * @return The reason {@link JobService#onStopJob(JobParameters)} was called on this job. Will
     * be {@link #STOP_REASON_UNDEFINED} if {@link JobService#onStopJob(JobParameters)} has not
     * yet been called.
     */
    @StopReason
    public int getStopReason() {
        return mStopReason;
    }

    /** @hide */
    public int getInternalStopReasonCode() {
        return mInternalStopReason;
    }

    /**
     * Reason onStopJob() was called on this job.
     *
     * @hide
     */
    public String getDebugStopReason() {
        return debugStopReason;
    }

    /**
     * @return The extras you passed in when constructing this job with
     * {@link android.app.job.JobInfo.Builder#setExtras(android.os.PersistableBundle)}. This will
     * never be null. If you did not set any extras this will be an empty bundle.
     */
    public @NonNull PersistableBundle getExtras() {
        return extras;
    }

    /**
     * @return The transient extras you passed in when constructing this job with
     * {@link android.app.job.JobInfo.Builder#setTransientExtras(android.os.Bundle)}. This will
     * never be null. If you did not set any extras this will be an empty bundle.
     */
    public @NonNull Bundle getTransientExtras() {
        return transientExtras;
    }

    /**
     * @return The clip you passed in when constructing this job with
     * {@link android.app.job.JobInfo.Builder#setClipData(ClipData, int)}. Will be null
     * if it was not set.
     */
    public @Nullable ClipData getClipData() {
        return clipData;
    }

    /**
     * @return The clip grant flags you passed in when constructing this job with
     * {@link android.app.job.JobInfo.Builder#setClipData(ClipData, int)}. Will be 0
     * if it was not set.
     */
    public int getClipGrantFlags() {
        return clipGrantFlags;
    }

    /**
     * @return Whether this job is running as an expedited job or not. A job is guaranteed to have
     * all expedited job guarantees for the duration of the job execution if this returns
     * {@code true}. This will return {@code false} if the job that wasn't requested to run as a
     * expedited job, or if it was requested to run as an expedited job but the app didn't have
     * any remaining expedited job quota at the time of execution.
     *
     * @see JobInfo.Builder#setExpedited(boolean)
     */
    public boolean isExpeditedJob() {
        return mIsExpedited;
    }

    /**
     * @return Whether this job is running as a user-initiated job or not. A job is guaranteed to
     * have all user-initiated job guarantees for the duration of the job execution if this returns
     * {@code true}. This will return {@code false} if the job wasn't requested to run as a
     * user-initiated job, or if it was requested to run as a user-initiated job but the app didn't
     * meet any of the requirements at the time of execution, such as having the
     * {@link android.Manifest.permission#RUN_LONG_JOBS} permission.
     *
     * @see JobInfo.Builder#setUserInitiated(boolean)
     */
    public boolean isUserInitiatedJob() {
        return mIsUserInitiated;
    }

    /**
     * For jobs with {@link android.app.job.JobInfo.Builder#setOverrideDeadline(long)} set, this
     * provides an easy way to tell whether the job is being executed due to the deadline
     * expiring. Note: If the job is running because its deadline expired, it implies that its
     * constraints will not be met.
     */
    public boolean isOverrideDeadlineExpired() {
        return overrideDeadlineExpired;
    }

    /**
     * For jobs with {@link android.app.job.JobInfo.Builder#addTriggerContentUri} set, this
     * reports which URIs have triggered the job.  This will be null if either no URIs have
     * triggered it (it went off due to a deadline or other reason), or the number of changed
     * URIs is too large to report.  Whether or not the number of URIs is too large, you can
     * always use {@link #getTriggeredContentAuthorities()} to determine whether the job was
     * triggered due to any content changes and the authorities they are associated with.
     */
    public @Nullable Uri[] getTriggeredContentUris() {
        return mTriggeredContentUris;
    }

    /**
     * For jobs with {@link android.app.job.JobInfo.Builder#addTriggerContentUri} set, this
     * reports which content authorities have triggered the job.  It will only be null if no
     * authorities have triggered it -- that is, the job executed for some other reason, such
     * as a deadline expiring.  If this is non-null, you can use {@link #getTriggeredContentUris()}
     * to retrieve the details of which URIs changed (as long as that has not exceeded the maximum
     * number it can reported).
     */
    public @Nullable String[] getTriggeredContentAuthorities() {
        return mTriggeredContentAuthorities;
    }

    /**
     * Return the network that should be used to perform any network requests
     * for this job.
     * <p>
     * Devices may have multiple active network connections simultaneously, or
     * they may not have a default network route at all. To correctly handle all
     * situations like this, your job should always use the network returned by
     * this method instead of implicitly using the default network route.
     * <p>
     * Note that the system may relax the constraints you originally requested,
     * such as allowing a {@link JobInfo#NETWORK_TYPE_UNMETERED} job to run over
     * a metered network when there is a surplus of metered data available.
     *
     * @return the network that should be used to perform any network requests
     *         for this job, or {@code null} if this job didn't set any required
     *         network type or if the job executed when there was no available network to use.
     * @see JobInfo.Builder#setRequiredNetworkType(int)
     * @see JobInfo.Builder#setRequiredNetwork(NetworkRequest)
     */
    public @Nullable Network getNetwork() {
        return network;
    }

    /**
     * Dequeue the next pending {@link JobWorkItem} from these JobParameters associated with their
     * currently running job.  Calling this method when there is no more work available and all
     * previously dequeued work has been completed will result in the system taking care of
     * stopping the job for you --
     * you should not call {@link JobService#jobFinished(JobParameters, boolean)} yourself
     * (otherwise you risk losing an upcoming JobWorkItem that is being enqueued at the same time).
     *
     * <p>Once you are done with the {@link JobWorkItem} returned by this method, you must call
     * {@link #completeWork(JobWorkItem)} with it to inform the system that you are done
     * executing the work.  The job will not be finished until all dequeued work has been
     * completed.  You do not, however, have to complete each returned work item before deqeueing
     * the next one -- you can use {@link #dequeueWork()} multiple times before completing
     * previous work if you want to process work in parallel, and you can complete the work
     * in whatever order you want.</p>
     *
     * <p>If the job runs to the end of its available time period before all work has been
     * completed, it will stop as normal.  You should return true from
     * {@link JobService#onStopJob(JobParameters)} in order to have the job rescheduled, and by
     * doing so any pending as well as remaining uncompleted work will be re-queued
     * for the next time the job runs.</p>
     *
     * <p>This example shows how to construct a JobService that will serially dequeue and
     * process work that is available for it:</p>
     *
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/JobWorkService.java
     *      service}
     *
     * @return Returns a new {@link JobWorkItem} if there is one pending, otherwise null.
     * If null is returned, the system will also stop the job if all work has also been completed.
     * (This means that for correct operation, you must always call dequeueWork() after you have
     * completed other work, to check either for more work or allow the system to stop the job.)
     */
    public @Nullable JobWorkItem dequeueWork() {
        try {
            return getCallback().dequeueWork(getJobId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report the completion of executing a {@link JobWorkItem} previously returned by
     * {@link #dequeueWork()}.  This tells the system you are done with the
     * work associated with that item, so it will not be returned again.  Note that if this
     * is the last work in the queue, completing it here will <em>not</em> finish the overall
     * job -- for that to happen, you still need to call {@link #dequeueWork()}
     * again.
     *
     * <p>If you are enqueueing work into a job, you must call this method for each piece
     * of work you process.  Do <em>not</em> call
     * {@link JobService#jobFinished(JobParameters, boolean)}
     * or else you can lose work in your queue.</p>
     *
     * @param work The work you have completed processing, as previously returned by
     * {@link #dequeueWork()}
     */
    public void completeWork(@NonNull JobWorkItem work) {
        try {
            if (!getCallback().completeWork(getJobId(), work.getWorkId())) {
                throw new IllegalArgumentException("Given work is not active: " + work);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public IJobCallback getCallback() {
        return IJobCallback.Stub.asInterface(callback);
    }

    private JobParameters(Parcel in) {
        jobId = in.readInt();
        mJobNamespace = in.readString();
        extras = in.readPersistableBundle();
        transientExtras = in.readBundle();
        if (in.readInt() != 0) {
            clipData = ClipData.CREATOR.createFromParcel(in);
            clipGrantFlags = in.readInt();
        } else {
            clipData = null;
            clipGrantFlags = 0;
        }
        callback = in.readStrongBinder();
        overrideDeadlineExpired = in.readInt() == 1;
        mIsExpedited = in.readBoolean();
        mIsUserInitiated = in.readBoolean();
        mTriggeredContentUris = in.createTypedArray(Uri.CREATOR);
        mTriggeredContentAuthorities = in.createStringArray();
        if (in.readInt() != 0) {
            network = Network.CREATOR.createFromParcel(in);
        } else {
            network = null;
        }
        mStopReason = in.readInt();
        mInternalStopReason = in.readInt();
        debugStopReason = in.readString();
    }

    /** @hide */
    public void setStopReason(@StopReason int reason, int internalStopReason,
            String debugStopReason) {
        mStopReason = reason;
        mInternalStopReason = internalStopReason;
        this.debugStopReason = debugStopReason;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(jobId);
        dest.writeString(mJobNamespace);
        dest.writePersistableBundle(extras);
        dest.writeBundle(transientExtras);
        if (clipData != null) {
            dest.writeInt(1);
            clipData.writeToParcel(dest, flags);
            dest.writeInt(clipGrantFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeStrongBinder(callback);
        dest.writeInt(overrideDeadlineExpired ? 1 : 0);
        dest.writeBoolean(mIsExpedited);
        dest.writeBoolean(mIsUserInitiated);
        dest.writeTypedArray(mTriggeredContentUris, flags);
        dest.writeStringArray(mTriggeredContentAuthorities);
        if (network != null) {
            dest.writeInt(1);
            network.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mStopReason);
        dest.writeInt(mInternalStopReason);
        dest.writeString(debugStopReason);
    }

    public static final @android.annotation.NonNull Creator<JobParameters> CREATOR = new Creator<JobParameters>() {
        @Override
        public JobParameters createFromParcel(Parcel in) {
            return new JobParameters(in);
        }

        @Override
        public JobParameters[] newArray(int size) {
            return new JobParameters[size];
        }
    };
}
