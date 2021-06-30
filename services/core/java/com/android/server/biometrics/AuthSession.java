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
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT;

import static com.android.server.biometrics.BiometricServiceStateProto.MULTI_SENSOR_STATE_FACE_SCANNING;
import static com.android.server.biometrics.BiometricServiceStateProto.MULTI_SENSOR_STATE_FP_SCANNING;
import static com.android.server.biometrics.BiometricServiceStateProto.MULTI_SENSOR_STATE_SWITCHING;
import static com.android.server.biometrics.BiometricServiceStateProto.MULTI_SENSOR_STATE_UNKNOWN;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

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

    /** Defined in biometrics.proto */
    @IntDef({
            MULTI_SENSOR_STATE_UNKNOWN,
            MULTI_SENSOR_STATE_FACE_SCANNING,
            MULTI_SENSOR_STATE_FP_SCANNING})
    @Retention(RetentionPolicy.SOURCE)
    @interface MultiSensorState {}

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
    private final IBiometricSysuiReceiver mSysuiReceiver;
    private final KeyStore mKeyStore;
    private final Random mRandom;
    private final ClientDeathReceiver mClientDeathReceiver;
    final PreAuthInfo mPreAuthInfo;

    // The following variables are passed to authenticateInternal, which initiates the
    // appropriate <Biometric>Services.
    @VisibleForTesting final IBinder mToken;
    // Info to be shown on BiometricDialog when all cookies are returned.
    @VisibleForTesting final PromptInfo mPromptInfo;
    private final long mOperationId;
    private final int mUserId;
    private final IBiometricSensorReceiver mSensorReceiver;
    // Original receiver from BiometricPrompt.
    private final IBiometricServiceReceiver mClientReceiver;
    private final String mOpPackageName;
    private final boolean mDebugEnabled;
    private final List<FingerprintSensorPropertiesInternal> mFingerprintSensorProperties;

    // The current state, which can be either idle, called, or started
    private @SessionState int mState = STATE_AUTH_IDLE;
    private @BiometricMultiSensorMode int mMultiSensorMode;
    private @MultiSensorState int mMultiSensorState;
    private int[] mSensors;
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
        mOperationId = operationId;
        mUserId = userId;
        mSensorReceiver = sensorReceiver;
        mClientReceiver = clientReceiver;
        mOpPackageName = opPackageName;
        mPromptInfo = promptInfo;
        mDebugEnabled = debugEnabled;
        mFingerprintSensorProperties = fingerprintSensorProperties;

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

    private void setSensorsToStateWaitingForCookie() throws RemoteException {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            final int cookie = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            final boolean requireConfirmation = isConfirmationRequired(sensor);

            if (DEBUG) {
                Slog.v(TAG, "waiting for cooking for sensor: " + sensor.id);
            }
            sensor.goToStateWaitingForCookie(requireConfirmation, mToken, mOperationId,
                    mUserId, mSensorReceiver, mOpPackageName, cookie,
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
            mMultiSensorState = MULTI_SENSOR_STATE_UNKNOWN;

            mStatusBarService.showAuthenticationDialog(
                    mPromptInfo,
                    mSysuiReceiver,
                    mSensors /* sensorIds */,
                    true /* credentialAllowed */,
                    false /* requireConfirmation */,
                    mUserId,
                    mOpPackageName,
                    mOperationId,
                    mMultiSensorMode);
        } else if (!mPreAuthInfo.eligibleSensors.isEmpty()) {
            // Some combination of biometric or biometric|credential is requested
            setSensorsToStateWaitingForCookie();
            mState = STATE_AUTH_CALLED;
        } else {
            // No authenticators requested. This should never happen - an exception should have
            // been thrown earlier in the pipeline.
            throw new IllegalStateException("No authenticators requested");
        }
    }

    void onCookieReceived(int cookie) {
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
                    mMultiSensorState = MULTI_SENSOR_STATE_UNKNOWN;

                    mStatusBarService.showAuthenticationDialog(mPromptInfo,
                            mSysuiReceiver,
                            mSensors,
                            mPreAuthInfo.shouldShowCredential(),
                            requireConfirmation,
                            mUserId,
                            mOpPackageName,
                            mOperationId,
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
                    if (DEBUG) {
                        Slog.v(TAG, "Canceling sensor: " + sensor.id);
                    }
                    sensor.goToStateCancelling(mToken, mOpPackageName);
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
        if (DEBUG) {
            Slog.v(TAG, "onErrorReceived sensor: " + sensorId + " error: " + error);
        }

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

        mErrorEscrow = error;
        mVendorCodeEscrow = vendorCode;

        final @BiometricAuthenticator.Modality int modality = sensorIdToModality(sensorId);

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
                    mMultiSensorState = MULTI_SENSOR_STATE_UNKNOWN;
                    mSensors = new int[0];

                    mStatusBarService.showAuthenticationDialog(
                            mPromptInfo,
                            mSysuiReceiver,
                            mSensors /* sensorIds */,
                            true /* credentialAllowed */,
                            false /* requireConfirmation */,
                            mUserId,
                            mOpPackageName,
                            mOperationId,
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
                    mStatusBarService.hideAuthenticationDialog();
                    // TODO: If multiple authenticators are simultaneously running, this will
                    // need to be modified. Send the error to the client here, instead of doing
                    // a round trip to SystemUI.
                    mClientReceiver.onError(modality, error, vendorCode);
                    return true;
                } else {
                    mState = STATE_ERROR_PENDING_SYSUI;
                    if (mMultiSensorMode == BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT
                            && mMultiSensorState == MULTI_SENSOR_STATE_FACE_SCANNING) {
                        // wait for the UI to signal when modality should switch
                        Slog.d(TAG, "onErrorReceived: waiting for modality switch callback");
                        mMultiSensorState = MULTI_SENSOR_STATE_SWITCHING;
                    }
                    mStatusBarService.onBiometricError(modality, error, vendorCode);
                }
                break;
            }

            case STATE_AUTH_PAUSED: {
                // In the "try again" state, we should forward canceled errors to
                // the client and clean up. The only error we should get here is
                // ERROR_CANCELED due to another client kicking us out.
                mClientReceiver.onError(modality, error, vendorCode);
                mStatusBarService.hideAuthenticationDialog();
                return true;
            }

            case STATE_SHOWING_DEVICE_CREDENTIAL:
                Slog.d(TAG, "Biometric canceled, ignoring from state: " + mState);
                break;

            case STATE_CLIENT_DIED_CANCELLING:
                mStatusBarService.hideAuthenticationDialog();
                return true;

            default:
                Slog.e(TAG, "Unhandled error state, mState: " + mState);
                break;
        }

        return false;
    }

    void onAcquired(int sensorId, int acquiredInfo, int vendorCode) {
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
            Slog.w(TAG, "onDialogAnimatedIn, unexpected state: " + mState);
        }

        mState = STATE_AUTH_STARTED_UI_SHOWING;

        if (mMultiSensorMode == BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT) {
            mMultiSensorState = MULTI_SENSOR_STATE_FACE_SCANNING;
        } else {
            startFingerprintSensorsNow();
        }
    }

    // call anytime after onDialogAnimatedIn() to indicate it's appropriate to start the
    // fingerprint sensor (i.e. face auth has failed or is not available)
    void onStartFingerprint() {
        if (mMultiSensorMode != BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT) {
            Slog.e(TAG, "onStartFingerprint, unexpected mode: " + mMultiSensorMode);
            return;
        }

        if (mState != STATE_AUTH_STARTED
                && mState != STATE_AUTH_STARTED_UI_SHOWING
                && mState != STATE_AUTH_PAUSED
                && mState != STATE_ERROR_PENDING_SYSUI) {
            Slog.w(TAG, "onStartFingerprint, started from unexpected state: " + mState);
        }

        mMultiSensorState = MULTI_SENSOR_STATE_FP_SCANNING;
        startFingerprintSensorsNow();
    }

    // unguarded helper for the above methods only
    private void startFingerprintSensorsNow() {
        startAllPreparedFingerprintSensors();
        mState = STATE_AUTH_STARTED_UI_SHOWING;
    }

    void onTryAgainPressed() {
        if (mState != STATE_AUTH_PAUSED) {
            Slog.w(TAG, "onTryAgainPressed, state: " + mState);
        }

        try {
            setSensorsToStateWaitingForCookie();
            mState = STATE_AUTH_PAUSED_RESUMING;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException: " + e);
        }
    }

    void onAuthenticationSucceeded(int sensorId, boolean strong,
            byte[] token) {
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
            mStatusBarService.onBiometricAuthenticated();

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
    }

    void onAuthenticationRejected() {
        try {
            mStatusBarService.onBiometricError(TYPE_NONE,
                    BiometricConstants.BIOMETRIC_PAUSED_REJECTED, 0 /* vendorCode */);

            // TODO: This logic will need to be updated if BP is multi-modal
            if (hasPausableBiometric()) {
                // Pause authentication. onBiometricAuthenticated(false) causes the
                // dialog to show a "try again" button for passive modalities.
                mState = STATE_AUTH_PAUSED;
            }

            mClientReceiver.onAuthenticationFailed();
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onAuthenticationTimedOut(int sensorId, int cookie, int error, int vendorCode) {
        try {
            mStatusBarService.onBiometricError(sensorIdToModality(sensorId), error, vendorCode);
            mState = STATE_AUTH_PAUSED;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onDeviceCredentialPressed() {
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
                    mStatusBarService.hideAuthenticationDialog();
                    return true;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception: " + e);
            return true;
        }
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

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED,
                    statsModality(),
                    mUserId,
                    isCrypto(),
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    mPreAuthInfo.confirmationRequested,
                    FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED,
                    latency,
                    mDebugEnabled,
                    -1 /* sensorId */,
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
            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED,
                    statsModality(),
                    mUserId,
                    isCrypto(),
                    BiometricsProtoEnums.ACTION_AUTHENTICATE,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    error,
                    0 /* vendorCode */,
                    mDebugEnabled,
                    latency,
                    -1 /* sensorId */);
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
                mStatusBarService.hideAuthenticationDialog();
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

    boolean allCookiesReceived() {
        final int remainingCookies = mPreAuthInfo.numSensorsWaitingForCookie();
        Slog.d(TAG, "Remaining cookies: " + remainingCookies);
        return remainingCookies == 0;
    }

    private boolean hasPausableBiometric() {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensor.modality == TYPE_FACE) {
                return true;
            }
        }
        return false;
    }

    @SessionState int getState() {
        return mState;
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
            return BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT;
        }
        return BIOMETRIC_MULTI_SENSOR_DEFAULT;
    }

    @Override
    public String toString() {
        return "State: " + mState
                + ", isCrypto: " + isCrypto()
                + ", PreAuthInfo: " + mPreAuthInfo;
    }
}
