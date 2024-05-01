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

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR_BASE;
import static android.hardware.fingerprint.FingerprintManager.getAcquiredString;
import static android.hardware.fingerprint.FingerprintManager.getErrorString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricFingerprintConstants.FingerprintAcquired;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.PowerPressHandler;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;

import java.util.function.Supplier;

public class FingerprintEnrollClient extends EnrollClient<AidlSession> implements Udfps,
        PowerPressHandler {

    private static final String TAG = "FingerprintEnrollClient";

    @NonNull private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull private final SensorOverlays mSensorOverlays;
    @NonNull private final CallbackWithProbe<Probe> mALSProbeCallback;

    private final @FingerprintManager.EnrollReason int mEnrollReason;
    @NonNull private final AuthenticationStateListeners mAuthenticationStateListeners;
    @Nullable private ICancellationSignal mCancellationSignal;
    private final int mMaxTemplatesPerUser;
    private boolean mIsPointerDown;

    private static boolean shouldVibrateFor(Context context,
            FingerprintSensorPropertiesInternal sensorProps) {
        if (sensorProps != null) {
            final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
            final boolean isAccessbilityEnabled = am.isTouchExplorationEnabled();
            return !sensorProps.isAnyUdfpsType() || isAccessbilityEnabled;
        } else {
            return true;
        }
    }

    public FingerprintEnrollClient(
            @NonNull Context context, @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner,
            @NonNull BiometricUtils<Fingerprint> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            int maxTemplatesPerUser, @FingerprintManager.EnrollReason int enrollReason,
            @NonNull FingerprintEnrollOptions options) {
        // UDFPS haptics occur when an image is acquired (instead of when the result is known)
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                0 /* timeoutSec */, sensorId, shouldVibrateFor(context, sensorProps),
                logger, biometricContext,
                BiometricFingerprintConstants.reasonToMetric(options.getEnrollReason()));
        setRequestId(requestId);
        mSensorProps = sensorProps;
        mSensorOverlays = new SensorOverlays(udfpsOverlayController);
        mAuthenticationStateListeners = authenticationStateListeners;

        mMaxTemplatesPerUser = maxTemplatesPerUser;

        mALSProbeCallback = getLogger().getAmbientLightProbe(true /* startWithClient */);

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
        return new ClientMonitorCompositeCallback(mALSProbeCallback,
                getBiometricContextUnsubscriber(), callback);
    }

    @Override
    public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        super.onEnrollResult(identifier, remaining);

        mSensorOverlays.ifUdfps(
                controller -> controller.onEnrollmentProgress(getSensorId(), remaining));

        if (remaining == 0) {
            resetIgnoreDisplayTouches();
            mSensorOverlays.hide(getSensorId());
            mAuthenticationStateListeners.onAuthenticationStopped(
                    new AuthenticationStoppedInfo.Builder(
                            BiometricSourceType.FINGERPRINT,
                            getRequestReasonFromFingerprintEnrollReason(mEnrollReason)).build()
            );
        }

    }

    @Override
    public void onAcquired(@FingerprintAcquired int acquiredInfo, int vendorCode) {
        if (acquiredInfo != FINGERPRINT_ACQUIRED_START) {
            mAuthenticationStateListeners.onAuthenticationAcquired(
                    new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FINGERPRINT,
                            getRequestReasonFromFingerprintEnrollReason(mEnrollReason),
                            acquiredInfo).build()
            );
        }
        String helpMsg = getAcquiredString(getContext(), acquiredInfo, vendorCode);
        if (helpMsg != null) {
            int helpCode = acquiredInfo == FINGERPRINT_ACQUIRED_VENDOR
                    ? (vendorCode + FINGERPRINT_ACQUIRED_VENDOR_BASE) : acquiredInfo;
            mAuthenticationStateListeners.onAuthenticationHelp(
                    new AuthenticationHelpInfo.Builder(BiometricSourceType.FINGERPRINT,
                            getRequestReasonFromFingerprintEnrollReason(mEnrollReason),
                            helpMsg, helpCode).build()
            );
        }

        boolean acquiredGood =
                acquiredInfo == BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
        // For UDFPS, notify SysUI that the illumination can be turned off.
        // See AcquiredInfo#GOOD and AcquiredInfo#RETRYING_CAPTURE
        if (mSensorProps != null && mSensorProps.isAnyUdfpsType()) {
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
        super.onAcquired(acquiredInfo, vendorCode);
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        mAuthenticationStateListeners.onAuthenticationError(new AuthenticationErrorInfo
                .Builder(BiometricSourceType.FINGERPRINT,
                getRequestReasonFromFingerprintEnrollReason(mEnrollReason),
                getErrorString(getContext(), errorCode, vendorCode), errorCode).build()
        );
        super.onError(errorCode, vendorCode);

        resetIgnoreDisplayTouches();
        mSensorOverlays.hide(getSensorId());
        mAuthenticationStateListeners.onAuthenticationStopped(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        getRequestReasonFromFingerprintEnrollReason(mEnrollReason)).build()
        );
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        return mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId()).size()
                >= mMaxTemplatesPerUser;
    }

    @Override
    protected void startHalOperation() {
        resetIgnoreDisplayTouches();
        mSensorOverlays.show(getSensorId(),
                getRequestReasonFromFingerprintEnrollReason(mEnrollReason), this);
        mAuthenticationStateListeners.onAuthenticationStarted(new AuthenticationStartedInfo
                .Builder(BiometricSourceType.FINGERPRINT,
                getRequestReasonFromFingerprintEnrollReason(mEnrollReason)).build()
        );

        BiometricNotificationUtils.cancelBadCalibrationNotification(getContext());
        try {
            doEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
                    0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private void doEnroll() throws RemoteException {
        final AidlSession session = getFreshDaemon();
        final HardwareAuthToken hat =
                HardwareAuthTokenUtils.toHardwareAuthToken(mHardwareAuthToken);

        if (session.hasContextMethods()) {
            final OperationContextExt opContext = getOperationContext();
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    mCancellationSignal = session.getSession().enrollWithContext(
                            hat, ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception when requesting enroll", e);
                    onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
                            0 /* vendorCode */);
                    mCallback.onClientFinished(this, false /* success */);
                }
            }, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            }, null /* options */);
        } else {
            mCancellationSignal = session.getSession().enroll(hat);
        }
    }

    @Override
    protected void stopHalOperation() {
        resetIgnoreDisplayTouches();
        mSensorOverlays.hide(getSensorId());
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FINGERPRINT,
                getRequestReasonFromFingerprintEnrollReason(mEnrollReason)).build()
        );

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
    public void onPointerDown(PointerContext pc) {
        try {
            mIsPointerDown = true;

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                session.getSession().onPointerDownWithContext(pc);
            } else {
                session.getSession().onPointerDown(pc.pointerId, (int) pc.x, (int) pc.y, pc.minor,
                        pc.major);
            }

            getListener().onUdfpsPointerDown(getSensorId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer down", e);
        }
    }

    @Override
    public void onPointerUp(PointerContext pc) {
        try {
            mIsPointerDown = false;

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                session.getSession().onPointerUpWithContext(pc);
            } else {
                session.getSession().onPointerUp(pc.pointerId);
            }

            getListener().onUdfpsPointerUp(getSensorId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send pointer up", e);
        }
    }

    @Override
    public boolean isPointerDown() {
        return mIsPointerDown;
    }

    @Override
    public void onUdfpsUiEvent(@FingerprintManager.UdfpsUiEvent int event) {
        try {
            switch (event) {
                case FingerprintManager.UDFPS_UI_OVERLAY_SHOWN:
                    getListener().onUdfpsOverlayShown();
                    break;
                case FingerprintManager.UDFPS_UI_READY:
                    getFreshDaemon().getSession().onUiReady();
                    break;
                default:
                    Slog.w(TAG, "No matching event for onUdfpsUiEvent");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send onUdfpsUiEvent", e);
        }
    }

    @Override
    public void onPowerPressed() {}
}
