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

package com.android.server.oemlock;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.oemlock.V1_0.IOemLock;
import android.hardware.oemlock.V1_0.OemLockSecureStatus;
import android.hardware.oemlock.V1_0.OemLockStatus;
import android.os.RemoteException;
import android.util.Slog;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * Uses the OEM lock HAL.
 */
class VendorLock extends OemLock {
    private static final String TAG = "OemLock";

    private Context mContext;
    private IOemLock mOemLock;

    static IOemLock getOemLockHalService() {
        try {
            return IOemLock.getService();
        } catch (NoSuchElementException e) {
            Slog.i(TAG, "OemLock HAL not present on device");
            return null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    VendorLock(Context context, IOemLock oemLock) {
        mContext = context;
        mOemLock = oemLock;
    }

    @Override
    @Nullable
    String getLockName() {
        final Integer[] requestStatus = new Integer[1];
        final String[] lockName = new String[1];

        try {
            mOemLock.getName((status, name) -> {
                requestStatus[0] = status;
                lockName[0] = name;
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get name from HAL", e);
            throw e.rethrowFromSystemServer();
        }

        switch (requestStatus[0]) {
            case OemLockStatus.OK:
                // Success
                return lockName[0];

            case OemLockStatus.FAILED:
                Slog.e(TAG, "Failed to get OEM lock name.");
                return null;

            default:
                Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                return null;
        }
    }

    @Override
    void setOemUnlockAllowedByCarrier(boolean allowed, @Nullable byte[] signature) {
        try {
            ArrayList<Byte> signatureBytes = toByteArrayList(signature);
            switch (mOemLock.setOemUnlockAllowedByCarrier(allowed, signatureBytes)) {
                case OemLockSecureStatus.OK:
                    Slog.i(TAG, "Updated carrier allows OEM lock state to: " + allowed);
                    return;

                case OemLockSecureStatus.INVALID_SIGNATURE:
                    if (signatureBytes.isEmpty()) {
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
        final Integer[] requestStatus = new Integer[1];
        final Boolean[] allowedByCarrier = new Boolean[1];

        try {
            mOemLock.isOemUnlockAllowedByCarrier((status, allowed) -> {
                requestStatus[0] = status;
                allowedByCarrier[0] = allowed;
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get carrier state from HAL");
            throw e.rethrowFromSystemServer();
        }

        switch (requestStatus[0]) {
            case OemLockStatus.OK:
                // Success
                return allowedByCarrier[0];

            default:
                Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                // Fallthrough
            case OemLockStatus.FAILED:
                throw new RuntimeException("Failed to get carrier OEM unlock state");
        }
    }

    @Override
    void setOemUnlockAllowedByDevice(boolean allowedByDevice) {
        try {
            switch (mOemLock.setOemUnlockAllowedByDevice(allowedByDevice)) {
                case OemLockSecureStatus.OK:
                    Slog.i(TAG, "Updated device allows OEM lock state to: " + allowedByDevice);
                    return;

                default:
                    Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                    // Fallthrough
                case OemLockSecureStatus.FAILED:
                    throw new RuntimeException("Failed to set device OEM unlock state");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set device state with HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    boolean isOemUnlockAllowedByDevice() {
        final Integer[] requestStatus = new Integer[1];
        final Boolean[] allowedByDevice = new Boolean[1];

        try {
            mOemLock.isOemUnlockAllowedByDevice((status, allowed) -> {
                requestStatus[0] = status;
                allowedByDevice[0] = allowed;
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get devie state from HAL");
            throw e.rethrowFromSystemServer();
        }

        switch (requestStatus[0]) {
            case OemLockStatus.OK:
                // Success
                return allowedByDevice[0];

            default:
                Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                // Fallthrough
            case OemLockStatus.FAILED:
                throw new RuntimeException("Failed to get device OEM unlock state");
        }
    }

    private ArrayList<Byte> toByteArrayList(byte[] data) {
        if (data == null) {
            return new ArrayList<Byte>();
        }
        ArrayList<Byte> result = new ArrayList<Byte>(data.length);
        for (final byte b : data) {
            result.add(b);
        }
        return result;
    }
}
