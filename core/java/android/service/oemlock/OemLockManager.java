/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.oemlock;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

/**
 * Interface for managing the OEM lock on the device.
 *
 * This will only be available if the device implements OEM lock protection.
 *
 * Multiple actors have an opinion on whether the device can be OEM unlocked and they must all be in
 * agreement for unlock to be possible.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.OEM_LOCK_SERVICE)
public class OemLockManager {
    private IOemLockService mService;

    /** @hide */
    public OemLockManager(IOemLockService service) {
        mService = service;
    }

    /**
     * Sets whether the carrier has allowed this device to be OEM unlocked.
     *
     * Depending on the implementation, the validity of the request might need to be proved. This
     * can be acheived by passing a signature that the system will use to verify the request is
     * legitimate.
     *
     * All actors involved must agree for OEM unlock to be possible.
     *
     * @param allowed Whether the device should be allowed to be unlocked.
     * @param signature Optional proof of request validity, {@code null} for none.
     * @throws IllegalArgumentException if a signature is required but was not provided.
     * @throws SecurityException if the wrong signature was provided.
     *
     * @see #isOemUnlockAllowedByCarrier()
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_CARRIER_OEM_UNLOCK_STATE)
    public void setOemUnlockAllowedByCarrier(boolean allowed, @Nullable byte[] signature) {
        try {
            mService.setOemUnlockAllowedByCarrier(allowed, signature);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the carrier has allowed this device to be OEM unlocked.
     * @return Whether OEM unlock is allowed by the carrier, or true if no OEM lock is present.
     *
     * @see #setOemUnlockAllowedByCarrier(boolean, byte[])
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_CARRIER_OEM_UNLOCK_STATE)
    public boolean isOemUnlockAllowedByCarrier() {
        try {
            return mService.isOemUnlockAllowedByCarrier();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets whether the user has allowed this device to be unlocked.
     *
     * All actors involved must agree for OEM unlock to be possible.
     *
     * @param allowed Whether the device should be allowed to be unlocked.
     * @throws SecurityException if the user is not allowed to unlock the device.
     *
     * @see #isOemUnlockAllowedByUser()
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USER_OEM_UNLOCK_STATE)
    public void setOemUnlockAllowedByUser(boolean allowed) {
        try {
            mService.setOemUnlockAllowedByUser(allowed);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether, or not, the user has allowed this device to be OEM unlocked.
     * @return Whether OEM unlock is allowed by the user, or true if no OEM lock is present.
     *
     * @see #setOemUnlockAllowedByUser(boolean)
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USER_OEM_UNLOCK_STATE)
    public boolean isOemUnlockAllowedByUser() {
        try {
            return mService.isOemUnlockAllowedByUser();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return Whether the bootloader is able to OEM unlock the device.
     *
     * @hide
     */
    public boolean isOemUnlockAllowed() {
        try {
            return mService.isOemUnlockAllowed();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return Whether the device has been OEM unlocked by the bootloader.
     *
     * @hide
     */
    public boolean isDeviceOemUnlocked() {
        try {
            return mService.isDeviceOemUnlocked();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
