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
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Repository to get CachedBluetoothDevices for the Bluetooth Dialog. */
@SysUISingleton
internal class BluetoothTileDialogRepository
@Inject
constructor(
    private val localBluetoothManager: LocalBluetoothManager?,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) {
    internal val cachedDevices: Collection<CachedBluetoothDevice>
        get() {
            return if (
                localBluetoothManager == null ||
                    bluetoothAdapter == null ||
                    !bluetoothAdapter.isEnabled
            ) {
                emptyList()
            } else {
                localBluetoothManager.cachedDeviceManager.cachedDevicesCopy
            }
        }
}
