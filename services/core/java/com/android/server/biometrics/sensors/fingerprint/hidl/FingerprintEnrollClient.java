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
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.fingerprint.SidefpsHelper;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

/**
 * Fingerprint-specific enroll client supporting the
 * {@link android.hardware.biometrics.fingerprint.V2_1} and
 * {@link android.hardware.biometrics.fingerprint.V2_2} HIDL interfaces.
 */
public class FingerprintEnrollClient extends EnrollClient<IBiometricsFingerprint>
        implements Udfps {

    private static final String TAG = "FingerprintEnrollClient";

    @Nullable private final IUdfpsOverlayController mUdfpsOverlayController;
    @Nullable private final ISidefpsController mSidefpsController;
    private final @FingerprintManager.EnrollReason int mEnrollReason;
    private boolean mIsPointerDown;

    FingerprintEnrollClient(@NonNull Context context,
            @NonNull LazyDaemon<IBiometricsFingerprint> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner,
            @NonNull BiometricUtils<Fingerprint> utils, int timeoutSec, int sensorId,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @Nullable ISidefpsController sidefpsController,
            @FingerprintManager.EnrollReason int enrollReason) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                timeoutSec, BiometricsProtoEnums.MODALITY_FINGERPRINT, sensorId,
                true /* shouldVibrate */);
        mUdfpsOverlayController = udfpsOverlayController;
        mSidefpsController = sidefpsController;

        mEnrollReason = enrollReason;
        if (enrollReason == FingerprintManager.ENROLL_FIND_SENSOR) {
            setShouldLog(false);
        }
    }

    @NonNull
    @Override
    protected Callback wrapCallbackForStart(@NonNull Callback callback) {
        return new CompositeCallback(createALSCallback(true /* startWithClient */), callback);
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        final int limit = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_fingerprintMaxTemplatesPerUser);
        final int enrolled = mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId())
                .size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many fingerprints registered, user: " + getTargetUserId());
            return true;
        }
        return false;
    }

    @Override
    protected void startHalOperation() {
        UdfpsHelper.showUdfpsOverlay(getSensorId(),
                UdfpsHelper.getReasonFromEnrollReason(mEnrollReason),
                mUdfpsOverlayController, this);
        SidefpsHelper.showOverlay(mSidefpsController);
        BiometricNotificationUtils.cancelBadCalibrationNotification(getContext());
        try {
            // GroupId was never used. In fact, groupId is always the same as userId.
            getFreshDaemon().enroll(mHardwareAuthToken, getTargetUserId(), mTimeoutSec);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
            SidefpsHelper.hideOverlay(mSidefpsController);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
        SidefpsHelper.hideOverlay(mSidefpsController);
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
    public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        super.onEnrollResult(identifier, remaining);

        UdfpsHelper.onEnrollmentProgress(getSensorId(), remaining, mUdfpsOverlayController);

        if (remaining == 0) {
            UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
            SidefpsHelper.hideOverlay(mSidefpsController);
        }
    }

    @Override
    public void onAcquired(int acquiredInfo, int vendorCode) {
        super.onAcquired(acquiredInfo, vendorCode);

        if (UdfpsHelper.isValidAcquisitionMessage(getContext(), acquiredInfo, vendorCode)) {
            UdfpsHelper.onEnrollmentHelp(getSensorId(), mUdfpsOverlayController);
        }
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
        SidefpsHelper.hideOverlay(mSidefpsController);
    }

    @Override
    public void onPointerDown(int x, int y, float minor, float major) {
        mIsPointerDown = true;
        UdfpsHelper.onFingerDown(getFreshDaemon(), x, y, minor, major);
    }

    @Override
    public void onPointerUp() {
        mIsPointerDown = false;
        UdfpsHelper.onFingerUp(getFreshDaemon());
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
