/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs.tiles

import android.provider.DeviceConfig
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags

enum class QSSettingsPanel {
    DEFAULT,
    OPEN_LONG_PRESS,
    OPEN_CLICK,
    USE_DETAIL
}

fun getQSSettingsPanelOption(): QSSettingsPanel =
        when (DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.QS_USE_SETTINGS_PANELS, 0)) {
            1 -> QSSettingsPanel.OPEN_LONG_PRESS
            2 -> QSSettingsPanel.OPEN_CLICK
            3 -> QSSettingsPanel.USE_DETAIL
            else -> QSSettingsPanel.DEFAULT
        }