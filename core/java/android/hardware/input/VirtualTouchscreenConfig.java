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

    /** The touchscreen width in pixels. */
    private final int mWidthInPixels;
    /** The touchscreen height in pixels. */
    private final int mHeightInPixels;

    private VirtualTouchscreenConfig(@NonNull Builder builder) {
        super(builder);
        mWidthInPixels = builder.mWidthInPixels;
        mHeightInPixels = builder.mHeightInPixels;
    }

    private VirtualTouchscreenConfig(@NonNull Parcel in) {
        super(in);
        mWidthInPixels = in.readInt();
        mHeightInPixels = in.readInt();
    }

    /** Returns the touchscreen width in pixels. */
    public int getWidthInPixels() {
        return mWidthInPixels;
    }

    /** Returns the touchscreen height in pixels. */
    public int getHeightInPixels() {
        return mHeightInPixels;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mWidthInPixels);
        dest.writeInt(mHeightInPixels);
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
        private int mWidthInPixels;
        private int mHeightInPixels;

        /**
         * @see VirtualTouchscreenConfig#getWidthInPixels().
         */
        @NonNull
        public Builder setWidthInPixels(int widthInPixels) {
            mWidthInPixels = widthInPixels;
            return this;
        }

        /**
         * @see VirtualTouchscreenConfig#getHeightInPixels().
         */
        @NonNull
        public Builder setHeightInPixels(int heightInPixels) {
            mHeightInPixels = heightInPixels;
            return this;
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
