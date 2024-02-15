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


import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.hardware.Sensor;
import android.hardware.SensorDirectChannel;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;


/**
 * Configuration for creation of a virtual sensor.
 *
 * @see VirtualSensor
 *
 * @hide
 */
@SystemApi
public final class VirtualSensorConfig implements Parcelable {
    private static final String TAG = "VirtualSensorConfig";

    // Mask for direct mode highest rate level, bit 7, 8, 9.
    private static final int DIRECT_REPORT_MASK = 0x380;
    private static final int DIRECT_REPORT_SHIFT = 7;

    // Mask for supported direct channel, bit 10, 11
    private static final int DIRECT_CHANNEL_SHIFT = 10;

    private final int mType;
    @NonNull
    private final String mName;
    @Nullable
    private final String mVendor;
    private final float mMaximumRange;
    private final float mResolution;
    private final float mPower;
    private final int mMinDelay;
    private final int mMaxDelay;

    private final int mFlags;

    private VirtualSensorConfig(int type, @NonNull String name, @Nullable String vendor,
            float maximumRange, float resolution, float power, int minDelay, int maxDelay,
            int flags) {
        mType = type;
        mName = name;
        mVendor = vendor;
        mMaximumRange = maximumRange;
        mResolution = resolution;
        mPower = power;
        mMinDelay = minDelay;
        mMaxDelay = maxDelay;
        mFlags = flags;
    }

    private VirtualSensorConfig(@NonNull Parcel parcel) {
        mType = parcel.readInt();
        mName = parcel.readString8();
        mVendor = parcel.readString8();
        mMaximumRange = parcel.readFloat();
        mResolution = parcel.readFloat();
        mPower = parcel.readFloat();
        mMinDelay = parcel.readInt();
        mMaxDelay = parcel.readInt();
        mFlags = parcel.readInt();
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
        parcel.writeFloat(mMaximumRange);
        parcel.writeFloat(mResolution);
        parcel.writeFloat(mPower);
        parcel.writeInt(mMinDelay);
        parcel.writeInt(mMaxDelay);
        parcel.writeInt(mFlags);
    }

