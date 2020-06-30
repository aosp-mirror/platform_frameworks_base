/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor implements RemovalConsumer {

    private static final String TAG = "Biometrics/RemovalClient";

    protected final int mBiometricId;
    private final BiometricUtils mBiometricUtils;

    public RemovalClient(@NonNull Context context, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int biometricId,
            int userId, boolean restricted, @NonNull String owner, @NonNull BiometricUtils utils,
            int sensorId, int statsModality) {
        super(context, token, listener, userId, restricted, owner, 0 /* cookie */, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_REMOVE,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricId = biometricId;
        mBiometricUtils = utils;
    }

    @Override
    public int start() {
        // The biometric template ids will be removed when we get confirmation from the HAL
        try {
            final int result = startHalOperation();
            if (result != 0) {
                Slog.w(TAG, "startRemove with id = " + mBiometricId + " failed, result=" +
                        result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startRemove failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopRemove: already cancelled!");
            return 0;
        }

        try {
            final int result = stopHalOperation();
            if (result != 0) {
                Slog.w(TAG, "stopRemoval failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer removing");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopRemoval failed", e);
            return BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS;
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    /*
     * @return true if we're done.
     */
    private boolean sendRemoved(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        try {
            if (getListener() != null) {
                getListener().onRemoved(identifier, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify Removed:", e);
        }
        return remaining == 0;
    }

    @Override
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (identifier.getBiometricId() != 0) {
            mBiometricUtils.removeBiometricForUser(getContext(), getTargetUserId(),
                    identifier.getBiometricId());
        }
        return sendRemoved(identifier, remaining);
    }
}
