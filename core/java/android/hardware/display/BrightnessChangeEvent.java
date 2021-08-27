/*
 * Copyright 2017 The Android Open Source Project
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

package android.hardware.display;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data about a brightness settings change.
 *
 * {@see DisplayManager.getBrightnessEvents()}
 * @hide
 */
@SystemApi
public final class BrightnessChangeEvent implements Parcelable {
    /** Brightness in nits */
    public final float brightness;

    /** Timestamp of the change {@see System.currentTimeMillis()} */
    public final long timeStamp;

    /** Package name of focused activity when brightness was changed.
     *  This will be null if the caller of {@see DisplayManager.getBrightnessEvents()}
     *  does not have access to usage stats {@see UsageStatsManager} */
    public final String packageName;

    /** User id of of the user running when brightness was changed.
     * @hide */
    public final int userId;

    /** The unique id of the screen on which the brightness was changed */
    @NonNull
    public final String uniqueDisplayId;

    /** Lux values of recent sensor data */
    public final float[] luxValues;

    /** Timestamps of the lux sensor readings {@see System.currentTimeMillis()} */
    public final long[] luxTimestamps;

    /** Most recent battery level when brightness was changed or Float.NaN */
    public final float batteryLevel;

    /** Factor applied to brightness due to battery level, 0.0-1.0 */
    public final float powerBrightnessFactor;

    /** Color filter active to provide night mode */
    public final boolean nightMode;

    /** If night mode color filter is active this will be the temperature in kelvin */
    public final int colorTemperature;

    /** Whether the bright color reduction color transform is active */
    public final boolean reduceBrightColors;

    /** How strong the bright color reduction color transform is set (only applicable if active),
     *  specified as an integer from 0 - 100, inclusive. This value (scaled to 0-1, inclusive) is
     *  then used in Ynew = (a * scaledStrength^2 + b * scaledStrength + c) * Ycurrent, where a, b,
     *  and c are coefficients provided in the bright color reduction coefficient matrix, and
     *  Ycurrent is the current hardware brightness in nits.
     */
    public final int reduceBrightColorsStrength;

    /** Applied offset for the bright color reduction color transform (only applicable if active).
     *  The offset is computed by summing the coefficients a, b, and c, from the coefficient matrix
     *  and multiplying by the current brightness.
     */
    public final float reduceBrightColorsOffset;

    /** Brightness level before slider adjustment */
    public final float lastBrightness;

    /** Whether brightness configuration is default version */
    public final boolean isDefaultBrightnessConfig;

    /** Whether brightness curve includes a user brightness point */
    public final boolean isUserSetBrightness;

    /**
     * Histogram counting how many times a pixel of a given value was displayed onscreen for the
     * Value component of HSV if the device supports color sampling, if the device does not support
     * color sampling or {@link BrightnessConfiguration#shouldCollectColorSamples()} is false the
     * value will be null.
     *
     * The buckets of the histogram are evenly weighted, the number of buckets is device specific.
     * The units are in pixels * milliseconds, with 1 pixel millisecond being 1 pixel displayed
     * for 1 millisecond.
     * For example if we had {100, 50, 30, 20}, value component was onscreen for 100 pixel
     * milliseconds in range 0x00->0x3F, 30 pixel milliseconds in range 0x40->0x7F, etc.
     *
     * {@see #colorSampleDuration}
     */
    @Nullable
    public final long[] colorValueBuckets;

    /**
     * How many milliseconds of data are contained in the colorValueBuckets, if the device does
     * not support color sampling or {@link BrightnessConfiguration#shouldCollectColorSamples()} is
     * false the value will be 0L.
     *
     * {@see #colorValueBuckets}
     */
    public final long colorSampleDuration;


    /** @hide */
    private BrightnessChangeEvent(float brightness, long timeStamp, String packageName,
            int userId, String uniqueDisplayId, float[] luxValues, long[] luxTimestamps,
            float batteryLevel, float powerBrightnessFactor, boolean nightMode,
            int colorTemperature, boolean reduceBrightColors, int reduceBrightColorsStrength,
            float reduceBrightColorsOffset, float lastBrightness, boolean isDefaultBrightnessConfig,
            boolean isUserSetBrightness, long[] colorValueBuckets, long colorSampleDuration) {
        this.brightness = brightness;
        this.timeStamp = timeStamp;
        this.packageName = packageName;
        this.userId = userId;
        this.uniqueDisplayId = uniqueDisplayId;
        this.luxValues = luxValues;
        this.luxTimestamps = luxTimestamps;
        this.batteryLevel = batteryLevel;
        this.powerBrightnessFactor = powerBrightnessFactor;
        this.nightMode = nightMode;
        this.colorTemperature = colorTemperature;
        this.reduceBrightColors = reduceBrightColors;
        this.reduceBrightColorsStrength = reduceBrightColorsStrength;
        this.reduceBrightColorsOffset = reduceBrightColorsOffset;
        this.lastBrightness = lastBrightness;
        this.isDefaultBrightnessConfig = isDefaultBrightnessConfig;
        this.isUserSetBrightness = isUserSetBrightness;
        this.colorValueBuckets = colorValueBuckets;
        this.colorSampleDuration = colorSampleDuration;
    }

