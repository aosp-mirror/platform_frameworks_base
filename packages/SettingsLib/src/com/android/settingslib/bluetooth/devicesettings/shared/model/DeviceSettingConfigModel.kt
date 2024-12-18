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

package com.android.settingslib.bluetooth.devicesettings.shared.model

import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId

/** Models a device setting config. */
data class DeviceSettingConfigModel(
    /** Items need to be shown in device details main page. */
    val mainItems: List<DeviceSettingConfigItemModel>,
    /** Items need to be shown in device details more settings page. */
    val moreSettingsItems: List<DeviceSettingConfigItemModel>,
    /**
     * Help button which need to be shown on the top right corner of device details more settings
     * page.
     */
    val moreSettingsHelpItem: DeviceSettingConfigItemModel?,
)

/** Models a device setting item in config. */
sealed interface DeviceSettingConfigItemModel {
    @DeviceSettingId val settingId: Int
    val highlighted: Boolean

    /** A built-in item in Settings. */
    sealed interface BuiltinItem : DeviceSettingConfigItemModel {
        @DeviceSettingId override val settingId: Int
        val preferenceKey: String

        /** A general built-in item in Settings. */
        data class CommonBuiltinItem(
            @DeviceSettingId override val settingId: Int,
            override val highlighted: Boolean,
            override val preferenceKey: String,
        ) : BuiltinItem

        /** A bluetooth profiles in Settings. */
        data class BluetoothProfilesItem(
            @DeviceSettingId override val settingId: Int,
            override val highlighted: Boolean,
            override val preferenceKey: String,
            val invisibleProfiles: List<String>,
        ) : BuiltinItem
    }

    /** A remote item provided by other apps. */
    data class AppProvidedItem(
        @DeviceSettingId override val settingId: Int,
        override val highlighted: Boolean,
    ) : DeviceSettingConfigItemModel
}
