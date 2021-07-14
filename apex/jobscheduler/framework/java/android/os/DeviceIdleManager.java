/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;

/**
 * Access to the service that keeps track of device idleness and drives low power mode based on
 * that.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.DEVICE_IDLE_CONTROLLER)
public class DeviceIdleManager {
    private final Context mContext;
    private final IDeviceIdleController mService;

    /**
     * @hide
     */
    public DeviceIdleManager(@NonNull Context context, @NonNull IDeviceIdleController service) {
        mContext = context;
        mService = service;
    }

    IDeviceIdleController getService() {
        return mService;
    }

    /**
     * Ends any active idle session.
     *
     * @param reason The reason to end. Used for debugging purposes.
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void endIdle(@NonNull String reason) {
        try {
            mService.exitIdle(reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return package names the system has white-listed to opt out of power save restrictions,
     * except for device idle mode.
     *
     * @hide Should be migrated to PowerExemptionManager
     */
    @TestApi
    public @NonNull String[] getSystemPowerWhitelistExceptIdle() {
        try {
            return mService.getSystemPowerWhitelistExceptIdle();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return package names the system has white-listed to opt out of power save restrictions for
     * all modes.
     *
     * @hide Should be migrated to PowerExemptionManager
     */
    @TestApi
    public @NonNull String[] getSystemPowerWhitelist() {
        try {
            return mService.getSystemPowerWhitelist();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
