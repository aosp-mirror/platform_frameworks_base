/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Data about the current brightness state.
 * {@see android.view.Display.getBrightnessInfo()}
 *
 * @hide
 */
public final class BrightnessInfo implements Parcelable {

    @IntDef(prefix = {"HIGH_BRIGHTNESS_MODE_"}, value = {
            HIGH_BRIGHTNESS_MODE_OFF,
            HIGH_BRIGHTNESS_MODE_SUNLIGHT,
            HIGH_BRIGHTNESS_MODE_HDR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HighBrightnessMode {}

    /**
     * High brightness mode is OFF. The high brightness range is not currently accessible to the
     * user.
     */
    public static final int HIGH_BRIGHTNESS_MODE_OFF = 0;

    /**
     * High brightness mode is ON due to high ambient light (sunlight). The high brightness range is
     * currently accessible to the user.
     */
    public static final int HIGH_BRIGHTNESS_MODE_SUNLIGHT = 1;

    /**
     * High brightness mode is ON due to high ambient light (sunlight). The high brightness range is
     * currently accessible to the user.
     */
    public static final int HIGH_BRIGHTNESS_MODE_HDR = 2;

    /** Brightness */
    public final float brightness;

    /** Current minimum supported brightness. */
    public final float brightnessMinimum;

    /** Current maximum supported brightness. */
    public final float brightnessMaximum;

    /**
     * Current state of high brightness mode.
     * Can be any of HIGH_BRIGHTNESS_MODE_* values.
     */
    public final int highBrightnessMode;

    public BrightnessInfo(float brightness, float brightnessMinimum, float brightnessMaximum,
            @HighBrightnessMode int highBrightnessMode) {
        this.brightness = brightness;
        this.brightnessMinimum = brightnessMinimum;
        this.brightnessMaximum = brightnessMaximum;
        this.highBrightnessMode = highBrightnessMode;
    }

    /**
     * @return User-friendly string for specified {@link HighBrightnessMode} parameter.
     */
    public static String hbmToString(@HighBrightnessMode int highBrightnessMode) {
        switch (highBrightnessMode) {
            case HIGH_BRIGHTNESS_MODE_OFF:
                return "off";
            case HIGH_BRIGHTNESS_MODE_HDR:
                return "hdr";
            case HIGH_BRIGHTNESS_MODE_SUNLIGHT:
                return "sunlight";
        }
        return "invalid";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(brightness);
        dest.writeFloat(brightnessMinimum);
        dest.writeFloat(brightnessMaximum);
        dest.writeInt(highBrightnessMode);
    }

    public static final @android.annotation.NonNull Creator<BrightnessInfo> CREATOR =
            new Creator<BrightnessInfo>() {
                @Override
                public BrightnessInfo createFromParcel(Parcel source) {
                    return new BrightnessInfo(source);
                }

                @Override
                public BrightnessInfo[] newArray(int size) {
                    return new BrightnessInfo[size];
                }
            };

    private BrightnessInfo(Parcel source) {
        brightness = source.readFloat();
        brightnessMinimum = source.readFloat();
        brightnessMaximum = source.readFloat();
        highBrightnessMode = source.readInt();
    }

}
