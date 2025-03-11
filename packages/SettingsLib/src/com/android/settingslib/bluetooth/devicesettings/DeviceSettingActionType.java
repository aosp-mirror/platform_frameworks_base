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
            DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_UNKNOWN,
            DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_INTENT,
            DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_PENDING_INTENT,
        },
        open = true)
public @interface DeviceSettingActionType {
    /** Device setting action type is unknown. */
    int DEVICE_SETTING_ACTION_TYPE_UNKNOWN = 0;

    /** Device setting action is an intent to start an activity. */
    int DEVICE_SETTING_ACTION_TYPE_INTENT = 1;

    /** Device setting action is a pending intent. */
    int DEVICE_SETTING_ACTION_TYPE_PENDING_INTENT = 2;
}
