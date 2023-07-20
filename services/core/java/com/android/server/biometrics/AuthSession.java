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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_MULTI_SENSOR_DEFAULT;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_MULTI_SENSOR_FINGERPRINT_AND_FACE;

import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTHENTICATED_PENDING_SYSUI;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_CALLED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_IDLE;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PAUSED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PAUSED_RESUMING;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PENDING_CONFIRM;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_STARTED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_STARTED_UI_SHOWING;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_CLIENT_DIED_CANCELLING;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_ERROR_PENDING_SYSUI;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_SHOWING_DEVICE_CREDENTIAL;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricManager.BiometricMultiSensorMode;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.security.KeyStore;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.log.BiometricFrameworkStatsLogger;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import com.android.internal.util.custom.faceunlock.FaceUnlockUtils;

/**
 * Class that defines the states of an authentication session invoked via
 * {@link android.hardware.biometrics.BiometricPrompt}, as well as all of the necessary
 * state information for such a session.
 */
public final class AuthSession implements IBinder.DeathRecipient {
    private static final String TAG = "BiometricService/AuthSession";
    private static final boolean DEBUG = false;

    /*
     * Defined in biometrics.proto
     */
    @IntDef({
            STATE_AUTH_IDLE,
            STATE_AUTH_CALLED,
            STATE_AUTH_STARTED,
            STATE_AUTH_STARTED_UI_SHOWING,
            STATE_AUTH_PAUSED,
            STATE_AUTH_PAUSED_RESUMING,
            STATE_AUTH_PENDING_CONFIRM,
            STATE_AUTHENTICATED_PENDING_SYSUI,
            STATE_ERROR_PENDING_SYSUI,
            STATE_SHOWING_DEVICE_CREDENTIAL})
    @Retention(RetentionPolicy.SOURCE)
    @interface SessionState {}

    /**
     * Notify the holder of the AuthSession that the caller/client's binder has died. The
     * holder (BiometricService) should schedule {@link AuthSession#onClientDied()} to be run
     * on its handler (instead of whatever thread invokes the death recipient callback).
     */
    interface ClientDeathReceiver {
        void onClientDied();
    }

    private final Context mContext;
    private final IStatusBarService mStatusBarService;
    @VisibleForTesting final IBiometricSysuiReceiver mSysuiReceiver;
    private final KeyStore mKeyStore;
    private final Random mRandom;
    private final ClientDeathReceiver mClientDeathReceiver;
    final PreAuthInfo mPreAuthInfo;

    // The following variables are passed to authenticateInternal, which initiates the
    // appropriate <Biometric>Services.
    @VisibleForTesting final IBinder mToken;
    // Info to be shown on BiometricDialog when all cookies are returned.
    @VisibleForTesting final PromptInfo mPromptInfo;
    private final long mRequestId;
    private final long mOperationId;
    private final int mUserId;
    @VisibleForTesting final IBiometricSensorReceiver mSensorReceiver;
    // Original receiver from BiometricPrompt.
    private final IBiometricServiceReceiver mClientReceiver;
    private final String mOpPackageName;
    private final boolean mDebugEnabled;
    private final List<FingerprintSensorPropertiesInternal> mFingerprintSensorProperties;

    // The current state, which can be either idle, called, or started
    private @SessionState int mState = STATE_AUTH_IDLE;
    private @BiometricMultiSensorMode int mMultiSensorMode;
    private int[] mSensors;
    // TODO(b/197265902): merge into state
    private boolean mCancelled;
    private int mAuthenticatedSensorId = -1;
    // For explicit confirmation, do not send to keystore until the user has confirmed
    // the authentication.
    private byte[] mTokenEscrow;
    // Waiting for SystemUI to complete animation
    private int mErrorEscrow;
    private int mVendorCodeEscrow;

    // Timestamp when authentication started
    private long mStartTimeMs;
    // Timestamp when hardware authentication occurred
    private long mAuthenticatedTimeMs;

