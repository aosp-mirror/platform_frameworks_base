/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.sensor;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.Sensor;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;


/**
 * A sensor event that originated from a virtual device's sensor.
 *
 * @hide
 */
@SystemApi
public final class VirtualSensorEvent implements Parcelable {

    @NonNull
    private float[] mValues;
    private long mTimestampNanos;

    private VirtualSensorEvent(@NonNull float[] values, long timestampNanos) {
        mValues = values;
        mTimestampNanos = timestampNanos;
    }

    private VirtualSensorEvent(@NonNull Parcel parcel) {
        final int valuesLength = parcel.readInt();
        mValues = new float[valuesLength];
        parcel.readFloatArray(mValues);
        mTimestampNanos = parcel.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeInt(mValues.length);
        parcel.writeFloatArray(mValues);
        parcel.writeLong(mTimestampNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the values of this sensor event. The length and contents depend on the sensor type.
     *
     * @see android.hardware.SensorEvent#values
     * @see Sensor#getType()
     * @see <a href="https://source.android.com/devices/sensors/sensor-types">Sensor types</a>
     */
    @NonNull
    public float[] getValues() {
        return mValues;
    }

    /**
     * The time in nanoseconds at which the event happened. For a given sensor, each new sensor
     * event should be monotonically increasing.
     *
     * @see Builder#setTimestampNanos(long)
     */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    /**
     * Builder for {@link VirtualSensorEvent}.
     */
    public static final class Builder {

        @NonNull
        private float[] mValues;
        private long mTimestampNanos = 0;

        /**
         * Creates a new builder.
         *
         * @param values the values of the sensor event.
         * @see android.hardware.SensorEvent#values
         */
        public Builder(@NonNull float[] values) {
            mValues = values;
        }

        /**
         * Creates a new {@link VirtualSensorEvent}.
         */
        @NonNull
        public VirtualSensorEvent build() {
            if (mValues == null || mValues.length == 0) {
                throw new IllegalArgumentException(
                        "Cannot build virtual sensor event with no values.");
            }
            if (mTimestampNanos <= 0) {
                mTimestampNanos = SystemClock.elapsedRealtimeNanos();
            }
            return new VirtualSensorEvent(mValues, mTimestampNanos);
        }

        /**
         * Sets the timestamp of this event. For a given sensor, each new sensor event should be
         * monotonically increasing using the same time base as
         * {@link android.os.SystemClock#elapsedRealtimeNanos()}.
         *
         * <p>If not explicitly set, the current timestamp is used for the sensor event.
         *
         * @see android.hardware.SensorEvent#timestamp
         */
        @NonNull
        public Builder setTimestampNanos(long timestampNanos) {
            mTimestampNanos = timestampNanos;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualSensorEvent> CREATOR =
            new Parcelable.Creator<>() {
                public VirtualSensorEvent createFromParcel(Parcel source) {
                    return new VirtualSensorEvent(source);
                }

                public VirtualSensorEvent[] newArray(int size) {
                    return new VirtualSensorEvent[size];
                }
            };
}
