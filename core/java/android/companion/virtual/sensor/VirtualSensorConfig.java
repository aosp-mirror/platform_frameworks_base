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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.Sensor;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Configuration for creation of a virtual sensor.
 * @see VirtualSensor
 * @hide
 */
@SystemApi
public final class VirtualSensorConfig implements Parcelable {

    private final int mType;
    @NonNull
    private final String mName;
    @Nullable
    private final String mVendor;

    private VirtualSensorConfig(int type, @NonNull String name, @Nullable String vendor) {
        mType = type;
        mName = name;
        mVendor = vendor;
    }

    private VirtualSensorConfig(@NonNull Parcel parcel) {
        mType = parcel.readInt();
        mName = parcel.readString8();
        mVendor = parcel.readString8();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeString8(mName);
        parcel.writeString8(mVendor);
    }

    /**
     * Returns the type of the sensor.
     *
     * @see Sensor#getType()
     * @see <a href="https://source.android.com/devices/sensors/sensor-types">Sensor types</a>
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns the name of the sensor, which must be unique per sensor type for each virtual device.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the vendor string of the sensor.
     * @see Builder#setVendor
     */
    @Nullable
    public String getVendor() {
        return mVendor;
    }

    /**
     * Builder for {@link VirtualSensorConfig}.
     */
    public static final class Builder {

        private final int mType;
        @NonNull
        private final String mName;
        @Nullable
        private String mVendor;

        /**
         * Creates a new builder.
         *
         * @param type The type of the sensor, matching {@link Sensor#getType}.
         * @param name The name of the sensor. Must be unique among all sensors with the same type
         * that belong to the same virtual device.
         */
        public Builder(int type, @NonNull String name) {
            mType = type;
            mName = Objects.requireNonNull(name);
        }

        /**
         * Creates a new {@link VirtualSensorConfig}.
         */
        @NonNull
        public VirtualSensorConfig build() {
            return new VirtualSensorConfig(mType, mName, mVendor);
        }

        /**
         * Sets the vendor string of the sensor.
         */
        @NonNull
        public VirtualSensorConfig.Builder setVendor(@Nullable String vendor) {
            mVendor = vendor;
            return this;
        }
    }

    @NonNull
    public static final Parcelable.Creator<VirtualSensorConfig> CREATOR =
            new Parcelable.Creator<>() {
                public VirtualSensorConfig createFromParcel(Parcel source) {
                    return new VirtualSensorConfig(source);
                }

                public VirtualSensorConfig[] newArray(int size) {
                    return new VirtualSensorConfig[size];
                }
            };
}