    AuthSession(@NonNull Context context,
            @NonNull IStatusBarService statusBarService,
            @NonNull IBiometricSysuiReceiver sysuiReceiver,
            @NonNull KeyStore keystore,
            @NonNull Random random,
            @NonNull ClientDeathReceiver clientDeathReceiver,
            @NonNull PreAuthInfo preAuthInfo,
            @NonNull IBinder token,
            long requestId,
            long operationId,
            int userId,
            @NonNull IBiometricSensorReceiver sensorReceiver,
            @NonNull IBiometricServiceReceiver clientReceiver,
            @NonNull String opPackageName,
            @NonNull PromptInfo promptInfo,
            boolean debugEnabled,
            @NonNull List<FingerprintSensorPropertiesInternal> fingerprintSensorProperties) {
        Slog.d(TAG, "Creating AuthSession with: " + preAuthInfo);
        mContext = context;
        mStatusBarService = statusBarService;
        mSysuiReceiver = sysuiReceiver;
        mKeyStore = keystore;
        mRandom = random;
        mClientDeathReceiver = clientDeathReceiver;
        mPreAuthInfo = preAuthInfo;
        mToken = token;
        mRequestId = requestId;
        mOperationId = operationId;
        mUserId = userId;
        mSensorReceiver = sensorReceiver;
        mClientReceiver = clientReceiver;
        mOpPackageName = opPackageName;
        mPromptInfo = promptInfo;
        mDebugEnabled = debugEnabled;
        mFingerprintSensorProperties = fingerprintSensorProperties;
        mCancelled = false;

        try {
            mClientReceiver.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to link to death");
        }

        setSensorsToStateUnknown();
    }

    @Override
    public void binderDied() {
        Slog.e(TAG, "Binder died, session: " + this);
        mClientDeathReceiver.onClientDied();
    }

    /**
     * @return bitmask representing the modalities that are running or could be running for the
     * current session.
     */
    private @BiometricAuthenticator.Modality int getEligibleModalities() {
        return mPreAuthInfo.getEligibleModalities();
    }

