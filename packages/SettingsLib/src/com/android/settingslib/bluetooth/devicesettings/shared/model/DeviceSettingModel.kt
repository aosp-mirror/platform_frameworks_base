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

import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId

/** Models a device setting. */
sealed interface DeviceSettingModel {
    val cachedDevice: CachedBluetoothDevice
    @DeviceSettingId val id: Int

    /** Models a device setting which should be displayed as an action/switch preference. */
    data class ActionSwitchPreference(
        override val cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId override val id: Int,
        val title: String,
        val summary: String? = null,
        val icon: DeviceSettingIcon? = null,
        val intent: Intent? = null,
        val switchState: DeviceSettingStateModel.ActionSwitchPreferenceState? = null,
        val isAllowedChangingState: Boolean = true,
        val updateState: ((DeviceSettingStateModel.ActionSwitchPreferenceState) -> Unit)? = null,
    ) : DeviceSettingModel

    /** Models a device setting which should be displayed as a multi-toggle preference. */
    data class MultiTogglePreference(
        override val cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId override val id: Int,
        val title: String,
        val toggles: List<ToggleModel>,
        val isActive: Boolean,
        val state: DeviceSettingStateModel.MultiTogglePreferenceState,
        val isAllowedChangingState: Boolean,
        val updateState: (DeviceSettingStateModel.MultiTogglePreferenceState) -> Unit
    ) : DeviceSettingModel

    /** Models a footer preference. */
    data class FooterPreference(
        override val cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId override val id: Int,
        val footerText: String,
    ) : DeviceSettingModel

    /** Models a help preference displayed on the top right corner of the fragment. */
    data class HelpPreference(
        override val cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId override val id: Int,
        val intent: Intent,
    ) : DeviceSettingModel

    /** Models an unknown preference. */
    data class Unknown(
        override val cachedDevice: CachedBluetoothDevice,
        @DeviceSettingId override val id: Int
    ) : DeviceSettingModel
}

/** Models a toggle in [DeviceSettingModel.MultiTogglePreference]. */
data class ToggleModel(val label: String, val icon: DeviceSettingIcon)

/** Models an icon in device settings. */
sealed interface DeviceSettingIcon {

    data class BitmapIcon(val bitmap: Bitmap) : DeviceSettingIcon

    data class ResourceIcon(@DrawableRes val resId: Int) : DeviceSettingIcon
}