    @Override
    public String toString() {
        return "VirtualSensorConfig{" + "mType=" + mType + ", mName='" + mName + '\'' + '}';
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
     *
     * @see Builder#setVendor
     */
    @Nullable
    public String getVendor() {
        return mVendor;
    }

    /**
     * Returns the maximum range of the sensor in the sensor's unit.
     *
     * @see Sensor#getMaximumRange
     */
    public float getMaximumRange() {
        return mMaximumRange;
    }

    /**
     * Returns the resolution of the sensor in the sensor's unit.
     *
     * @see Sensor#getResolution
     */
    public float getResolution() {
        return mResolution;
    }

    /**
     * Returns the power in mA used by this sensor while in use.
     *
     * @see Sensor#getPower
     */
    public float getPower() {
        return mPower;
    }

    /**
     * Returns the minimum delay allowed between two events in microseconds, or zero depending on
     * the sensor type.
     *
     * @see Sensor#getMinDelay
     */
    public int getMinDelay() {
        return mMinDelay;
    }

    /**
     * Returns the maximum delay between two sensor events in microseconds.
     *
     * @see Sensor#getMaxDelay
     */
    public int getMaxDelay() {
        return mMaxDelay;
    }

    /**
     * Returns the highest supported direct report mode rate level of the sensor.
     *
     * @see Sensor#getHighestDirectReportRateLevel()
     */
    @SensorDirectChannel.RateLevel
    public int getHighestDirectReportRateLevel() {
        int rateLevel = ((mFlags & DIRECT_REPORT_MASK) >> DIRECT_REPORT_SHIFT);
        return rateLevel <= SensorDirectChannel.RATE_VERY_FAST
                ? rateLevel : SensorDirectChannel.RATE_VERY_FAST;
    }

    /**
     * Returns a combination of all supported direct channel types.
     *
     * @see Builder#setDirectChannelTypesSupported(int)
     * @see Sensor#isDirectChannelTypeSupported(int)
     */
    public @SensorDirectChannel.MemoryType int getDirectChannelTypesSupported() {
        int memoryTypes = 0;
        if ((mFlags & (1 << DIRECT_CHANNEL_SHIFT)) > 0) {
            memoryTypes |= SensorDirectChannel.TYPE_MEMORY_FILE;
        }
        if ((mFlags & (1 << (DIRECT_CHANNEL_SHIFT + 1))) > 0) {
            memoryTypes |= SensorDirectChannel.TYPE_HARDWARE_BUFFER;
        }
        return memoryTypes;
    }

    /**
     * Returns the sensor flags.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public int getFlags() {
        return mFlags;
    }

    /**
     * Builder for {@link VirtualSensorConfig}.
     */
    public static final class Builder {

        private static final int FLAG_MEMORY_FILE_DIRECT_CHANNEL_SUPPORTED =
                1 << DIRECT_CHANNEL_SHIFT;
        private final int mType;
        @NonNull
        private final String mName;
        @Nullable
        private String mVendor;
        private float mMaximumRange;
        private float mResolution;
        private float mPower;
        private int mMinDelay;
        private int mMaxDelay;
        private int mFlags;
        @SensorDirectChannel.RateLevel
        int mHighestDirectReportRateLevel;

        /**
         * Creates a new builder.
         *
         * @param type The type of the sensor, matching {@link Sensor#getType}.
         * @param name The name of the sensor. Must be unique among all sensors with the same type
         *   that belong to the same virtual device.
         */
        public Builder(@IntRange(from = 1) int type, @NonNull String name) {
            if (type <= 0) {
                throw new IllegalArgumentException("Virtual sensor type must be positive");
            }
            mType = type;
            mName = Objects.requireNonNull(name);
        }

        /**
         * Creates a new {@link VirtualSensorConfig}.
         */
        @NonNull
        public VirtualSensorConfig build() {
            if (mHighestDirectReportRateLevel > 0) {
                if ((mFlags & FLAG_MEMORY_FILE_DIRECT_CHANNEL_SUPPORTED) == 0) {
                    throw new IllegalArgumentException("Setting direct channel type is required "
                            + "for sensors with direct channel support.");
                }
                mFlags |= mHighestDirectReportRateLevel << DIRECT_REPORT_SHIFT;
            }
            if ((mFlags & FLAG_MEMORY_FILE_DIRECT_CHANNEL_SUPPORTED) > 0
                    && mHighestDirectReportRateLevel == 0) {
                throw new IllegalArgumentException("Highest direct report rate level is "
                        + "required for sensors with direct channel support.");
            }
            return new VirtualSensorConfig(mType, mName, mVendor, mMaximumRange, mResolution,
                    mPower, mMinDelay, mMaxDelay, mFlags);
        }

        /**
         * Sets the vendor string of the sensor.
         */
        @NonNull
        public VirtualSensorConfig.Builder setVendor(@Nullable String vendor) {
            mVendor = vendor;
            return this;
        }

        /**
         * Sets the maximum range of the sensor in the sensor's unit.
         *
         * @see Sensor#getMaximumRange
         */
        @NonNull
        public VirtualSensorConfig.Builder setMaximumRange(float maximumRange) {
            mMaximumRange = maximumRange;
            return this;
        }

        /**
         * Sets the resolution of the sensor in the sensor's unit.
         *
         * @see Sensor#getResolution
         */
        @NonNull
        public VirtualSensorConfig.Builder setResolution(float resolution) {
            mResolution = resolution;
            return this;
        }

        /**
         * Sets the power in mA used by this sensor while in use.
         *
         * @see Sensor#getPower
         */
        @NonNull
        public VirtualSensorConfig.Builder setPower(float power) {
            mPower = power;
            return this;
        }

        /**
         * Sets the minimum delay allowed between two events in microseconds.
         *
         * @see Sensor#getMinDelay
         */
        @NonNull
        public VirtualSensorConfig.Builder setMinDelay(int minDelay) {
            mMinDelay = minDelay;
            return this;
        }

        /**
         * Sets the maximum delay between two sensor events in microseconds.
         *
         * @see Sensor#getMaxDelay
         */
        @NonNull
        public VirtualSensorConfig.Builder setMaxDelay(int maxDelay) {
            mMaxDelay = maxDelay;
            return this;
        }

        /**
         * Sets the highest supported rate level for direct sensor report.
         *
         * @see VirtualSensorConfig#getHighestDirectReportRateLevel()
         */
        @NonNull
        public VirtualSensorConfig.Builder setHighestDirectReportRateLevel(
                @SensorDirectChannel.RateLevel int rateLevel) {
            mHighestDirectReportRateLevel = rateLevel;
            return this;
        }

        /**
         * Sets whether direct sensor channel of the given types is supported.
         *
         * @param memoryTypes A combination of {@link SensorDirectChannel.MemoryType} flags
         *   indicating the types of shared memory supported for creating direct channels. Only
         *   {@link SensorDirectChannel#TYPE_MEMORY_FILE} direct channels may be supported for
         *   virtual sensors.
         * @throws IllegalArgumentException if {@link SensorDirectChannel#TYPE_HARDWARE_BUFFER} is
         *   set to be supported.
         */
        @NonNull
        public VirtualSensorConfig.Builder setDirectChannelTypesSupported(
                @SensorDirectChannel.MemoryType int memoryTypes) {
            if ((memoryTypes & SensorDirectChannel.TYPE_MEMORY_FILE) > 0) {
                mFlags |= FLAG_MEMORY_FILE_DIRECT_CHANNEL_SUPPORTED;
            } else {
                mFlags &= ~FLAG_MEMORY_FILE_DIRECT_CHANNEL_SUPPORTED;
            }
            if ((memoryTypes & ~SensorDirectChannel.TYPE_MEMORY_FILE) > 0) {
                throw new IllegalArgumentException(
                        "Only TYPE_MEMORY_FILE direct channels can be supported for virtual "
                                + "sensors.");
            }
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
