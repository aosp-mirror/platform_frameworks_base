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
import android.text.TextUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreference
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingContract
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingFooterPreference
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingHelpPreference
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference
import com.android.settingslib.bluetooth.devicesettings.ToggleInfo
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigItemModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigItemModel.AppProvidedItem
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigItemModel.BuiltinItem.BluetoothProfilesItem
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigItemModel.BuiltinItem.CommonBuiltinItem
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingIcon
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingStateModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.ToggleModel
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Provides functionality to control bluetooth device settings. */
interface DeviceSettingRepository {
    /** Gets config for the bluetooth device, returns null if failed. */
    suspend fun getDeviceSettingsConfig(
        cachedDevice: CachedBluetoothDevice
    ): DeviceSettingConfigModel?

    /** Gets device setting for the bluetooth device. */
    fun getDeviceSetting(
        cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId settingId: Int
    ): Flow<DeviceSettingModel?>
}

class DeviceSettingRepositoryImpl(
    private val context: Context,
    private val bluetoothAdaptor: BluetoothAdapter,
    private val coroutineScope: CoroutineScope,
    private val backgroundCoroutineContext: CoroutineContext,
) : DeviceSettingRepository {
    private val connectionCache:
        LoadingCache<CachedBluetoothDevice, DeviceSettingServiceConnection> =
        CacheBuilder.newBuilder()
            .weakValues()
            .build(
                object : CacheLoader<CachedBluetoothDevice, DeviceSettingServiceConnection>() {
                    override fun load(
                        cachedDevice: CachedBluetoothDevice
                    ): DeviceSettingServiceConnection =
                        DeviceSettingServiceConnection(
                            cachedDevice,
                            context,
                            bluetoothAdaptor,
                            coroutineScope,
                            backgroundCoroutineContext,
                        )
                })

    override suspend fun getDeviceSettingsConfig(
        cachedDevice: CachedBluetoothDevice
    ): DeviceSettingConfigModel? =
        connectionCache.get(cachedDevice).getDeviceSettingsConfig()?.toModel()

    override fun getDeviceSetting(
        cachedDevice: CachedBluetoothDevice,
        settingId: Int
    ): Flow<DeviceSettingModel?> =
        connectionCache.get(cachedDevice).let { connection ->
            connection.getDeviceSetting(settingId).map { it?.toModel(cachedDevice, connection) }
        }

    private fun DeviceSettingsConfig.toModel(): DeviceSettingConfigModel =
        DeviceSettingConfigModel(
            mainItems = mainContentItems.map { it.toModel() },
            moreSettingsItems = moreSettingsItems.map { it.toModel() },
            moreSettingsHelpItem = moreSettingsHelpItem?.toModel(), )

    private fun DeviceSettingItem.toModel(): DeviceSettingConfigItemModel {
        return if (!TextUtils.isEmpty(preferenceKey)) {
            if (settingId == DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_PROFILES) {
                BluetoothProfilesItem(
                    settingId,
                    highlighted,
                    preferenceKey!!,
                    extras.getStringArrayList(DeviceSettingContract.INVISIBLE_PROFILES)
                        ?: emptyList()
                )
            } else {
                CommonBuiltinItem(settingId, highlighted, preferenceKey!!)
            }
        } else {
            AppProvidedItem(settingId, highlighted)
        }
    }

    private fun DeviceSetting.toModel(
        cachedDevice: CachedBluetoothDevice,
        connection: DeviceSettingServiceConnection
    ): DeviceSettingModel =
        when (val pref = preference) {
            is ActionSwitchPreference ->
                DeviceSettingModel.ActionSwitchPreference(
                    cachedDevice = cachedDevice,
                    id = settingId,
                    title = pref.title,
                    summary = pref.summary,
                    icon = pref.icon?.let { DeviceSettingIcon.BitmapIcon(it) },
                    isAllowedChangingState = pref.isAllowedChangingState,
                    intent = pref.intent,
                    switchState =
                        if (pref.hasSwitch()) {
                            DeviceSettingStateModel.ActionSwitchPreferenceState(pref.checked)
                        } else {
                            null
                        },
                    updateState = { newState ->
                        coroutineScope.launch(backgroundCoroutineContext) {
                            connection.updateDeviceSettings(
                                settingId,
                                newState.toParcelable(),
                            )
                        }
                    },
                )
            is MultiTogglePreference ->
                DeviceSettingModel.MultiTogglePreference(
                    cachedDevice = cachedDevice,
                    id = settingId,
                    title = pref.title,
                    toggles = pref.toggleInfos.map { it.toModel() },
                    isAllowedChangingState = pref.isAllowedChangingState,
                    isActive = pref.isActive,
                    state = DeviceSettingStateModel.MultiTogglePreferenceState(pref.state),
                    updateState = { newState ->
                        coroutineScope.launch(backgroundCoroutineContext) {
                            connection.updateDeviceSettings(settingId, newState.toParcelable())
                        }
                    },
                )
            is DeviceSettingFooterPreference -> DeviceSettingModel.FooterPreference(
                cachedDevice = cachedDevice,
                id = settingId, footerText = pref.footerText)
            is DeviceSettingHelpPreference -> DeviceSettingModel.HelpPreference(
                cachedDevice = cachedDevice,
                id = settingId, intent = pref.intent)
            else -> DeviceSettingModel.Unknown(cachedDevice, settingId)
        }

    private fun ToggleInfo.toModel(): ToggleModel =
        ToggleModel(label, DeviceSettingIcon.BitmapIcon(icon))
}
