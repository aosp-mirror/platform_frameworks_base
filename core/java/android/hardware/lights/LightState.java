/**
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

package android.hardware.lights;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the state of a device light.
 *
 * <p>Controlling the color and brightness of a light is done on a best-effort basis. Each of the R,
 * G and B channels represent the intensities of the respective part of an RGB LED, if that is
 * supported. For devices that only support on or off lights, everything that's not off will turn
 * the light on. If the light is monochrome and only the brightness can be controlled, the RGB color
 * will be converted to only a brightness value and that will be used for the light's single
 * channel.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class LightState implements Parcelable {
    private final int mColor;

    /**
     * Creates a new LightState with the desired color and intensity.
     *
     * @param color the desired color and intensity in ARGB format.
     */
    public LightState(@ColorInt int color) {
        mColor = color;
    }

    private LightState(@NonNull Parcel in) {
        mColor = in.readInt();
    }

    /**
     * Return the color and intensity associated with this LightState.
     * @return the color and intensity in ARGB format. The A channel is ignored.
     */
    public @ColorInt int getColor() {
        return mColor;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mColor);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<LightState> CREATOR =
            new Parcelable.Creator<LightState>() {
                public LightState createFromParcel(Parcel in) {
                    return new LightState(in);
                }

                public LightState[] newArray(int size) {
                    return new LightState[size];
                }
            };
}
