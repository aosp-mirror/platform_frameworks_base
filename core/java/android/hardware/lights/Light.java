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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a logical light on the device.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class Light implements Parcelable {
    private final int mId;
    private final int mOrdinal;
    private final int mType;

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, int ordinal, int type) {
        mId = id;
        mOrdinal = ordinal;
        mType = type;
    }

    private Light(@NonNull Parcel in) {
        mId = in.readInt();
        mOrdinal = in.readInt();
        mType = in.readInt();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mOrdinal);
        dest.writeInt(mType);
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
    public boolean equals(Object obj) {
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
