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

import android.app.job.IJobCallback;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * Contains the parameters used to configure/identify your job. You do not create this object
 * yourself, instead it is handed in to your application by the System.
 */
public class JobParameters implements Parcelable {

    /** @hide */
    public static final int REASON_CANCELED = 0;
    /** @hide */
    public static final int REASON_CONSTRAINTS_NOT_SATISFIED = 1;
    /** @hide */
    public static final int REASON_PREEMPT = 2;
    /** @hide */
    public static final int REASON_TIMEOUT = 3;
    /** @hide */
    public static final int REASON_DEVICE_IDLE = 4;

    private final int jobId;
    private final PersistableBundle extras;
    private final IBinder callback;
    private final boolean overrideDeadlineExpired;
    private final Uri[] mTriggeredContentUris;
    private final String[] mTriggeredContentAuthorities;

    private int stopReason; // Default value of stopReason is REASON_CANCELED

    /** @hide */
    public JobParameters(IBinder callback, int jobId, PersistableBundle extras,
                boolean overrideDeadlineExpired, Uri[] triggeredContentUris,
            String[] triggeredContentAuthorities) {
        this.jobId = jobId;
        this.extras = extras;
        this.callback = callback;
        this.overrideDeadlineExpired = overrideDeadlineExpired;
        this.mTriggeredContentUris = triggeredContentUris;
        this.mTriggeredContentAuthorities = triggeredContentAuthorities;
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
     * @return The extras you passed in when constructing this job with
     * {@link android.app.job.JobInfo.Builder#setExtras(android.os.PersistableBundle)}. This will
     * never be null. If you did not set any extras this will be an empty bundle.
     */
    public PersistableBundle getExtras() {
        return extras;
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
    public Uri[] getTriggeredContentUris() {
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
    public String[] getTriggeredContentAuthorities() {
        return mTriggeredContentAuthorities;
    }

    /** @hide */
    public IJobCallback getCallback() {
        return IJobCallback.Stub.asInterface(callback);
    }

    private JobParameters(Parcel in) {
        jobId = in.readInt();
        extras = in.readPersistableBundle();
        callback = in.readStrongBinder();
        overrideDeadlineExpired = in.readInt() == 1;
        mTriggeredContentUris = in.createTypedArray(Uri.CREATOR);
        mTriggeredContentAuthorities = in.createStringArray();
        stopReason = in.readInt();
    }

    /** @hide */
    public void setStopReason(int reason) {
        stopReason = reason;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(jobId);
        dest.writePersistableBundle(extras);
        dest.writeStrongBinder(callback);
        dest.writeInt(overrideDeadlineExpired ? 1 : 0);
        dest.writeTypedArray(mTriggeredContentUris, flags);
        dest.writeStringArray(mTriggeredContentAuthorities);
        dest.writeInt(stopReason);
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
