/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License.
 */

package android.app.job;

import static android.app.job.JobInfo.NETWORK_BYTES_UNKNOWN;

import android.annotation.BytesLong;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A unit of work that can be enqueued for a job using
 * {@link JobScheduler#enqueue JobScheduler.enqueue}.  See
 * {@link JobParameters#dequeueWork() JobParameters.dequeueWork} for more details.
 */
final public class JobWorkItem implements Parcelable {
    final Intent mIntent;
    final long mNetworkDownloadBytes;
    final long mNetworkUploadBytes;
    int mDeliveryCount;
    int mWorkId;
    Object mGrants;

    /**
     * Create a new piece of work, which can be submitted to
     * {@link JobScheduler#enqueue JobScheduler.enqueue}.
     *
     * @param intent The general Intent describing this work.
     */
    public JobWorkItem(Intent intent) {
        mIntent = intent;
        mNetworkDownloadBytes = NETWORK_BYTES_UNKNOWN;
        mNetworkUploadBytes = NETWORK_BYTES_UNKNOWN;
    }

    /**
     * @deprecated replaced by {@link #JobWorkItem(Intent, long, long)}
     * @removed
     */
    @Deprecated
    public JobWorkItem(Intent intent, @BytesLong long networkBytes) {
        this(intent, networkBytes, NETWORK_BYTES_UNKNOWN);
    }

    /**
     * Create a new piece of work, which can be submitted to
     * {@link JobScheduler#enqueue JobScheduler.enqueue}.
     * <p>
     * See {@link JobInfo.Builder#setEstimatedNetworkBytes(long, long)} for
     * details about how to estimate network traffic.
     *
     * @param intent The general Intent describing this work.
     * @param downloadBytes The estimated size of network traffic that will be
     *            downloaded by this job work item, in bytes.
     * @param uploadBytes The estimated size of network traffic that will be
     *            uploaded by this job work item, in bytes.
     */
    public JobWorkItem(Intent intent, @BytesLong long downloadBytes, @BytesLong long uploadBytes) {
        mIntent = intent;
        mNetworkDownloadBytes = downloadBytes;
        mNetworkUploadBytes = uploadBytes;
    }

    /**
     * Return the Intent associated with this work.
     */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * @deprecated replaced by {@link #getEstimatedNetworkDownloadBytes()} and
     *             {@link #getEstimatedNetworkUploadBytes()}.
     * @removed
     */
    @Deprecated
    public @BytesLong long getEstimatedNetworkBytes() {
        if (mNetworkDownloadBytes == NETWORK_BYTES_UNKNOWN
                && mNetworkUploadBytes == NETWORK_BYTES_UNKNOWN) {
            return NETWORK_BYTES_UNKNOWN;
        } else if (mNetworkDownloadBytes == NETWORK_BYTES_UNKNOWN) {
            return mNetworkUploadBytes;
        } else if (mNetworkUploadBytes == NETWORK_BYTES_UNKNOWN) {
            return mNetworkDownloadBytes;
        } else {
            return mNetworkDownloadBytes + mNetworkUploadBytes;
        }
    }

    /**
     * Return the estimated size of download traffic that will be performed by
     * this job, in bytes.
     *
     * @return Estimated size of download traffic, or
     *         {@link JobInfo#NETWORK_BYTES_UNKNOWN} when unknown.
     */
    public @BytesLong long getEstimatedNetworkDownloadBytes() {
        return mNetworkDownloadBytes;
    }

    /**
     * Return the estimated size of upload traffic that will be performed by
     * this job work item, in bytes.
     *
     * @return Estimated size of upload traffic, or
     *         {@link JobInfo#NETWORK_BYTES_UNKNOWN} when unknown.
     */
    public @BytesLong long getEstimatedNetworkUploadBytes() {
        return mNetworkUploadBytes;
    }

    /**
     * Return the count of the number of times this work item has been delivered
     * to the job.  The value will be > 1 if it has been redelivered because the job
     * was stopped or crashed while it had previously been delivered but before the
     * job had called {@link JobParameters#completeWork JobParameters.completeWork} for it.
     */
    public int getDeliveryCount() {
        return mDeliveryCount;
    }

    /**
     * @hide
     */
    public void bumpDeliveryCount() {
        mDeliveryCount++;
    }

    /**
     * @hide
     */
    public void setWorkId(int id) {
        mWorkId = id;
    }

    /**
     * @hide
     */
    public int getWorkId() {
        return mWorkId;
    }

    /**
     * @hide
     */
    public void setGrants(Object grants) {
        mGrants = grants;
    }

    /**
     * @hide
     */
    public Object getGrants() {
        return mGrants;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("JobWorkItem{id=");
        sb.append(mWorkId);
        sb.append(" intent=");
        sb.append(mIntent);
        if (mNetworkDownloadBytes != NETWORK_BYTES_UNKNOWN) {
            sb.append(" downloadBytes=");
            sb.append(mNetworkDownloadBytes);
        }
        if (mNetworkUploadBytes != NETWORK_BYTES_UNKNOWN) {
            sb.append(" uploadBytes=");
            sb.append(mNetworkUploadBytes);
        }
        if (mDeliveryCount != 0) {
            sb.append(" dcount=");
            sb.append(mDeliveryCount);
        }
        sb.append("}");
        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        if (mIntent != null) {
            out.writeInt(1);
            mIntent.writeToParcel(out, 0);
        } else {
            out.writeInt(0);
        }
        out.writeLong(mNetworkDownloadBytes);
        out.writeLong(mNetworkUploadBytes);
        out.writeInt(mDeliveryCount);
        out.writeInt(mWorkId);
    }

    public static final Parcelable.Creator<JobWorkItem> CREATOR
            = new Parcelable.Creator<JobWorkItem>() {
        public JobWorkItem createFromParcel(Parcel in) {
            return new JobWorkItem(in);
        }

        public JobWorkItem[] newArray(int size) {
            return new JobWorkItem[size];
        }
    };

    JobWorkItem(Parcel in) {
        if (in.readInt() != 0) {
            mIntent = Intent.CREATOR.createFromParcel(in);
        } else {
            mIntent = null;
        }
        mNetworkDownloadBytes = in.readLong();
        mNetworkUploadBytes = in.readLong();
        mDeliveryCount = in.readInt();
        mWorkId = in.readInt();
    }
}
