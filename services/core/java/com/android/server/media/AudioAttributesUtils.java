/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.MediaRoute2Info;

/* package */ final class AudioAttributesUtils {

    /* package */ static final AudioAttributes ATTRIBUTES_MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();

    private AudioAttributesUtils() {
        // no-op to prevent instantiation.
    }

    @MediaRoute2Info.Type
    /* package */ static int mapToMediaRouteType(
            @NonNull AudioDeviceAttributes audioDeviceAttributes) {
        switch (audioDeviceAttributes.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return MediaRoute2Info.TYPE_WIRED_HEADSET;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return MediaRoute2Info.TYPE_WIRED_HEADPHONES;
            case AudioDeviceInfo.TYPE_DOCK:
            case AudioDeviceInfo.TYPE_DOCK_ANALOG:
                return MediaRoute2Info.TYPE_DOCK;
            case AudioDeviceInfo.TYPE_HDMI:
                return MediaRoute2Info.TYPE_HDMI;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return MediaRoute2Info.TYPE_USB_DEVICE;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return MediaRoute2Info.TYPE_BLE_HEADSET;
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return MediaRoute2Info.TYPE_HEARING_AID;
            default:
                return MediaRoute2Info.TYPE_UNKNOWN;
        }
    }


    /* package */ static boolean isDeviceOutputAttributes(
            @Nullable AudioDeviceAttributes audioDeviceAttributes) {
        if (audioDeviceAttributes == null) {
            return false;
        }

        if (audioDeviceAttributes.getRole() != AudioDeviceAttributes.ROLE_OUTPUT) {
            return false;
        }

        switch (audioDeviceAttributes.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_DOCK:
            case AudioDeviceInfo.TYPE_DOCK_ANALOG:
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return true;
            default:
                return false;
        }
    }

    /* package */ static boolean isBluetoothOutputAttributes(
            @Nullable AudioDeviceAttributes audioDeviceAttributes) {
        if (audioDeviceAttributes == null) {
            return false;
        }

        if (audioDeviceAttributes.getRole() != AudioDeviceAttributes.ROLE_OUTPUT) {
            return false;
        }

        switch (audioDeviceAttributes.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return true;
            default:
                return false;
        }
    }

}
