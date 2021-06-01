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
import android.content.Context;

/**
 * A factory that returns the relevant instance of {@link ActionDisabledByAdminController}.
 */
public final class ActionDisabledByAdminControllerFactory {

    /**
     * Returns the relevant instance of {@link ActionDisabledByAdminController}.
     */
    public static ActionDisabledByAdminController createInstance(Context context,
            DeviceAdminStringProvider stringProvider) {
        return isFinancedDevice(context)
                ? new FinancedDeviceActionDisabledByAdminController(stringProvider)
                : new ManagedDeviceActionDisabledByAdminController(stringProvider);
    }

    private static boolean isFinancedDevice(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm.isDeviceManaged() && dpm.getDeviceOwnerType(
                dpm.getDeviceOwnerComponentOnAnyUser()) == DEVICE_OWNER_TYPE_FINANCED;
    }

    private ActionDisabledByAdminControllerFactory() {
        throw new UnsupportedOperationException("provides only static methods");
    }
}
