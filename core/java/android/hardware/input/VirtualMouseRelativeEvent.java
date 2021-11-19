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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event describing a mouse movement interaction originating from a remote device.
 *
 * See {@link android.view.MotionEvent}.
 *
 * @hide
 */
@SystemApi
public final class VirtualMouseRelativeEvent implements Parcelable {

    private final float mRelativeX;
    private final float mRelativeY;

    private VirtualMouseRelativeEvent(float relativeX, float relativeY) {
        mRelativeX = relativeX;
        mRelativeY = relativeY;
    }

    private VirtualMouseRelativeEvent(@NonNull Parcel parcel) {
        mRelativeX = parcel.readFloat();
        mRelativeY = parcel.readFloat();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeFloat(mRelativeX);
        parcel.writeFloat(mRelativeY);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the relative x-axis movement, in pixels.
     */
    public float getRelativeX() {
        return mRelativeX;
    }

    /**
     * Returns the relative x-axis movement, in pixels.
     */
    public float getRelativeY() {
        return mRelativeY;
    }

    /**
     * Builder for {@link VirtualMouseRelativeEvent}.
     */
    public static final class Builder {

        private float mRelativeX;
        private float mRelativeY;

        /**
         * Creates a {@link VirtualMouseRelativeEvent} object with the current builder
         * configuration.
         */
        public @NonNull VirtualMouseRelativeEvent build() {
            return new VirtualMouseRelativeEvent(mRelativeX, mRelativeY);
        }

        /**
         * Sets the relative x-axis movement, in pixels.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setRelativeX(float relativeX) {
            mRelativeX = relativeX;
            return this;
        }

        /**
         * Sets the relative y-axis movement, in pixels.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setRelativeY(float relativeY) {
            mRelativeY = relativeY;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualMouseRelativeEvent> CREATOR =
            new Parcelable.Creator<VirtualMouseRelativeEvent>() {
                public VirtualMouseRelativeEvent createFromParcel(Parcel source) {
                    return new VirtualMouseRelativeEvent(source);
                }

                public VirtualMouseRelativeEvent[] newArray(int size) {
                    return new VirtualMouseRelativeEvent[size];
                }
            };
}
