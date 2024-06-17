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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.hidl.HidlToAidlSessionAdapter;

import java.util.function.Supplier;

/**
 * Face-specific resetLockout client for the {@link IFace} AIDL HAL interface.
 * Updates the framework's lockout cache and notifies clients such as Keyguard when lockout is
 * cleared.
 */
public class FaceResetLockoutClient extends HalClientMonitor<AidlSession> implements ErrorConsumer {

    private static final String TAG = "FaceResetLockoutClient";

    private final HardwareAuthToken mHardwareAuthToken;
    private final LockoutTracker mLockoutTracker;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final int mBiometricStrength;

    public FaceResetLockoutClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon, int userId, String owner, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull byte[] hardwareAuthToken, @NonNull LockoutTracker lockoutTracker,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @Authenticators.Types int biometricStrength) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId, owner,
                0 /* cookie */, sensorId, logger, biometricContext);
        mHardwareAuthToken = HardwareAuthTokenUtils.toHardwareAuthToken(hardwareAuthToken);
        mLockoutTracker = lockoutTracker;
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mBiometricStrength = biometricStrength;
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            final ISession session = getFreshDaemon().getSession();
            session.resetLockout(mHardwareAuthToken);
            if (session instanceof HidlToAidlSessionAdapter) {
                mCallback.onClientFinished(this, true /* success */);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to reset lockout", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    void onLockoutCleared() {
        resetLocalLockoutStateToNone(getSensorId(), getTargetUserId(), mLockoutTracker,
                mLockoutResetDispatcher, getBiometricContext().getAuthSessionCoordinator(),
                mBiometricStrength, getRequestId());
        mCallback.onClientFinished(this, true /* success */);
    }

    public boolean interruptsPrecedingClients() {
        return true;
    }

    /**
     * Reset the local lockout state and notify any listeners.
     *
     * This should only be called when the HAL sends a reset request directly to the
     * framework (i.e. time based reset, etc.). When the HAL is responding to a
     * resetLockout request from an instance of this client {@link #onLockoutCleared()} should
     * be used instead.
     */
    static void resetLocalLockoutStateToNone(int sensorId, int userId,
            @NonNull LockoutTracker lockoutTracker,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull AuthSessionCoordinator authSessionCoordinator,
            @Authenticators.Types int biometricStrength, long requestId) {
        authSessionCoordinator.resetLockoutFor(userId, biometricStrength, requestId);
        lockoutTracker.setLockoutModeForUser(userId, LockoutTracker.LOCKOUT_NONE);
        lockoutResetDispatcher.notifyLockoutResetCallbacks(sensorId);
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_RESET_LOCKOUT;
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        Slog.e(TAG, "Error during resetLockout: " + errorCode);
        mCallback.onClientFinished(this, false /* success */);
    }
}
