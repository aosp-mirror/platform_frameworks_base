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

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Configurations to create a virtual stylus.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
@SystemApi
public final class VirtualStylusConfig extends VirtualTouchDeviceConfig implements Parcelable {

    private VirtualStylusConfig(@NonNull Builder builder) {
        super(builder);
    }

    private VirtualStylusConfig(@NonNull Parcel in) {
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
    public static final Creator<VirtualStylusConfig> CREATOR =
            new Creator<>() {
                @Override
                public VirtualStylusConfig createFromParcel(Parcel in) {
                    return new VirtualStylusConfig(in);
                }

                @Override
                public VirtualStylusConfig[] newArray(int size) {
                    return new VirtualStylusConfig[size];
                }
            };

    /**
     * Builder for creating a {@link VirtualStylusConfig}.
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
    public static final class Builder extends VirtualTouchDeviceConfig.Builder<Builder> {

        /**
         * Creates a new instance for the given dimensions of the screen targeted by the
         * {@link VirtualStylus}.
         *
         * <p>The dimensions are not pixels but in the screen's raw coordinate space. They do
         * not necessarily have to correspond to the display size or aspect ratio. In this case the
         * framework will handle the scaling appropriately.
         *
         * @param screenWidth The width of the targeted screen.
         * @param screenHeight The height of the targeted screen.
         */
        public Builder(@IntRange(from = 1) int screenWidth,
                @IntRange(from = 1) int screenHeight) {
            super(screenWidth, screenHeight);
        }

        /**
         * Builds the {@link VirtualStylusConfig} instance.
         */
        @NonNull
        public VirtualStylusConfig build() {
            return new VirtualStylusConfig(this);
        }
    }
}
