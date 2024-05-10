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
 * Configurations to create virtual navigation touchpad.
 *
 * @hide
 */
@SystemApi
public final class VirtualNavigationTouchpadConfig extends VirtualInputDeviceConfig
        implements Parcelable {

    /** The touchpad height. */
    private final int mHeight;
    /** The touchpad width. */
    private final int mWidth;

    private VirtualNavigationTouchpadConfig(@NonNull Builder builder) {
        super(builder);
        mHeight = builder.mHeight;
        mWidth = builder.mWidth;
    }

    private VirtualNavigationTouchpadConfig(@NonNull Parcel in) {
        super(in);
        mHeight = in.readInt();
        mWidth = in.readInt();
    }

    /** Returns the touchpad height. */
    @IntRange(from = 1) public int getHeight() {
        return mHeight;
    }

    /** Returns the touchpad width. */
    @IntRange(from = 1) public int getWidth() {
        return mWidth;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mHeight);
        dest.writeInt(mWidth);
    }

    @Override
    @NonNull
    String additionalFieldsToString() {
        return " width=" + mWidth + " height=" + mHeight;
    }

    @NonNull
    public static final Creator<VirtualNavigationTouchpadConfig> CREATOR =
            new Creator<VirtualNavigationTouchpadConfig>() {
                @Override
                public VirtualNavigationTouchpadConfig createFromParcel(Parcel in) {
                    return new VirtualNavigationTouchpadConfig(in);
                }

                @Override
                public VirtualNavigationTouchpadConfig[] newArray(int size) {
                    return new VirtualNavigationTouchpadConfig[size];
                }
            };

    /**
     * Builder for creating a {@link VirtualNavigationTouchpadConfig}.
     */
    public static final class Builder extends VirtualInputDeviceConfig.Builder<Builder> {
        private final int mHeight;
        private final int mWidth;

        /**
         * Creates a new instance for the given dimensions of the {@link VirtualNavigationTouchpad}.
         *
         * @param touchpadWidth The width of the touchpad.
         * @param touchpadHeight The height of the touchpad.
         */
        public Builder(@IntRange(from = 1) int touchpadWidth,
                @IntRange(from = 1) int touchpadHeight) {
            if (touchpadHeight <= 0 || touchpadWidth <= 0) {
                throw new IllegalArgumentException(
                        "Cannot create a virtual navigation touchpad, touchpad dimensions must be "
                                + "positive. Got: (" + touchpadHeight + ", "
                                + touchpadWidth + ")");
            }
            mHeight = touchpadHeight;
            mWidth = touchpadWidth;
        }

        /**
         * Builds the {@link VirtualNavigationTouchpadConfig} instance.
         */
        @NonNull
        public VirtualNavigationTouchpadConfig build() {
            return new VirtualNavigationTouchpadConfig(this);
        }
    }
}
