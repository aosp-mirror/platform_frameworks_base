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

package com.android.systemui.volume.data.repository

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioDevicePort

@SuppressLint("VisibleForTests")
object TestAudioDevicesFactory {

    fun builtInDevice(deviceName: String = "built_in"): AudioDeviceInfo {
        return AudioDeviceInfo(
            AudioDevicePort.createForTesting(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                deviceName,
                "",
            )
        )
    }

    fun wiredDevice(deviceName: String = "wired"): AudioDeviceInfo {
        return AudioDeviceInfo(
            AudioDevicePort.createForTesting(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                deviceName,
                "",
            )
        )
    }

    fun bluetoothDevice(
        deviceName: String = "bt",
        deviceAddress: String = "test_address",
    ): AudioDeviceInfo {
        return AudioDeviceInfo(
            AudioDevicePort.createForTesting(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                deviceName,
                deviceAddress,
            )
        )
    }
}
