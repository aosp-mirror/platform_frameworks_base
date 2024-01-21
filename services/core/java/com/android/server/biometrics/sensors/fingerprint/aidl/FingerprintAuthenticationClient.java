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

import static com.android.systemui.shared.Flags.sidefpsControllerRefactor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricFingerprintConstants.FingerprintAcquired;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationState;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.biometrics.Flags;
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

import java.time.Clock;
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
    private static final int MESSAGE_AUTH_SUCCESS = 2;
    private static final int MESSAGE_FINGER_UP = 3;
    @NonNull
    private final SensorOverlays mSensorOverlays;
    @NonNull
    private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull
    private final CallbackWithProbe<Probe> mALSProbeCallback;
    private final Handler mHandler;
    private final int mSkipWaitForPowerAcquireMessage;
    private final int mSkipWaitForPowerVendorAcquireMessage;
    private final long mFingerUpIgnoresPower = 500;
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    @NonNull private final AuthenticationStateListeners mAuthenticationStateListeners;
    @Nullable
    private ICancellationSignal mCancellationSignal;
    private boolean mIsPointerDown;
    private long mWaitForAuthKeyguard;
    private long mWaitForAuthBp;
    private long mIgnoreAuthFor;
    private long mSideFpsLastAcquireStartTime;
    private Runnable mAuthSuccessRunnable;
    private final Clock mClock;

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
            // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
            @Nullable ISidefpsController sidefpsController,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            boolean allowBackgroundAuthentication,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull Handler handler,
            @Authenticators.Types int biometricStrength,
            @NonNull Clock clock,
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
        if (sidefpsControllerRefactor()) {
            mSensorOverlays = new SensorOverlays(udfpsOverlayController);
        } else {
            mSensorOverlays = new SensorOverlays(udfpsOverlayController, sidefpsController);
        }
        mAuthenticationStateListeners = authenticationStateListeners;
        mSensorProps = sensorProps;
        mALSProbeCallback = getLogger().getAmbientLightProbe(false /* startWithClient */);
        mHandler = handler;

        mWaitForAuthKeyguard =
                context.getResources()
                        .getInteger(R.integer.config_sidefpsKeyguardPowerPressWindow);
        mWaitForAuthBp =
                context.getResources().getInteger(R.integer.config_sidefpsBpPowerPressWindow);
        mIgnoreAuthFor =
                context.getResources().getInteger(R.integer.config_sidefpsPostAuthDowntime);

        mSkipWaitForPowerAcquireMessage =
                context.getResources().getInteger(
                        R.integer.config_sidefpsSkipWaitForPowerAcquireMessage);
        mSkipWaitForPowerVendorAcquireMessage =
                context.getResources().getInteger(
                        R.integer.config_sidefpsSkipWaitForPowerVendorAcquireMessage);
        mAuthSessionCoordinator = biometricContext.getAuthSessionCoordinator();
        mSideFpsLastAcquireStartTime = -1;
        mClock = clock;

        if (mSensorProps.isAnySidefpsType()) {
            if (Build.isDebuggable()) {
                mWaitForAuthKeyguard = Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.FINGERPRINT_SIDE_FPS_KG_POWER_WINDOW,
                        (int) mWaitForAuthKeyguard, UserHandle.USER_CURRENT);
                mWaitForAuthBp = Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.FINGERPRINT_SIDE_FPS_BP_POWER_WINDOW, (int) mWaitForAuthBp,
                        UserHandle.USER_CURRENT);
                mIgnoreAuthFor = Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.FINGERPRINT_SIDE_FPS_AUTH_DOWNTIME, (int) mIgnoreAuthFor,
                        UserHandle.USER_CURRENT);
            }
        }
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
            mSensorOverlays.hide(getSensorId());
            if (sidefpsControllerRefactor()) {
                mAuthenticationStateListeners.onAuthenticationStopped();
            }
        } else {
            mState = STATE_STARTED_PAUSED_ATTEMPTED;
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
                mSensorOverlays.hide(getSensorId());
                if (sidefpsControllerRefactor()) {
                    mAuthenticationStateListeners.onAuthenticationStopped();
                }
                onErrorInternal(errorCode, 0 /* vendorCode */, false /* finish */);
                cancel();
            }
        }
    }

    @Override
    public void onAcquired(@FingerprintAcquired int acquiredInfo, int vendorCode) {
        // For UDFPS, notify SysUI with acquiredInfo, so that the illumination can be turned off
        // for most ACQUIRED messages. See BiometricFingerprintConstants#FingerprintAcquired
        mSensorOverlays.ifUdfps(controller -> controller.onAcquired(getSensorId(), acquiredInfo));
        super.onAcquired(acquiredInfo, vendorCode);
        PerformanceTracker pt = PerformanceTracker.getInstanceForSensorId(getSensorId());
        pt.incrementAcquireForUser(getTargetUserId(), isCryptoOperation());
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        if (errorCode == BiometricFingerprintConstants.FINGERPRINT_ERROR_BAD_CALIBRATION) {
            BiometricNotificationUtils.showBadCalibrationNotification(getContext());
        }

        mSensorOverlays.hide(getSensorId());
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStopped();
        }
    }

    @Override
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), getRequestReason(), this);
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStarted(getRequestReason());
        }

        try {
            if (Flags.deHidl()) {
                startAuthentication();
            } else {
                mCancellationSignal = doAuthenticate();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            onError(
                    BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mSensorOverlays.hide(getSensorId());
            if (sidefpsControllerRefactor()) {
                mAuthenticationStateListeners.onAuthenticationStopped();
            }
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doAuthenticate() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        final OperationContextExt opContext = getOperationContext();
        final ICancellationSignal cancel;
        if (session.hasContextMethods()) {
            cancel = session.getSession().authenticateWithContext(
                    mOperationId, opContext.toAidlContext(getOptions()));
        } else {
            cancel = session.getSession().authenticate(mOperationId);
        }

        getBiometricContext().subscribe(opContext, ctx -> {
            if (session.hasContextMethods()) {
                try {
                    session.getSession().onContextChanged(ctx);
                    // TODO(b/317414324): Deprecate setIgnoreDisplayTouches
                    if (ctx.operationState != null && ctx.operationState.getTag()
                            == OperationState.fingerprintOperationState) {
                        session.getSession().setIgnoreDisplayTouches(
                                ctx.operationState.getFingerprintOperationState().isHardwareIgnoringTouches);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            }

            // TODO(b/243836005): this should come via ctx
            final boolean isAwake = getBiometricContext().isAwake();
            if (isAwake) {
                mALSProbeCallback.getProbe().enable();
            } else {
                mALSProbeCallback.getProbe().disable();
            }
        });
        if (getBiometricContext().isAwake()) {
            mALSProbeCallback.getProbe().enable();
        }

        return cancel;
    }

    private void startAuthentication() {
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
                if (sidefpsControllerRefactor()) {
                    mAuthenticationStateListeners.onAuthenticationStopped();
                }
                mCallback.onClientFinished(this, false /* success */);
            }
        }, ctx -> {
            if (session.hasContextMethods()) {
                try {
                    session.getSession().onContextChanged(ctx);
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
        mSensorOverlays.hide(getSensorId());
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStopped();
        }
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

            if (getListener() != null) {
                getListener().onUdfpsPointerDown(getSensorId());
            }
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

            if (getListener() != null) {
                getListener().onUdfpsPointerUp(getSensorId());
            }
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
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        mSensorOverlays.hide(getSensorId());
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStopped();
        }
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
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        mSensorOverlays.hide(getSensorId());
        if (sidefpsControllerRefactor()) {
            mAuthenticationStateListeners.onAuthenticationStopped();
        }
        mCallback.onClientFinished(this, false /* success */);
    }

    @Override
    public void onPowerPressed() { }
}
