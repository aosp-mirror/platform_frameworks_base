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
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Performs fingerprint detection without exposing any matching information (e.g. accept/reject
 * have the same haptic, lockout counter is not increased).
 */
class FingerprintDetectClient extends AcquisitionClient<IBiometricsFingerprint>
        implements AuthenticationConsumer, Udfps {

    private static final String TAG = "FingerprintDetectClient";

    private final boolean mIsStrongBiometric;
    @NonNull private final SensorOverlays mSensorOverlays;
    private boolean mIsPointerDown;

    public FingerprintDetectClient(@NonNull Context context,
            @NonNull Supplier<IBiometricsFingerprint> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            boolean isStrongBiometric) {
        super(context, lazyDaemon, token, listener, options.getUserId(),
                options.getOpPackageName(), 0 /* cookie */, options.getSensorId(),
                true /* shouldVibrate */, biometricLogger, biometricContext);
        setRequestId(requestId);
        if (sidefpsControllerRefactor()) {
            mSensorOverlays = new SensorOverlays(udfpsOverlayController);
        } else {
            mSensorOverlays = new SensorOverlays(
                    udfpsOverlayController, null /* sideFpsController */);
        }
        mIsStrongBiometric = isStrongBiometric;
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());

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
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), BiometricRequestConstants.REASON_AUTH_KEYGUARD,
                this);

        try {
            getFreshDaemon().authenticate(0 /* operationId */, getTargetUserId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting auth", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mSensorOverlays.hide(getSensorId());
            mCallback.onClientFinished(this, false /* success */);
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

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> hardwareAuthToken) {
        getLogger().logOnAuthenticated(getContext(), getOperationContext(),
                authenticated, false /* requireConfirmation */,
                getTargetUserId(), false /* isBiometricPrompt */);

        // Do not distinguish between success/failures.
        vibrateSuccess();

        final PerformanceTracker pm = PerformanceTracker.getInstanceForSensorId(getSensorId());
        pm.incrementAuthForUser(getTargetUserId(), authenticated);

        if (getListener() != null) {
            try {
                getListener().onDetected(getSensorId(), getTargetUserId(), mIsStrongBiometric);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when sending onDetected", e);
            }
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_DETECT_INTERACTION;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }
}