    private void setSensorsToStateUnknown() {
        // Generate random cookies to pass to the services that should prepare to start
        // authenticating. Store the cookie here and wait for all services to "ack"
        // with the cookie. Once all cookies are received, we can show the prompt
        // and let the services start authenticating. The cookie should be non-zero.
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (DEBUG) {
                Slog.v(TAG, "set to unknown state sensor: " + sensor.id);
            }
            sensor.goToStateUnknown();
        }
    }

    private void setSensorsToStateWaitingForCookie(boolean isTryAgain) throws RemoteException {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            @BiometricSensor.SensorState final int state = sensor.getSensorState();
            if (isTryAgain
                    && state != BiometricSensor.STATE_STOPPED
                    && state != BiometricSensor.STATE_CANCELING) {
                Slog.d(TAG, "Skip retry because sensor: " + sensor.id + " is: " + state);
                continue;
            }

            final int cookie = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            final boolean requireConfirmation = isConfirmationRequired(sensor);

            if (DEBUG) {
                Slog.v(TAG, "waiting for cooking for sensor: " + sensor.id);
            }
            sensor.goToStateWaitingForCookie(requireConfirmation, mToken, mOperationId,
                    mUserId, mSensorReceiver, mOpPackageName, mRequestId, cookie,
                    mPromptInfo.isAllowBackgroundAuthentication());
        }
    }

    void goToInitialState() throws RemoteException {
        if (mPreAuthInfo.credentialAvailable && mPreAuthInfo.eligibleSensors.isEmpty()) {
            // Only device credential should be shown. In this case, we don't need to wait,
            // since LockSettingsService/Gatekeeper is always ready to check for credential.
            // SystemUI invokes that path.
            mState = STATE_SHOWING_DEVICE_CREDENTIAL;
            mSensors = new int[0];
            mMultiSensorMode = BIOMETRIC_MULTI_SENSOR_DEFAULT;

            mStatusBarService.showAuthenticationDialog(
                    mPromptInfo,
                    mSysuiReceiver,
                    mSensors /* sensorIds */,
                    true /* credentialAllowed */,
                    false /* requireConfirmation */,
                    mUserId,
                    mOperationId,
                    mOpPackageName,
                    mRequestId,
                    mMultiSensorMode);
        } else if (!mPreAuthInfo.eligibleSensors.isEmpty()) {
            // Some combination of biometric or biometric|credential is requested
            setSensorsToStateWaitingForCookie(false /* isTryAgain */);
            mState = STATE_AUTH_CALLED;
        } else {
            // No authenticators requested. This should never happen - an exception should have
            // been thrown earlier in the pipeline.
            throw new IllegalStateException("No authenticators requested");
        }
    }

    void onCookieReceived(int cookie) {
        if (mCancelled) {
            Slog.w(TAG, "Received cookie but already cancelled (ignoring): " + cookie);
            return;
        }
        if (hasAuthenticated()) {
            Slog.d(TAG, "onCookieReceived after successful auth");
            return;
        }

        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            sensor.goToStateCookieReturnedIfCookieMatches(cookie);
        }

        if (allCookiesReceived()) {
            mStartTimeMs = System.currentTimeMillis();

            // Do not start fingerprint sensors until BiometricPrompt UI is shown. Otherwise,
            // the affordance may be shown before the BP UI is finished animating in.
            startAllPreparedSensorsExceptFingerprint();

            // No need to request the UI if we're coming from the paused state.
            if (mState != STATE_AUTH_PAUSED_RESUMING) {
                try {
                    // If any sensor requires confirmation, request it to be shown.
                    final boolean requireConfirmation = isConfirmationRequiredByAnyEligibleSensor();

                    mSensors = new int[mPreAuthInfo.eligibleSensors.size()];
                    for (int i = 0; i < mPreAuthInfo.eligibleSensors.size(); i++) {
                        mSensors[i] = mPreAuthInfo.eligibleSensors.get(i).id;
                    }
                    mMultiSensorMode = getMultiSensorModeForNewSession(
                            mPreAuthInfo.eligibleSensors);

                    mStatusBarService.showAuthenticationDialog(mPromptInfo,
                            mSysuiReceiver,
                            mSensors,
                            mPreAuthInfo.shouldShowCredential(),
                            requireConfirmation,
                            mUserId,
                            mOperationId,
                            mOpPackageName,
                            mRequestId,
                            mMultiSensorMode);
                    mState = STATE_AUTH_STARTED;
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            } else {
                // The UI was already showing :)
                mState = STATE_AUTH_STARTED_UI_SHOWING;
            }
        } else {
            Slog.v(TAG, "onCookieReceived: still waiting");
        }
    }

    private boolean isConfirmationRequired(BiometricSensor sensor) {
        if (sensor.modality == TYPE_FACE && FaceUnlockUtils.isFaceUnlockSupported()) {
            return true;
        }
        return sensor.confirmationSupported()
                && (sensor.confirmationAlwaysRequired(mUserId)
                || mPreAuthInfo.confirmationRequested);
    }

    private boolean isConfirmationRequiredByAnyEligibleSensor() {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (isConfirmationRequired(sensor)) {
                return true;
            }
        }
        return false;
    }

    private void startAllPreparedSensorsExceptFingerprint() {
        startAllPreparedSensors(sensor -> sensor.modality != TYPE_FINGERPRINT);
    }

    private void startAllPreparedFingerprintSensors() {
        startAllPreparedSensors(sensor -> sensor.modality == TYPE_FINGERPRINT);
    }

    private void startAllPreparedSensors(Function<BiometricSensor, Boolean> filter) {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (filter.apply(sensor)) {
                try {
                    if (DEBUG) {
                        Slog.v(TAG, "Starting sensor: " + sensor.id);
                    }
                    sensor.startSensor();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to start prepared client, sensor: " + sensor, e);
                }
            }
        }
    }

    private void cancelAllSensors() {
        cancelAllSensors(sensor -> true);
    }

    private void cancelAllSensors(Function<BiometricSensor, Boolean> filter) {
        // TODO: For multiple modalities, send a single ERROR_CANCELED only when all
        // drivers have canceled authentication. We'd probably have to add a state for
        // STATE_CANCELING for when we're waiting for final ERROR_CANCELED before
        // sending the final error callback to the application.
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            try {
                if (filter.apply(sensor)) {
                    Slog.d(TAG, "Cancelling sensorId: " + sensor.id);
                    sensor.goToStateCancelling(mToken, mOpPackageName, mRequestId);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to cancel authentication");
            }
        }
    }

    /**
     * @return true if this AuthSession is finished, e.g. should be set to null.
     */
    boolean onErrorReceived(int sensorId, int cookie, @BiometricConstants.Errors int error,
            int vendorCode) throws RemoteException {
        Slog.d(TAG, "onErrorReceived sensor: " + sensorId + " error: " + error);

        if (!containsCookie(cookie)) {
            Slog.e(TAG, "Unknown/expired cookie: " + cookie);
            return false;
        }

        // TODO: The sensor-specific state is not currently used, this would need to be updated if
        // multiple authenticators are running.
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensor.getSensorState() == BiometricSensor.STATE_AUTHENTICATING) {
                sensor.goToStoppedStateIfCookieMatches(cookie, error);
            }
        }

        // do not propagate the error and let onAuthenticationSucceeded handle the new state
        if (hasAuthenticated()) {
            Slog.d(TAG, "onErrorReceived after successful auth (ignoring)");
            return false;
        }

        mErrorEscrow = error;
        mVendorCodeEscrow = vendorCode;

        @Modality final int modality = sensorIdToModality(sensorId);

        switch (mState) {
            case STATE_AUTH_CALLED: {
                // If any error is received while preparing the auth session (lockout, etc),
                // and if device credential is allowed, just show the credential UI.
                if (isAllowDeviceCredential()) {
                    @BiometricManager.Authenticators.Types int authenticators =
                            mPromptInfo.getAuthenticators();
                    // Disallow biometric and notify SystemUI to show the authentication prompt.
                    authenticators = Utils.removeBiometricBits(authenticators);
                    mPromptInfo.setAuthenticators(authenticators);

                    mState = STATE_SHOWING_DEVICE_CREDENTIAL;
                    mMultiSensorMode = BIOMETRIC_MULTI_SENSOR_DEFAULT;
                    mSensors = new int[0];

                    mStatusBarService.showAuthenticationDialog(
                            mPromptInfo,
                            mSysuiReceiver,
                            mSensors /* sensorIds */,
                            true /* credentialAllowed */,
                            false /* requireConfirmation */,
                            mUserId,
                            mOperationId,
                            mOpPackageName,
                            mRequestId,
                            mMultiSensorMode);
                } else {
                    mClientReceiver.onError(modality, error, vendorCode);
                    return true;
                }
                break;
            }

            case STATE_AUTH_STARTED:
            case STATE_AUTH_STARTED_UI_SHOWING: {
                final boolean errorLockout = error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                        || error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                if (isAllowDeviceCredential() && errorLockout) {
                    // SystemUI handles transition from biometric to device credential.
                    mState = STATE_SHOWING_DEVICE_CREDENTIAL;
                    mStatusBarService.onBiometricError(modality, error, vendorCode);
                } else if (error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
                    mStatusBarService.hideAuthenticationDialog(mRequestId);
                    // TODO: If multiple authenticators are simultaneously running, this will
                    // need to be modified. Send the error to the client here, instead of doing
                    // a round trip to SystemUI.
                    mClientReceiver.onError(modality, error, vendorCode);
                    return true;
                } else {
                    mState = STATE_ERROR_PENDING_SYSUI;
                    mStatusBarService.onBiometricError(modality, error, vendorCode);
                }
                break;
            }

            case STATE_AUTH_PAUSED: {
                // In the "try again" state, we should forward canceled errors to
                // the client and clean up. The only error we should get here is
                // ERROR_CANCELED due to another client kicking us out.
                mClientReceiver.onError(modality, error, vendorCode);
                mStatusBarService.hideAuthenticationDialog(mRequestId);
                return true;
            }

            case STATE_SHOWING_DEVICE_CREDENTIAL:
                Slog.d(TAG, "Biometric canceled, ignoring from state: " + mState);
                break;

            case STATE_CLIENT_DIED_CANCELLING:
                mStatusBarService.hideAuthenticationDialog(mRequestId);
                return true;

            default:
                Slog.e(TAG, "Unhandled error state, mState: " + mState);
                break;
        }

        return false;
    }

    void onAcquired(int sensorId, int acquiredInfo, int vendorCode) {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onAcquired after successful auth");
            return;
        }

        final String message = getAcquiredMessageForSensor(sensorId, acquiredInfo, vendorCode);
        Slog.d(TAG, "sensorId: " + sensorId + " acquiredInfo: " + acquiredInfo
                + " message: " + message);
        if (message == null) {
            return;
        }

        try {
            mStatusBarService.onBiometricHelp(sensorIdToModality(sensorId), message);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    void onSystemEvent(int event) {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onSystemEvent after successful auth");
            return;
        }
        if (!mPromptInfo.isReceiveSystemEvents()) {
            return;
        }

        try {
            mClientReceiver.onSystemEvent(event);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onDialogAnimatedIn() {
        if (mState != STATE_AUTH_STARTED) {
            Slog.e(TAG, "onDialogAnimatedIn, unexpected state: " + mState);
            return;
        }

        mState = STATE_AUTH_STARTED_UI_SHOWING;
        startAllPreparedFingerprintSensors();
    }

    void onTryAgainPressed() {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onTryAgainPressed after successful auth");
            return;
        }

        if (mState != STATE_AUTH_PAUSED) {
            Slog.w(TAG, "onTryAgainPressed, state: " + mState);
        }

        try {
            setSensorsToStateWaitingForCookie(true /* isTryAgain */);
            mState = STATE_AUTH_PAUSED_RESUMING;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException: " + e);
        }
    }

    void onAuthenticationSucceeded(int sensorId, boolean strong, byte[] token) {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onAuthenticationSucceeded after successful auth");
            return;
        }

        mAuthenticatedSensorId = sensorId;
        if (strong) {
            mTokenEscrow = token;
        } else {
            if (token != null) {
                Slog.w(TAG, "Dropping authToken for non-strong biometric, id: " + sensorId);
            }
        }

        try {
            // Notify SysUI that the biometric has been authenticated. SysUI already knows
            // the implicit/explicit state and will react accordingly.
            mStatusBarService.onBiometricAuthenticated(sensorIdToModality(sensorId));

            final boolean requireConfirmation = isConfirmationRequiredByAnyEligibleSensor();

            if (!requireConfirmation) {
                mState = STATE_AUTHENTICATED_PENDING_SYSUI;
            } else {
                mAuthenticatedTimeMs = System.currentTimeMillis();
                mState = STATE_AUTH_PENDING_CONFIRM;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }

        cancelAllSensors(sensor -> sensor.id != sensorId);
    }

    void onAuthenticationRejected(int sensorId) {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onAuthenticationRejected after successful auth");
            return;
        }

        try {
            mStatusBarService.onBiometricError(sensorIdToModality(sensorId),
                    BiometricConstants.BIOMETRIC_PAUSED_REJECTED, 0 /* vendorCode */);
            if (pauseSensorIfSupported(sensorId)) {
                mState = STATE_AUTH_PAUSED;
            }
            mClientReceiver.onAuthenticationFailed();
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onAuthenticationTimedOut(int sensorId, int cookie, int error, int vendorCode) {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onAuthenticationTimedOut after successful auth");
            return;
        }

        try {
            mStatusBarService.onBiometricError(sensorIdToModality(sensorId), error, vendorCode);
            pauseSensorIfSupported(sensorId);
            mState = STATE_AUTH_PAUSED;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    private boolean pauseSensorIfSupported(int sensorId) {
        if (sensorIdToModality(sensorId) == TYPE_FACE) {
            cancelAllSensors(sensor -> sensor.id == sensorId);
            return true;
        }
        return false;
    }

    void onDeviceCredentialPressed() {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onDeviceCredentialPressed after successful auth");
            return;
        }

        // Cancel authentication. Skip the token/package check since we are cancelling
        // from system server. The interface is permission protected so this is fine.
        cancelAllSensors();
        mState = STATE_SHOWING_DEVICE_CREDENTIAL;
    }

    /**
     * @return true if this session is finished and should be set to null.
     */
    boolean onClientDied() {
        try {
            switch (mState) {
                case STATE_AUTH_STARTED:
                case STATE_AUTH_STARTED_UI_SHOWING:
                    mState = STATE_CLIENT_DIED_CANCELLING;
                    cancelAllSensors();
                    return false;
                default:
                    mStatusBarService.hideAuthenticationDialog(mRequestId);
                    return true;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception: " + e);
            return true;
        }
    }

    private boolean hasAuthenticated() {
        return mAuthenticatedSensorId != -1;
    }

    private void logOnDialogDismissed(@BiometricPrompt.DismissedReason int reason) {
        if (reason == BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED) {
            // Explicit auth, authentication confirmed.
            // Latency in this case is authenticated -> confirmed. <Biometric>Service
            // should have the first half (first acquired -> authenticated).
            final long latency = System.currentTimeMillis() - mAuthenticatedTimeMs;

            if (DEBUG) {
                Slog.v(TAG, "Confirmed! Modality: " + statsModality()
                        + ", User: " + mUserId
                        + ", IsCrypto: " + isCrypto()
                        + ", Client: " + BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT
                        + ", RequireConfirmation: " + mPreAuthInfo.confirmationRequested
                        + ", State: " + FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED
                        + ", Latency: " + latency);
            }

            final OperationContext operationContext = new OperationContext();
            operationContext.isCrypto = isCrypto();
            BiometricFrameworkStatsLogger.getInstance().authenticate(
                    operationContext,
                    statsModality(),
                    BiometricsProtoEnums.ACTION_UNKNOWN,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    mDebugEnabled,
                    latency,
                    FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED,
                    mPreAuthInfo.confirmationRequested,
                    mUserId,
                    -1f /* ambientLightLux */);
        } else {
            final long latency = System.currentTimeMillis() - mStartTimeMs;

            int error = reason == BiometricPrompt.DISMISSED_REASON_NEGATIVE
                    ? BiometricConstants.BIOMETRIC_ERROR_NEGATIVE_BUTTON
                    : reason == BiometricPrompt.DISMISSED_REASON_USER_CANCEL
                            ? BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED
                            : 0;
            if (DEBUG) {
                Slog.v(TAG, "Dismissed! Modality: " + statsModality()
                        + ", User: " + mUserId
                        + ", IsCrypto: " + isCrypto()
                        + ", Action: " + BiometricsProtoEnums.ACTION_AUTHENTICATE
                        + ", Client: " + BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT
                        + ", Reason: " + reason
                        + ", Error: " + error
                        + ", Latency: " + latency);
            }
            // Auth canceled
            final OperationContext operationContext = new OperationContext();
            operationContext.isCrypto = isCrypto();
            BiometricFrameworkStatsLogger.getInstance().error(
                    operationContext,
                    statsModality(),
                    BiometricsProtoEnums.ACTION_AUTHENTICATE,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    mDebugEnabled,
                    latency,
                    error,
                    0 /* vendorCode */,
                    mUserId);
        }
    }

    void onDialogDismissed(@BiometricPrompt.DismissedReason int reason,
            @Nullable byte[] credentialAttestation) {
        logOnDialogDismissed(reason);
        try {
            switch (reason) {
                case BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED:
                    if (credentialAttestation != null) {
                        mKeyStore.addAuthToken(credentialAttestation);
                    } else {
                        Slog.e(TAG, "credentialAttestation is null");
                    }
                case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED:
                case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED:
                    if (mTokenEscrow != null) {
                        final int result = mKeyStore.addAuthToken(mTokenEscrow);
                        Slog.d(TAG, "addAuthToken: " + result);
                    } else {
                        Slog.e(TAG, "mTokenEscrow is null");
                    }
                    mClientReceiver.onAuthenticationSucceeded(
                            Utils.getAuthenticationTypeForResult(reason));
                    break;

                case BiometricPrompt.DISMISSED_REASON_NEGATIVE:
                    mClientReceiver.onDialogDismissed(reason);
                    break;

                case BiometricPrompt.DISMISSED_REASON_USER_CANCEL:
                    mClientReceiver.onError(
                            getEligibleModalities(),
                            BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED,
                            0 /* vendorCode */
                    );
                    break;

                case BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED:
                case BiometricPrompt.DISMISSED_REASON_ERROR:
                    mClientReceiver.onError(
                            getEligibleModalities(),
                            mErrorEscrow,
                            mVendorCodeEscrow
                    );
                    break;

                default:
                    Slog.w(TAG, "Unhandled reason: " + reason);
                    break;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        } finally {
            // ensure everything is cleaned up when dismissed
            cancelAllSensors();
        }
    }

    /**
     * Cancels authentication for the entire authentication session. The caller will receive
     * {@link BiometricPrompt#BIOMETRIC_ERROR_CANCELED} at some point.
     *
     * @param force if true, will immediately dismiss the dialog and send onError to the client
     * @return true if this AuthSession is finished, e.g. should be set to null
     */
    boolean onCancelAuthSession(boolean force) {
        if (hasAuthenticated()) {
            Slog.d(TAG, "onCancelAuthSession after successful auth");
            return true;
        }

        mCancelled = true;

        final boolean authStarted = mState == STATE_AUTH_CALLED
                || mState == STATE_AUTH_STARTED
                || mState == STATE_AUTH_STARTED_UI_SHOWING;

        cancelAllSensors();
        if (authStarted && !force) {
            // Wait for ERROR_CANCELED to be returned from the sensors
            return false;
        } else {
            // If we're in a state where biometric sensors are not running (e.g. pending confirm,
            // showing device credential, etc), we need to dismiss the dialog and send our own
            // ERROR_CANCELED to the client, since we won't be getting an onError from the driver.
            try {
                // Send error to client
                mClientReceiver.onError(
                        getEligibleModalities(),
                        BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                        0 /* vendorCode */
                );
                mStatusBarService.hideAuthenticationDialog(mRequestId);
                return true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }
        return false;
    }

    boolean isCrypto() {
        return mOperationId != 0;
    }

    private boolean containsCookie(int cookie) {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensor.getCookie() == cookie) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowDeviceCredential() {
        return Utils.isCredentialRequested(mPromptInfo);
    }

    @VisibleForTesting
    boolean allCookiesReceived() {
        final int remainingCookies = mPreAuthInfo.numSensorsWaitingForCookie();
        Slog.d(TAG, "Remaining cookies: " + remainingCookies);
        return remainingCookies == 0;
    }

    @SessionState int getState() {
        return mState;
    }

    long getRequestId() {
        return mRequestId;
    }

    private int statsModality() {
        int modality = 0;

        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if ((sensor.modality & BiometricAuthenticator.TYPE_FINGERPRINT) != 0) {
                modality |= BiometricsProtoEnums.MODALITY_FINGERPRINT;
            }
            if ((sensor.modality & BiometricAuthenticator.TYPE_IRIS) != 0) {
                modality |= BiometricsProtoEnums.MODALITY_IRIS;
            }
            if ((sensor.modality & BiometricAuthenticator.TYPE_FACE) != 0) {
                modality |= BiometricsProtoEnums.MODALITY_FACE;
            }
        }

        return modality;
    }

    private @Modality int sensorIdToModality(int sensorId) {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensorId == sensor.id) {
                return sensor.modality;
            }
        }
        Slog.e(TAG, "Unknown sensor: " + sensorId);
        return TYPE_NONE;
    }

    private String getAcquiredMessageForSensor(int sensorId, int acquiredInfo, int vendorCode) {
        final @Modality int modality = sensorIdToModality(sensorId);
        switch (modality) {
            case BiometricAuthenticator.TYPE_FINGERPRINT:
                return FingerprintManager.getAcquiredString(mContext, acquiredInfo, vendorCode);
            case BiometricAuthenticator.TYPE_FACE:
                return FaceManager.getAuthHelpMessage(mContext, acquiredInfo, vendorCode);
            default:
                return null;
        }
    }

    @BiometricMultiSensorMode
    private static int getMultiSensorModeForNewSession(Collection<BiometricSensor> sensors) {
        boolean hasFace = false;
        boolean hasFingerprint = false;

        for (BiometricSensor sensor: sensors) {
            if (sensor.modality == TYPE_FACE) {
                hasFace = true;
            } else if (sensor.modality == TYPE_FINGERPRINT) {
                hasFingerprint = true;
            }
        }

        if (hasFace && hasFingerprint) {
            return BIOMETRIC_MULTI_SENSOR_FINGERPRINT_AND_FACE;
        }
        return BIOMETRIC_MULTI_SENSOR_DEFAULT;
    }

    @Override
    public String toString() {
        return "State: " + mState
                + ", cancelled: " + mCancelled
                + ", isCrypto: " + isCrypto()
                + ", PreAuthInfo: " + mPreAuthInfo
                + ", requestId: " + mRequestId;
    }
}
