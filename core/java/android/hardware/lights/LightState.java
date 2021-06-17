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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
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
 */
public final class LightState implements Parcelable {
    private final int mColor;
    private final int mPlayerId;

    /**
     * Creates a new LightState with the desired color and intensity, for a light type
     * of RBG color or monochrome color.
     *
     * @param color the desired color and intensity in ARGB format.
     * @deprecated this has been replaced with {@link android.hardware.lights.LightState.Builder }
     * @hide
     */
    @Deprecated
    @SystemApi
    public LightState(@ColorInt int color) {
        this(color, 0);
    }

    /**
     * Creates a new LightState with the desired color and intensity, and the player Id.
     * Player Id will only be applied on Light with type
     * {@link android.hardware.lights.Light#LIGHT_TYPE_PLAYER_ID}
     *
     * @param color the desired color and intensity in ARGB format.
     * @hide
     */
    public LightState(@ColorInt int color, int playerId) {
        mColor = color;
        mPlayerId = playerId;
    }

    /**
     * Builder for creating device light change requests.
     */
    public static final class Builder {
        private int mValue;
        private boolean mIsForPlayerId;

        /** Creates a new {@link LightState.Builder}. */
        public Builder() {
            mValue = 0;
            mIsForPlayerId = false;
        }

        /**
         * Set the desired color and intensity of the LightState Builder, for a light type
         * of RBG color or single monochrome color.
         *
         * @param color the desired color and intensity in ARGB format.
         * @return The {@link LightState.Builder} object contains the light color and intensity.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setColor(@ColorInt int color) {
            mIsForPlayerId = false;
            mValue = color;
            return this;
        }

        /**
         * Set the desired player id of the LightState Builder, for a light with type
         * {@link android.hardware.lights.Light#LIGHT_TYPE_PLAYER_ID}.
         *
         * @param playerId the desired player id.
         * @return The {@link LightState.Builder} object contains the player id.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setPlayerId(int playerId) {
            mIsForPlayerId = true;
            mValue = playerId;
            return this;
        }

        /**
         * Create a LightState object used to control lights on the device.
         *
         * <p>The generated {@link LightState} should be used in
         * {@link LightsRequest.Builder#addLight(Light, LightState)}.
         */
        public @NonNull LightState build() {
            if (!mIsForPlayerId) {
                return new LightState(mValue, 0);
            } else {
                return new LightState(0, mValue);
            }
        }
    }

    /**
     * Creates a new LightState from a parcel object.
     */
    private LightState(@NonNull Parcel in) {
        mColor = in.readInt();
        mPlayerId = in.readInt();
    }

    /**
     * Returns the color and intensity associated with this LightState.
     * @return the color and intensity in ARGB format. The A channel is ignored. return 0 when
     * calling LightsManager.getLightState with
     * {@link android.hardware.lights.Light#LIGHT_TYPE_PLAYER_ID}.
     */
    public @ColorInt int getColor() {
        return mColor;
    }

    /**
     * Returns the player ID associated with this LightState for Light with type
     * {@link android.hardware.lights.Light#LIGHT_TYPE_PLAYER_ID},
     * or 0 for other types.
     * @return the player ID.
     */
    public int getPlayerId() {
        return mPlayerId;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mColor);
        dest.writeInt(mPlayerId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "LightState{Color=0x" + Integer.toHexString(mColor) + ", PlayerId="
                + mPlayerId + "}";
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
