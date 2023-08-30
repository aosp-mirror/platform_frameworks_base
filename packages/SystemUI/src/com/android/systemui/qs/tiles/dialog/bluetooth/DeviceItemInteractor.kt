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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Holds business logic for the Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class DeviceItemInteractor
@Inject
constructor(
    private val bluetoothTileDialogRepository: BluetoothTileDialogRepository,
    private val audioManager: AudioManager,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) {
    private var deviceItemFactoryList: List<DeviceItemFactory> =
        listOf(
            AvailableMediaDeviceItemFactory(),
            ConnectedDeviceItemFactory(),
            SavedDeviceItemFactory()
        )

    private var displayPriority: List<DeviceItemType> =
        listOf(
            DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
            DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
            DeviceItemType.SAVED_BLUETOOTH_DEVICE,
        )

    internal fun getDeviceItems(context: Context): List<DeviceItem> {
        val mostRecentlyConnectedDevices = bluetoothAdapter?.mostRecentlyConnectedDevices

        return bluetoothTileDialogRepository.cachedDevices
            .mapNotNull { cachedDevice ->
                deviceItemFactoryList
                    .firstOrNull { it.isFilterMatched(cachedDevice, audioManager) }
                    ?.create(context, cachedDevice)
            }
            .sort(displayPriority, mostRecentlyConnectedDevices)
    }

    private fun List<DeviceItem>.sort(
        displayPriority: List<DeviceItemType>,
        mostRecentlyConnectedDevices: List<BluetoothDevice>?
    ): List<DeviceItem> {
        return this.sortedWith(
            compareBy<DeviceItem> { displayPriority.indexOf(it.type) }
                .thenBy {
                    mostRecentlyConnectedDevices?.indexOf(it.cachedBluetoothDevice.device) ?: 0
                }
        )
    }

    internal fun updateDeviceItemOnClick(deviceItem: DeviceItem): Boolean {
        var isClicked = false
        when (deviceItem.type) {
            DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE -> {
                if (!BluetoothUtils.isActiveMediaDevice(deviceItem.cachedBluetoothDevice)) {
                    deviceItem.cachedBluetoothDevice.setActive()
                    isClicked = true
                }
            }
            DeviceItemType.CONNECTED_BLUETOOTH_DEVICE -> {}
            DeviceItemType.SAVED_BLUETOOTH_DEVICE -> {
                deviceItem.cachedBluetoothDevice.connect()
                isClicked = true
            }
        }
        if (isClicked) {
            deviceItem.isEnabled = false
            deviceItem.alpha = BluetoothTileDialog.DISABLED_ALPHA
        }
        return isClicked
    }

    internal fun setDeviceItemFactoryListForTesting(list: List<DeviceItemFactory>) {
        deviceItemFactoryList = list
    }

    internal fun setDisplayPriorityForTesting(list: List<DeviceItemType>) {
        displayPriority = list
    }
}
