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

/** An abstract class representing a device setting action. */
public abstract class DeviceSettingAction {
    @DeviceSettingActionType private final int mActionType;

    public static final DeviceSettingAction EMPTY_ACTION =
            new DeviceSettingAction(DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_UNKNOWN) {};

    protected DeviceSettingAction(@DeviceSettingActionType int actionType) {
        mActionType = actionType;
    }

    /** Read a {@link DeviceSettingPreference} instance from {@link Parcel} */
    @NonNull
    public static DeviceSettingAction readFromParcel(@NonNull Parcel in) {
        int type = in.readInt();
        return switch (type) {
            case DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_INTENT ->
                    DeviceSettingIntentAction.readFromParcel(in);
            case DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_PENDING_INTENT ->
                    DeviceSettingPendingIntentAction.readFromParcel(in);
            default -> EMPTY_ACTION;
        };
    }

    /** Writes the instance to {@link Parcel}. */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mActionType);
    }

    /**
     * Gets the setting action type, as defined by IntDef {@link DeviceSettingActionType}.
     *
     * @return the setting action type.
     */
    @DeviceSettingType
    public int getActionType() {
        return mActionType;
    }
}
