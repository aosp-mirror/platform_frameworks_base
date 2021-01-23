/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Representation of a ranging measurement between the local device and a remote device
 *
 * @hide
 */
@SystemApi
public final class RangingMeasurement implements Parcelable {
    private final UwbAddress mRemoteDeviceAddress;
    private final @Status int mStatus;
    private final long mElapsedRealtimeNanos;
    private final DistanceMeasurement mDistanceMeasurement;
    private final AngleOfArrivalMeasurement mAngleOfArrivalMeasurement;

    private RangingMeasurement(@NonNull UwbAddress remoteDeviceAddress, @Status int status,
            long elapsedRealtimeNanos, @Nullable DistanceMeasurement distanceMeasurement,
            @Nullable AngleOfArrivalMeasurement angleOfArrivalMeasurement) {
        mRemoteDeviceAddress = remoteDeviceAddress;
        mStatus = status;
        mElapsedRealtimeNanos = elapsedRealtimeNanos;
        mDistanceMeasurement = distanceMeasurement;
        mAngleOfArrivalMeasurement = angleOfArrivalMeasurement;
    }

    /**
     * Get the remote device's {@link UwbAddress}
     *
     * @return the remote device's {@link UwbAddress}
     */
    @NonNull
    public UwbAddress getRemoteDeviceAddress() {
        return mRemoteDeviceAddress;
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            RANGING_STATUS_SUCCESS,
            RANGING_STATUS_FAILURE_OUT_OF_RANGE,
            RANGING_STATUS_FAILURE_UNKNOWN_ERROR})
    public @interface Status {}

    /**
     * Ranging attempt was successful for this device
     */
    public static final int RANGING_STATUS_SUCCESS = 0;

    /**
     * Ranging failed for this device because it is out of range
     */
    public static final int RANGING_STATUS_FAILURE_OUT_OF_RANGE = 1;

    /**
     * Ranging failed for this device because of unknown error
     */
    public static final int RANGING_STATUS_FAILURE_UNKNOWN_ERROR = -1;

    /**
     * Get the status of this ranging measurement
     *
     * <p>Possible values are
     * {@link #RANGING_STATUS_SUCCESS},
     * {@link #RANGING_STATUS_FAILURE_OUT_OF_RANGE},
     * {@link #RANGING_STATUS_FAILURE_UNKNOWN_ERROR}.
     *
     * @return the status of the ranging measurement
     */
    @Status
    public int getStatus() {
        return mStatus;
    }

    /**
     * Timestamp of this ranging measurement in time since boot nanos in the same namespace as
     * {@link SystemClock#elapsedRealtimeNanos()}
     *
     * @return timestamp of ranging measurement in nanoseconds
     */
    @SuppressLint("MethodNameUnits")
    public long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNanos;
    }

    /**
     * Get the distance measurement
     *
     * @return a {@link DistanceMeasurement} or null if {@link #getStatus()} !=
     *         {@link #RANGING_STATUS_SUCCESS}
     */
    @Nullable
    public DistanceMeasurement getDistanceMeasurement() {
        return mDistanceMeasurement;
    }

    /**
     * Get the angle of arrival measurement
     *
     * @return an {@link AngleOfArrivalMeasurement} or null if {@link #getStatus()} !=
     *         {@link #RANGING_STATUS_SUCCESS}
     */
    @Nullable
    public AngleOfArrivalMeasurement getAngleOfArrivalMeasurement() {
        return mAngleOfArrivalMeasurement;
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof RangingMeasurement) {
            RangingMeasurement other = (RangingMeasurement) obj;
            return mRemoteDeviceAddress.equals(other.getRemoteDeviceAddress())
                    && mStatus == other.getStatus()
                    && mElapsedRealtimeNanos == other.getElapsedRealtimeNanos()
                    && mDistanceMeasurement.equals(other.getDistanceMeasurement())
                    && mAngleOfArrivalMeasurement.equals(other.getAngleOfArrivalMeasurement());
        }
        return false;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mRemoteDeviceAddress, mStatus, mElapsedRealtimeNanos,
                mDistanceMeasurement, mAngleOfArrivalMeasurement);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mRemoteDeviceAddress, flags);
        dest.writeInt(mStatus);
        dest.writeLong(mElapsedRealtimeNanos);
        dest.writeParcelable(mDistanceMeasurement, flags);
        dest.writeParcelable(mAngleOfArrivalMeasurement, flags);
    }

    public static final @android.annotation.NonNull Creator<RangingMeasurement> CREATOR =
            new Creator<RangingMeasurement>() {
                @Override
                public RangingMeasurement createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    builder.setRemoteDeviceAddress(
                            in.readParcelable(UwbAddress.class.getClassLoader()));
                    builder.setStatus(in.readInt());
                    builder.setElapsedRealtimeNanos(in.readLong());
                    builder.setDistanceMeasurement(
                            in.readParcelable(DistanceMeasurement.class.getClassLoader()));
                    builder.setAngleOfArrivalMeasurement(
                            in.readParcelable(AngleOfArrivalMeasurement.class.getClassLoader()));
                    return builder.build();
                }

                @Override
                public RangingMeasurement[] newArray(int size) {
                    return new RangingMeasurement[size];
                }
    };

    /**
     * Builder for a {@link RangingMeasurement} object.
     */
    public static final class Builder {
        private UwbAddress mRemoteDeviceAddress = null;
        private @Status int mStatus = RANGING_STATUS_FAILURE_UNKNOWN_ERROR;
        private long mElapsedRealtimeNanos = -1L;
        private DistanceMeasurement mDistanceMeasurement = null;
        private AngleOfArrivalMeasurement mAngleOfArrivalMeasurement = null;

        /**
         * Set the remote device address that this measurement is for
         *
         * @param remoteDeviceAddress remote device's address
         */
        @NonNull
        public Builder setRemoteDeviceAddress(@NonNull UwbAddress remoteDeviceAddress) {
            mRemoteDeviceAddress = remoteDeviceAddress;
            return this;
        }

        /**
         * Set the status of ranging measurement
         *
         * @param status the status of the ranging measurement
         */
        @NonNull
        public Builder setStatus(@Status int status) {
            mStatus = status;
            return this;
        }

        /**
         * Set the elapsed realtime in nanoseconds when the ranging measurement occurred
         *
         * @param elapsedRealtimeNanos time the ranging measurement occurred
         */
        @NonNull
        public Builder setElapsedRealtimeNanos(long elapsedRealtimeNanos) {
            if (elapsedRealtimeNanos < 0) {
                throw new IllegalArgumentException("elapsedRealtimeNanos must be >= 0");
            }
            mElapsedRealtimeNanos = elapsedRealtimeNanos;
            return this;
        }

        /**
         * Set the {@link DistanceMeasurement}
         *
         * @param distanceMeasurement the distance measurement for this ranging measurement
         */
        @NonNull
        public Builder setDistanceMeasurement(@NonNull DistanceMeasurement distanceMeasurement) {
            mDistanceMeasurement = distanceMeasurement;
            return this;
        }

        /**
         * Set the {@link AngleOfArrivalMeasurement}
         *
         * @param angleOfArrivalMeasurement the angle of arrival measurement for this ranging
         *                                  measurement
         */
        @NonNull
        public Builder setAngleOfArrivalMeasurement(
                @NonNull AngleOfArrivalMeasurement angleOfArrivalMeasurement) {
            mAngleOfArrivalMeasurement = angleOfArrivalMeasurement;
            return this;
        }

        /**
         * Build the {@link RangingMeasurement} object
         *
         * @throws IllegalStateException if a distance or angle of arrival measurement is provided
         *                               but the measurement was not successful, if the
         *                               elapsedRealtimeNanos of the measurement is invalid, or
         *                               if no remote device address is set
         */
        @NonNull
        public RangingMeasurement build() {
            if (mStatus != RANGING_STATUS_SUCCESS) {
                if (mDistanceMeasurement != null) {
                    throw new IllegalStateException(
                            "Distance Measurement must be null if ranging is not successful");
                }

                if (mAngleOfArrivalMeasurement != null) {
                    throw new IllegalStateException(
                            "Angle of Arrival must be null if ranging is not successful");
                }
            }

            if (mRemoteDeviceAddress == null) {
                throw new IllegalStateException("No remote device address was set");
            }

            if (mElapsedRealtimeNanos < 0) {
                throw new IllegalStateException(
                        "elapsedRealtimeNanos must be >=0: " + mElapsedRealtimeNanos);
            }

            return new RangingMeasurement(mRemoteDeviceAddress, mStatus, mElapsedRealtimeNanos,
                    mDistanceMeasurement, mAngleOfArrivalMeasurement);
        }
    }
}
