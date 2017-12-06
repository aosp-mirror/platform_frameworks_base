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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data about a brightness settings change.
 *
 * {@see DisplayManager.getBrightnessEvents()}
 * TODO make this SystemAPI
 * @hide
 */
public final class BrightnessChangeEvent implements Parcelable {
    /** Brightness in nits */
    public int brightness;

    /** Timestamp of the change {@see System.currentTimeMillis()} */
    public long timeStamp;

    /** Package name of focused activity when brightness was changed.
     *  This will be null if the caller of {@see DisplayManager.getBrightnessEvents()}
     *  does not have access to usage stats {@see UsageStatsManager} */
    public String packageName;

    /** User id of of the user running when brightness was changed.
     * @hide */
    public int userId;

    /** Lux values of recent sensor data */
    public float[] luxValues;

    /** Timestamps of the lux sensor readings {@see System.currentTimeMillis()} */
    public long[] luxTimestamps;

    /** Most recent battery level when brightness was changed or Float.NaN */
    public float batteryLevel;

    /** Color filter active to provide night mode */
    public boolean nightMode;

    /** If night mode color filter is active this will be the temperature in kelvin */
    public int colorTemperature;

    /** Brightness level before slider adjustment */
    public int lastBrightness;

    public BrightnessChangeEvent() {
    }

    /** @hide */
    public BrightnessChangeEvent(BrightnessChangeEvent other) {
        this.brightness = other.brightness;
        this.timeStamp = other.timeStamp;
        this.packageName = other.packageName;
        this.userId = other.userId;
        this.luxValues = other.luxValues;
        this.luxTimestamps = other.luxTimestamps;
        this.batteryLevel = other.batteryLevel;
        this.nightMode = other.nightMode;
        this.colorTemperature = other.colorTemperature;
        this.lastBrightness = other.lastBrightness;
    }

    private BrightnessChangeEvent(Parcel source) {
        brightness = source.readInt();
        timeStamp = source.readLong();
        packageName = source.readString();
        userId = source.readInt();
        luxValues = source.createFloatArray();
        luxTimestamps = source.createLongArray();
        batteryLevel = source.readFloat();
        nightMode = source.readBoolean();
        colorTemperature = source.readInt();
        lastBrightness = source.readInt();
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
        dest.writeInt(brightness);
        dest.writeLong(timeStamp);
        dest.writeString(packageName);
        dest.writeInt(userId);
        dest.writeFloatArray(luxValues);
        dest.writeLongArray(luxTimestamps);
        dest.writeFloat(batteryLevel);
        dest.writeBoolean(nightMode);
        dest.writeInt(colorTemperature);
        dest.writeInt(lastBrightness);
    }
}
