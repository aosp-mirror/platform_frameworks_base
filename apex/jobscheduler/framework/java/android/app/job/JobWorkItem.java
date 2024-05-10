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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.compat.Compatibility;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * A unit of work that can be enqueued for a job using
 * {@link JobScheduler#enqueue JobScheduler.enqueue}.  See
 * {@link JobParameters#dequeueWork() JobParameters.dequeueWork} for more details.
 *
 * <p class="caution"><strong>Note:</strong> Prior to Android version
 * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, JobWorkItems could not be persisted.
 * Apps were not allowed to enqueue JobWorkItems with persisted jobs and the system would throw
 * an {@link IllegalArgumentException} if they attempted to do so. Starting with
 * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, JobWorkItems can be persisted alongside
 * the hosting job. However, Intents cannot be persisted. Set a {@link PersistableBundle} using
 * {@link Builder#setExtras(PersistableBundle)} for any information that needs to be persisted.
 */
final public class JobWorkItem implements Parcelable {
    @NonNull
    private final PersistableBundle mExtras;
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
     * <p>
     * Intents cannot be used for persisted JobWorkItems.
     * Use {@link Builder#setExtras(PersistableBundle)} instead for persisted JobWorkItems.
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
     * <p>
     * Intents cannot be used for persisted JobWorkItems.
     * Use {@link Builder#setExtras(PersistableBundle)} instead for persisted JobWorkItems.
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
     * <p>
     * Intents cannot be used for persisted JobWorkItems.
     * Use {@link Builder#setExtras(PersistableBundle)} instead for persisted JobWorkItems.
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
        mExtras = PersistableBundle.EMPTY;
        mIntent = intent;
        mNetworkDownloadBytes = downloadBytes;
        mNetworkUploadBytes = uploadBytes;
        mMinimumChunkBytes = minimumChunkBytes;
        enforceValidity(Compatibility.isChangeEnabled(JobInfo.REJECT_NEGATIVE_NETWORK_ESTIMATES));
    }

    private JobWorkItem(@NonNull Builder builder) {
        mDeliveryCount = builder.mDeliveryCount;
        mExtras = builder.mExtras.deepCopy();
        mIntent = builder.mIntent;
        mNetworkDownloadBytes = builder.mNetworkDownloadBytes;
        mNetworkUploadBytes = builder.mNetworkUploadBytes;
        mMinimumChunkBytes = builder.mMinimumNetworkChunkBytes;
    }

    /**
     * Return the extras associated with this work.
     *
     * @see Builder#setExtras(PersistableBundle)
     */
    @NonNull
    public PersistableBundle getExtras() {
        return mExtras;
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
    @Nullable
    public Object getGrants() {
        return mGrants;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("JobWorkItem{id=");
        sb.append(mWorkId);
        sb.append(" intent=");
        sb.append(mIntent);
        sb.append(" extras=");
        sb.append(mExtras);
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
     * Builder class for constructing {@link JobWorkItem} objects.
     */
    public static final class Builder {
        private int mDeliveryCount;
        private PersistableBundle mExtras = PersistableBundle.EMPTY;
        private Intent mIntent;
        private long mNetworkDownloadBytes = NETWORK_BYTES_UNKNOWN;
        private long mNetworkUploadBytes = NETWORK_BYTES_UNKNOWN;
        private long mMinimumNetworkChunkBytes = NETWORK_BYTES_UNKNOWN;

        /**
         * Initialize a new Builder to construct a {@link JobWorkItem} object.
         */
        public Builder() {
        }

        /**
         * @see JobWorkItem#getDeliveryCount()
         * @return This object for method chaining
         * @hide
         */
        @NonNull
        public Builder setDeliveryCount(int deliveryCount) {
            mDeliveryCount = deliveryCount;
            return this;
        }

        /**
         * Set optional extras. This can be persisted, so we only allow primitive types.
         * @param extras Bundle containing extras you want the scheduler to hold on to for you.
         * @return This object for method chaining
         * @see JobWorkItem#getExtras()
         */
        @NonNull
        public Builder setExtras(@NonNull PersistableBundle extras) {
            if (extras == null) {
                throw new IllegalArgumentException("extras cannot be null");
            }
            mExtras = extras;
            return this;
        }

        /**
         * Set an intent with information relevant to this work item.
         *
         * <p>
         * Intents cannot be used for persisted JobWorkItems.
         * Use {@link #setExtras(PersistableBundle)} instead for persisted JobWorkItems.
         *
         * @return This object for method chaining
         * @see JobWorkItem#getIntent()
         */
        @NonNull
        public Builder setIntent(@NonNull Intent intent) {
            mIntent = intent;
            return this;
        }

        /**
         * Set the estimated size of network traffic that will be performed for this work item,
         * in bytes.
         *
         * See {@link JobInfo.Builder#setEstimatedNetworkBytes(long, long)} for
         * details about how to estimate network traffic.
         *
         * @param downloadBytes The estimated size of network traffic that will be
         *                      downloaded for this work item, in bytes.
         * @param uploadBytes   The estimated size of network traffic that will be
         *                      uploaded for this work item, in bytes.
         * @return This object for method chaining
         * @see JobInfo.Builder#setEstimatedNetworkBytes(long, long)
         * @see JobWorkItem#getEstimatedNetworkDownloadBytes()
         * @see JobWorkItem#getEstimatedNetworkUploadBytes()
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setEstimatedNetworkBytes(@BytesLong long downloadBytes,
                @BytesLong long uploadBytes) {
            if (downloadBytes != NETWORK_BYTES_UNKNOWN && downloadBytes < 0) {
                throw new IllegalArgumentException(
                        "Invalid network download bytes: " + downloadBytes);
            }
            if (uploadBytes != NETWORK_BYTES_UNKNOWN && uploadBytes < 0) {
                throw new IllegalArgumentException("Invalid network upload bytes: " + uploadBytes);
            }
            mNetworkDownloadBytes = downloadBytes;
            mNetworkUploadBytes = uploadBytes;
            return this;
        }

        /**
         * Set the minimum size of non-resumable network traffic this work item requires, in bytes.
         * When the upload or download can be easily paused and resumed, use this to set the
         * smallest size that must be transmitted between start and stop events to be considered
         * successful. If the transfer cannot be paused and resumed, then this should be the sum
         * of the values provided to {@link #setEstimatedNetworkBytes(long, long)}.
         *
         * See {@link JobInfo.Builder#setMinimumNetworkChunkBytes(long)} for
         * details about how to set the minimum chunk.
         *
         * @param chunkSizeBytes The smallest piece of data that cannot be easily paused and
         *                       resumed, in bytes.
         * @return This object for method chaining
         * @see JobInfo.Builder#setMinimumNetworkChunkBytes(long)
         * @see JobWorkItem#getMinimumNetworkChunkBytes()
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
         * @return The JobWorkItem object to hand to the JobScheduler. This object is immutable.
         */
        @NonNull
        public JobWorkItem build() {
            return build(Compatibility.isChangeEnabled(JobInfo.REJECT_NEGATIVE_NETWORK_ESTIMATES));
        }

        /** @hide */
        @NonNull
        public JobWorkItem build(boolean rejectNegativeNetworkEstimates) {
            JobWorkItem jobWorkItem = new JobWorkItem(this);
            jobWorkItem.enforceValidity(rejectNegativeNetworkEstimates);
            return jobWorkItem;
        }
    }

    /**
     * @hide
     */
    public void enforceValidity(boolean rejectNegativeNetworkEstimates) {
        if (rejectNegativeNetworkEstimates) {
            if (mNetworkUploadBytes != NETWORK_BYTES_UNKNOWN && mNetworkUploadBytes < 0) {
                throw new IllegalArgumentException(
                        "Invalid network upload bytes: " + mNetworkUploadBytes);
            }
            if (mNetworkDownloadBytes != NETWORK_BYTES_UNKNOWN && mNetworkDownloadBytes < 0) {
                throw new IllegalArgumentException(
                        "Invalid network download bytes: " + mNetworkDownloadBytes);
            }
        }
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
        out.writePersistableBundle(mExtras);
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
        final PersistableBundle extras = in.readPersistableBundle();
        mExtras = extras != null ? extras : PersistableBundle.EMPTY;
        mNetworkDownloadBytes = in.readLong();
        mNetworkUploadBytes = in.readLong();
        mMinimumChunkBytes = in.readLong();
        mDeliveryCount = in.readInt();
        mWorkId = in.readInt();
    }
}
