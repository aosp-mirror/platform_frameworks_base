/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation;

import java.util.Objects;

/**
 * Status information regarding the throttle status of an APN type.
 *
 * @hide
 */
@SystemApi
public final class ThrottleStatus implements Parcelable {
    /**
     * The APN type is not throttled.
     */
    public static final int THROTTLE_TYPE_NONE = 1;

    /**
     * The APN type is throttled until {@link android.os.SystemClock#elapsedRealtime()}
     * has reached {@link ThrottleStatus#getThrottleExpiryTimeMillis}
     */
    public static final int THROTTLE_TYPE_ELAPSED_TIME = 2;

    /** {@hide} */
    @IntDef(flag = true, prefix = {"THROTTLE_TYPE_"}, value = {
            ThrottleStatus.THROTTLE_TYPE_NONE,
            ThrottleStatus.THROTTLE_TYPE_ELAPSED_TIME,
    })
    public @interface ThrottleType {
    }

    /**
     * The framework will not retry the APN type.
     */
    public static final int RETRY_TYPE_NONE = 1;

    /**
     * The next time the framework retries, it will attempt to establish a new connection.
     */
    public static final int RETRY_TYPE_NEW_CONNECTION = 2;

    /**
     * The next time the framework retires, it will retry to handover.
     */
    public static final int RETRY_TYPE_HANDOVER = 3;

    /** {@hide} */
    @IntDef(flag = true, prefix = {"RETRY_TYPE_"}, value = {
            ThrottleStatus.RETRY_TYPE_NONE,
            ThrottleStatus.RETRY_TYPE_NEW_CONNECTION,
            ThrottleStatus.RETRY_TYPE_HANDOVER,
    })
    public @interface RetryType {
    }

    private final int mSlotIndex;
    private final @AccessNetworkConstants.TransportType int mTransportType;
    private final @Annotation.ApnType int mApnType;
    private final long mThrottleExpiryTimeMillis;
    private final @RetryType int mRetryType;
    private final @ThrottleType int mThrottleType;

    /**
     * The slot index that the status applies to.
     *
     * @return the slot index
     */
    public int getSlotIndex() {
        return mSlotIndex;
    }

    /**
     * The type of transport that the status applies to.
     *
     * @return the transport type
     */
    @AccessNetworkConstants.TransportType
    public int getTransportType() {
        return mTransportType;
    }

    /**
     * The APN type that the status applies to.
     *
     * @return the apn type
     */
    @Annotation.ApnType
    public int getApnType() {
        return mApnType;
    }

    /**
     * The type of throttle applied to the APN type.
     *
     * @return the throttle type
     */
    @ThrottleType
    public int getThrottleType() {
        return mThrottleType;
    }

    /**
     * Indicates the type of request that the framework will make the next time it retries
     * to call {@link IDataService#setupDataCall}.
     *
     * @return the retry type
     */
    @RetryType
    public int getRetryType() {
        return mRetryType;
    }

    /**
     * Gets the time at which the throttle expires.  The value is based off of
     * {@link SystemClock#elapsedRealtime}.
     *
     * This value only applies when the throttle type is set to
     * {@link ThrottleStatus#THROTTLE_TYPE_ELAPSED_TIME}.
     *
     * A value of {@link Long#MAX_VALUE} implies that the APN type is throttled indefinitely.
     *
     * @return the time at which the throttle expires
     */
    @ElapsedRealtimeLong
    public long getThrottleExpiryTimeMillis() {
        return mThrottleExpiryTimeMillis;
    }

    private ThrottleStatus(int slotIndex,
            @AccessNetworkConstants.TransportType int transportType,
            @Annotation.ApnType int apnTypes,
            @ThrottleType int throttleType,
            long throttleExpiryTimeMillis,
            @RetryType int retryType) {
        mSlotIndex = slotIndex;
        mTransportType = transportType;
        mApnType = apnTypes;
        mThrottleType = throttleType;
        mThrottleExpiryTimeMillis = throttleExpiryTimeMillis;
        mRetryType = retryType;
    }

