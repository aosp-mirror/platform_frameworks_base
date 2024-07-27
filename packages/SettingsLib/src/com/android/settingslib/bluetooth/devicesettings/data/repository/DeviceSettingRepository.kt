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

package com.android.settingslib.bluetooth.devicesettings.data.repository

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Provides functionality to control bluetooth device settings. */
interface DeviceSettingRepository {
    /** Gets config for the bluetooth device, returns null if failed. */
    suspend fun getDeviceSettingsConfig(cachedDevice: CachedBluetoothDevice): DeviceSettingsConfig?

    /** Gets all device settings for the bluetooth device. */
    fun getDeviceSettingList(
        cachedDevice: CachedBluetoothDevice,
    ): Flow<List<DeviceSetting>?>

    /** Gets device setting for the bluetooth device. */
    fun getDeviceSetting(
        cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId settingId: Int
    ): Flow<DeviceSetting?>

    /** Updates device setting for the bluetooth device. */
    suspend fun updateDeviceSettingState(
        cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId deviceSettingId: Int,
        deviceSettingPreferenceState: DeviceSettingPreferenceState,
    )
}

class DeviceSettingRepositoryImpl(
    private val context: Context,
    private val bluetoothAdaptor: BluetoothAdapter,
    private val coroutineScope: CoroutineScope,
    private val backgroundCoroutineContext: CoroutineContext,
) : DeviceSettingRepository {
    private val deviceSettings =
        ConcurrentHashMap<CachedBluetoothDevice, DeviceSettingServiceConnection>()

    override suspend fun getDeviceSettingsConfig(
        cachedDevice: CachedBluetoothDevice
    ): DeviceSettingsConfig? = createConnectionIfAbsent(cachedDevice).getDeviceSettingsConfig()

    override fun getDeviceSettingList(
        cachedDevice: CachedBluetoothDevice
    ): Flow<List<DeviceSetting>?> = createConnectionIfAbsent(cachedDevice).getDeviceSettingList()

    override fun getDeviceSetting(
        cachedDevice: CachedBluetoothDevice,
        settingId: Int
    ): Flow<DeviceSetting?> = createConnectionIfAbsent(cachedDevice).getDeviceSetting(settingId)

    override suspend fun updateDeviceSettingState(
        cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId deviceSettingId: Int,
        deviceSettingPreferenceState: DeviceSettingPreferenceState,
    ) =
        createConnectionIfAbsent(cachedDevice)
            .updateDeviceSettings(deviceSettingId, deviceSettingPreferenceState)

    private fun createConnectionIfAbsent(
        cachedDevice: CachedBluetoothDevice
    ): DeviceSettingServiceConnection =
        deviceSettings.computeIfAbsent(cachedDevice) {
            DeviceSettingServiceConnection(
                cachedDevice,
                context,
                bluetoothAdaptor,
                coroutineScope,
                backgroundCoroutineContext,
            )
        }
}
