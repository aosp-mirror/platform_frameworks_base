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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.Map;
import java.util.function.Supplier;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient<S extends BiometricAuthenticator.Identifier, T>
        extends HalClientMonitor<T> implements RemovalConsumer, EnrollmentModifier {

    private static final String TAG = "Biometrics/RemovalClient";

    private final BiometricUtils<S> mBiometricUtils;
    private final Map<Integer, Long> mAuthenticatorIds;
    private final boolean mHasEnrollmentsBeforeStarting;

    public RemovalClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int userId, @NonNull String owner, @NonNull BiometricUtils<S> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                logger, biometricContext, false /* isMandatoryBiometrics */);
        mBiometricUtils = utils;
        mAuthenticatorIds = authenticatorIds;
        mHasEnrollmentsBeforeStarting = !utils.getBiometricsForUser(context, userId).isEmpty();
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        // The biometric template ids will be removed when we get confirmation from the HAL
        startHalOperation();
    }

    @Override
    public void onRemoved(@NonNull BiometricAuthenticator.Identifier identifier, int remaining) {
        // This happens when we have failed to remove a biometric.
        if (identifier == null) {
            Slog.e(TAG, "identifier was null, skipping onRemove()");
            try {
                getListener().onError(getSensorId(), getCookie(),
                        BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_REMOVE,
                        0 /* vendorCode */);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to send error to client for onRemoved", e);
            }
            mCallback.onClientFinished(this, false /* success */);
            return;
        }

        Slog.d(TAG, "onRemoved: " + identifier.getBiometricId() + " remaining: " + remaining);
        mBiometricUtils.removeBiometricForUser(getContext(), getTargetUserId(),
                identifier.getBiometricId());

        try {
            getListener().onRemoved(identifier, remaining);
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
            mCallback.onClientFinished(this, true /* success */);
        }
    }

    @Override
    public boolean hasEnrollmentStateChanged() {
        final boolean hasEnrollmentsNow = !mBiometricUtils
                .getBiometricsForUser(getContext(), getTargetUserId()).isEmpty();
        return hasEnrollmentsNow != mHasEnrollmentsBeforeStarting;
    }

    @Override
    public boolean hasEnrollments() {
        return !mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId()).isEmpty();
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_REMOVE;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }
}
