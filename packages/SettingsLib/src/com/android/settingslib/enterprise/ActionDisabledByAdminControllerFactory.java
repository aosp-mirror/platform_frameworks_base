/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.enterprise;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;

import android.app.admin.DevicePolicyManager;

/**
 * A factory that returns the relevant instance of {@link ActionDisabledByAdminController}.
 */
public class ActionDisabledByAdminControllerFactory {

    /**
     * Returns the relevant instance of {@link ActionDisabledByAdminController}.
     */
    public static ActionDisabledByAdminController createInstance(
            DevicePolicyManager dpm,
            ActionDisabledLearnMoreButtonLauncher helper,
            DeviceAdminStringProvider deviceAdminStringProvider) {
        if (isFinancedDevice(dpm)) {
            return new FinancedDeviceActionDisabledByAdminController(
                    helper, deviceAdminStringProvider);
        }
        return new ManagedDeviceActionDisabledByAdminController(
                helper, deviceAdminStringProvider);
    }

    private static boolean isFinancedDevice(DevicePolicyManager dpm) {
        return dpm.isDeviceManaged() && dpm.getDeviceOwnerType(
                dpm.getDeviceOwnerComponentOnAnyUser()) == DEVICE_OWNER_TYPE_FINANCED;
    }
}