    private ThrottleStatus(@NonNull Parcel source) {
        mSlotIndex = source.readInt();
        mTransportType = source.readInt();
        mApnType = source.readInt();
        mThrottleExpiryTimeMillis = source.readLong();
        mRetryType = source.readInt();
        mThrottleType = source.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSlotIndex);
        dest.writeInt(mTransportType);
        dest.writeInt(mApnType);
        dest.writeLong(mThrottleExpiryTimeMillis);
        dest.writeInt(mRetryType);
        dest.writeInt(mThrottleType);
    }

    public static final @NonNull Parcelable.Creator<ThrottleStatus> CREATOR =
            new Parcelable.Creator<ThrottleStatus>() {
                @Override
                public ThrottleStatus createFromParcel(@NonNull Parcel source) {
                    return new ThrottleStatus(source);
                }

                @Override
                public ThrottleStatus[] newArray(int size) {
                    return new ThrottleStatus[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSlotIndex, mApnType, mRetryType, mThrottleType,
                mThrottleExpiryTimeMillis, mTransportType);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        } else if (obj instanceof ThrottleStatus) {
            ThrottleStatus other = (ThrottleStatus) obj;
            return this.mSlotIndex == other.mSlotIndex
                    && this.mApnType == other.mApnType
                    && this.mRetryType == other.mRetryType
                    && this.mThrottleType == other.mThrottleType
                    && this.mThrottleExpiryTimeMillis == other.mThrottleExpiryTimeMillis
                    && this.mTransportType == other.mTransportType;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "ThrottleStatus{"
                + "mSlotIndex=" + mSlotIndex
                + ", mTransportType=" + mTransportType
                + ", mApnType=" + ApnSetting.getApnTypeString(mApnType)
                + ", mThrottleExpiryTimeMillis=" + mThrottleExpiryTimeMillis
                + ", mRetryType=" + mRetryType
                + ", mThrottleType=" + mThrottleType
                + '}';
    }

    /**
     * Provides a convenient way to set the fields of an {@link ThrottleStatus} when creating a
     * new instance.
     *
     * <p>The example below shows how you might create a new {@code ThrottleStatus}:
     *
     * <pre><code>
     *
     * ThrottleStatus = new ThrottleStatus.Builder()
     *     .setSlotIndex(1)
     *     .setApnType({@link ApnSetting#TYPE_EMERGENCY})
     *     .setNoThrottle()
     *     .setRetryType({@link ThrottleStatus#RETRY_TYPE_NEW_CONNECTION})
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        private int mSlotIndex;
        private @AccessNetworkConstants.TransportType int mTransportType;
        private @Annotation.ApnType int mApnType;
        private long mThrottleExpiryTimeMillis;
        private @RetryType int mRetryType;
        private @ThrottleType int mThrottleType;

        /**
         * @hide
         */
        public static final long NO_THROTTLE_EXPIRY_TIME =
                DataCallResponse.RETRY_DURATION_UNDEFINED;

        /**
         * Default constructor for the Builder.
         */
        public Builder() {
        }

        /**
         * Set the slot index.
         *
         * @param slotIndex the slot index.
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setSlotIndex(int slotIndex) {
            this.mSlotIndex = slotIndex;
            return this;
        }

        /**
         * Set the transport type.
         *
         * @param transportType the transport type.
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setTransportType(@AccessNetworkConstants.TransportType
                int transportType) {
            this.mTransportType = transportType;
            return this;
        }

        /**
         * Set the APN type.
         *
         * @param apnType  the APN type.
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setApnType(@Annotation.ApnType int apnType) {
            this.mApnType = apnType;
            return this;
        }

        /**
         * Sets the time at which the throttle will expire.  The value is based off of
         * {@link SystemClock#elapsedRealtime}.
         *
         * When setting this value, the throttle type is set to
         * {@link ThrottleStatus#THROTTLE_TYPE_ELAPSED_TIME}.
         *
         * A value of {@link Long#MAX_VALUE} implies that the APN type is throttled indefinitely.
         *
         * @param throttleExpiryTimeMillis The elapsed time at which the throttle expires.
         *                                 Throws {@link IllegalArgumentException} for values less
         *                                 than 0.
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setThrottleExpiryTimeMillis(
                @ElapsedRealtimeLong long throttleExpiryTimeMillis) {
            if (throttleExpiryTimeMillis >= 0) {
                this.mThrottleExpiryTimeMillis = throttleExpiryTimeMillis;
                this.mThrottleType = THROTTLE_TYPE_ELAPSED_TIME;
            } else {
                throw new IllegalArgumentException("throttleExpiryTimeMillis must be greater than "
                        + "or equal to 0");
            }
            return this;
        }

        /**
         * Sets the status of the APN type as not being throttled.
         *
         * When setting this value, the throttle type is set to
         * {@link ThrottleStatus#THROTTLE_TYPE_NONE} and the expiry time is set to
         * {@link Builder#NO_THROTTLE_EXPIRY_TIME}.
         *
         * @return The same instance of the builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setNoThrottle() {
            mThrottleType = THROTTLE_TYPE_NONE;
            mThrottleExpiryTimeMillis = NO_THROTTLE_EXPIRY_TIME;
            return this;
        }

        /**
         * Set the type of request that the framework will make the next time it retries
         * to call {@link IDataService#setupDataCall}.
         *
         * @param retryType the type of request
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setRetryType(@RetryType int retryType) {
            this.mRetryType = retryType;
            return this;
        }

        /**
         * Build the {@link ThrottleStatus}
         *
         * @return the {@link ThrottleStatus} object
         */
        @NonNull
        public ThrottleStatus build() {
            return new ThrottleStatus(
                    mSlotIndex,
                    mTransportType,
                    mApnType,
                    mThrottleType,
                    mThrottleExpiryTimeMillis,
                    mRetryType);
        }
    }
}
