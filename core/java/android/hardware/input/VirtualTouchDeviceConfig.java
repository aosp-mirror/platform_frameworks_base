/*
 * Copyright 2023 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;

/**
 * Configurations to create a virtual touch-based device.
 *
 * @hide
 */
abstract class VirtualTouchDeviceConfig extends VirtualInputDeviceConfig {

    /** The touch device width. */
    private final int mWidth;
    /** The touch device height. */
    private final int mHeight;

    VirtualTouchDeviceConfig(@NonNull Builder<? extends Builder<?>> builder) {
        super(builder);
        mWidth = builder.mWidth;
        mHeight = builder.mHeight;
    }

    VirtualTouchDeviceConfig(@NonNull Parcel in) {
        super(in);
        mWidth = in.readInt();
        mHeight = in.readInt();
    }

    /** Returns the touch device width. */
    public int getWidth() {
        return mWidth;
    }

    /** Returns the touch device height. */
    public int getHeight() {
        return mHeight;
    }

    @Override
    void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
    }

    @Override
    @NonNull
    String additionalFieldsToString() {
        return " width=" + mWidth + " height=" + mHeight;
    }

    /**
     * Builder for creating a {@link VirtualTouchDeviceConfig}.
     *
     * @param <T> The subclass to be built.
     */
    abstract static class Builder<T extends Builder<T>>
            extends VirtualInputDeviceConfig.Builder<T> {

        private final int mWidth;
        private final int mHeight;

        /**
         * Creates a new instance for the given dimensions of the touch device.
         *
         * <p>The dimensions are not pixels but in the screen's raw coordinate space. They do
         * not necessarily have to correspond to the display size or aspect ratio. In this case the
         * framework will handle the scaling appropriately.
         *
         * @param touchDeviceWidth The width of the touch device.
         * @param touchDeviceHeight The height of the touch device.
         */
        Builder(@IntRange(from = 1) int touchDeviceWidth,
                @IntRange(from = 1) int touchDeviceHeight) {
            if (touchDeviceHeight <= 0 || touchDeviceWidth <= 0) {
                throw new IllegalArgumentException(
                        "Cannot create a virtual touch-based device, dimensions must be "
                                + "positive. Got: (" + touchDeviceHeight + ", "
                                + touchDeviceWidth + ")");
            }
            mHeight = touchDeviceHeight;
            mWidth = touchDeviceWidth;
        }
    }
}
