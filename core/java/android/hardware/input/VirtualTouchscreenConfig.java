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
 * Configurations to create a virtual touchscreen.
 *
 * @hide
 */
@SystemApi
public final class VirtualTouchscreenConfig extends VirtualTouchDeviceConfig implements Parcelable {

    private VirtualTouchscreenConfig(@NonNull Builder builder) {
        super(builder);
    }

    private VirtualTouchscreenConfig(@NonNull Parcel in) {
        super(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
    }

    @NonNull
    public static final Creator<VirtualTouchscreenConfig> CREATOR =
            new Creator<>() {
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
    public static final class Builder extends VirtualTouchDeviceConfig.Builder<Builder> {

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
            super(touchscreenWidth, touchscreenHeight);
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
