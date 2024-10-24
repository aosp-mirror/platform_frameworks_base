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

import android.os.Parcel;

import androidx.annotation.NonNull;

/** An abstract class representing a device setting preference. */
public abstract class DeviceSettingPreference {
    @DeviceSettingType private final int mSettingType;

    public static final DeviceSettingPreference UNKNOWN =
            new DeviceSettingPreference(DeviceSettingType.DEVICE_SETTING_TYPE_UNKNOWN) {};

    protected DeviceSettingPreference(@DeviceSettingType int settingType) {
        mSettingType = settingType;
    }

    /** Read a {@link DeviceSettingPreference} instance from {@link Parcel} */
    @NonNull
    public static DeviceSettingPreference readFromParcel(@NonNull Parcel in) {
        int type = in.readInt();
        switch (type) {
            case DeviceSettingType.DEVICE_SETTING_TYPE_ACTION_SWITCH:
                return ActionSwitchPreference.readFromParcel(in);
            case DeviceSettingType.DEVICE_SETTING_TYPE_MULTI_TOGGLE:
                return MultiTogglePreference.readFromParcel(in);
            case DeviceSettingType.DEVICE_SETTING_TYPE_FOOTER:
                return DeviceSettingFooterPreference.readFromParcel(in);
            case DeviceSettingType.DEVICE_SETTING_TYPE_HELP:
                return DeviceSettingHelpPreference.readFromParcel(in);
            default:
                return UNKNOWN;
        }
    }

    /** Writes the instance to {@link Parcel}. */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSettingType);
    }

    /**
     * Gets the setting type, as defined by IntDef {@link DeviceSettingType}.
     *
     * @return the setting type.
     */
    @DeviceSettingType
    public int getSettingType() {
        return mSettingType;
    }
}
