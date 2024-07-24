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

import static com.android.systemui.shared.Flags.sidefpsControllerRefactor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

import java.util.function.Supplier;

/**
 * Fingerprint-specific enroll client supporting the
 * {@link android.hardware.biometrics.fingerprint.V2_1} and
 * {@link android.hardware.biometrics.fingerprint.V2_2} HIDL interfaces.
 */
public class FingerprintEnrollClient extends EnrollClient<IBiometricsFingerprint>
        implements Udfps {

    private static final String TAG = "FingerprintEnrollClient";

    @NonNull private final SensorOverlays mSensorOverlays;
    private final @FingerprintManager.EnrollReason int mEnrollReason;
    @NonNull private final AuthenticationStateListeners mAuthenticationStateListeners;
    private boolean mIsPointerDown;

    FingerprintEnrollClient(
            @NonNull Context context, @NonNull Supplier<IBiometricsFingerprint> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner,
            @NonNull BiometricUtils<Fingerprint> utils, int timeoutSec, int sensorId,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
            @Nullable ISidefpsController sidefpsController,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            @FingerprintManager.EnrollReason int enrollReason,
            @NonNull FingerprintEnrollOptions options) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                timeoutSec, sensorId, true /* shouldVibrate */, biometricLogger,
                biometricContext,
                BiometricFingerprintConstants.reasonToMetric(options.getEnrollReason()));
        setRequestId(requestId);
        if (sidefpsControllerRefactor()) {
            mSensorOverlays = new SensorOverlays(udfpsOverlayController);
        } else {
            mSensorOverlays = new SensorOverlays(udfpsOverlayController, sidefpsController);
        }
        mAuthenticationStateListeners = authenticationStateListeners;

        mEnrollReason = enrollReason;
        if (enrollReason == FingerprintManager.ENROLL_FIND_SENSOR) {
            getLogger().disableMetrics();
        }
        Slog.w(TAG, "EnrollOptions "
                + FingerprintEnrollOptions.enrollReasonToString(options.getEnrollReason()));
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        BiometricNotificationUtils.cancelFingerprintEnrollNotification(getContext());
    }

    @NonNull
    @Override
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(
                getLogger().getAmbientLightProbe(true /* startWithClient */), callback);
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
        mSensorOverlays.show(getSensorId(), getRequestReasonFromEnrollReason(mEnrollReason),
                this);
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStarted(
                    getRequestReasonFromEnrollReason(mEnrollReason));
        }

        BiometricNotificationUtils.cancelBadCalibrationNotification(getContext());
        try {
            // GroupId was never used. In fact, groupId is always the same as userId.
            getFreshDaemon().enroll(mHardwareAuthToken, getTargetUserId(), mTimeoutSec);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mSensorOverlays.hide(getSensorId());
            if (sidefpsControllerRefactor()) {
                mAuthenticationStateListeners.onAuthenticationStopped();
            }
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStopped();
        }

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

        mSensorOverlays.ifUdfps(
                controller -> controller.onEnrollmentProgress(getSensorId(), remaining));

        if (remaining == 0) {
            mSensorOverlays.hide(getSensorId());
            if (sidefpsControllerRefactor()) {
                mAuthenticationStateListeners.onAuthenticationStopped();
            }
        }
    }

    @Override
    public void onAcquired(int acquiredInfo, int vendorCode) {
        super.onAcquired(acquiredInfo, vendorCode);

        mSensorOverlays.ifUdfps(controller -> {
            if (UdfpsHelper.isValidAcquisitionMessage(getContext(), acquiredInfo, vendorCode)) {
                controller.onEnrollmentHelp(getSensorId());
            }
        });

        mCallback.onBiometricAction(BiometricStateListener.ACTION_SENSOR_TOUCH);
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        mSensorOverlays.hide(getSensorId());
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStopped();
        }
    }

    @Override
    public void onPointerDown(PointerContext pc) {
        mIsPointerDown = true;
        UdfpsHelper.onFingerDown(getFreshDaemon(), (int) pc.x, (int) pc.y, pc.minor, pc.major);
    }

    @Override
    public void onPointerUp(PointerContext pc) {
        mIsPointerDown = false;
        UdfpsHelper.onFingerUp(getFreshDaemon());
    }

    @Override
    public boolean isPointerDown() {
        return mIsPointerDown;
    }

    @Override
    public void onUdfpsUiEvent(@FingerprintManager.UdfpsUiEvent int event) {
        // Unsupported in HIDL.
    }
}
