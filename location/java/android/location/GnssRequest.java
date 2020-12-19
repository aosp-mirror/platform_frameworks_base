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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class contains extra parameters to pass to a GNSS provider implementation.
 * @hide
 */
@SystemApi
public final class GnssRequest implements Parcelable {
    private final boolean mFullTracking;

    /**
     * Creates a {@link GnssRequest} with a full list of parameters.
     */
    private GnssRequest(boolean fullTracking) {
        mFullTracking = fullTracking;
    }

    /**
     * Represents whether to enable full GNSS tracking.
     *
     * <p>If true, GNSS chipset switches off duty cycling. In such a mode, no clock
     * discontinuities are expected, and when supported, carrier phase should be continuous in
     * good signal conditions. All non-blacklisted, healthy constellations, satellites and
     * frequency bands that the chipset supports must be reported in this mode. The GNSS chipset
     * is allowed to consume more power in this mode. If false, GNSS chipset optimizes power via
     * duty cycling, constellations and frequency limits, etc.
     *
     * <p>Full GNSS tracking mode affects GnssMeasurement and other GNSS functionalities
     * including GNSS location.
     */
    public boolean isFullTracking() {
        return mFullTracking;
    }

    /**
     * Converts the {@link GnssRequest} into a {@link GnssMeasurementRequest}.
     * @hide
     */
    @NonNull
    public GnssMeasurementRequest toGnssMeasurementRequest() {
        return new GnssMeasurementRequest.Builder().setFullTracking(isFullTracking()).build();
    }

    @NonNull
    public static final Creator<GnssRequest> CREATOR =
            new Creator<GnssRequest>() {
                @Override
                @NonNull
                public GnssRequest createFromParcel(@NonNull Parcel parcel) {
                    return new GnssRequest(parcel.readBoolean());
                }

                @Override
                public GnssRequest[] newArray(int i) {
                    return new GnssRequest[i];
                }
            };

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("GnssRequest[");
        if (mFullTracking) {
            s.append("FullTracking");
        }
        s.append(']');
        return s.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (!(obj instanceof GnssRequest)) return false;

        GnssRequest other = (GnssRequest) obj;
        if (mFullTracking != other.mFullTracking) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return mFullTracking ? 1 : 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBoolean(mFullTracking);
    }

    /** Builder for {@link GnssRequest} */
    public static final class Builder {
        private boolean mFullTracking;

        /**
         * Constructs a {@link Builder} instance.
         */
        public Builder() {
        }

        /**
         * Constructs a {@link Builder} instance by copying a {@link GnssRequest}.
         */
        public Builder(@NonNull GnssRequest request) {
            mFullTracking = request.isFullTracking();
        }

        /**
         * Set the value of whether to enable full GNSS tracking, which is false by default.
         *
         * <p>If true, GNSS chipset switches off duty cycling. In such a mode, no clock
         * discontinuities are expected, and when supported, carrier phase should be continuous in
         * good signal conditions. All non-blacklisted, healthy constellations, satellites and
         * frequency bands that the chipset supports must be reported in this mode. The GNSS chipset
         * is allowed to consume more power in this mode. If false, GNSS chipset optimizes power via
         * duty cycling, constellations and frequency limits, etc.
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

        /** Builds a {@link GnssRequest} instance as specified by this builder. */
        @NonNull
        public GnssRequest build() {
            return new GnssRequest(mFullTracking);
        }
    }
}
