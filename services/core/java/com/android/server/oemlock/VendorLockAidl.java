/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.oemlock;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.oemlock.IOemLock;
import android.hardware.oemlock.OemLockSecureStatus;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

/** Uses the OEM lock HAL. */
class VendorLockAidl extends OemLock {
    private static final String TAG = "OemLock";
    private IOemLock mOemLock;

    static IOemLock getOemLockHalService() {
        return IOemLock.Stub.asInterface(
                ServiceManager.waitForDeclaredService(IOemLock.DESCRIPTOR + "/default"));
    }

    VendorLockAidl(Context context) {
        mOemLock = getOemLockHalService();
    }

    @Override
    @Nullable
    String getLockName() {
        try {
            return mOemLock.getName();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get name from HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    void setOemUnlockAllowedByCarrier(boolean allowed, @Nullable byte[] signature) {
        try {
            final int status;
            if (signature == null) {
                status = mOemLock.setOemUnlockAllowedByCarrier(allowed, new byte[0]);
            } else {
                status = mOemLock.setOemUnlockAllowedByCarrier(allowed, signature);
            }
            switch (status) {
                case OemLockSecureStatus.OK:
                    Slog.i(TAG, "Updated carrier allows OEM lock state to: " + allowed);
                    return;

                case OemLockSecureStatus.INVALID_SIGNATURE:
                    if (signature == null) {
                        throw new IllegalArgumentException("Signature required for carrier unlock");
                    }
                    throw new SecurityException(
                            "Invalid signature used in attempt to carrier unlock");

                default:
                    Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                    // Fallthrough
                case OemLockSecureStatus.FAILED:
                    throw new RuntimeException("Failed to set carrier OEM unlock state");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set carrier state with HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    boolean isOemUnlockAllowedByCarrier() {
        try {
            return mOemLock.isOemUnlockAllowedByCarrier();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get carrier state from HAL");
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    void setOemUnlockAllowedByDevice(boolean allowedByDevice) {
        try {
            mOemLock.setOemUnlockAllowedByDevice(allowedByDevice);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set device state with HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    boolean isOemUnlockAllowedByDevice() {

        try {
            return mOemLock.isOemUnlockAllowedByDevice();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get devie state from HAL");
            throw e.rethrowFromSystemServer();
        }
    }
}
