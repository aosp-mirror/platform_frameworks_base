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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * This class contains extra parameters to pass in a GNSS measurement request.
 */
public final class GnssMeasurementRequest implements Parcelable {
    private final boolean mCorrelationVectorOutputsEnabled;
    private final boolean mFullTracking;
    private final int mIntervalMillis;

    /**
     * Creates a {@link GnssMeasurementRequest} with a full list of parameters.
     */
    private GnssMeasurementRequest(boolean fullTracking, boolean correlationVectorOutputsEnabled,
            int intervalMillis) {
        mFullTracking = fullTracking;
        mCorrelationVectorOutputsEnabled = correlationVectorOutputsEnabled;
        mIntervalMillis = intervalMillis;
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
     * frequency bands that the chipset supports must be reported in this mode. The GNSS chipset
     * will consume more power in full tracking mode than in duty cycling mode. If false, GNSS
     * chipset optimizes power via duty cycling, constellations and frequency limits, etc.
     *
     * <p>Full GNSS tracking mode affects GnssMeasurement and other GNSS functionalities
     * including GNSS location.
     */
    public boolean isFullTracking() {
        return mFullTracking;
    }

    /**
     * Represents the requested time interval between the reported measurements in milliseconds.
     *
     * <p>If the time interval is not set, the default value is 0, which means the fastest rate the
     * GNSS chipset can report.
     *
     * <p>The GNSS chipset may report measurements with a rate faster than requested.
     */
    public @IntRange(from = 0) int getIntervalMillis() {
        return mIntervalMillis;
    }

    @NonNull
    public static final Creator<GnssMeasurementRequest> CREATOR =
            new Creator<GnssMeasurementRequest>() {
                @Override
                @NonNull
                public GnssMeasurementRequest createFromParcel(@NonNull Parcel parcel) {
                    return new GnssMeasurementRequest(parcel.readBoolean(), parcel.readBoolean(),
                            parcel.readInt());
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
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("GnssMeasurementRequest[");
        s.append("@");
        TimeUtils.formatDuration(mIntervalMillis, s);
        if (mFullTracking) {
            s.append(", FullTracking");
        }
        if (mCorrelationVectorOutputsEnabled) {
            s.append(", CorrelationVectorOutputs");
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
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFullTracking, mCorrelationVectorOutputsEnabled, mIntervalMillis);
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
         * default.
         *
         * <p>An interval of 0 milliseconds means the fastest rate the chipset can report.
         *
         * <p>The GNSS chipset may report measurements with a rate faster than requested.
         */
        @NonNull public Builder setIntervalMillis(@IntRange(from = 0) int value) {
            mIntervalMillis = Preconditions.checkArgumentInRange(value, 0, Integer.MAX_VALUE,
                    "intervalMillis");
            return this;
        }

        /** Builds a {@link GnssMeasurementRequest} instance as specified by this builder. */
        @NonNull
        public GnssMeasurementRequest build() {
            return new GnssMeasurementRequest(mFullTracking, mCorrelationVectorOutputsEnabled,
                    mIntervalMillis);
        }
    }
}
