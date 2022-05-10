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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * An event describing a mouse scroll interaction originating from a remote device.
 *
 * See {@link android.view.MotionEvent}.
 *
 * @hide
 */
@SystemApi
public final class VirtualMouseScrollEvent implements Parcelable {

    private final float mXAxisMovement;
    private final float mYAxisMovement;

    private VirtualMouseScrollEvent(float xAxisMovement, float yAxisMovement) {
        mXAxisMovement = xAxisMovement;
        mYAxisMovement = yAxisMovement;
    }

    private VirtualMouseScrollEvent(@NonNull Parcel parcel) {
        mXAxisMovement = parcel.readFloat();
        mYAxisMovement = parcel.readFloat();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeFloat(mXAxisMovement);
        parcel.writeFloat(mYAxisMovement);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the x-axis scroll movement, normalized from -1.0 to 1.0, inclusive. Positive values
     * indicate scrolling upward; negative values, downward.
     */
    public float getXAxisMovement() {
        return mXAxisMovement;
    }

    /**
     * Returns the y-axis scroll movement, normalized from -1.0 to 1.0, inclusive. Positive values
     * indicate scrolling towards the right; negative values, to the left.
     */
    public float getYAxisMovement() {
        return mYAxisMovement;
    }

    /**
     * Builder for {@link VirtualMouseScrollEvent}.
     */
    public static final class Builder {

        private float mXAxisMovement;
        private float mYAxisMovement;

        /**
         * Creates a {@link VirtualMouseScrollEvent} object with the current builder configuration.
         */
        public @NonNull VirtualMouseScrollEvent build() {
            return new VirtualMouseScrollEvent(mXAxisMovement, mYAxisMovement);
        }

        /**
         * Sets the x-axis scroll movement, normalized from -1.0 to 1.0, inclusive. Positive values
         * indicate scrolling upward; negative values, downward.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setXAxisMovement(
                @FloatRange(from = -1.0f, to = 1.0f) float xAxisMovement) {
            Preconditions.checkArgumentInRange(xAxisMovement, -1f, 1f, "xAxisMovement");
            mXAxisMovement = xAxisMovement;
            return this;
        }

        /**
         * Sets the y-axis scroll movement, normalized from -1.0 to 1.0, inclusive. Positive values
         * indicate scrolling towards the right; negative values, to the left.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setYAxisMovement(
                @FloatRange(from = -1.0f, to = 1.0f) float yAxisMovement) {
            Preconditions.checkArgumentInRange(yAxisMovement, -1f, 1f, "yAxisMovement");
            mYAxisMovement = yAxisMovement;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualMouseScrollEvent> CREATOR =
            new Parcelable.Creator<VirtualMouseScrollEvent>() {
                public VirtualMouseScrollEvent createFromParcel(Parcel source) {
                    return new VirtualMouseScrollEvent(source);
                }

                public VirtualMouseScrollEvent[] newArray(int size) {
                    return new VirtualMouseScrollEvent[size];
                }
            };
}
