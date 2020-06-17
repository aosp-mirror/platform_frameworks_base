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

package com.android.server.biometrics.sensors.fingerprint;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Surface;

import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.util.ArrayList;

/**
 * Fingerprint-specific authentication client supporting the
 * {@link android.hardware.biometrics.fingerprint.V2_1} and
 * {@link android.hardware.biometrics.fingerprint.V2_2} HIDL interfaces.
 */
class FingerprintAuthenticationClient extends AuthenticationClient {

    private static final String TAG = "Biometrics/FingerprintAuthClient";

    private final IBiometricsFingerprint mDaemon;
    private final LockoutFrameworkImpl mLockoutFrameworkImpl;

    FingerprintAuthenticationClient(@NonNull Context context,
            @NonNull IBiometricsFingerprint daemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, int groupId,
            long operationId, boolean restricted, @NonNull String owner, int cookie,
            boolean requireConfirmation, int sensorId, boolean isStrongBiometric,
            @Nullable Surface surface, int statsClient,
            @NonNull TaskStackListener taskStackListener,
            @NonNull LockoutFrameworkImpl lockoutTracker) {
        super(context, token, listener, targetUserId, groupId, operationId, restricted, owner,
                cookie, requireConfirmation, sensorId, isStrongBiometric,
                BiometricsProtoEnums.MODALITY_FINGERPRINT, statsClient, taskStackListener,
                lockoutTracker);
        mDaemon = daemon;
        mLockoutFrameworkImpl = lockoutTracker;
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        final boolean result = super.onAuthenticated(identifier, authenticated, token);

        if (authenticated) {
            resetFailedAttempts(getTargetUserId());
        } else {
            final @LockoutTracker.LockoutMode int lockoutMode =
                    mLockoutFrameworkImpl.getLockoutModeForUser(getTargetUserId());
            if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
                Slog.w(TAG, "Fingerprint locked out, lockoutMode(" + lockoutMode + ")");
                stop(false /* initiatedByClient */);
                final int errorCode = lockoutMode == LockoutTracker.LOCKOUT_TIMED
                        ? BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                        : BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                onError(errorCode, 0 /* vendorCode */);
            }
        }

        // Authentication lifecycle ends either when
        // 1) Authenticated == true
        // 2) Error occurred
        // Note that authentication doesn't end when Authenticated == false
        return result;
    }

    private void resetFailedAttempts(int userId) {
        mLockoutFrameworkImpl.resetFailedAttemptsForUser(true /* clearAttemptCounter */, userId);
    }

    @Override
    public @LockoutTracker.LockoutMode int handleFailedAttempt(int userId) {
        mLockoutFrameworkImpl.addFailedAttemptForUser(userId);
        return super.handleFailedAttempt(userId);
    }

    @Override
    protected int startHalOperation() throws RemoteException {
        return mDaemon.authenticate(mOperationId, getGroupId());
    }

    @Override
    protected int stopHalOperation() throws RemoteException {
        return mDaemon.cancel();
    }
}
