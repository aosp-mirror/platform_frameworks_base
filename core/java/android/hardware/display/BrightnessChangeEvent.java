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

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data about a brightness settings change.
 *
 * {@see DisplayManager.getBrightnessEvents()}
 * @hide
 */
@SystemApi
@TestApi
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

    /** Lux values of recent sensor data */
    public final float[] luxValues;

    /** Timestamps of the lux sensor readings {@see System.currentTimeMillis()} */
    public final long[] luxTimestamps;

    /** Most recent battery level when brightness was changed or Float.NaN */
    public final float batteryLevel;

    /** Color filter active to provide night mode */
    public final boolean nightMode;

    /** If night mode color filter is active this will be the temperature in kelvin */
    public final int colorTemperature;

    /** Brightness le vel before slider adjustment */
    public final float lastBrightness;

    /** @hide */
    private BrightnessChangeEvent(float brightness, long timeStamp, String packageName,
            int userId, float[] luxValues, long[] luxTimestamps, float batteryLevel,
            boolean nightMode, int colorTemperature, float lastBrightness) {
        this.brightness = brightness;
        this.timeStamp = timeStamp;
        this.packageName = packageName;
        this.userId = userId;
        this.luxValues = luxValues;
        this.luxTimestamps = luxTimestamps;
        this.batteryLevel = batteryLevel;
        this.nightMode = nightMode;
        this.colorTemperature = colorTemperature;
        this.lastBrightness = lastBrightness;
    }

    /** @hide */
    public BrightnessChangeEvent(BrightnessChangeEvent other, boolean redactPackage) {
        this.brightness = other.brightness;
        this.timeStamp = other.timeStamp;
        this.packageName = redactPackage ? null : other.packageName;
        this.userId = other.userId;
        this.luxValues = other.luxValues;
        this.luxTimestamps = other.luxTimestamps;
        this.batteryLevel = other.batteryLevel;
        this.nightMode = other.nightMode;
        this.colorTemperature = other.colorTemperature;
        this.lastBrightness = other.lastBrightness;
    }

    private BrightnessChangeEvent(Parcel source) {
        brightness = source.readFloat();
        timeStamp = source.readLong();
        packageName = source.readString();
        userId = source.readInt();
        luxValues = source.createFloatArray();
        luxTimestamps = source.createLongArray();
        batteryLevel = source.readFloat();
        nightMode = source.readBoolean();
        colorTemperature = source.readInt();
        lastBrightness = source.readFloat();
    }

    public static final Creator<BrightnessChangeEvent> CREATOR =
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
        dest.writeFloatArray(luxValues);
        dest.writeLongArray(luxTimestamps);
        dest.writeFloat(batteryLevel);
        dest.writeBoolean(nightMode);
        dest.writeInt(colorTemperature);
        dest.writeFloat(lastBrightness);
    }

    /** @hide */
    public static class Builder {
        private float mBrightness;
        private long mTimeStamp;
        private String mPackageName;
        private int mUserId;
        private float[] mLuxValues;
        private long[] mLuxTimestamps;
        private float mBatteryLevel;
        private boolean mNightMode;
        private int mColorTemperature;
        private float mLastBrightness;

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

        /** {@see BrightnessChangeEvent#lastBrightness} */
        public Builder setLastBrightness(float lastBrightness) {
            mLastBrightness = lastBrightness;
            return this;
        }

        /** Builds a BrightnessChangeEvent */
        public BrightnessChangeEvent build() {
            return new BrightnessChangeEvent(mBrightness, mTimeStamp,
                    mPackageName, mUserId, mLuxValues, mLuxTimestamps, mBatteryLevel,
                    mNightMode, mColorTemperature, mLastBrightness);
        }
    }
}
