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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the codec status (configuration and capability) for a Bluetooth
 * A2DP source device.
 *
 * {@see BluetoothA2dp}
 */
public final class BluetoothCodecStatus implements Parcelable {
    /**
     * Extra for the codec configuration intents of the individual profiles.
     *
     * This extra represents the current codec status of the A2DP
     * profile.
     */
    public static final String EXTRA_CODEC_STATUS =
            "android.bluetooth.extra.CODEC_STATUS";

    private final @Nullable BluetoothCodecConfig mCodecConfig;
    private final @Nullable List<BluetoothCodecConfig> mCodecsLocalCapabilities;
    private final @Nullable List<BluetoothCodecConfig> mCodecsSelectableCapabilities;

    public BluetoothCodecStatus(@Nullable BluetoothCodecConfig codecConfig,
            @Nullable List<BluetoothCodecConfig> codecsLocalCapabilities,
            @Nullable List<BluetoothCodecConfig> codecsSelectableCapabilities) {
        mCodecConfig = codecConfig;
        mCodecsLocalCapabilities = codecsLocalCapabilities;
        mCodecsSelectableCapabilities = codecsSelectableCapabilities;
    }

    private BluetoothCodecStatus(Parcel in) {
        mCodecConfig = in.readTypedObject(BluetoothCodecConfig.CREATOR);
        mCodecsLocalCapabilities = in.createTypedArrayList(BluetoothCodecConfig.CREATOR);
        mCodecsSelectableCapabilities = in.createTypedArrayList(BluetoothCodecConfig.CREATOR);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof BluetoothCodecStatus) {
            BluetoothCodecStatus other = (BluetoothCodecStatus) o;
            return (Objects.equals(other.mCodecConfig, mCodecConfig)
                    && sameCapabilities(other.mCodecsLocalCapabilities, mCodecsLocalCapabilities)
                    && sameCapabilities(other.mCodecsSelectableCapabilities,
                    mCodecsSelectableCapabilities));
        }
        return false;
    }

    /**
     * Checks whether two lists of capabilities contain same capabilities.
     * The order of the capabilities in each list is ignored.
     *
     * @param c1 the first list of capabilities to compare
     * @param c2 the second list of capabilities to compare
     * @return {@code true} if both lists contain same capabilities
     */
    private static boolean sameCapabilities(@Nullable List<BluetoothCodecConfig> c1,
                                           @Nullable List<BluetoothCodecConfig> c2) {
        if (c1 == null) {
            return (c2 == null);
        }
        if (c2 == null) {
            return false;
        }
        if (c1.size() != c2.size()) {
            return false;
        }
        return c1.containsAll(c2);
    }

    /**
     * Checks whether the codec config matches the selectable capabilities.
     * Any parameters of the codec config with NONE value will be considered a wildcard matching.
     *
     * @param codecConfig the codec config to compare against
     * @return {@code true} if the codec config matches, {@code false} otherwise
     */
    public boolean isCodecConfigSelectable(@Nullable BluetoothCodecConfig codecConfig) {
        if (codecConfig == null || !codecConfig.hasSingleSampleRate()
                || !codecConfig.hasSingleBitsPerSample() || !codecConfig.hasSingleChannelMode()) {
            return false;
        }
        for (BluetoothCodecConfig selectableConfig : mCodecsSelectableCapabilities) {
            if (codecConfig.getCodecType() != selectableConfig.getCodecType()) {
                continue;
            }
            int sampleRate = codecConfig.getSampleRate();
            if ((sampleRate & selectableConfig.getSampleRate()) == 0
                    && sampleRate != BluetoothCodecConfig.SAMPLE_RATE_NONE) {
                continue;
            }
            int bitsPerSample = codecConfig.getBitsPerSample();
            if ((bitsPerSample & selectableConfig.getBitsPerSample()) == 0
                    && bitsPerSample != BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
                continue;
            }
            int channelMode = codecConfig.getChannelMode();
            if ((channelMode & selectableConfig.getChannelMode()) == 0
                    && channelMode != BluetoothCodecConfig.CHANNEL_MODE_NONE) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Returns a hash based on the codec config and local capabilities.
     */
    @Override
    public int hashCode() {
        return Objects.hash(mCodecConfig, mCodecsLocalCapabilities,
                mCodecsLocalCapabilities);
    }

    /**
     * Returns a {@link String} that describes each BluetoothCodecStatus parameter
     * current value.
     */
    @Override
    public String toString() {
        return "{mCodecConfig:" + mCodecConfig
                + ",mCodecsLocalCapabilities:" + mCodecsLocalCapabilities
                + ",mCodecsSelectableCapabilities:" + mCodecsSelectableCapabilities
                + "}";
    }

    /**
     * @return 0
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothCodecStatus> CREATOR =
            new Parcelable.Creator<BluetoothCodecStatus>() {
                public BluetoothCodecStatus createFromParcel(Parcel in) {
                    return new BluetoothCodecStatus(in);
                }

                public BluetoothCodecStatus[] newArray(int size) {
                    return new BluetoothCodecStatus[size];
                }
            };

    /**
     * Flattens the object to a parcel.
     *
     * @param out The Parcel in which the object should be written
     * @param flags Additional flags about how the object should be written
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeTypedObject(mCodecConfig, 0);
        out.writeTypedList(mCodecsLocalCapabilities);
        out.writeTypedList(mCodecsSelectableCapabilities);
    }

    /**
     * Returns the current codec configuration.
     */
    public @Nullable BluetoothCodecConfig getCodecConfig() {
        return mCodecConfig;
    }

    /**
     * Returns the codecs local capabilities.
     */
    public @NonNull List<BluetoothCodecConfig> getCodecsLocalCapabilities() {
        return (mCodecsLocalCapabilities == null)
                ? Collections.emptyList() : mCodecsLocalCapabilities;
    }

    /**
     * Returns the codecs selectable capabilities.
     */
    public @NonNull List<BluetoothCodecConfig> getCodecsSelectableCapabilities() {
        return (mCodecsSelectableCapabilities == null)
                ? Collections.emptyList() : mCodecsSelectableCapabilities;
    }
}