    /** @hide */
    public BrightnessChangeEvent(BrightnessChangeEvent other, boolean redactPackage) {
        this.brightness = other.brightness;
        this.timeStamp = other.timeStamp;
        this.packageName = redactPackage ? null : other.packageName;
        this.userId = other.userId;
        this.uniqueDisplayId = other.uniqueDisplayId;
        this.luxValues = other.luxValues;
        this.luxTimestamps = other.luxTimestamps;
        this.batteryLevel = other.batteryLevel;
        this.powerBrightnessFactor = other.powerBrightnessFactor;
        this.nightMode = other.nightMode;
        this.colorTemperature = other.colorTemperature;
        this.reduceBrightColors = other.reduceBrightColors;
        this.reduceBrightColorsStrength = other.reduceBrightColorsStrength;
        this.reduceBrightColorsOffset = other.reduceBrightColorsOffset;
        this.lastBrightness = other.lastBrightness;
        this.isDefaultBrightnessConfig = other.isDefaultBrightnessConfig;
        this.isUserSetBrightness = other.isUserSetBrightness;
        this.colorValueBuckets = other.colorValueBuckets;
        this.colorSampleDuration = other.colorSampleDuration;
    }

    private BrightnessChangeEvent(Parcel source) {
        brightness = source.readFloat();
        timeStamp = source.readLong();
        packageName = source.readString();
        userId = source.readInt();
        uniqueDisplayId = source.readString();
        luxValues = source.createFloatArray();
        luxTimestamps = source.createLongArray();
        batteryLevel = source.readFloat();
        powerBrightnessFactor = source.readFloat();
        nightMode = source.readBoolean();
        colorTemperature = source.readInt();
        reduceBrightColors = source.readBoolean();
        reduceBrightColorsStrength = source.readInt();
        reduceBrightColorsOffset = source.readFloat();
        lastBrightness = source.readFloat();
        isDefaultBrightnessConfig = source.readBoolean();
        isUserSetBrightness = source.readBoolean();
        colorValueBuckets = source.createLongArray();
        colorSampleDuration = source.readLong();
    }

