/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Configurations to create virtual touchscreen.
 *
 * @hide
 */
@SystemApi
public final class VirtualTouchscreenConfig extends VirtualInputDeviceConfig implements Parcelable {

    /** The touchscreen width. */
    private final int mWidth;
    /** The touchscreen height. */
    private final int mHeight;

    private VirtualTouchscreenConfig(@NonNull Builder builder) {
        super(builder);
        mWidth = builder.mWidth;
        mHeight = builder.mHeight;
    }

    private VirtualTouchscreenConfig(@NonNull Parcel in) {
        super(in);
        mWidth = in.readInt();
        mHeight = in.readInt();
    }

    /** Returns the touchscreen width. */
    public int getWidth() {
        return mWidth;
    }

    /** Returns the touchscreen height. */
    public int getHeight() {
        return mHeight;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
    }

    @Override
    @NonNull
    String additionalFieldsToString() {
        return " width=" + mWidth + " height=" + mHeight;
    }

    @NonNull
    public static final Creator<VirtualTouchscreenConfig> CREATOR =
            new Creator<VirtualTouchscreenConfig>() {
                @Override
                public VirtualTouchscreenConfig createFromParcel(Parcel in) {
                    return new VirtualTouchscreenConfig(in);
                }

                @Override
                public VirtualTouchscreenConfig[] newArray(int size) {
                    return new VirtualTouchscreenConfig[size];
                }
            };

    /**
     * Builder for creating a {@link VirtualTouchscreenConfig}.
     */
    public static final class Builder extends VirtualInputDeviceConfig.Builder<Builder> {
        private int mWidth;
        private int mHeight;

        /**
         * Creates a new instance for the given dimensions of the {@link VirtualTouchscreen}.
         *
         * <p>The dimensions are not pixels but in the touchscreens raw coordinate space. They do
         * not necessarily have to correspond to the display size or aspect ratio. In this case the
         * framework will handle the scaling appropriately.
         *
         * @param touchscreenWidth The width of the touchscreen.
         * @param touchscreenHeight The height of the touchscreen.
         */
        public Builder(@IntRange(from = 1) int touchscreenWidth,
                @IntRange(from = 1) int touchscreenHeight) {
            if (touchscreenHeight <= 0 || touchscreenWidth <= 0) {
                throw new IllegalArgumentException(
                        "Cannot create a virtual touchscreen, touchscreen dimensions must be "
                                + "positive. Got: (" + touchscreenHeight + ", "
                                + touchscreenWidth + ")");
            }
            mHeight = touchscreenHeight;
            mWidth = touchscreenWidth;
        }

        /**
         * Builds the {@link VirtualTouchscreenConfig} instance.
         */
        @NonNull
        public VirtualTouchscreenConfig build() {
            return new VirtualTouchscreenConfig(this);
        }
    }
}
