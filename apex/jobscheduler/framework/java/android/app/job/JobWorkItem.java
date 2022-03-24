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
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A unit of work that can be enqueued for a job using
 * {@link JobScheduler#enqueue JobScheduler.enqueue}.  See
 * {@link JobParameters#dequeueWork() JobParameters.dequeueWork} for more details.
 */
final public class JobWorkItem implements Parcelable {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    final Intent mIntent;
    private final long mNetworkDownloadBytes;
    private final long mNetworkUploadBytes;
    private final long mMinimumChunkBytes;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    int mDeliveryCount;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    int mWorkId;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    Object mGrants;

    /**
     * Create a new piece of work, which can be submitted to
     * {@link JobScheduler#enqueue JobScheduler.enqueue}.
     *
     * @param intent The general Intent describing this work.
     */
    public JobWorkItem(Intent intent) {
        this(intent, NETWORK_BYTES_UNKNOWN, NETWORK_BYTES_UNKNOWN);
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
        this(intent, downloadBytes, uploadBytes, NETWORK_BYTES_UNKNOWN);
    }

    /**
     * Create a new piece of work, which can be submitted to
     * {@link JobScheduler#enqueue JobScheduler.enqueue}.
     * <p>
     * See {@link JobInfo.Builder#setEstimatedNetworkBytes(long, long)} for
     * details about how to estimate network traffic.
     *
     * @param intent            The general Intent describing this work.
     * @param downloadBytes     The estimated size of network traffic that will be
     *                          downloaded by this job work item, in bytes.
     * @param uploadBytes       The estimated size of network traffic that will be
     *                          uploaded by this job work item, in bytes.
     * @param minimumChunkBytes The smallest piece of data that cannot be easily paused and
     *                          resumed, in bytes.
     */
    public JobWorkItem(@Nullable Intent intent, @BytesLong long downloadBytes,
            @BytesLong long uploadBytes, @BytesLong long minimumChunkBytes) {
        if (minimumChunkBytes != NETWORK_BYTES_UNKNOWN && minimumChunkBytes <= 0) {
            throw new IllegalArgumentException("Minimum chunk size must be positive");
        }
        final long estimatedTransfer;
        if (uploadBytes == NETWORK_BYTES_UNKNOWN) {
            estimatedTransfer = downloadBytes;
        } else {
            estimatedTransfer = uploadBytes
                    + (downloadBytes == NETWORK_BYTES_UNKNOWN ? 0 : downloadBytes);
        }
        if (minimumChunkBytes != NETWORK_BYTES_UNKNOWN && estimatedTransfer != NETWORK_BYTES_UNKNOWN
                && minimumChunkBytes > estimatedTransfer) {
            throw new IllegalArgumentException(
                    "Minimum chunk size can't be greater than estimated network usage");
        }
        mIntent = intent;
        mNetworkDownloadBytes = downloadBytes;
        mNetworkUploadBytes = uploadBytes;
        mMinimumChunkBytes = minimumChunkBytes;
    }

    /**
     * Return the Intent associated with this work.
     */
    public Intent getIntent() {
        return mIntent;
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
     * Return the smallest piece of data that cannot be easily paused and resumed, in bytes.
     *
     * @return Smallest piece of data that cannot be easily paused and resumed, or
     * {@link JobInfo#NETWORK_BYTES_UNKNOWN} when unknown.
     */
    public @BytesLong long getMinimumNetworkChunkBytes() {
        return mMinimumChunkBytes;
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
        if (mMinimumChunkBytes != NETWORK_BYTES_UNKNOWN) {
            sb.append(" minimumChunkBytes=");
            sb.append(mMinimumChunkBytes);
        }
        if (mDeliveryCount != 0) {
            sb.append(" dcount=");
            sb.append(mDeliveryCount);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * @hide
     */
    public void enforceValidity() {
        final long estimatedTransfer;
        if (mNetworkUploadBytes == NETWORK_BYTES_UNKNOWN) {
            estimatedTransfer = mNetworkDownloadBytes;
        } else {
            estimatedTransfer = mNetworkUploadBytes
                    + (mNetworkDownloadBytes == NETWORK_BYTES_UNKNOWN ? 0 : mNetworkDownloadBytes);
        }
        if (mMinimumChunkBytes != NETWORK_BYTES_UNKNOWN
                && estimatedTransfer != NETWORK_BYTES_UNKNOWN
                && mMinimumChunkBytes > estimatedTransfer) {
            throw new IllegalArgumentException(
                    "Minimum chunk size can't be greater than estimated network usage");
        }
        if (mMinimumChunkBytes != NETWORK_BYTES_UNKNOWN && mMinimumChunkBytes <= 0) {
            throw new IllegalArgumentException("Minimum chunk size must be positive");
        }
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
        out.writeLong(mMinimumChunkBytes);
        out.writeInt(mDeliveryCount);
        out.writeInt(mWorkId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<JobWorkItem> CREATOR
            = new Parcelable.Creator<JobWorkItem>() {
        public JobWorkItem createFromParcel(Parcel in) {
            return new JobWorkItem(in);
        }

        public JobWorkItem[] newArray(int size) {
            return new JobWorkItem[size];
        }
    };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    JobWorkItem(Parcel in) {
        if (in.readInt() != 0) {
            mIntent = Intent.CREATOR.createFromParcel(in);
        } else {
            mIntent = null;
        }
        mNetworkDownloadBytes = in.readLong();
        mNetworkUploadBytes = in.readLong();
        mMinimumChunkBytes = in.readLong();
        mDeliveryCount = in.readInt();
        mWorkId = in.readInt();
    }
}
