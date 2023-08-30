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
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.res.R

/** Factories to create different types of Bluetooth device items from CachedBluetoothDevice. */
internal abstract class DeviceItemFactory {
    internal val connected = R.string.quick_settings_bluetooth_device_connected
    abstract fun predicate(cachedBluetoothDevice: CachedBluetoothDevice): Boolean
    abstract fun create(context: Context, cachedBluetoothDevice: CachedBluetoothDevice): DeviceItem
}

internal class AvailableMediaDeviceItemFactory : DeviceItemFactory() {
    // TODO(b/298124674): Add actual predicate
    override fun predicate(cachedBluetoothDevice: CachedBluetoothDevice): Boolean = true

    override fun create(context: Context, cachedDevice: CachedBluetoothDevice) =
        DeviceItem(
            type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
            cachedBluetoothDevice = cachedDevice,
            deviceName = cachedDevice.name,
            connectionSummary = cachedDevice.connectionSummary.takeUnless { it.isNullOrEmpty() }
                    ?: context.getString(connected),
            iconWithDescription =
                BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice).let { p ->
                    Pair(p.first, p.second)
                },
            background = R.drawable.settingslib_switch_bar_bg_on
        )
}
