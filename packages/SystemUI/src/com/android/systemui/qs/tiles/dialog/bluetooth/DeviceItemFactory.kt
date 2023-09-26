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

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.res.R

private val backgroundOn = R.drawable.settingslib_switch_bar_bg_on
private val connected = R.string.quick_settings_bluetooth_device_connected
private val saved = R.string.quick_settings_bluetooth_device_saved

/** Factories to create different types of Bluetooth device items from CachedBluetoothDevice. */
internal abstract class DeviceItemFactory {
    abstract fun isFilterMatched(
        cachedDevice: CachedBluetoothDevice,
        audioManager: AudioManager?
    ): Boolean

    abstract fun create(context: Context, cachedDevice: CachedBluetoothDevice): DeviceItem
}

internal class AvailableMediaDeviceItemFactory : DeviceItemFactory() {
    override fun isFilterMatched(
        cachedDevice: CachedBluetoothDevice,
        audioManager: AudioManager?
    ): Boolean {
        return BluetoothUtils.isAvailableMediaBluetoothDevice(cachedDevice, audioManager)
    }

    // TODO(b/298124674): move create() to the abstract class to reduce duplicate code
    override fun create(context: Context, cachedDevice: CachedBluetoothDevice): DeviceItem {
        return DeviceItem(
            type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
            cachedBluetoothDevice = cachedDevice,
            deviceName = cachedDevice.name,
            connectionSummary = cachedDevice.connectionSummary.takeUnless { it.isNullOrEmpty() }
                    ?: context.getString(connected),
            iconWithDescription =
                BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice).let { p ->
                    Pair(p.first, p.second)
                },
            background = context.getDrawable(backgroundOn),
            isEnabled = !cachedDevice.isBusy,
            alpha =
                if (cachedDevice.isBusy) BluetoothTileDialog.DISABLED_ALPHA
                else BluetoothTileDialog.ENABLED_ALPHA,
        )
    }
}

internal class ConnectedDeviceItemFactory : DeviceItemFactory() {
    override fun isFilterMatched(
        cachedDevice: CachedBluetoothDevice,
        audioManager: AudioManager?
    ): Boolean {
        return BluetoothUtils.isConnectedBluetoothDevice(cachedDevice, audioManager)
    }

    override fun create(context: Context, cachedDevice: CachedBluetoothDevice): DeviceItem {
        return DeviceItem(
            type = DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
            cachedBluetoothDevice = cachedDevice,
            deviceName = cachedDevice.name,
            connectionSummary = cachedDevice.connectionSummary.takeUnless { it.isNullOrEmpty() }
                    ?: context.getString(connected),
            iconWithDescription =
                BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice).let { p ->
                    Pair(p.first, p.second)
                },
            isEnabled = !cachedDevice.isBusy,
            alpha =
                if (cachedDevice.isBusy) BluetoothTileDialog.DISABLED_ALPHA
                else BluetoothTileDialog.ENABLED_ALPHA,
        )
    }
}

internal class SavedDeviceItemFactory : DeviceItemFactory() {
    override fun isFilterMatched(
        cachedDevice: CachedBluetoothDevice,
        audioManager: AudioManager?
    ): Boolean {
        return cachedDevice.bondState == BluetoothDevice.BOND_BONDED && !cachedDevice.isConnected
    }

    override fun create(context: Context, cachedDevice: CachedBluetoothDevice): DeviceItem {
        return DeviceItem(
            type = DeviceItemType.SAVED_BLUETOOTH_DEVICE,
            cachedBluetoothDevice = cachedDevice,
            deviceName = cachedDevice.name,
            connectionSummary = cachedDevice.connectionSummary.takeUnless { it.isNullOrEmpty() }
                    ?: context.getString(saved),
            iconWithDescription =
                BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice).let { p ->
                    Pair(p.first, p.second)
                },
            isEnabled = !cachedDevice.isBusy,
            alpha =
                if (cachedDevice.isBusy) BluetoothTileDialog.DISABLED_ALPHA
                else BluetoothTileDialog.ENABLED_ALPHA,
        )
    }
}
