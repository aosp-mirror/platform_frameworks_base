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

import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.util.Slog;
import android.view.Surface;

import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricServiceBase;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.Constants;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.util.ArrayList;

/**
 * Fingerprint-specific authentication client supporting the
 * {@link android.hardware.biometrics.fingerprint.V2_1} and
 * {@link android.hardware.biometrics.fingerprint.V2_2} HIDL interfaces.
 */
class FingerprintAuthenticationClient extends AuthenticationClient {
    private final LockoutFrameworkImpl mLockoutFrameworkImpl;

    FingerprintAuthenticationClient(Context context, Constants constants,
            BiometricServiceBase.DaemonWrapper daemon, IBinder token,
            ClientMonitorCallbackConverter listener, int targetUserId, int groupId, long opId,
            boolean restricted, String owner, int cookie, boolean requireConfirmation, int sensorId,
            boolean isStrongBiometric, Surface surface, int statsClient,
            TaskStackListener taskStackListener, LockoutFrameworkImpl lockoutTracker) {
        super(context, constants, daemon, token, listener, targetUserId, groupId, opId,
                restricted, owner, cookie, requireConfirmation, sensorId, isStrongBiometric,
                BiometricsProtoEnums.MODALITY_FINGERPRINT, statsClient, taskStackListener,
                lockoutTracker, surface);

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
                Slog.w(getLogTag(), "Fingerprint locked out, lockoutMode(" + lockoutMode + ")");
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
}
