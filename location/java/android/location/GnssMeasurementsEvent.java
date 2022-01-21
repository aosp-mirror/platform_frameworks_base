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

package android.location;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A class implementing a container for data associated with a measurement event.
 * Events are delivered to registered instances of {@link Callback}.
 */
public final class GnssMeasurementsEvent implements Parcelable {
    private final GnssClock mClock;
    private final List<GnssMeasurement> mMeasurements;
    private final List<GnssAutomaticGainControl> mGnssAgcs;

    /**
     * Used for receiving GNSS satellite measurements from the GNSS engine.
     * Each measurement contains raw and computed data identifying a satellite.
     * You can implement this interface and call
     * {@link LocationManager#registerGnssMeasurementsCallback}.
     */
    public static abstract class Callback {
        /**
         * The status of the GNSS measurements event.
         * @deprecated Do not use.
         * @hide
         */
        @Deprecated
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({STATUS_NOT_SUPPORTED, STATUS_READY, STATUS_LOCATION_DISABLED, STATUS_NOT_ALLOWED})
        public @interface GnssMeasurementsStatus {}

        /**
         * The system does not support tracking of GNSS Measurements.
         *
         * <p>This status will not change in the future.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_NOT_SUPPORTED = 0;

        /**
         * GNSS Measurements are successfully being tracked, it will receive updates once they are
         * available.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_READY = 1;

        /**
         * GPS provider or Location is disabled, updates will not be received until they are
         * enabled.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_LOCATION_DISABLED = 2;

        /**
         * The client is not allowed to register for GNSS Measurements in general or in the
         * requested mode.
         *
         * <p>Such a status is returned when a client tries to request a functionality from the GNSS
         * chipset while another client has an ongoing request that does not allow such
         * functionality to be performed.
         *
         * <p>If such a status is received, one would try again at a later time point where no
         * other client is having a conflicting request.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_NOT_ALLOWED = 3;

        /**
         * Reports the latest collected GNSS Measurements.
         */
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs) {}

        /**
         * Reports the latest status of the GNSS Measurements sub-system.
         *
         * @deprecated Do not rely on this callback. From Android S onwards this callback will be
         * invoked once with {@link #STATUS_READY} in all cases for backwards compatibility, and
         * then never invoked again. Use LocationManager APIs if you need to determine if
         * GNSS measurements are supported or if location is off, etc...
         */
        @Deprecated
        public void onStatusChanged(@GnssMeasurementsStatus int status) {}
    }

    /**
     * Create a {@link GnssMeasurementsEvent} instance with a full list of parameters.
     */
    private GnssMeasurementsEvent(@NonNull GnssClock clock,
            @NonNull List<GnssMeasurement> measurements,
            @NonNull List<GnssAutomaticGainControl> agcs) {
        mMeasurements = measurements;
        mGnssAgcs = agcs;
        mClock = clock;
    }

    /**
     * Gets the GNSS receiver clock information associated with the measurements for the current
     * event.
     */
    @NonNull
    public GnssClock getClock() {
        return mClock;
    }

    /**
     * Gets the collection of measurements associated with the current event.
     */
    @NonNull
    public Collection<GnssMeasurement> getMeasurements() {
        return mMeasurements;
    }

    /**
     * Gets the collection of {@link GnssAutomaticGainControl} associated with the
     * current event.
     */
    @NonNull
    public Collection<GnssAutomaticGainControl> getGnssAutomaticGainControls() {
        return mGnssAgcs;
    }

    public static final @android.annotation.NonNull Creator<GnssMeasurementsEvent> CREATOR =
            new Creator<GnssMeasurementsEvent>() {
        @Override
        public GnssMeasurementsEvent createFromParcel(Parcel in) {
            GnssClock clock = in.readParcelable(getClass().getClassLoader(), android.location.GnssClock.class);
            List<GnssMeasurement> measurements = in.createTypedArrayList(GnssMeasurement.CREATOR);
            List<GnssAutomaticGainControl> agcs = in.createTypedArrayList(
                    GnssAutomaticGainControl.CREATOR);
            return new GnssMeasurementsEvent(clock, measurements, agcs);
        }

        @Override
        public GnssMeasurementsEvent[] newArray(int size) {
            return new GnssMeasurementsEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mClock, flags);
        parcel.writeTypedList(mMeasurements);
        parcel.writeTypedList(mGnssAgcs);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("GnssMeasurementsEvent[");
        builder.append(mClock);
        builder.append(' ').append(mMeasurements.toString());
        builder.append(' ').append(mGnssAgcs.toString());
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GnssMeasurementsEvent} */
    public static final class Builder {
        private GnssClock mClock;
        private List<GnssMeasurement> mMeasurements;
        private List<GnssAutomaticGainControl> mGnssAgcs;

        /**
         * Constructs a {@link GnssMeasurementsEvent.Builder} instance.
         */
        public Builder() {
            mClock = new GnssClock();
            mMeasurements = new ArrayList<>();
            mGnssAgcs = new ArrayList<>();
        }

        /**
         * Constructs a {@link GnssMeasurementsEvent.Builder} instance by copying a
         * {@link GnssMeasurementsEvent}.
         */
        public Builder(@NonNull GnssMeasurementsEvent event) {
            mClock = event.getClock();
            mMeasurements = (List<GnssMeasurement>) event.getMeasurements();
            mGnssAgcs = (List<GnssAutomaticGainControl>) event.getGnssAutomaticGainControls();
        }

        /**
         * Sets the {@link GnssClock}.
         */
        @NonNull
        public Builder setClock(@NonNull GnssClock clock) {
            Preconditions.checkNotNull(clock);
            mClock = clock;
            return this;
        }

        /**
         * Sets the collection of {@link GnssMeasurement}.
         *
         * This API exists for JNI since it is easier for JNI to work with an array than a
         * collection.
         * @hide
         */
        @NonNull
        public Builder setMeasurements(@Nullable GnssMeasurement... measurements) {
            mMeasurements = measurements == null ? Collections.emptyList() : Arrays.asList(
                    measurements);
            return this;
        }

        /**
         * Sets the collection of {@link GnssMeasurement}.
         */
        @NonNull
        public Builder setMeasurements(@NonNull Collection<GnssMeasurement> measurements) {
            mMeasurements = new ArrayList<>(measurements);
            return this;
        }

        /**
         * Sets the collection of {@link GnssAutomaticGainControl}.
         *
         * This API exists for JNI since it is easier for JNI to work with an array than a
         * collection.
         * @hide
         */
        @NonNull
        public Builder setGnssAutomaticGainControls(@Nullable GnssAutomaticGainControl... agcs) {
            mGnssAgcs = agcs == null ? Collections.emptyList() : Arrays.asList(agcs);
            return this;
        }

        /**
         * Sets the collection of {@link GnssAutomaticGainControl}.
         */
        @NonNull
        public Builder setGnssAutomaticGainControls(
                @NonNull Collection<GnssAutomaticGainControl> agcs) {
            mGnssAgcs = new ArrayList<>(agcs);
            return this;
        }

        /** Builds a {@link GnssMeasurementsEvent} instance as specified by this builder. */
        @NonNull
        public GnssMeasurementsEvent build() {
            return new GnssMeasurementsEvent(mClock, mMeasurements, mGnssAgcs);
        }
    }
}
