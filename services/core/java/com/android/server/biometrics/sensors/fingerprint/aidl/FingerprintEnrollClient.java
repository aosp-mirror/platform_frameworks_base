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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

class FingerprintEnrollClient extends EnrollClient<ISession> implements Udfps {

    private static final String TAG = "FingerprintEnrollClient";

    @Nullable private final IUdfpsOverlayController mUdfpsOverlayController;
    private final @FingerprintManager.EnrollReason int mEnrollReason;
    @Nullable private ICancellationSignal mCancellationSignal;
    private final int mMaxTemplatesPerUser;

    FingerprintEnrollClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner,
            @NonNull BiometricUtils<Fingerprint> utils, int sensorId,
            @Nullable IUdfpsOverlayController udfpsOvelayController, int maxTemplatesPerUser,
            @FingerprintManager.EnrollReason int enrollReason) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                0 /* timeoutSec */, BiometricsProtoEnums.MODALITY_FINGERPRINT, sensorId,
                true /* shouldVibrate */);
        mUdfpsOverlayController = udfpsOvelayController;
        mMaxTemplatesPerUser = maxTemplatesPerUser;

        mEnrollReason = enrollReason;
        if (enrollReason == FingerprintManager.ENROLL_FIND_SENSOR) {
            setShouldLog(false);
        }
    }

    @Override
    public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        super.onEnrollResult(identifier, remaining);

        UdfpsHelper.onEnrollmentProgress(getSensorId(), remaining, mUdfpsOverlayController);

        if (remaining == 0) {
            UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
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
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        return FingerprintUtils.getInstance(getSensorId())
                .getBiometricsForUser(getContext(), getTargetUserId()).size()
                >= mMaxTemplatesPerUser;
    }

    @Override
    protected void stopHalOperation() {
        UdfpsHelper.hideUdfpsOverlay(getSensorId(), mUdfpsOverlayController);
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
        UdfpsHelper.showUdfpsOverlay(getSensorId(),
                UdfpsHelper.getReasonFromEnrollReason(mEnrollReason),
                mUdfpsOverlayController, this);
        try {
            mCancellationSignal = getFreshDaemon().enroll(mSequentialId,
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
            getFreshDaemon().onPointerDown(0 /* pointerId */, x, y, minor, major);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer down", e);
        }
    }

    @Override
    public void onPointerUp() {
        try {
            getFreshDaemon().onPointerUp(0 /* pointerId */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer up", e);
        }
    }
}
