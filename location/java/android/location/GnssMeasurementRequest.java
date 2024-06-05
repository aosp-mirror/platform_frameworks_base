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

package android.location;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.WorkSource;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * This class contains extra parameters to pass in a GNSS measurement request.
 */
public final class GnssMeasurementRequest implements Parcelable {
    /**
     * Represents a passive only request. Such a request will not trigger any active GNSS
     * measurements or power usage itself, but may receive GNSS measurements generated in response
     * to other requests.
     *
     * <p class="note">Note that on Android T, such a request will trigger one GNSS measurement.
     * Another GNSS measurement will be triggered after {@link #PASSIVE_INTERVAL} and so on.
     *
     * @see GnssMeasurementRequest#getIntervalMillis()
     */
    public static final int PASSIVE_INTERVAL = Integer.MAX_VALUE;

    private final boolean mCorrelationVectorOutputsEnabled;
    private final boolean mFullTracking;
    private final int mIntervalMillis;
    private WorkSource mWorkSource;
    /**
     * Creates a {@link GnssMeasurementRequest} with a full list of parameters.
     */
    private GnssMeasurementRequest(boolean fullTracking, boolean correlationVectorOutputsEnabled,
            int intervalMillis, WorkSource workSource) {
        mFullTracking = fullTracking;
        mCorrelationVectorOutputsEnabled = correlationVectorOutputsEnabled;
        mIntervalMillis = intervalMillis;
        mWorkSource = Objects.requireNonNull(workSource);
    }

    /**
     * Represents whether to enable correlation vector outputs.
     *
     * <p>If true, enable correlation vectors as part of the raw GNSS measurements outputs.
     * If false, disable correlation vectors.
     *
     * @hide
     */
    @SystemApi
    public boolean isCorrelationVectorOutputsEnabled() {
        return mCorrelationVectorOutputsEnabled;
    }

    /**
     * Represents whether to enable full GNSS tracking.
     *
     * <p>If true, GNSS chipset switches off duty cycling. In such a mode, no clock
     * discontinuities are expected, and when supported, carrier phase should be continuous in
     * good signal conditions. All non-blocklisted, healthy constellations, satellites and
     * frequency bands that are meaningful to positioning accuracy must be tracked and reported in
     * this mode. The GNSS chipset will consume more power in full tracking mode than in duty
     * cycling mode. If false, GNSS chipset optimizes power via duty cycling, constellations and
     * frequency limits, etc.
     *
     * <p>Full GNSS tracking mode affects GnssMeasurement and other GNSS functionalities
     * including GNSS location.
     */
    public boolean isFullTracking() {
        return mFullTracking;
    }

    /**
     * Returns the requested time interval between the reported measurements in milliseconds, or
     * {@link #PASSIVE_INTERVAL} if this is a passive, no power request. A passive request will not
     * actively generate GNSS measurement updates, but may receive GNSS measurement updates
     * generated as a result of other GNSS measurement requests.
     *
     * <p>If the time interval is not set, the default value is 0, which means the fastest rate the
     * GNSS chipset can report.
     *
     * <p>The GNSS chipset may report measurements with a rate faster than requested.
     *
     * <p class="note">Note that on Android T, a request interval of {@link #PASSIVE_INTERVAL}
     * will first trigger one GNSS measurement. Another GNSS measurement will be triggered after
     * {@link #PASSIVE_INTERVAL} milliseconds ans so on.
     */
    public @IntRange(from = 0) int getIntervalMillis() {
        return mIntervalMillis;
    }

