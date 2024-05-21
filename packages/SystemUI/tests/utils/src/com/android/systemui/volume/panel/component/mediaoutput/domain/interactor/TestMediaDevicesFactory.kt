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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.drawable.TestStubDrawable
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.PhoneMediaDevice
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

@SuppressLint("StaticFieldLeak") // These are mocks
object TestMediaDevicesFactory {

    fun builtInMediaDevice(): MediaDevice = mock {
        whenever(name).thenReturn("built_in_media")
        whenever(icon).thenReturn(TestStubDrawable())
    }

    fun wiredMediaDevice(): MediaDevice =
        mock<PhoneMediaDevice> {
            whenever(deviceType)
                .thenReturn(MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE)
            whenever(name).thenReturn("wired_media")
            whenever(icon).thenReturn(TestStubDrawable())
        }

    fun bluetoothMediaDevice(): MediaDevice {
        val bluetoothDevice = mock<BluetoothDevice>()
        val cachedBluetoothDevice: CachedBluetoothDevice = mock {
            whenever(isHearingAidDevice).thenReturn(true)
            whenever(address).thenReturn("bt_media_device")
            whenever(device).thenReturn(bluetoothDevice)
        }
        return mock<BluetoothMediaDevice> {
            whenever(name).thenReturn("bt_media")
            whenever(icon).thenReturn(TestStubDrawable())
            whenever(cachedDevice).thenReturn(cachedBluetoothDevice)
        }
    }
}
