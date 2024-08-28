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

package com.android.settingslib.bluetooth.devicesettings;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.SOURCE)
@IntDef(
        value = {
            DeviceSettingType.DEVICE_SETTING_TYPE_UNKNOWN,
            DeviceSettingType.DEVICE_SETTING_TYPE_ACTION_SWITCH,
            DeviceSettingType.DEVICE_SETTING_TYPE_MULTI_TOGGLE,
            DeviceSettingType.DEVICE_SETTING_TYPE_FOOTER,
        },
        open = true)
public @interface DeviceSettingType {
    /** Device setting type is unknown. */
    int DEVICE_SETTING_TYPE_UNKNOWN = 0;

    /** Device setting type is action/switch preference. */
    int DEVICE_SETTING_TYPE_ACTION_SWITCH = 1;

    /** Device setting type is multi-toggle preference. */
    int DEVICE_SETTING_TYPE_MULTI_TOGGLE = 2;

    /** Device setting type is footer preference. */
    int DEVICE_SETTING_TYPE_FOOTER = 3;

    /** Device setting type is "help" preference. */
    int DEVICE_SETTING_TYPE_HELP = 4;
}
