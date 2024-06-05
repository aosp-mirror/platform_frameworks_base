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

import static android.adaptiveauth.Flags.reportBiometricAuthAttempts;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR_BASE;
import static android.hardware.fingerprint.FingerprintManager.getAcquiredString;
import static android.hardware.fingerprint.FingerprintManager.getErrorString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricFingerprintConstants.FingerprintAcquired;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationState;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.PowerPressHandler;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Fingerprint-specific authentication client supporting the {@link
 * android.hardware.biometrics.fingerprint.IFingerprint} AIDL interface.
 */
public class FingerprintAuthenticationClient
        extends AuthenticationClient<AidlSession, FingerprintAuthenticateOptions>
        implements Udfps, LockoutConsumer, PowerPressHandler {
    private static final String TAG = "FingerprintAuthenticationClient";
    @NonNull
    private final SensorOverlays mSensorOverlays;
    @NonNull
    private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull
    private final CallbackWithProbe<Probe> mALSProbeCallback;
    private final boolean mIsStrongBiometric;
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    @NonNull private final AuthenticationStateListeners mAuthenticationStateListeners;
    @Nullable
    private ICancellationSignal mCancellationSignal;
    private boolean mIsPointerDown;

    public FingerprintAuthenticationClient(
            @NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token,
            long requestId,
            @NonNull ClientMonitorCallbackConverter listener,
            long operationId,
            boolean restricted,
            @NonNull FingerprintAuthenticateOptions options,
            int cookie,
            boolean requireConfirmation,
            @NonNull BiometricLogger biometricLogger,
            @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric,
            @Nullable TaskStackListener taskStackListener,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            boolean allowBackgroundAuthentication,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @Authenticators.Types int biometricStrength,
            @Nullable LockoutTracker lockoutTracker) {
        super(
                context,
                lazyDaemon,
                token,
                listener,
                operationId,
                restricted,
                options,
                cookie,
                requireConfirmation,
                biometricLogger,
                biometricContext,
                isStrongBiometric,
                taskStackListener,
                lockoutTracker,
                allowBackgroundAuthentication,
                false /* shouldVibrate */,
                biometricStrength);
        setRequestId(requestId);
        mSensorOverlays = new SensorOverlays(udfpsOverlayController);
        mAuthenticationStateListeners = authenticationStateListeners;
        mIsStrongBiometric = isStrongBiometric;
        mSensorProps = sensorProps;
        mALSProbeCallback = getLogger().getAmbientLightProbe(false /* startWithClient */);
        mAuthSessionCoordinator = biometricContext.getAuthSessionCoordinator();
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
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
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(
                mALSProbeCallback, getBiometricContextUnsubscriber(), callback);
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
    public void onAuthenticated(
            BiometricAuthenticator.Identifier identifier,
            boolean authenticated,
            ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);
        handleLockout(authenticated);
        if (authenticated) {
            mState = STATE_STOPPED;
            resetIgnoreDisplayTouches();
            mSensorOverlays.hide(getSensorId());
            if (reportBiometricAuthAttempts()) {
                mAuthenticationStateListeners.onAuthenticationSucceeded(
                        new AuthenticationSucceededInfo.Builder(BiometricSourceType.FINGERPRINT,
                                getRequestReason(), mIsStrongBiometric, getTargetUserId()).build()
                );
            }
            mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                    .Builder(BiometricSourceType.FINGERPRINT, getRequestReason()).build()
            );
        } else {
            mState = STATE_STARTED_PAUSED_ATTEMPTED;
            if (reportBiometricAuthAttempts()) {
                mAuthenticationStateListeners.onAuthenticationFailed(new AuthenticationFailedInfo
                        .Builder(BiometricSourceType.FINGERPRINT, getRequestReason(),
                        getTargetUserId()).build()
                );
            }
        }
    }

    private void handleLockout(boolean authenticated) {
        if (getLockoutTracker() == null) {
            Slog.d(TAG, "Lockout is implemented by the HAL");
            return;
        }
        if (authenticated) {
            getLockoutTracker().resetFailedAttemptsForUser(true /* clearAttemptCounter */,
                    getTargetUserId());
        } else {
            @LockoutTracker.LockoutMode final int lockoutMode =
                    getLockoutTracker().getLockoutModeForUser(getTargetUserId());
            if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
                Slog.w(TAG, "Fingerprint locked out, lockoutMode(" + lockoutMode + ")");
                final int errorCode = lockoutMode == LockoutTracker.LOCKOUT_TIMED
                        ? BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                        : BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                // Send the error, but do not invoke the FinishCallback yet. Since lockout is not
                // controlled by the HAL, the framework must stop the sensor before finishing the
                // client.
                resetIgnoreDisplayTouches();
                mSensorOverlays.hide(getSensorId());
                mAuthenticationStateListeners.onAuthenticationError(
                        new AuthenticationErrorInfo.Builder(BiometricSourceType.FINGERPRINT,
                                getRequestReason(),
                                getErrorString(getContext(), errorCode, 0 /* vendorCode */),
                                errorCode).build()
                );
                onErrorInternal(errorCode, 0 /* vendorCode */, false /* finish */);
                cancel();
            }
        }
    }

    @Override
    public void onAcquired(@FingerprintAcquired int acquiredInfo, int vendorCode) {
        mAuthenticationStateListeners.onAuthenticationAcquired(
                new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FINGERPRINT,
                        getRequestReason(), acquiredInfo).build()
        );
        if (acquiredInfo != BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START) {
            String helpMsg = getAcquiredString(getContext(), acquiredInfo, vendorCode);
            if (helpMsg != null) {
                int helpCode = acquiredInfo == FINGERPRINT_ACQUIRED_VENDOR
                        ? (vendorCode + FINGERPRINT_ACQUIRED_VENDOR_BASE) : acquiredInfo;
                mAuthenticationStateListeners.onAuthenticationHelp(
                        new AuthenticationHelpInfo.Builder(BiometricSourceType.FINGERPRINT,
                                getRequestReason(), helpMsg, helpCode).build()
                );
            }
        }
        // For UDFPS, notify SysUI with acquiredInfo, so that the illumination can be turned off
        // for most ACQUIRED messages. See BiometricFingerprintConstants#FingerprintAcquired
        mSensorOverlays.ifUdfps(controller -> controller.onAcquired(getSensorId(), acquiredInfo));
        super.onAcquired(acquiredInfo, vendorCode);
        PerformanceTracker pt = PerformanceTracker.getInstanceForSensorId(getSensorId());
        pt.incrementAcquireForUser(getTargetUserId(), isCryptoOperation());
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        mAuthenticationStateListeners.onAuthenticationError(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FINGERPRINT,
                        getRequestReason(), getErrorString(getContext(), errorCode, vendorCode),
                        errorCode).build()
        );
        super.onError(errorCode, vendorCode);

        if (errorCode == BiometricFingerprintConstants.FINGERPRINT_ERROR_BAD_CALIBRATION) {
            BiometricNotificationUtils.showBadCalibrationNotification(getContext());
        }

        resetIgnoreDisplayTouches();
        mSensorOverlays.hide(getSensorId());
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FINGERPRINT, getRequestReason()).build()
        );
    }

    @Override
    protected void startHalOperation() {
        resetIgnoreDisplayTouches();
        mSensorOverlays.show(getSensorId(), getRequestReason(), this);
        mAuthenticationStateListeners.onAuthenticationStarted(new AuthenticationStartedInfo
                .Builder(BiometricSourceType.FINGERPRINT, getRequestReason()).build()
        );
        try {
            doAuthenticate();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            onError(
                    BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mSensorOverlays.hide(getSensorId());
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private void doAuthenticate() throws RemoteException {
        final AidlSession session = getFreshDaemon();
        final OperationContextExt opContext = getOperationContext();

        getBiometricContext().subscribe(opContext, ctx -> {
            try {
                if (session.hasContextMethods()) {
                    mCancellationSignal = session.getSession().authenticateWithContext(mOperationId,
                            ctx);
                } else {
                    mCancellationSignal = session.getSession().authenticate(mOperationId);
                }

                if (getBiometricContext().isAwake()) {
                    mALSProbeCallback.getProbe().enable();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                onError(
                        BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                mSensorOverlays.hide(getSensorId());
                mCallback.onClientFinished(this, false /* success */);
            }
        }, ctx -> {
            if (session.hasContextMethods()) {
                try {
                    session.getSession().onContextChanged(ctx);
                    // TODO(b/317414324): Deprecate setIgnoreDisplayTouches
                    if (ctx.operationState != null && ctx.operationState.getTag()
                            == OperationState.fingerprintOperationState) {
                        session.getSession().setIgnoreDisplayTouches(ctx.operationState
                                .getFingerprintOperationState().isHardwareIgnoringTouches);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            }

            final boolean isAwake = getBiometricContext().isAwake();
            if (isAwake) {
                mALSProbeCallback.getProbe().enable();
            } else {
                mALSProbeCallback.getProbe().disable();
            }
        }, getOptions());
    }

    @Override
    protected void stopHalOperation() {
        resetIgnoreDisplayTouches();
        mSensorOverlays.hide(getSensorId());
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FINGERPRINT, getRequestReason()).build()
        );
        unsubscribeBiometricContext();

        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                onError(
                        BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        } else {
            Slog.e(TAG, "cancellation signal was null");
        }
    }

    @Override
    public void onPointerDown(PointerContext pc) {
        try {
            mIsPointerDown = true;
            mState = STATE_STARTED;

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                session.getSession().onPointerDownWithContext(pc);
            } else {
                session.getSession().onPointerDown(pc.pointerId, (int) pc.x, (int) pc.y, pc.minor,
                        pc.major);
            }

            getListener().onUdfpsPointerDown(getSensorId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onPointerUp(PointerContext pc) {
        try {
            mIsPointerDown = false;
            mState = STATE_STARTED_PAUSED_ATTEMPTED;

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                session.getSession().onPointerUpWithContext(pc);
            } else {
                session.getSession().onPointerUp(pc.pointerId);
            }

            getListener().onUdfpsPointerUp(getSensorId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public boolean isPointerDown() {
        return mIsPointerDown;
    }

    @Override
    public void onUdfpsUiEvent(@FingerprintManager.UdfpsUiEvent int event) {
        try {
            if (event == FingerprintManager.UDFPS_UI_READY) {
                getFreshDaemon().getSession().onUiReady();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onLockoutTimed(long durationMillis) {
        mAuthSessionCoordinator.lockOutTimed(getTargetUserId(), getSensorStrength(), getSensorId(),
                durationMillis, getRequestId());
        // Lockout metrics are logged as an error code.
        final int error = BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT;
        getLogger()
                .logOnError(
                        getContext(),
                        getOperationContext(),
                        error,
                        0 /* vendorCode */,
                        getTargetUserId());

        PerformanceTracker.getInstanceForSensorId(getSensorId())
                .incrementTimedLockoutForUser(getTargetUserId());

        try {
            mAuthenticationStateListeners.onAuthenticationError(new AuthenticationErrorInfo
                    .Builder(BiometricSourceType.FINGERPRINT, getRequestReason(),
                    getErrorString(getContext(), error, 0), error).build()
            );
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        resetIgnoreDisplayTouches();
        mSensorOverlays.hide(getSensorId());
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FINGERPRINT, getRequestReason()).build()
        );
        mCallback.onClientFinished(this, false /* success */);
    }

    @Override
    public void onLockoutPermanent() {
        mAuthSessionCoordinator.lockedOutFor(getTargetUserId(), getSensorStrength(), getSensorId(),
                getRequestId());
        // Lockout metrics are logged as an error code.
        final int error = BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
        getLogger()
                .logOnError(
                        getContext(),
                        getOperationContext(),
                        error,
                        0 /* vendorCode */,
                        getTargetUserId());

        PerformanceTracker.getInstanceForSensorId(getSensorId())
                .incrementPermanentLockoutForUser(getTargetUserId());

        try {
            mAuthenticationStateListeners.onAuthenticationError(new AuthenticationErrorInfo
                    .Builder(BiometricSourceType.FINGERPRINT, getRequestReason(),
                    getErrorString(getContext(), error, 0), error).build()
            );
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        resetIgnoreDisplayTouches();
        mSensorOverlays.hide(getSensorId());
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FINGERPRINT, getRequestReason()).build()
        );
        mCallback.onClientFinished(this, false /* success */);
    }

    @Override
    public void onPowerPressed() { }
}
