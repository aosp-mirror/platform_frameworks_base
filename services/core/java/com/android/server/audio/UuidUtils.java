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

package com.android.server.audio;

import android.media.AudioDeviceAttributes;
import android.media.AudioSystem;
import android.util.Slog;

import java.util.UUID;

/**
 *  UuidUtils class implements helper functions to handle unique identifiers
 *  used to associate head tracking sensors to audio devices.
 */
class UuidUtils {
    private static final String TAG = "AudioService.UuidUtils";

    private static final long LSB_PREFIX_MASK = 0xFFFF000000000000L;
    private static final long LSB_SUFFIX_MASK = 0x0000FFFFFFFFFFFFL;
    // The sensor UUID for Bluetooth devices is defined as follows:
    // - 8 most significant bytes: All 0s
    // - 8 most significant bytes: Ascii B, Ascii T, Device MAC address on 6 bytes
    private static final long LSB_PREFIX_BT = 0x4254000000000000L;

    /**
     * Special UUID for a head tracking sensor not associated with an audio device.
     */
    public static final UUID STANDALONE_UUID = new UUID(0, 0);

    /**
     *  Generate a headtracking UUID from AudioDeviceAttributes
     */
    public static UUID uuidFromAudioDeviceAttributes(AudioDeviceAttributes device) {
        switch (device.getInternalType()) {
            case AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP:
                String address = device.getAddress().replace(":", "");
                if (address.length() != 12) {
                    return null;
                }
                address = "0x" + address;
                long lsb = LSB_PREFIX_BT;
                try {
                    lsb |= Long.decode(address).longValue();
                } catch (NumberFormatException e) {
                    return null;
                }
                if (AudioService.DEBUG_DEVICES) {
                    Slog.i(TAG, "uuidFromAudioDeviceAttributes lsb: " + Long.toHexString(lsb));
                }
                return new UUID(0, lsb);
            default:
                // Handle other device types here
                return null;
        }
    }
}