    public static final @android.annotation.NonNull Creator<BrightnessChangeEvent> CREATOR =
            new Creator<BrightnessChangeEvent>() {
                public BrightnessChangeEvent createFromParcel(Parcel source) {
                    return new BrightnessChangeEvent(source);
                }
                public BrightnessChangeEvent[] newArray(int size) {
                    return new BrightnessChangeEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(brightness);
        dest.writeLong(timeStamp);
        dest.writeString(packageName);
        dest.writeInt(userId);
        dest.writeString(uniqueDisplayId);
        dest.writeFloatArray(luxValues);
        dest.writeLongArray(luxTimestamps);
        dest.writeFloat(batteryLevel);
        dest.writeFloat(powerBrightnessFactor);
        dest.writeBoolean(nightMode);
        dest.writeInt(colorTemperature);
        dest.writeBoolean(reduceBrightColors);
        dest.writeInt(reduceBrightColorsStrength);
        dest.writeFloat(reduceBrightColorsOffset);
        dest.writeFloat(lastBrightness);
        dest.writeBoolean(isDefaultBrightnessConfig);
        dest.writeBoolean(isUserSetBrightness);
        dest.writeLongArray(colorValueBuckets);
        dest.writeLong(colorSampleDuration);
    }

    /** @hide */
    public static class Builder {
        private float mBrightness;
        private long mTimeStamp;
        private String mPackageName;
        private int mUserId;
        private String mUniqueDisplayId;
        private float[] mLuxValues;
        private long[] mLuxTimestamps;
        private float mBatteryLevel;
        private float mPowerBrightnessFactor;
        private boolean mNightMode;
        private int mColorTemperature;
        private boolean mReduceBrightColors;
        private int mReduceBrightColorsStrength;
        private float mReduceBrightColorsOffset;
        private float mLastBrightness;
        private boolean mIsDefaultBrightnessConfig;
        private boolean mIsUserSetBrightness;
        private long[] mColorValueBuckets;
        private long mColorSampleDuration;

        /** {@see BrightnessChangeEvent#brightness} */
        public Builder setBrightness(float brightness) {
            mBrightness = brightness;
            return this;
        }

        /** {@see BrightnessChangeEvent#timeStamp} */
        public Builder setTimeStamp(long timeStamp) {
            mTimeStamp = timeStamp;
            return this;
        }

        /** {@see BrightnessChangeEvent#packageName} */
        public Builder setPackageName(String packageName) {
            mPackageName = packageName;
            return this;
        }

        /** {@see BrightnessChangeEvent#userId} */
        public Builder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        /** {@see BrightnessChangeEvent#uniqueScreenId} */
        public Builder setUniqueDisplayId(String uniqueId) {
            mUniqueDisplayId = uniqueId;
            return this;
        }

        /** {@see BrightnessChangeEvent#luxValues} */
        public Builder setLuxValues(float[] luxValues) {
            mLuxValues = luxValues;
            return this;
        }

        /** {@see BrightnessChangeEvent#luxTimestamps} */
        public Builder setLuxTimestamps(long[] luxTimestamps) {
            mLuxTimestamps = luxTimestamps;
            return this;
        }

        /** {@see BrightnessChangeEvent#batteryLevel} */
        public Builder setBatteryLevel(float batteryLevel) {
            mBatteryLevel = batteryLevel;
            return this;
        }

        /** {@see BrightnessChangeEvent#powerSaveBrightness} */
        public Builder setPowerBrightnessFactor(float powerBrightnessFactor) {
            mPowerBrightnessFactor = powerBrightnessFactor;
            return this;
        }

        /** {@see BrightnessChangeEvent#nightMode} */
        public Builder setNightMode(boolean nightMode) {
            mNightMode = nightMode;
            return this;
        }

        /** {@see BrightnessChangeEvent#colorTemperature} */
        public Builder setColorTemperature(int colorTemperature) {
            mColorTemperature = colorTemperature;
            return this;
        }

        /** {@see BrightnessChangeEvent#reduceBrightColors} */
        public Builder setReduceBrightColors(boolean reduceBrightColors) {
            mReduceBrightColors = reduceBrightColors;
            return this;
        }

        /** {@see BrightnessChangeEvent#reduceBrightColorsStrength} */
        public Builder setReduceBrightColorsStrength(int strength) {
            mReduceBrightColorsStrength = strength;
            return this;
        }

        /** {@see BrightnessChangeEvent#reduceBrightColorsOffset} */
        public Builder setReduceBrightColorsOffset(float offset) {
            mReduceBrightColorsOffset = offset;
            return this;
        }

        /** {@see BrightnessChangeEvent#lastBrightness} */
        public Builder setLastBrightness(float lastBrightness) {
            mLastBrightness = lastBrightness;
            return this;
        }

        /** {@see BrightnessChangeEvent#isDefaultBrightnessConfig} */
        public Builder setIsDefaultBrightnessConfig(boolean isDefaultBrightnessConfig) {
            mIsDefaultBrightnessConfig = isDefaultBrightnessConfig;
            return this;
        }

        /** {@see BrightnessChangeEvent#userBrightnessPoint} */
        public Builder setUserBrightnessPoint(boolean isUserSetBrightness) {
            mIsUserSetBrightness = isUserSetBrightness;
            return this;
        }

        /** {@see BrightnessChangeEvent#colorValueBuckets}
         *  {@see BrightnessChangeEvent#colorSampleDuration} */
        public Builder setColorValues(@NonNull long[] colorValueBuckets, long colorSampleDuration) {
            Objects.requireNonNull(colorValueBuckets);
            mColorValueBuckets = colorValueBuckets;
            mColorSampleDuration = colorSampleDuration;
            return this;
        }

        /** Builds a BrightnessChangeEvent */
        public BrightnessChangeEvent build() {
            return new BrightnessChangeEvent(mBrightness, mTimeStamp,
                    mPackageName, mUserId, mUniqueDisplayId, mLuxValues, mLuxTimestamps,
                    mBatteryLevel, mPowerBrightnessFactor, mNightMode, mColorTemperature,
                    mReduceBrightColors, mReduceBrightColorsStrength, mReduceBrightColorsOffset,
                    mLastBrightness, mIsDefaultBrightnessConfig, mIsUserSetBrightness,
                    mColorValueBuckets, mColorSampleDuration);
        }
    }
}