    /**
     * Returns the work source used for power blame for this request. If empty (i.e.,
     * {@link WorkSource#isEmpty()} is {@code true}, the system is free to assign power blame as it
     * deems most appropriate.
     *
     * @return the work source used for power blame for this request
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_GNSS_API_MEASUREMENT_REQUEST_WORK_SOURCE)
    @SystemApi
    public @NonNull WorkSource getWorkSource() {
        return mWorkSource;
    }

    @NonNull
    public static final Creator<GnssMeasurementRequest> CREATOR =
            new Creator<GnssMeasurementRequest>() {
                @Override
                @NonNull
                public GnssMeasurementRequest createFromParcel(@NonNull Parcel parcel) {
                    return new GnssMeasurementRequest(
                            /* fullTracking= */ parcel.readBoolean(),
                            /* correlationVectorOutputsEnabled= */ parcel.readBoolean(),
                            /* intervalMillis= */ parcel.readInt(),
                            /* workSource= */ parcel.readTypedObject(WorkSource.CREATOR));
                }

                @Override
                public GnssMeasurementRequest[] newArray(int i) {
                    return new GnssMeasurementRequest[i];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBoolean(mFullTracking);
        parcel.writeBoolean(mCorrelationVectorOutputsEnabled);
        parcel.writeInt(mIntervalMillis);
        parcel.writeTypedObject(mWorkSource, 0);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("GnssMeasurementRequest[");
        if (mIntervalMillis == PASSIVE_INTERVAL) {
            s.append("passive");
        } else {
            s.append("@");
            TimeUtils.formatDuration(mIntervalMillis, s);
        }
        if (mFullTracking) {
            s.append(", FullTracking");
        }
        if (mCorrelationVectorOutputsEnabled) {
            s.append(", CorrelationVectorOutputs");
        }
        if (mWorkSource != null && !mWorkSource.isEmpty()) {
            s.append(", ").append(mWorkSource);
        }
        s.append(']');
        return s.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (!(obj instanceof GnssMeasurementRequest)) return false;

        GnssMeasurementRequest other = (GnssMeasurementRequest) obj;
        if (mFullTracking != other.mFullTracking) return false;
        if (mCorrelationVectorOutputsEnabled != other.mCorrelationVectorOutputsEnabled) {
            return false;
        }
        if (mIntervalMillis != other.mIntervalMillis) {
            return false;
        }
        if (!Objects.equals(mWorkSource, other.mWorkSource)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFullTracking, mCorrelationVectorOutputsEnabled, mIntervalMillis,
                mWorkSource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Builder for {@link GnssMeasurementRequest} */
    public static final class Builder {
        private boolean mCorrelationVectorOutputsEnabled;
        private boolean mFullTracking;
        private int mIntervalMillis;
        private WorkSource mWorkSource;

        /**
         * Constructs a {@link Builder} instance.
         */
        public Builder() {
        }

        /**
         * Constructs a {@link Builder} instance by copying a {@link GnssMeasurementRequest}.
         */
        public Builder(@NonNull GnssMeasurementRequest request) {
            mCorrelationVectorOutputsEnabled = request.isCorrelationVectorOutputsEnabled();
            mFullTracking = request.isFullTracking();
            mIntervalMillis = request.getIntervalMillis();
            mWorkSource = request.getWorkSource();
        }

        /**
         * Set the value of whether to enable correlation vector outputs, which is false by default.
         *
         * <p>If true, enable correlation vectors as part of the raw GNSS measurements outputs.
         * If false, disable correlation vectors.
         *
         * @hide
         */
        @SystemApi
        @NonNull public Builder setCorrelationVectorOutputsEnabled(boolean value) {
            mCorrelationVectorOutputsEnabled = value;
            return this;
        }

        /**
         * Set the value of whether to enable full GNSS tracking, which is false by default.
         *
         * <p>If true, GNSS chipset switches off duty cycling. In such a mode, no clock
         * discontinuities are expected, and when supported, carrier phase should be continuous in
         * good signal conditions. All non-blocklisted, healthy constellations, satellites and
         * frequency bands that the chipset supports must be reported in this mode. The GNSS chipset
         * will consume more power in full tracking mode than in duty cycling mode. If false,
         * GNSS chipset optimizes power via duty cycling, constellations and frequency limits, etc.
         *
         * <p>Full GNSS tracking mode affects GnssMeasurement and other GNSS functionalities
         * including GNSS location.
         *
         * <p>Full tracking requests always override non-full tracking requests. If any full
         * tracking request occurs, all listeners on the device will receive full tracking GNSS
         * measurements.
         */
        @NonNull public Builder setFullTracking(boolean value) {
            mFullTracking = value;
            return this;
        }

        /**
         * Set the time interval between the reported measurements in milliseconds, which is 0 by
         * default. The request interval may be set to {@link #PASSIVE_INTERVAL} which indicates
         * this request will not actively generate GNSS measurement updates, but may receive
         * GNSS measurement updates generated as a result of other GNSS measurement requests.
         *
         * <p>An interval of 0 milliseconds means the fastest rate the chipset can report.
         *
         * <p>The GNSS chipset may report measurements with a rate faster than requested.
         *
         * <p class="note">Note that on Android T, a request interval of {@link #PASSIVE_INTERVAL}
         * will first trigger one GNSS measurement. Another GNSS measurement will be triggered after
         * {@link #PASSIVE_INTERVAL} milliseconds and so on.
         */
        @NonNull public Builder setIntervalMillis(@IntRange(from = 0) int value) {
            mIntervalMillis = Preconditions.checkArgumentInRange(value, 0, Integer.MAX_VALUE,
                    "intervalMillis");
            return this;
        }

        /**
         * Sets the work source to use for power blame for this request. Passing in null or leaving
         * it unset will be an empty WorkSource, which implies the system is free to assign power
         * blame as it determines best for this request (which usually means blaming the owner of
         * the GnssMeasurement listener).
         *
         * <p>Permissions enforcement occurs when resulting request is actually used, not when this
         * method is invoked.
         *
         * @hide
         */
        @FlaggedApi(Flags.FLAG_GNSS_API_MEASUREMENT_REQUEST_WORK_SOURCE)
        @SystemApi
        @RequiresPermission(Manifest.permission.UPDATE_DEVICE_STATS)
        public @NonNull Builder setWorkSource(@Nullable WorkSource workSource) {
            mWorkSource = workSource;
            return this;
        }

        /** Builds a {@link GnssMeasurementRequest} instance as specified by this builder. */
        @NonNull
        public GnssMeasurementRequest build() {
            return new GnssMeasurementRequest(mFullTracking, mCorrelationVectorOutputsEnabled,
                    mIntervalMillis, new WorkSource(mWorkSource));
        }
    }
}
