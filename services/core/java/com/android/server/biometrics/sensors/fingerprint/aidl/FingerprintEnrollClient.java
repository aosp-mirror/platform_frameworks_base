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
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricFingerprintConstants.FingerprintAcquired;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

class FingerprintEnrollClient extends EnrollClient<ISession> implements Udfps {

    private static final String TAG = "FingerprintEnrollClient";

    @NonNull private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull private final SensorOverlays mSensorOverlays;

    private final @FingerprintManager.EnrollReason int mEnrollReason;
    @Nullable private ICancellationSignal mCancellationSignal;
    private final int mMaxTemplatesPerUser;
    private boolean mIsPointerDown;

    FingerprintEnrollClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner,
            @NonNull BiometricUtils<Fingerprint> utils, int sensorId,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @Nullable ISidefpsController sidefpsController,
            int maxTemplatesPerUser, @FingerprintManager.EnrollReason int enrollReason) {
        // UDFPS haptics occur when an image is acquired (instead of when the result is known)
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                0 /* timeoutSec */, BiometricsProtoEnums.MODALITY_FINGERPRINT, sensorId,
                !sensorProps.isAnyUdfpsType() /* shouldVibrate */);
        setRequestId(requestId);
        mSensorProps = sensorProps;
        mSensorOverlays = new SensorOverlays(udfpsOverlayController, sidefpsController);
        mMaxTemplatesPerUser = maxTemplatesPerUser;

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
    public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        super.onEnrollResult(identifier, remaining);

        mSensorOverlays.ifUdfps(
                controller -> controller.onEnrollmentProgress(getSensorId(), remaining));

        if (remaining == 0) {
            mSensorOverlays.hide(getSensorId());
        }
    }

    @Override
    public void onAcquired(@FingerprintAcquired int acquiredInfo, int vendorCode) {
        // For UDFPS, notify SysUI that the illumination can be turned off.
        // See AcquiredInfo#GOOD and AcquiredInfo#RETRYING_CAPTURE
        if (acquiredInfo == BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD
                && mSensorProps.isAnyUdfpsType()) {
            vibrateSuccess();
            mSensorOverlays.ifUdfps(controller -> controller.onAcquiredGood(getSensorId()));
        }

        mSensorOverlays.ifUdfps(controller -> {
            if (UdfpsHelper.isValidAcquisitionMessage(getContext(), acquiredInfo, vendorCode)) {
                controller.onEnrollmentHelp(getSensorId());
            }
        });

        super.onAcquired(acquiredInfo, vendorCode);
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        mSensorOverlays.hide(getSensorId());
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        return FingerprintUtils.getInstance(getSensorId())
                .getBiometricsForUser(getContext(), getTargetUserId()).size()
                >= mMaxTemplatesPerUser;
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());

        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when requesting cancel", e);
                onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), getOverlayReasonFromEnrollReason(mEnrollReason), this);

        BiometricNotificationUtils.cancelBadCalibrationNotification(getContext());
        try {
            mCancellationSignal = getFreshDaemon().enroll(
                    HardwareAuthTokenUtils.toHardwareAuthToken(mHardwareAuthToken));
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
                    0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void onPointerDown(int x, int y, float minor, float major) {
        try {
            mIsPointerDown = true;
            getFreshDaemon().onPointerDown(0 /* pointerId */, x, y, minor, major);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer down", e);
        }
    }

    @Override
    public void onPointerUp() {
        try {
            mIsPointerDown = false;
            getFreshDaemon().onPointerUp(0 /* pointerId */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer up", e);
        }
    }

    @Override
    public boolean isPointerDown() {
        return mIsPointerDown;
    }

    @Override
    public void onUiReady() {
        try {
            getFreshDaemon().onUiReady();
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send UI ready", e);
        }
    }
}
