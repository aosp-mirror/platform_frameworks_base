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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Holds business logic for the Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class DeviceItemInteractor
@Inject
constructor(private val bluetoothTileDialogRepository: BluetoothTileDialogRepository) :
    DeviceItemOnClickCallback {
    private var deviceItemFactoryList: List<DeviceItemFactory> =
        listOf(AvailableMediaDeviceItemFactory())

    internal fun getDeviceItems(context: Context): List<DeviceItem> {
        return bluetoothTileDialogRepository.cachedDevices.mapNotNull { cachedDevice ->
            deviceItemFactoryList
                .firstOrNull { it.predicate(cachedDevice) }
                ?.create(context, cachedDevice)
        }
    }

    internal fun setDeviceItemFactoryListForTesting(list: List<DeviceItemFactory>) {
        deviceItemFactoryList = list
    }

    override fun onClicked(deviceItem: DeviceItem) {
        when (deviceItem.type) {
            DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE -> {
                // TODO(b/298124674): Add actual action with cachedBluetoothDevice
            }
        }
    }
}

internal interface DeviceItemOnClickCallback {
    fun onClicked(deviceItem: DeviceItem)
}
