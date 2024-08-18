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
            DeviceSettingId.DEVICE_SETTING_ID_UNKNOWN,
            DeviceSettingId.DEVICE_SETTING_ID_HEADER,
            DeviceSettingId.DEVICE_SETTING_ID_ADVANCED_HEADER,
            DeviceSettingId.DEVICE_SETTING_ID_LE_AUDIO_HEADER,
            DeviceSettingId.DEVICE_SETTING_ID_HEARING_AID_PAIR_OTHER_BUTTON,
            DeviceSettingId.DEVICE_SETTING_ID_HEARING_AID_SPACE_LAYOUT,
            DeviceSettingId.DEVICE_SETTING_ID_ACTION_BUTTONS,
            DeviceSettingId.DEVICE_SETTING_ID_DEVICE_STYLUS,
            DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_EXTRA_CONTROL,
            DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_DEVICE_SLICE_CATEGORY,
            DeviceSettingId.DEVICE_SETTING_ID_DEVICE_COMPANION_APPS,
            DeviceSettingId.DEVICE_SETTING_ID_HEARING_DEVICE_GROUP,
            DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_AUDIO_DEVICE_TYPE_GROUP,
            DeviceSettingId.DEVICE_SETTING_ID_SPATIAL_AUDIO_GROUP,
            DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_PROFILES,
            DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_EXTRA_OPTIONS,
            DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_RELATED_TOOLS,
            DeviceSettingId.DEVICE_SETTING_ID_DATA_SYNC_GROUP,
            DeviceSettingId.DEVICE_SETTING_ID_KEYBOARD_SETTINGS,
            DeviceSettingId.DEVICE_SETTING_ID_DEVICE_DETAILS_FOOTER,
            DeviceSettingId.DEVICE_SETTING_ID_ANC,
        },
        open = true)
public @interface DeviceSettingId {
    /** Device setting ID is unknown. */
    int DEVICE_SETTING_ID_UNKNOWN = 0;

    /** Device setting ID for header. */
    int DEVICE_SETTING_ID_HEADER = 1;

    /** Device setting ID for advanced header. */
    int DEVICE_SETTING_ID_ADVANCED_HEADER = 2;

    /** Device setting ID for LeAudio header. */
    int DEVICE_SETTING_ID_LE_AUDIO_HEADER = 3;

    /** Device setting ID for hearing aid “pair other” button. */
    int DEVICE_SETTING_ID_HEARING_AID_PAIR_OTHER_BUTTON = 4;

    /** Device setting ID for hearing aid space layout. */
    int DEVICE_SETTING_ID_HEARING_AID_SPACE_LAYOUT = 5;

    /** Device setting ID for action buttons(Forget, Connect/Disconnect). */
    int DEVICE_SETTING_ID_ACTION_BUTTONS = 6;

    /** Device setting ID for stylus device. */
    int DEVICE_SETTING_ID_DEVICE_STYLUS = 7;

    /** Device setting ID for bluetooth extra control. */
    int DEVICE_SETTING_ID_BLUETOOTH_EXTRA_CONTROL = 8;

    /** Device setting ID for bluetooth device slice category. */
    int DEVICE_SETTING_ID_BLUETOOTH_DEVICE_SLICE_CATEGORY = 9;

    /** Device setting ID for device companion apps. */
    int DEVICE_SETTING_ID_DEVICE_COMPANION_APPS = 10;

    /** Device setting ID for hearing device group. */
    int DEVICE_SETTING_ID_HEARING_DEVICE_GROUP = 11;

    /** Device setting ID for bluetooth audio device type group. */
    int DEVICE_SETTING_ID_BLUETOOTH_AUDIO_DEVICE_TYPE_GROUP = 12;

    /** Device setting ID for spatial audio group. */
    int DEVICE_SETTING_ID_SPATIAL_AUDIO_GROUP = 13;

    /** Device setting ID for bluetooth profiles. */
    int DEVICE_SETTING_ID_BLUETOOTH_PROFILES = 14;

    /** Device setting ID for bluetooth extra options. */
    int DEVICE_SETTING_ID_BLUETOOTH_EXTRA_OPTIONS = 15;

    /** Device setting ID for bluetooth related tools. */
    int DEVICE_SETTING_ID_BLUETOOTH_RELATED_TOOLS = 16;

    /** Device setting ID for data sync group. */
    int DEVICE_SETTING_ID_DATA_SYNC_GROUP = 17;

    /** Device setting ID for keyboard settings. */
    int DEVICE_SETTING_ID_KEYBOARD_SETTINGS = 18;

    /** Device setting ID for device details footer. */
    int DEVICE_SETTING_ID_DEVICE_DETAILS_FOOTER = 19;

    /** Device setting ID for spatial audio group. */
    int DEVICE_SETTING_ID_SPATIAL_AUDIO_MULTI_TOGGLE = 20;

    /** Device setting ID for "More Settings" page. */
    int DEVICE_SETTING_ID_MORE_SETTINGS = 21;

    /** Device setting ID for ANC. */
    int DEVICE_SETTING_ID_ANC = 1001;
}
