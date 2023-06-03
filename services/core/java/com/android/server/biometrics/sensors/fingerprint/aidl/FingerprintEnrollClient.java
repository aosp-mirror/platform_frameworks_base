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
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.PowerPressHandler;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

import java.util.function.Supplier;

class FingerprintEnrollClient extends EnrollClient<AidlSession> implements Udfps,
        PowerPressHandler {

    private static final String TAG = "FingerprintEnrollClient";

    @NonNull private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull private final SensorOverlays mSensorOverlays;
    @NonNull private final CallbackWithProbe<Probe> mALSProbeCallback;

    private final @FingerprintManager.EnrollReason int mEnrollReason;
    @Nullable private ICancellationSignal mCancellationSignal;
    private final int mMaxTemplatesPerUser;
    private boolean mIsPointerDown;

    private static boolean shouldVibrateFor(Context context,
            FingerprintSensorPropertiesInternal sensorProps) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        final boolean isAccessbilityEnabled = am.isTouchExplorationEnabled();
        return !sensorProps.isAnyUdfpsType() || isAccessbilityEnabled;
    }

    FingerprintEnrollClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon, @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner,
            @NonNull BiometricUtils<Fingerprint> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @Nullable ISidefpsController sidefpsController,
            int maxTemplatesPerUser, @FingerprintManager.EnrollReason int enrollReason) {
        // UDFPS haptics occur when an image is acquired (instead of when the result is known)
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                0 /* timeoutSec */, sensorId, shouldVibrateFor(context, sensorProps), logger,
                biometricContext);
        setRequestId(requestId);
        mSensorProps = sensorProps;
        mSensorOverlays = new SensorOverlays(udfpsOverlayController, sidefpsController);
        mMaxTemplatesPerUser = maxTemplatesPerUser;

        mALSProbeCallback = getLogger().getAmbientLightProbe(true /* startWithClient */);

        mEnrollReason = enrollReason;
        if (enrollReason == FingerprintManager.ENROLL_FIND_SENSOR) {
            getLogger().disableMetrics();
        }
    }

    @NonNull
    @Override
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(mALSProbeCallback,
                getBiometricContextUnsubscriber(), callback);
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
        boolean acquiredGood =
                acquiredInfo == BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
        // For UDFPS, notify SysUI that the illumination can be turned off.
        // See AcquiredInfo#GOOD and AcquiredInfo#RETRYING_CAPTURE
        if (mSensorProps.isAnyUdfpsType()) {
            if (acquiredGood && mShouldVibrate) {
                vibrateSuccess();
            }
            mSensorOverlays.ifUdfps(
                    controller -> controller.onAcquired(getSensorId(), acquiredInfo));
        }

        mSensorOverlays.ifUdfps(controller -> {
            if (UdfpsHelper.isValidAcquisitionMessage(getContext(), acquiredInfo, vendorCode)) {
                controller.onEnrollmentHelp(getSensorId());
            }
        });
        mCallback.onBiometricAction(BiometricStateListener.ACTION_SENSOR_TOUCH);

        if (getContext().getResources().getBoolean(R.bool.config_powerPressMapping)
                && acquiredInfo == BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR
                && vendorCode == getContext().getResources()
                .getInteger(R.integer.config_powerPressCode)) {
            // Translating vendor code to internal code
            super.onAcquired(BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_POWER_PRESSED,
                    0 /* vendorCode */);
        } else {
            super.onAcquired(acquiredInfo, vendorCode);
        }
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
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), getOverlayReasonFromEnrollReason(mEnrollReason), this);

        BiometricNotificationUtils.cancelBadCalibrationNotification(getContext());
        try {
            mCancellationSignal = doEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
                    0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doEnroll() throws RemoteException {
        final AidlSession session = getFreshDaemon();
        final HardwareAuthToken hat =
                HardwareAuthTokenUtils.toHardwareAuthToken(mHardwareAuthToken);

        if (session.hasContextMethods()) {
            final OperationContext opContext = getOperationContext();
            final ICancellationSignal cancel = session.getSession().enrollWithContext(
                    hat, opContext);
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            });
            return cancel;
        } else {
            return session.getSession().enroll(hat);
        }
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());
        unsubscribeBiometricContext();

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
    public void onPointerDown(int x, int y, float minor, float major) {
        try {
            mIsPointerDown = true;

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                final PointerContext context = new PointerContext();
                context.pointerId = 0;
                context.x = x;
                context.y = y;
                context.minor = minor;
                context.major = major;
                context.isAod = getBiometricContext().isAod();
                session.getSession().onPointerDownWithContext(context);
            } else {
                session.getSession().onPointerDown(0 /* pointerId */, x, y, minor, major);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer down", e);
        }
    }

    @Override
    public void onPointerUp() {
        try {
            mIsPointerDown = false;

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                final PointerContext context = new PointerContext();
                context.pointerId = 0;
                session.getSession().onPointerUpWithContext(context);
            } else {
                session.getSession().onPointerUp(0 /* pointerId */);
            }
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
            getFreshDaemon().getSession().onUiReady();
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send UI ready", e);
        }
    }

    @Override
    public void onPowerPressed() {}
}
