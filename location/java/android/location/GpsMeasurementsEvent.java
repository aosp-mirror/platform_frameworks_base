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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * A class implementing a container for data associated with a measurement event.
 * Events are delivered to registered instances of {@link Listener}.
 *
 * @hide
 */
public class GpsMeasurementsEvent implements Parcelable {
    private final GpsClock mClock;
    private final Collection<GpsMeasurement> mReadOnlyMeasurements;

    /**
     * Used for receiving GPS satellite measurements from the GPS engine.
     * Each measurement contains raw and computed data identifying a satellite.
     * You can implement this interface and call {@link LocationManager#addGpsMeasurementListener}.
     *
     * @hide
     */
    public interface Listener {
        void onGpsMeasurementsReceived(GpsMeasurementsEvent eventArgs);
    }

    public GpsMeasurementsEvent(GpsClock clock, GpsMeasurement[] measurements) {
        if (clock == null) {
            throw new InvalidParameterException("Parameter 'clock' must not be null.");
        }
        if (measurements == null || measurements.length == 0) {
            throw new InvalidParameterException(
                    "Parameter 'measurements' must not be null or empty.");
        }

        mClock = clock;
        Collection<GpsMeasurement> measurementCollection = Arrays.asList(measurements);
        mReadOnlyMeasurements = Collections.unmodifiableCollection(measurementCollection);
    }

    @NonNull
    public GpsClock getClock() {
        return mClock;
    }

    /**
     * Gets a read-only collection of measurements associated with the current event.
     */
    @NonNull
    public Collection<GpsMeasurement> getMeasurements() {
        return mReadOnlyMeasurements;
    }

    public static final Creator<GpsMeasurementsEvent> CREATOR =
            new Creator<GpsMeasurementsEvent>() {
        @Override
        public GpsMeasurementsEvent createFromParcel(Parcel in) {
            ClassLoader classLoader = getClass().getClassLoader();

            GpsClock clock = in.readParcelable(classLoader);

            int measurementsLength = in.readInt();
            GpsMeasurement[] measurementsArray = new GpsMeasurement[measurementsLength];
            in.readTypedArray(measurementsArray, GpsMeasurement.CREATOR);

            return new GpsMeasurementsEvent(clock, measurementsArray);
        }

        @Override
        public GpsMeasurementsEvent[] newArray(int size) {
            return new GpsMeasurementsEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mClock, flags);

        GpsMeasurement[] measurementsArray = mReadOnlyMeasurements.toArray(new GpsMeasurement[0]);
        parcel.writeInt(measurementsArray.length);
        parcel.writeTypedArray(measurementsArray, flags);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ GpsMeasurementsEvent:\n\n");

        builder.append(mClock.toString());
        builder.append("\n");

        for (GpsMeasurement measurement : mReadOnlyMeasurements) {
            builder.append(measurement.toString());
            builder.append("\n");
        }

        builder.append("]");

        return builder.toString();
    }
}
