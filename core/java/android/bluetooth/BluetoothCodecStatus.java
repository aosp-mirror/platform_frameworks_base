/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the codec status (configuration and capability) for a Bluetooth
 * A2DP source device.
 *
 * {@see BluetoothA2dp}
 *
 * {@hide}
 */
public final class BluetoothCodecStatus implements Parcelable {
    /**
     * Extra for the codec configuration intents of the individual profiles.
     *
     * This extra represents the current codec status of the A2DP
     * profile.
     */
    public static final String EXTRA_CODEC_STATUS =
        "android.bluetooth.codec.extra.CODEC_STATUS";

    private final BluetoothCodecConfig mCodecConfig;
    private final BluetoothCodecConfig[] mCodecsLocalCapabilities;
    private final BluetoothCodecConfig[] mCodecsSelectableCapabilities;

    public BluetoothCodecStatus(BluetoothCodecConfig codecConfig,
                                BluetoothCodecConfig[] codecsLocalCapabilities,
                                BluetoothCodecConfig[] codecsSelectableCapabilities) {
        mCodecConfig = codecConfig;
        mCodecsLocalCapabilities = codecsLocalCapabilities;
        mCodecsSelectableCapabilities = codecsSelectableCapabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothCodecStatus) {
            BluetoothCodecStatus other = (BluetoothCodecStatus)o;
            return (Objects.equals(other.mCodecConfig, mCodecConfig) &&
                    Objects.equals(other.mCodecsLocalCapabilities,
                                   mCodecsLocalCapabilities) &&
                    Objects.equals(other.mCodecsSelectableCapabilities,
                                   mCodecsSelectableCapabilities));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCodecConfig, mCodecsLocalCapabilities,
                            mCodecsLocalCapabilities);
    }

    @Override
    public String toString() {
        return "{mCodecConfig:" + mCodecConfig +
            ",mCodecsLocalCapabilities:" + Arrays.toString(mCodecsLocalCapabilities) +
            ",mCodecsSelectableCapabilities:" + Arrays.toString(mCodecsSelectableCapabilities) +
            "}";
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothCodecStatus> CREATOR =
            new Parcelable.Creator<BluetoothCodecStatus>() {
        public BluetoothCodecStatus createFromParcel(Parcel in) {
            final BluetoothCodecConfig codecConfig = in.readTypedObject(BluetoothCodecConfig.CREATOR);
            final BluetoothCodecConfig[] codecsLocalCapabilities = in.createTypedArray(BluetoothCodecConfig.CREATOR);
            final BluetoothCodecConfig[] codecsSelectableCapabilities = in.createTypedArray(BluetoothCodecConfig.CREATOR);

            return new BluetoothCodecStatus(codecConfig,
                                            codecsLocalCapabilities,
                                            codecsSelectableCapabilities);
        }
        public BluetoothCodecStatus[] newArray(int size) {
            return new BluetoothCodecStatus[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(mCodecConfig, 0);
        out.writeTypedArray(mCodecsLocalCapabilities, 0);
        out.writeTypedArray(mCodecsSelectableCapabilities, 0);
    }

    /**
     * Gets the current codec configuration.
     *
     * @return the current codec configuration
     */
    public BluetoothCodecConfig getCodecConfig() {
        return mCodecConfig;
    }

    /**
     * Gets the codecs local capabilities.
     *
     * @return an array with the codecs local capabilities
     */
    public BluetoothCodecConfig[] getCodecsLocalCapabilities() {
        return mCodecsLocalCapabilities;
    }

    /**
     * Gets the codecs selectable capabilities.
     *
     * @return an array with the codecs selectable capabilities
     */
    public BluetoothCodecConfig[] getCodecsSelectableCapabilities() {
        return mCodecsSelectableCapabilities;
    }
}
