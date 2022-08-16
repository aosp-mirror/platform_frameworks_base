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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a logical light on the device.
 *
 */
public final class Light implements Parcelable {
    // These enum values copy the values from {@link com.android.server.lights.LightsManager}
    // and the light HAL. Since 0-7 are lights reserved for system use, 8 for microphone light is
    // defined in {@link android.hardware.lights.LightsManager}, following types are available
    // through this API.
    /** Type for lights that indicate microphone usage */
    public static final int LIGHT_TYPE_MICROPHONE = 8;

    /** Type for lights that indicate camera usage
     *
     * @hide
     */
    public static final int LIGHT_TYPE_CAMERA = 9;

    // These enum values start from 10001 to avoid collision with expanding of HAL light types.
    /**
     * Type for lights that indicate a monochrome color LED light.
     */
    public static final int LIGHT_TYPE_INPUT = 10001;

    /**
     * Type for lights that indicate a group of LED lights representing player id.
     * Player id lights normally present on game controllers are lights that consist of a row of
     * LEDs.
     * During multi-player game, the player id for the current game controller is represented by
     * one of the LED that is lit according to its position in the row.
     */
    public static final int LIGHT_TYPE_PLAYER_ID = 10002;

    /**
     * Capability for lights that could adjust its LED brightness. If the capability is not present
     * the led can only be turned either on or off.
     */
    public static final int LIGHT_CAPABILITY_BRIGHTNESS = 1 << 0;

    /**
     * Capability for lights that has red, green and blue LEDs to control the light's color.
     */
    public static final int LIGHT_CAPABILITY_RGB = 0 << 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LIGHT_TYPE_"},
        value = {
            LIGHT_TYPE_MICROPHONE,
            LIGHT_TYPE_INPUT,
            LIGHT_TYPE_PLAYER_ID,
        })
    public @interface LightType {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"LIGHT_CAPABILITY_"},
        value = {
            LIGHT_CAPABILITY_BRIGHTNESS,
            LIGHT_CAPABILITY_RGB,
        })
    public @interface LightCapability {}

    private final int mId;
    private final String mName;
    private final int mOrdinal;
    private final int mType;
    private final int mCapabilities;

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, int ordinal, int type) {
        this(id, "Light", ordinal, type, 0);
    }

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, String name, int ordinal, int type, int capabilities) {
        mId = id;
        mName = name;
        mOrdinal = ordinal;
        mType = type;
        mCapabilities = capabilities;
    }

    private Light(@NonNull Parcel in) {
        mId = in.readInt();
        mName = in.readString();
        mOrdinal = in.readInt();
        mType = in.readInt();
        mCapabilities = in.readInt();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mName);
        dest.writeInt(mOrdinal);
        dest.writeInt(mType);
        dest.writeInt(mCapabilities);
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Parcelable.Creator<Light> CREATOR =
            new Parcelable.Creator<Light>() {
                public Light createFromParcel(Parcel in) {
                    return new Light(in);
                }

                public Light[] newArray(int size) {
                    return new Light[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof Light) {
            Light light = (Light) obj;
            return mId == light.mId && mOrdinal == light.mOrdinal && mType == light.mType
                    && mCapabilities == light.mCapabilities;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mId;
    }

    @Override
    public String toString() {
        return "[Name=" + mName + " Id=" + mId + " Type=" + mType + " Capabilities="
                + mCapabilities + " Ordinal=" + mOrdinal + "]";
    }

    /**
     * Returns the id of the light.
     *
     * <p>This is an opaque value used as a unique identifier for the light.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the name of the light.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the ordinal of the light.
     *
     * <p>This is a sort key that represents the physical order of lights on the device with the
     * same type. In the case of multiple lights arranged in a line, for example, the ordinals
     * could be [1, 2, 3, 4], or [0, 10, 20, 30], or any other values that have the same sort order.
     */
    public int getOrdinal() {
        return mOrdinal;
    }

    /**
     * Returns the logical type of the light.
     */
    public @LightType int getType() {
        return mType;
    }

    /**
     * Returns the capabilities of the light.
     * @hide
     */
    @TestApi
    public @LightCapability int getCapabilities() {
        return mCapabilities;
    }

    /**
     * Check whether the light has led brightness control.
     *
     * @return True if the hardware can control the led brightness, otherwise false.
     */
    public boolean hasBrightnessControl() {
        return (mCapabilities & LIGHT_CAPABILITY_BRIGHTNESS) == LIGHT_CAPABILITY_BRIGHTNESS;
    }

    /**
     * Check whether the light has RGB led control.
     *
     * @return True if the hardware can control the RGB led, otherwise false.
     */
    public boolean hasRgbControl() {
        return (mCapabilities & LIGHT_CAPABILITY_RGB) == LIGHT_CAPABILITY_RGB;
    }

}
