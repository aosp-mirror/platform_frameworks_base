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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Map;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient<T> extends ClientMonitor<T> implements RemovalConsumer {

    private static final String TAG = "Biometrics/RemovalClient";

    protected final int mBiometricId;
    private final BiometricUtils mBiometricUtils;
    private final Map<Integer, Long> mAuthenticatorIds;

    public RemovalClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int biometricId, int userId, @NonNull String owner, @NonNull BiometricUtils utils,
            int sensorId, @NonNull Map<Integer, Long> authenticatorIds, int statsModality) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_REMOVE,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricId = biometricId;
        mBiometricUtils = utils;
        mAuthenticatorIds = authenticatorIds;
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    public void start(@NonNull FinishCallback finishCallback) {
        super.start(finishCallback);

        // The biometric template ids will be removed when we get confirmation from the HAL
        startHalOperation();
    }

    @Override
    public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (identifier.getBiometricId() != 0) {
            mBiometricUtils.removeBiometricForUser(getContext(), getTargetUserId(),
                    identifier.getBiometricId());
        }

        try {
            if (getListener() != null) {
                getListener().onRemoved(identifier, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify Removed:", e);
        }

        if (remaining == 0) {
            if (mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId()).isEmpty()) {
                Slog.d(TAG, "Last biometric removed for user: " + getTargetUserId());
                // When the last biometric of a group is removed, update the authenticator id.
                // Note that multiple ClientMonitors may be cause onRemoved (e.g. internal
                // cleanup).
                mAuthenticatorIds.put(getTargetUserId(), 0L);
            }
            mFinishCallback.onClientFinished(this, true /* success */);
        }
    }
}
