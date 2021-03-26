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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

import java.util.ArrayList;

/**
 * Fingerprint-specific authentication client supporting the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} AIDL interface.
 */
class FingerprintAuthenticationClient extends AuthenticationClient<ISession> implements
        Udfps, LockoutConsumer {
    private static final String TAG = "FingerprintAuthenticationClient";

    @NonNull private final LockoutCache mLockoutCache;
    @Nullable private final IUdfpsOverlayController mUdfpsOverlayController;
    @Nullable private ICancellationSignal mCancellationSignal;

    FingerprintAuthenticationClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, @NonNull String owner, int cookie, boolean requireConfirmation,
            int sensorId, boolean isStrongBiometric, int statsClient,
            @Nullable TaskStackListener taskStackListener, @NonNull LockoutCache lockoutCache,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            boolean allowBackgroundAuthentication) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted, owner,
                cookie, requireConfirmation, sensorId, isStrongBiometric,
                BiometricsProtoEnums.MODALITY_FINGERPRINT, statsClient, taskStackListener,
                lockoutCache, allowBackgroundAuthentication);
        mLockoutCache = lockoutCache;
        mUdfpsOverlayController = udfpsOverlayController;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);

        if (authenticated) {
            UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
            mCallback.onClientFinished(this, true /* success */);
        }
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
    }

    @Override
    protected void startHalOperation() {
        UdfpsHelper.showUdfpsOverlay(getSensorId(), Utils.getUdfpsAuthReason(this),
                mUdfpsOverlayController, this);
        try {
            mCancellationSignal = getFreshDaemon().authenticate(mOperationId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
        try {
            mCancellationSignal.cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void onPointerDown(int x, int y, float minor, float major) {
        try {
            getFreshDaemon().onPointerDown(0 /* pointerId */, x, y, minor, major);
            if (getListener() != null) {
                getListener().onUdfpsPointerDown(getSensorId(), getCookie());
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onPointerUp() {
        try {
            getFreshDaemon().onPointerUp(0 /* pointerId */);
            if (getListener() != null) {
                getListener().onUdfpsPointerUp(getSensorId(), getCookie());
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onLockoutTimed(long durationMillis) {
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_TIMED);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT;
        logOnError(getContext(), error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onLockoutPermanent() {
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_PERMANENT);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
        logOnError(getContext(), error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }
}
