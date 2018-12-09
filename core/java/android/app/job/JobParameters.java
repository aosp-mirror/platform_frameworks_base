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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.job.IJobCallback;
import android.content.ClipData;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;

/**
 * Contains the parameters used to configure/identify your job. You do not create this object
 * yourself, instead it is handed in to your application by the System.
 */
public class JobParameters implements Parcelable {

    /** @hide */
    public static final int REASON_CANCELED = JobProtoEnums.STOP_REASON_CANCELLED; // 0.
    /** @hide */
    public static final int REASON_CONSTRAINTS_NOT_SATISFIED =
            JobProtoEnums.STOP_REASON_CONSTRAINTS_NOT_SATISFIED; //1.
    /** @hide */
    public static final int REASON_PREEMPT = JobProtoEnums.STOP_REASON_PREEMPT; // 2.
    /** @hide */
    public static final int REASON_TIMEOUT = JobProtoEnums.STOP_REASON_TIMEOUT; // 3.
    /** @hide */
    public static final int REASON_DEVICE_IDLE = JobProtoEnums.STOP_REASON_DEVICE_IDLE; // 4.
    /** @hide */
    public static final int REASON_DEVICE_THERMAL = JobProtoEnums.STOP_REASON_DEVICE_THERMAL; // 5.

    /** @hide */
    public static String getReasonName(int reason) {
        switch (reason) {
            case REASON_CANCELED: return "canceled";
            case REASON_CONSTRAINTS_NOT_SATISFIED: return "constraints";
            case REASON_PREEMPT: return "preempt";
            case REASON_TIMEOUT: return "timeout";
            case REASON_DEVICE_IDLE: return "device_idle";
            default: return "unknown:" + reason;
        }
    }

    @UnsupportedAppUsage
    private final int jobId;
    private final PersistableBundle extras;
    private final Bundle transientExtras;
    private final ClipData clipData;
    private final int clipGrantFlags;
    @UnsupportedAppUsage
    private final IBinder callback;
    private final boolean overrideDeadlineExpired;
    private final Uri[] mTriggeredContentUris;
    private final String[] mTriggeredContentAuthorities;
    private final Network network;

    private int stopReason; // Default value of stopReason is REASON_CANCELED
    private String debugStopReason; // Human readable stop reason for debugging.

    /** @hide */
    public JobParameters(IBinder callback, int jobId, PersistableBundle extras,
            Bundle transientExtras, ClipData clipData, int clipGrantFlags,
            boolean overrideDeadlineExpired, Uri[] triggeredContentUris,
            String[] triggeredContentAuthorities, Network network) {
        this.jobId = jobId;
        this.extras = extras;
        this.transientExtras = transientExtras;
        this.clipData = clipData;
        this.clipGrantFlags = clipGrantFlags;
        this.callback = callback;
        this.overrideDeadlineExpired = overrideDeadlineExpired;
        this.mTriggeredContentUris = triggeredContentUris;
        this.mTriggeredContentAuthorities = triggeredContentAuthorities;
        this.network = network;
    }

    /**
     * @return The unique id of this job, specified at creation time.
     */
    public int getJobId() {
        return jobId;
    }

    /**
     * Reason onStopJob() was called on this job.
     * @hide
     */
    public int getStopReason() {
        return stopReason;
    }

    /**
     * Reason onStopJob() was called on this job.
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
     *         network type.
     * @see JobInfo.Builder#setRequiredNetworkType(int)
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
        mTriggeredContentUris = in.createTypedArray(Uri.CREATOR);
        mTriggeredContentAuthorities = in.createStringArray();
        if (in.readInt() != 0) {
            network = Network.CREATOR.createFromParcel(in);
        } else {
            network = null;
        }
        stopReason = in.readInt();
        debugStopReason = in.readString();
    }

    /** @hide */
    public void setStopReason(int reason, String debugStopReason) {
        stopReason = reason;
        this.debugStopReason = debugStopReason;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(jobId);
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
        dest.writeTypedArray(mTriggeredContentUris, flags);
        dest.writeStringArray(mTriggeredContentAuthorities);
        if (network != null) {
            dest.writeInt(1);
            network.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(stopReason);
        dest.writeString(debugStopReason);
    }

    public static final Creator<JobParameters> CREATOR = new Creator<JobParameters>() {
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
