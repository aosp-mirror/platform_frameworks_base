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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

import java.util.ArrayList;

/**
 * Fingerprint-specific authentication client supporting the
 * {@link android.hardware.biometrics.fingerprint.V2_1} and
 * {@link android.hardware.biometrics.fingerprint.V2_2} HIDL interfaces.
 */
class FingerprintAuthenticationClient extends AuthenticationClient<IBiometricsFingerprint>
        implements Udfps {

    private static final String TAG = "Biometrics/FingerprintAuthClient";

    private final LockoutFrameworkImpl mLockoutFrameworkImpl;
    @Nullable private final IUdfpsOverlayController mUdfpsOverlayController;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull private final CallbackWithProbe<Probe> mALSProbeCallback;

    private boolean mIsPointerDown;

    FingerprintAuthenticationClient(@NonNull Context context,
            @NonNull LazyDaemon<IBiometricsFingerprint> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, @NonNull String owner, int cookie, boolean requireConfirmation,
            int sensorId, boolean isStrongBiometric, int statsClient,
            @NonNull TaskStackListener taskStackListener,
            @NonNull LockoutFrameworkImpl lockoutTracker,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            boolean allowBackgroundAuthentication,
            @NonNull FingerprintSensorPropertiesInternal sensorProps) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted,
                owner, cookie, requireConfirmation, sensorId, isStrongBiometric,
                BiometricsProtoEnums.MODALITY_FINGERPRINT, statsClient, taskStackListener,
                lockoutTracker, allowBackgroundAuthentication, true /* shouldVibrate */,
                false /* isKeyguardBypassEnabled */);
        mLockoutFrameworkImpl = lockoutTracker;
        mUdfpsOverlayController = udfpsOverlayController;
        mSensorProps = sensorProps;
        mALSProbeCallback = createALSCallback(false /* startWithClient */);
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);

        if (mSensorProps.isAnyUdfpsType()) {
            // UDFPS requires user to touch before becoming "active"
            mState = STATE_STARTED_PAUSED;
        } else {
            mState = STATE_STARTED;
        }
    }

    @NonNull
    @Override
    protected Callback wrapCallbackForStart(@NonNull Callback callback) {
        return new CompositeCallback(mALSProbeCallback, callback);
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);

        // Authentication lifecycle ends either when
        // 1) Authenticated == true
        // 2) Error occurred (lockout or some other error)
        // Note that authentication doesn't end when Authenticated == false

        if (authenticated) {
            mState = STATE_STOPPED;
            resetFailedAttempts(getTargetUserId());
            UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
        } else {
            mState = STATE_STARTED_PAUSED_ATTEMPTED;
            final @LockoutTracker.LockoutMode int lockoutMode =
                    mLockoutFrameworkImpl.getLockoutModeForUser(getTargetUserId());
            if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
                Slog.w(TAG, "Fingerprint locked out, lockoutMode(" + lockoutMode + ")");
                final int errorCode = lockoutMode == LockoutTracker.LOCKOUT_TIMED
                        ? BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                        : BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                // Send the error, but do not invoke the FinishCallback yet. Since lockout is not
                // controlled by the HAL, the framework must stop the sensor before finishing the
                // client.
                UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
                onErrorInternal(errorCode, 0 /* vendorCode */, false /* finish */);
                cancel();
            }
        }
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        if (errorCode == BiometricFingerprintConstants.FINGERPRINT_ERROR_BAD_CALIBRATION) {
            BiometricNotificationUtils.showBadCalibrationNotification(getContext());
        }

        UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
    }

    private void resetFailedAttempts(int userId) {
        mLockoutFrameworkImpl.resetFailedAttemptsForUser(true /* clearAttemptCounter */, userId);
    }

    @Override
    protected void handleLifecycleAfterAuth(boolean authenticated) {
        if (authenticated) {
            mCallback.onClientFinished(this, true /* success */);
        }
    }

    @Override
    public boolean wasUserDetected() {
        // TODO: Update if it needs to be used for fingerprint, i.e. success/reject, error_timeout
        return false;
    }

    @Override
    public @LockoutTracker.LockoutMode int handleFailedAttempt(int userId) {
        mLockoutFrameworkImpl.addFailedAttemptForUser(userId);
        return super.handleFailedAttempt(userId);
    }

    @Override
    protected void startHalOperation() {
        UdfpsHelper.showUdfpsOverlay(getSensorId(), Utils.getUdfpsAuthReason(this),
                mUdfpsOverlayController, this);
        try {
            // GroupId was never used. In fact, groupId is always the same as userId.
            getFreshDaemon().authenticate(mOperationId, getTargetUserId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting auth", e);
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
            getFreshDaemon().cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting cancel", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void onPointerDown(int x, int y, float minor, float major) {
        mIsPointerDown = true;
        mState = STATE_STARTED;
        mALSProbeCallback.getProbe().enable();
        UdfpsHelper.onFingerDown(getFreshDaemon(), x, y, minor, major);

        if (getListener() != null) {
            try {
                getListener().onUdfpsPointerDown(getSensorId());
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }
    }

    @Override
    public void onPointerUp() {
        mIsPointerDown = false;
        mState = STATE_STARTED_PAUSED_ATTEMPTED;
        mALSProbeCallback.getProbe().disable();
        UdfpsHelper.onFingerUp(getFreshDaemon());

        if (getListener() != null) {
            try {
                getListener().onUdfpsPointerUp(getSensorId());
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }
    }

    @Override
    public boolean isPointerDown() {
        return mIsPointerDown;
    }

    @Override
    public void onUiReady() {
        // Unsupported in HIDL.
    }
}
