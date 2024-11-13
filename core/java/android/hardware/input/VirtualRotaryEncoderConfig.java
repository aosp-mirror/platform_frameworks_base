/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Configuration for creating a virtual rotary encoder.
 *
 * @see android.companion.virtual.VirtualDeviceManager.VirtualDevice#createVirtualRotaryEncoder
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_VIRTUAL_ROTARY)
@SystemApi
public final class VirtualRotaryEncoderConfig extends VirtualInputDeviceConfig
        implements Parcelable {
    @NonNull
    public static final Creator<VirtualRotaryEncoderConfig> CREATOR = new Creator<>() {
        @Override
        public VirtualRotaryEncoderConfig createFromParcel(Parcel in) {
            return new VirtualRotaryEncoderConfig(in);
        }

        @Override
        public VirtualRotaryEncoderConfig[] newArray(int size) {
            return new VirtualRotaryEncoderConfig[size];
        }
    };

    private VirtualRotaryEncoderConfig(@NonNull VirtualRotaryEncoderConfig.Builder builder) {
        super(builder);
    }

    private VirtualRotaryEncoderConfig(@NonNull Parcel in) {
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

    /**
     * Builder for creating a {@link VirtualRotaryEncoderConfig}.
     */
    public static final class Builder extends VirtualInputDeviceConfig.Builder<Builder> {

        /**
         * Builds the {@link VirtualRotaryEncoderConfig} instance.
         */
        @NonNull
        public VirtualRotaryEncoderConfig build() {
            return new VirtualRotaryEncoderConfig(this);
        }
    }
}
