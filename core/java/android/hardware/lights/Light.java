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

    // These enum values start from 10001 to avoid collision with expanding of HAL light types.
    /**
     * Type for lights that indicate a monochrome color LED light.
     */
    public static final int LIGHT_TYPE_INPUT_SINGLE = 10001;

    /**
     * Type for lights that indicate a group of LED lights representing player ID.
     * Player ID lights normally present on game controllers are lights that consist of a row of
     * LEDs.
     * During multi-player game, the player ID for the current game controller is represented by
     * one of the LED that is lit according to its position in the row.
     */
    public static final int LIGHT_TYPE_INPUT_PLAYER_ID = 10002;

    /**
     * Type for lights that indicate a color LED light.
     */
    public static final int LIGHT_TYPE_INPUT_RGB = 10003;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LIGHT_TYPE_"},
        value = {
            LIGHT_TYPE_INPUT_PLAYER_ID,
            LIGHT_TYPE_INPUT_SINGLE,
            LIGHT_TYPE_INPUT_RGB,
        })
    public @interface LightType {}

    private final int mId;
    private final int mOrdinal;
    private final int mType;
    private final String mName;

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, int ordinal, int type) {
        this(id, ordinal, type, "Light");
    }

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, int ordinal, int type, String name) {
        mId = id;
        mOrdinal = ordinal;
        mType = type;
        mName = name;
    }

    private Light(@NonNull Parcel in) {
        mId = in.readInt();
        mOrdinal = in.readInt();
        mType = in.readInt();
        mName = in.readString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mOrdinal);
        dest.writeInt(mType);
        dest.writeString(mName);
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
            return mId == light.mId && mOrdinal == light.mOrdinal && mType == light.mType;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mId;
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
    public @LightsManager.LightType int getType() {
        return mType;
    }
}
