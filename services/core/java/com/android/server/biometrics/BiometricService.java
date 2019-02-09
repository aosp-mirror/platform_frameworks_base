/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_IRIS;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IActivityTaskManager;
import android.app.KeyguardManager;
import android.app.TaskStackListener;
import android.app.UserSwitchObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * System service that arbitrates the modality for BiometricPrompt to use.
 */
public class BiometricService extends SystemService {

    private static final String TAG = "BiometricService";

    private static final int[] FEATURE_ID = {
        TYPE_FINGERPRINT,
        TYPE_IRIS,
        TYPE_FACE
    };

    private final AppOpsManager mAppOps;
    private final Handler mHandler;
    private final boolean mHasFeatureFingerprint;
    private final boolean mHasFeatureIris;
    private final boolean mHasFeatureFace;
    private final SettingObserver mSettingObserver;
    private final List<EnabledOnKeyguardCallback> mEnabledOnKeyguardCallbacks;

    private IFingerprintService mFingerprintService;
    private IFaceService mFaceService;

    // Get and cache the available authenticator (manager) classes. Used since aidl doesn't support
    // polymorphism :/
    final ArrayList<Authenticator> mAuthenticators = new ArrayList<>();

    // Cache the current service that's being used. This is the service which
    // cancelAuthentication() must be forwarded to. This is just a cache, and the actual
    // check (is caller the current client) is done in the <Biometric>Service.
    // Since Settings/System (not application) is responsible for changing preference, this
    // should be safe.
    private int mCurrentModality;

    private final class Authenticator {
        int mType;
        BiometricAuthenticator mAuthenticator;

        Authenticator(int type, BiometricAuthenticator authenticator) {
            mType = type;
            mAuthenticator = authenticator;
        }

        int getType() {
            return mType;
        }

        BiometricAuthenticator getAuthenticator() {
            return mAuthenticator;
        }
    }

    private final class SettingObserver extends ContentObserver {
        private final Uri FACE_UNLOCK_KEYGUARD_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED);
        private final Uri FACE_UNLOCK_APP_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_APP_ENABLED);
        private final Uri FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION);

        private final ContentResolver mContentResolver;
        private boolean mFaceEnabledOnKeyguard;
        private boolean mFaceEnabledForApps;
        private boolean mFaceAlwaysRequireConfirmation;

        /**
         * Creates a content observer.
         *
         * @param handler The handler to run {@link #onChange} on, or null if none.
         */
        SettingObserver(Handler handler) {
            super(handler);
            mContentResolver = getContext().getContentResolver();
            updateContentObserver();
        }

        void updateContentObserver() {
            mContentResolver.unregisterContentObserver(this);
            mContentResolver.registerContentObserver(FACE_UNLOCK_KEYGUARD_ENABLED,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    UserHandle.USER_CURRENT);
            mContentResolver.registerContentObserver(FACE_UNLOCK_APP_ENABLED,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    UserHandle.USER_CURRENT);
            mContentResolver.registerContentObserver(FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    UserHandle.USER_CURRENT);

            // Update the value immediately
            onChange(true /* selfChange */, FACE_UNLOCK_KEYGUARD_ENABLED);
            onChange(true /* selfChange */, FACE_UNLOCK_APP_ENABLED);
            onChange(true /* selfChange */, FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (FACE_UNLOCK_KEYGUARD_ENABLED.equals(uri)) {
                mFaceEnabledOnKeyguard =
                        Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED,
                                1 /* default */,
                                UserHandle.USER_CURRENT) != 0;

                List<EnabledOnKeyguardCallback> callbacks = mEnabledOnKeyguardCallbacks;
                for (int i = 0; i < callbacks.size(); i++) {
                    callbacks.get(i).notify(BiometricSourceType.FACE, mFaceEnabledOnKeyguard);
                }
            } else if (FACE_UNLOCK_APP_ENABLED.equals(uri)) {
                mFaceEnabledForApps =
                        Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_APP_ENABLED,
                                1 /* default */,
                                UserHandle.USER_CURRENT) != 0;
            } else if (FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION.equals(uri)) {
                mFaceAlwaysRequireConfirmation =
                        Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
                                0 /* default */,
                                UserHandle.USER_CURRENT) != 0;
            }
        }

        boolean getFaceEnabledOnKeyguard() {
            return mFaceEnabledOnKeyguard;
        }

        boolean getFaceEnabledForApps() {
            return mFaceEnabledForApps;
        }

        boolean getFaceAlwaysRequireConfirmation() {
            return mFaceAlwaysRequireConfirmation;
        }
    }

    private final class EnabledOnKeyguardCallback implements IBinder.DeathRecipient {

        private final IBiometricEnabledOnKeyguardCallback mCallback;

        EnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
            mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(EnabledOnKeyguardCallback.this, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to linkToDeath", e);
            }
        }

        void notify(BiometricSourceType sourceType, boolean enabled) {
            try {
                mCallback.onChanged(sourceType, enabled);
            } catch (DeadObjectException e) {
                Slog.w(TAG, "Death while invoking notify", e);
                mEnabledOnKeyguardCallbacks.remove(this);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke onChanged", e);
            }
        }

        @Override
        public void binderDied() {
            Slog.e(TAG, "Enabled callback binder died");
            mEnabledOnKeyguardCallbacks.remove(this);
        }
    }

    /**
     * This is just a pass-through service that wraps Fingerprint, Iris, Face services. This service
     * should not carry any state. The reality is we need to keep a tiny amount of state so that
     * cancelAuthentication() can go to the right place.
     */
    private final class BiometricServiceWrapper extends IBiometricService.Stub {

        /**
         * Authentication either just called and we have not transitioned to the CALLED state, or
         * authentication terminated (success or error).
         */
        private static final int STATE_AUTH_IDLE = 0;
        /**
         * Authentication was called and we are waiting for the <Biometric>Services to return their
         * cookies before starting the hardware and showing the BiometricPrompt.
         */
        private static final int STATE_AUTH_CALLED = 1;
        /**
         * Authentication started, BiometricPrompt is showing and the hardware is authenticating.
         */
        private static final int STATE_AUTH_STARTED = 2;
        /**
         * Authentication is paused, waiting for the user to press "try again" button. Since the
         * try again button requires us to cancel authentication, this represents the state where
         * ERROR_CANCELED is not received yet.
         */
        private static final int STATE_AUTH_PAUSED = 3;
        /**
         * Same as above, except the ERROR_CANCELED has been received.
         */
        private static final int STATE_AUTH_PAUSED_CANCELED = 4;
        /**
         * Authentication is successful, but we're waiting for the user to press "confirm" button.
         */
        private static final int STATE_AUTH_PENDING_CONFIRM = 5;

        final class AuthSession {
            // Map of Authenticator/Cookie pairs. We expect to receive the cookies back from
            // <Biometric>Services before we can start authenticating. Pairs that have been returned
            // are moved to mModalitiesMatched.
            final HashMap<Integer, Integer> mModalitiesWaiting;
            // Pairs that have been matched.
            final HashMap<Integer, Integer> mModalitiesMatched = new HashMap<>();

            // The following variables are passed to authenticateInternal, which initiates the
            // appropriate <Biometric>Services.
            final IBinder mToken;
            final long mSessionId;
            final int mUserId;
            // Original receiver from BiometricPrompt.
            final IBiometricServiceReceiver mClientReceiver;
            final String mOpPackageName;
            // Info to be shown on BiometricDialog when all cookies are returned.
            final Bundle mBundle;
            final int mCallingUid;
            final int mCallingPid;
            final int mCallingUserId;
            // Continue authentication with the same modality/modalities after "try again" is
            // pressed
            final int mModality;
            final boolean mRequireConfirmation;

            // The current state, which can be either idle, called, or started
            private int mState = STATE_AUTH_IDLE;
            // For explicit confirmation, do not send to keystore until the user has confirmed
            // the authentication.
            byte[] mTokenEscrow;

            // Timestamp when hardware authentication occurred
            private long mAuthenticatedTimeMs;

            AuthSession(HashMap<Integer, Integer> modalities, IBinder token, long sessionId,
                    int userId, IBiometricServiceReceiver receiver, String opPackageName,
                    Bundle bundle, int callingUid, int callingPid, int callingUserId,
                    int modality, boolean requireConfirmation) {
                mModalitiesWaiting = modalities;
                mToken = token;
                mSessionId = sessionId;
                mUserId = userId;
                mClientReceiver = receiver;
                mOpPackageName = opPackageName;
                mBundle = bundle;
                mCallingUid = callingUid;
                mCallingPid = callingPid;
                mCallingUserId = callingUserId;
                mModality = modality;
                mRequireConfirmation = requireConfirmation;
            }

            boolean isCrypto() {
                return mSessionId != 0;
            }

            boolean containsCookie(int cookie) {
                if (mModalitiesWaiting != null && mModalitiesWaiting.containsValue(cookie)) {
                    return true;
                }
                if (mModalitiesMatched != null && mModalitiesMatched.containsValue(cookie)) {
                    return true;
                }
                return false;
            }
        }

        final class BiometricTaskStackListener extends TaskStackListener {
            @Override
            public void onTaskStackChanged() {
                try {
                    final List<ActivityManager.RunningTaskInfo> runningTasks =
                            mActivityTaskManager.getTasks(1);
                    if (!runningTasks.isEmpty()) {
                        final String topPackage = runningTasks.get(0).topActivity.getPackageName();
                        if (mCurrentAuthSession != null
                                && !topPackage.contentEquals(mCurrentAuthSession.mOpPackageName)) {
                            mStatusBarService.hideBiometricDialog();
                            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
                            mCurrentAuthSession.mClientReceiver.onError(
                                    BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                                    getContext().getString(
                                            com.android.internal.R.string.biometric_error_canceled)
                            );
                            mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                            mCurrentAuthSession = null;
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to get running tasks", e);
                }
            }
        }

        private final IActivityTaskManager mActivityTaskManager = getContext().getSystemService(
                ActivityTaskManager.class).getService();
        private final IStatusBarService mStatusBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        private final BiometricTaskStackListener mTaskStackListener =
                new BiometricTaskStackListener();
        private final Random mRandom = new Random();

        // TODO(b/123378871): Remove when moved.
        // When BiometricPrompt#setAllowDeviceCredentials is set to true, we need to store the
        // client (app) receiver. BiometricService internally launches CDCA which invokes
        // BiometricService to start authentication (normal path). When auth is success/rejected,
        // CDCA will use an aidl method to poke BiometricService - the result will then be forwarded
        // to this receiver.
        private IBiometricServiceReceiver mConfirmDeviceCredentialReceiver;

        // The current authentication session, null if idle/done. We need to track both the current
        // and pending sessions since errors may be sent to either.
        private AuthSession mCurrentAuthSession;
        private AuthSession mPendingAuthSession;

        // Wrap the client's receiver so we can do things with the BiometricDialog first
        private final IBiometricServiceReceiverInternal mInternalReceiver =
                new IBiometricServiceReceiverInternal.Stub() {
            @Override
            public void onAuthenticationSucceeded(boolean requireConfirmation, byte[] token)
                    throws RemoteException {
                try {
                    // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
                    // after user dismissed/canceled dialog).
                    if (mCurrentAuthSession == null) {
                        Slog.e(TAG, "onAuthenticationSucceeded(): Auth session is null");
                        return;
                    }

                    if (!requireConfirmation) {
                        mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
                        KeyStore.getInstance().addAuthToken(token);
                        mCurrentAuthSession.mClientReceiver.onAuthenticationSucceeded();
                        mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                        mCurrentAuthSession = null;
                    } else {
                        mCurrentAuthSession.mAuthenticatedTimeMs = System.currentTimeMillis();
                        // Store the auth token and submit it to keystore after the confirmation
                        // button has been pressed.
                        mCurrentAuthSession.mTokenEscrow = token;
                        mCurrentAuthSession.mState = STATE_AUTH_PENDING_CONFIRM;
                    }

                    // Notify SysUI that the biometric has been authenticated. SysUI already knows
                    // the implicit/explicit state and will react accordingly.
                    mStatusBarService.onBiometricAuthenticated(true);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }

            @Override
            public void onAuthenticationFailed(int cookie, boolean requireConfirmation)
                    throws RemoteException {
                try {
                    // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
                    // after user dismissed/canceled dialog).
                    if (mCurrentAuthSession == null) {
                        Slog.e(TAG, "onAuthenticationFailed(): Auth session is null");
                        return;
                    }

                    mStatusBarService.onBiometricAuthenticated(false);

                    // TODO: This logic will need to be updated if BP is multi-modal
                    if ((mCurrentAuthSession.mModality & TYPE_FACE) != 0) {
                        // Pause authentication. onBiometricAuthenticated(false) causes the
                        // dialog to show a "try again" button for passive modalities.
                        mCurrentAuthSession.mState = STATE_AUTH_PAUSED;
                        // Cancel authentication. Skip the token/package check since we are
                        // cancelling from system server. The interface is permission protected so
                        // this is fine.
                        cancelInternal(null /* token */, null /* package */,
                                false /* fromClient */);
                    }

                    mCurrentAuthSession.mClientReceiver.onAuthenticationFailed();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }

            @Override
            public void onError(int cookie, int error, String message) throws RemoteException {
                Slog.d(TAG, "Error: " + error + " cookie: " + cookie);
                // Errors can either be from the current auth session or the pending auth session.
                // The pending auth session may receive errors such as ERROR_LOCKOUT before
                // it becomes the current auth session. Similarly, the current auth session may
                // receive errors such as ERROR_CANCELED while the pending auth session is preparing
                // to be started. Thus we must match error messages with their cookies to be sure
                // of their intended receivers.
                try {
                    if (mCurrentAuthSession != null && mCurrentAuthSession.containsCookie(cookie)) {
                        if (mCurrentAuthSession.mState == STATE_AUTH_STARTED) {
                            mStatusBarService.onBiometricError(message);
                            if (error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
                                    mActivityTaskManager.unregisterTaskStackListener(
                                            mTaskStackListener);
                                    mCurrentAuthSession.mClientReceiver.onError(error, message);
                                    mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                                    mCurrentAuthSession = null;
                                    mStatusBarService.hideBiometricDialog();
                            } else {
                                // Send errors after the dialog is dismissed.
                                mHandler.postDelayed(() -> {
                                    try {
                                        if (mCurrentAuthSession != null) {
                                            mActivityTaskManager.unregisterTaskStackListener(
                                                    mTaskStackListener);
                                            mCurrentAuthSession.mClientReceiver.onError(error,
                                                    message);
                                            mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                                            mCurrentAuthSession = null;
                                        }
                                    } catch (RemoteException e) {
                                        Slog.e(TAG, "Remote exception", e);
                                    }
                                }, BiometricPrompt.HIDE_DIALOG_DELAY);
                            }
                        } else if (mCurrentAuthSession.mState == STATE_AUTH_PAUSED
                                || mCurrentAuthSession.mState == STATE_AUTH_PAUSED_CANCELED) {
                            if (mCurrentAuthSession.mState == STATE_AUTH_PAUSED
                                    && error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
                                // Skip the first ERROR_CANCELED message when this happens, since
                                // "try again" requires us to cancel authentication but keep
                                // the prompt showing.
                                mCurrentAuthSession.mState = STATE_AUTH_PAUSED_CANCELED;
                            } else {
                                // In the "try again" state, we should forward canceled errors to
                                // the client and and clean up.
                                mCurrentAuthSession.mClientReceiver.onError(error, message);
                                mStatusBarService.onBiometricError(message);
                                mActivityTaskManager.unregisterTaskStackListener(
                                        mTaskStackListener);
                                mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                                mCurrentAuthSession = null;
                            }
                        } else {
                            Slog.e(TAG, "Impossible session error state: "
                                    + mCurrentAuthSession.mState);
                        }
                    } else if (mPendingAuthSession != null
                            && mPendingAuthSession.containsCookie(cookie)) {
                        if (mPendingAuthSession.mState == STATE_AUTH_CALLED) {
                            mPendingAuthSession.mClientReceiver.onError(error, message);
                            mPendingAuthSession.mState = STATE_AUTH_IDLE;
                            mPendingAuthSession = null;
                        } else {
                            Slog.e(TAG, "Impossible pending session error state: "
                                    + mPendingAuthSession.mState);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }

            @Override
            public void onAcquired(int acquiredInfo, String message) throws RemoteException {
                // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
                // after user dismissed/canceled dialog).
                if (mCurrentAuthSession == null) {
                    Slog.e(TAG, "onAcquired(): Auth session is null");
                    return;
                }

                if (acquiredInfo != BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
                    try {
                        mStatusBarService.onBiometricHelp(message);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote exception", e);
                    }
                }
            }

            @Override
            public void onDialogDismissed(int reason) throws RemoteException {
                if (mCurrentAuthSession == null) {
                    Slog.e(TAG, "onDialogDismissed: " + reason + ", auth session null");
                    return;
                }

                logDialogDismissed(reason);

                if (reason != BiometricPrompt.DISMISSED_REASON_POSITIVE) {
                    // Positive button is used by passive modalities as a "confirm" button,
                    // do not send to client
                    mCurrentAuthSession.mClientReceiver.onDialogDismissed(reason);
                    // Cancel authentication. Skip the token/package check since we are cancelling
                    // from system server. The interface is permission protected so this is fine.
                    cancelInternal(null /* token */, null /* package */, false /* fromClient */);
                }
                if (reason == BiometricPrompt.DISMISSED_REASON_USER_CANCEL) {
                    mCurrentAuthSession.mClientReceiver.onError(
                            BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED,
                            getContext().getString(
                                    com.android.internal.R.string.biometric_error_user_canceled));
                } else if (reason == BiometricPrompt.DISMISSED_REASON_POSITIVE) {
                    // Have the service send the token to KeyStore, and send onAuthenticated
                    // to the application
                    KeyStore.getInstance().addAuthToken(mCurrentAuthSession.mTokenEscrow);
                    mCurrentAuthSession.mClientReceiver.onAuthenticationSucceeded();
                }
                mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
                mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                mCurrentAuthSession = null;
            }

            @Override
            public void onTryAgainPressed() {
                Slog.d(TAG, "onTryAgainPressed");
                // No need to check permission, since it can only be invoked by SystemUI
                // (or system server itself).
                mHandler.post(() -> {
                    authenticateInternal(mCurrentAuthSession.mToken,
                            mCurrentAuthSession.mSessionId,
                            mCurrentAuthSession.mUserId,
                            mCurrentAuthSession.mClientReceiver,
                            mCurrentAuthSession.mOpPackageName,
                            mCurrentAuthSession.mBundle,
                            mCurrentAuthSession.mCallingUid,
                            mCurrentAuthSession.mCallingPid,
                            mCurrentAuthSession.mCallingUserId,
                            mCurrentAuthSession.mModality);
                });
            }

            private void logDialogDismissed(int reason) {
                if (reason == BiometricPrompt.DISMISSED_REASON_POSITIVE) {
                    // Explicit auth, authentication confirmed.
                    // Latency in this case is authenticated -> confirmed. <Biometric>Service
                    // should have the first half (first acquired -> authenticated).
                    final long latency = System.currentTimeMillis()
                            - mCurrentAuthSession.mAuthenticatedTimeMs;

                    if (LoggableMonitor.DEBUG) {
                        Slog.v(LoggableMonitor.TAG, "Confirmed! Modality: " + statsModality()
                                + ", User: " + mCurrentAuthSession.mUserId
                                + ", IsCrypto: " + mCurrentAuthSession.isCrypto()
                                + ", Client: " + BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT
                                + ", RequireConfirmation: "
                                    + mCurrentAuthSession.mRequireConfirmation
                                + ", State: " + StatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED
                                + ", Latency: " + latency);
                    }

                    StatsLog.write(StatsLog.BIOMETRIC_AUTHENTICATED,
                            statsModality(),
                            mCurrentAuthSession.mUserId,
                            mCurrentAuthSession.isCrypto(),
                            BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                            mCurrentAuthSession.mRequireConfirmation,
                            StatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED,
                            latency);
                } else {
                    int error = reason == BiometricPrompt.DISMISSED_REASON_NEGATIVE
                            ? BiometricConstants.BIOMETRIC_ERROR_NEGATIVE_BUTTON
                            : reason == BiometricPrompt.DISMISSED_REASON_USER_CANCEL
                                    ? BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED
                                    : 0;
                    if (LoggableMonitor.DEBUG) {
                        Slog.v(LoggableMonitor.TAG, "Dismissed! Modality: " + statsModality()
                                + ", User: " + mCurrentAuthSession.mUserId
                                + ", IsCrypto: " + mCurrentAuthSession.isCrypto()
                                + ", Action: " + BiometricsProtoEnums.ACTION_AUTHENTICATE
                                + ", Client: " + BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT
                                + ", Error: " + error);
                    }
                    // Auth canceled
                    StatsLog.write(StatsLog.BIOMETRIC_ERROR_OCCURRED,
                            statsModality(),
                            mCurrentAuthSession.mUserId,
                            mCurrentAuthSession.isCrypto(),
                            BiometricsProtoEnums.ACTION_AUTHENTICATE,
                            BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                            error,
                            0 /* vendorCode */);
                }
            }

            private int statsModality() {
                int modality = 0;
                if (mCurrentAuthSession == null) {
                    return BiometricsProtoEnums.MODALITY_UNKNOWN;
                }
                if ((mCurrentAuthSession.mModality & BiometricAuthenticator.TYPE_FINGERPRINT)
                        != 0) {
                    modality |= BiometricsProtoEnums.MODALITY_FINGERPRINT;
                }
                if ((mCurrentAuthSession.mModality & BiometricAuthenticator.TYPE_IRIS) != 0) {
                    modality |= BiometricsProtoEnums.MODALITY_IRIS;
                }
                if ((mCurrentAuthSession.mModality & BiometricAuthenticator.TYPE_FACE) != 0) {
                    modality |= BiometricsProtoEnums.MODALITY_FACE;
                }
                return modality;
            }
        };

        @Override // Binder call
        public void onReadyForAuthentication(int cookie, boolean requireConfirmation, int userId) {
            checkInternalPermission();

            Iterator it = mPendingAuthSession.mModalitiesWaiting.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Integer> pair = (Map.Entry) it.next();
                if (pair.getValue() == cookie) {
                    mPendingAuthSession.mModalitiesMatched.put(pair.getKey(), pair.getValue());
                    mPendingAuthSession.mModalitiesWaiting.remove(pair.getKey());
                    Slog.d(TAG, "Matched cookie: " + cookie + ", "
                            + mPendingAuthSession.mModalitiesWaiting.size() + " remaining");
                    break;
                }
            }

            if (mPendingAuthSession.mModalitiesWaiting.isEmpty()) {
                final boolean continuing = mCurrentAuthSession != null &&
                        (mCurrentAuthSession.mState == STATE_AUTH_PAUSED
                                || mCurrentAuthSession.mState == STATE_AUTH_PAUSED_CANCELED);

                mCurrentAuthSession = mPendingAuthSession;
                mPendingAuthSession = null;

                mCurrentAuthSession.mState = STATE_AUTH_STARTED;
                try {
                    int modality = TYPE_NONE;
                    it = mCurrentAuthSession.mModalitiesMatched.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, Integer> pair = (Map.Entry) it.next();
                        if (pair.getKey() == TYPE_FINGERPRINT) {
                            mFingerprintService.startPreparedClient(pair.getValue());
                        } else if (pair.getKey() == TYPE_IRIS) {
                            Slog.e(TAG, "Iris unsupported");
                        } else if (pair.getKey() == TYPE_FACE) {
                            mFaceService.startPreparedClient(pair.getValue());
                        } else {
                            Slog.e(TAG, "Unknown modality: " + pair.getKey());
                        }
                        modality |= pair.getKey();
                    }

                    if (!continuing) {
                        mStatusBarService.showBiometricDialog(mCurrentAuthSession.mBundle,
                                mInternalReceiver, modality, requireConfirmation, userId);
                        mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        }

        @Override // Binder call
        public void authenticate(IBinder token, long sessionId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle)
                throws RemoteException {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            // In the BiometricServiceBase, check do the AppOps and foreground check.
            if (userId == callingUserId) {
                // Check the USE_BIOMETRIC permission here.
                checkPermission();
            } else {
                // Only allow internal clients to authenticate with a different userId
                Slog.w(TAG, "User " + callingUserId + " is requesting authentication of userid: "
                        + userId);
                checkInternalPermission();
            }

            if (token == null || receiver == null || opPackageName == null || bundle == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return;
            }

            // Check the usage of this in system server. Need to remove this check if it becomes
            // a public API.
            final boolean useDefaultTitle =
                    bundle.getBoolean(BiometricPrompt.KEY_USE_DEFAULT_TITLE, false);
            if (useDefaultTitle) {
                checkInternalPermission();
                // Set the default title if necessary
                try {
                    if (useDefaultTitle) {
                        final List<ActivityManager.RunningAppProcessInfo> procs =
                                ActivityManager.getService().getRunningAppProcesses();
                        for (int i = 0; i < procs.size(); i++) {
                            final ActivityManager.RunningAppProcessInfo info = procs.get(i);
                            if (info.uid == callingUid
                                    && info.importance == IMPORTANCE_FOREGROUND) {
                                PackageManager pm = getContext().getPackageManager();
                                final CharSequence label = pm.getApplicationLabel(
                                        pm.getApplicationInfo(info.processName,
                                                PackageManager.GET_META_DATA));
                                final String title = getContext()
                                        .getString(R.string.biometric_dialog_default_title, label);
                                if (TextUtils.isEmpty(
                                        bundle.getCharSequence(BiometricPrompt.KEY_TITLE))) {
                                    bundle.putCharSequence(BiometricPrompt.KEY_TITLE, title);
                                }
                                break;
                            }
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Name not found", e);
                }
            }

            // Launch CDC instead if necessary. CDC will return results through an AIDL call, since
            // we can't get activity results. Store the receiver somewhere so we can forward the
            // result back to the client.
            // TODO(b/123378871): Remove when moved.
            if (bundle.getBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL)) {
                mHandler.post(() -> {
                    final KeyguardManager kgm = getContext().getSystemService(
                            KeyguardManager.class);
                    if (!kgm.isDeviceSecure()) {
                        try {
                            receiver.onError(BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
                                    getContext().getString(
                                            R.string.biometric_error_device_not_secured));
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Remote exception", e);
                        }
                        return;
                    }
                    mConfirmDeviceCredentialReceiver = receiver;
                    // Use this so we don't need to duplicate logic..
                    final Intent intent = kgm.createConfirmDeviceCredentialIntent(null /* title */,
                            null /* description */);
                    // Then give it the bundle to do magic behavior..
                    intent.putExtra(KeyguardManager.EXTRA_BIOMETRIC_PROMPT_BUNDLE, bundle);
                    getContext().startActivityAsUser(intent, UserHandle.CURRENT);
                });
                return;
            }

            mHandler.post(() -> {
                final Pair<Integer, Integer> result = checkAndGetBiometricModality(userId);
                final int modality = result.first;
                final int error = result.second;

                // Check for errors, notify callback, and return
                if (error != BiometricConstants.BIOMETRIC_SUCCESS) {
                    try {
                        final String hardwareUnavailable =
                                getContext().getString(R.string.biometric_error_hw_unavailable);
                        switch (error) {
                            case BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                                receiver.onError(error, hardwareUnavailable);
                                break;
                            case BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                                receiver.onError(error, hardwareUnavailable);
                                break;
                            case BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS:
                                receiver.onError(error,
                                        getErrorString(modality, error, 0 /* vendorCode */));
                                break;
                            default:
                                Slog.e(TAG, "Unhandled error");
                                break;
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to send error", e);
                    }
                    return;
                }

                mCurrentModality = modality;

                // Start preparing for authentication. Authentication starts when
                // all modalities requested have invoked onReadyForAuthentication.
                authenticateInternal(token, sessionId, userId, receiver, opPackageName, bundle,
                        callingUid, callingPid, callingUserId, modality);
            });
        }

        @Override // Binder call
        public void onConfirmDeviceCredentialSuccess() {
            checkInternalPermission();
            if (mConfirmDeviceCredentialReceiver == null) {
                Slog.w(TAG, "onCDCASuccess null!");
                return;
            }
            try {
                mConfirmDeviceCredentialReceiver.onAuthenticationSucceeded();
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException", e);
            }
            mConfirmDeviceCredentialReceiver = null;
        }

        @Override // Binder call
        public void onConfirmDeviceCredentialError(int error, String message) {
            checkInternalPermission();
            if (mConfirmDeviceCredentialReceiver == null) {
                Slog.w(TAG, "onCDCAError null! Error: " + error + " " + message);
                return;
            }
            try {
                mConfirmDeviceCredentialReceiver.onError(error, message);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException", e);
            }
            mConfirmDeviceCredentialReceiver = null;
        }

        /**
         * authenticate() (above) which is called from BiometricPrompt determines which
         * modality/modalities to start authenticating with. authenticateInternal() should only be
         * used for:
         * 1) Preparing <Biometric>Services for authentication when BiometricPrompt#authenticate is,
         *    invoked, shortly after which BiometricPrompt is shown and authentication starts
         * 2) Preparing <Biometric>Services for authentication when BiometricPrompt is already shown
         *    and the user has pressed "try again"
         */
        private void authenticateInternal(IBinder token, long sessionId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle,
                int callingUid, int callingPid, int callingUserId, int modality) {
            try {
                boolean requireConfirmation = bundle.getBoolean(
                        BiometricPrompt.KEY_REQUIRE_CONFIRMATION, true /* default */);
                if ((modality & TYPE_FACE) != 0) {
                    // Check if the user has forced confirmation to be required in Settings.
                    requireConfirmation = requireConfirmation
                            || mSettingObserver.getFaceAlwaysRequireConfirmation();
                }
                // Generate random cookies to pass to the services that should prepare to start
                // authenticating. Store the cookie here and wait for all services to "ack"
                // with the cookie. Once all cookies are received, we can show the prompt
                // and let the services start authenticating. The cookie should be non-zero.
                final int cookie = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
                Slog.d(TAG, "Creating auth session. Modality: " + modality
                        + ", cookie: " + cookie);
                final HashMap<Integer, Integer> authenticators = new HashMap<>();
                authenticators.put(modality, cookie);
                mPendingAuthSession = new AuthSession(authenticators, token, sessionId, userId,
                        receiver, opPackageName, bundle, callingUid, callingPid, callingUserId,
                        modality, requireConfirmation);
                mPendingAuthSession.mState = STATE_AUTH_CALLED;
                // No polymorphism :(
                if ((modality & TYPE_FINGERPRINT) != 0) {
                    mFingerprintService.prepareForAuthentication(token, sessionId, userId,
                            mInternalReceiver, opPackageName, cookie,
                            callingUid, callingPid, callingUserId);
                }
                if ((modality & TYPE_IRIS) != 0) {
                    Slog.w(TAG, "Iris unsupported");
                }
                if ((modality & TYPE_FACE) != 0) {
                    mFaceService.prepareForAuthentication(requireConfirmation,
                            token, sessionId, userId, mInternalReceiver, opPackageName,
                            cookie, callingUid, callingPid, callingUserId);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to start authentication", e);
            }
        }

        @Override // Binder call
        public void cancelAuthentication(IBinder token, String opPackageName)
                throws RemoteException {
            checkPermission();
            if (token == null || opPackageName == null) {
                Slog.e(TAG, "Unable to cancel, one or more null arguments");
                return;
            }

            // We need to check the current authenticators state. If we're pending confirm
            // or idle, we need to dismiss the dialog and send an ERROR_CANCELED to the client,
            // since we won't be getting an onError from the driver.
            if (mCurrentAuthSession != null && mCurrentAuthSession.mState != STATE_AUTH_STARTED) {
                mHandler.post(() -> {
                    try {
                        // Send error to client
                        mCurrentAuthSession.mClientReceiver.onError(
                                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                                getContext().getString(
                                        com.android.internal.R.string.biometric_error_user_canceled)
                        );

                        mCurrentAuthSession.mState = STATE_AUTH_IDLE;
                        mCurrentAuthSession = null;
                        mStatusBarService.hideBiometricDialog();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote exception", e);
                    }
                });
            } else {
                cancelInternal(token, opPackageName, true /* fromClient */);
            }
        }

        @Override // Binder call
        public int canAuthenticate(String opPackageName) {
            checkPermission();
            checkAppOp(opPackageName, Binder.getCallingUid());

            final int userId = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            int error;
            try {
                final Pair<Integer, Integer> result = checkAndGetBiometricModality(userId);
                error = result.second;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return error;
        }

        @Override // Binder call
        public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback)
                throws RemoteException {
            checkInternalPermission();
            mEnabledOnKeyguardCallbacks.add(new EnabledOnKeyguardCallback(callback));
            try {
                callback.onChanged(BiometricSourceType.FACE,
                        mSettingObserver.getFaceEnabledOnKeyguard());
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception", e);
            }
        }

        @Override // Binder call
        public void setActiveUser(int userId) {
            checkInternalPermission();
            final long ident = Binder.clearCallingIdentity();
            try {
                for (int i = 0; i < mAuthenticators.size(); i++) {
                    mAuthenticators.get(i).getAuthenticator().setActiveUser(userId);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void resetTimeout(byte[] token) {
            checkInternalPermission();
            final long ident = Binder.clearCallingIdentity();
            try {
                if (mFingerprintService != null) {
                    mFingerprintService.resetTimeout(token);
                }
                if (mFaceService != null) {
                    mFaceService.resetTimeout(token);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        void cancelInternal(IBinder token, String opPackageName, boolean fromClient) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();
            mHandler.post(() -> {
                try {
                    // TODO: For multiple modalities, send a single ERROR_CANCELED only when all
                    // drivers have canceled authentication.
                    if ((mCurrentModality & TYPE_FINGERPRINT) != 0) {
                        mFingerprintService.cancelAuthenticationFromService(token, opPackageName,
                                callingUid, callingPid, callingUserId, fromClient);
                    }
                    if ((mCurrentModality & TYPE_IRIS) != 0) {
                        Slog.w(TAG, "Iris unsupported");
                    }
                    if ((mCurrentModality & TYPE_FACE) != 0) {
                        mFaceService.cancelAuthenticationFromService(token, opPackageName,
                                callingUid, callingPid, callingUserId, fromClient);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to cancel authentication");
                }
            });
        }
    }

    private void checkAppOp(String opPackageName, int callingUid) {
        if (mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, callingUid,
                opPackageName) != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; permission denied");
            throw new SecurityException("Permission denied");
        }
    }

    private void checkInternalPermission() {
        getContext().enforceCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL,
                "Must have USE_BIOMETRIC_INTERNAL permission");
    }

    private void checkPermission() {
        if (getContext().checkCallingPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            getContext().enforceCallingPermission(USE_BIOMETRIC,
                    "Must have USE_BIOMETRIC permission");
        }
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public BiometricService(Context context) {
        super(context);

        mAppOps = context.getSystemService(AppOpsManager.class);
        mHandler = new Handler(Looper.getMainLooper());
        mEnabledOnKeyguardCallbacks = new ArrayList<>();
        mSettingObserver = new SettingObserver(mHandler);

        final PackageManager pm = context.getPackageManager();
        mHasFeatureFingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        mHasFeatureIris = pm.hasSystemFeature(PackageManager.FEATURE_IRIS);
        mHasFeatureFace = pm.hasSystemFeature(PackageManager.FEATURE_FACE);

        try {
            ActivityManager.getService().registerUserSwitchObserver(
                    new UserSwitchObserver() {
                        @Override
                        public void onUserSwitchComplete(int newUserId) {
                            mSettingObserver.updateContentObserver();
                        }
                    }, BiometricService.class.getName()
            );
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register user switch observer", e);
        }
    }

    @Override
    public void onStart() {
        // TODO: maybe get these on-demand
        if (mHasFeatureFingerprint) {
            mFingerprintService = IFingerprintService.Stub.asInterface(
                    ServiceManager.getService(Context.FINGERPRINT_SERVICE));
        }
        if (mHasFeatureFace) {
            mFaceService = IFaceService.Stub.asInterface(
                    ServiceManager.getService(Context.FACE_SERVICE));
        }

        // Cache the authenticators
        for (int i = 0; i < FEATURE_ID.length; i++) {
            if (hasFeature(FEATURE_ID[i])) {
                Authenticator authenticator =
                        new Authenticator(FEATURE_ID[i], getAuthenticator(FEATURE_ID[i]));
                mAuthenticators.add(authenticator);
            }
        }

        publishBinderService(Context.BIOMETRIC_SERVICE, new BiometricServiceWrapper());
    }

    /**
     * Checks if there are any available biometrics, and returns the modality. This method also
     * returns errors through the callback (no biometric feature, hardware not detected, no
     * templates enrolled, etc). This service must not start authentication if errors are sent.
     *
     * @Returns A pair [Modality, Error] with Modality being one of
     * {@link BiometricAuthenticator#TYPE_NONE},
     * {@link BiometricAuthenticator#TYPE_FINGERPRINT},
     * {@link BiometricAuthenticator#TYPE_IRIS},
     * {@link BiometricAuthenticator#TYPE_FACE}
     * and the error containing one of the {@link BiometricConstants} errors.
     */
    private Pair<Integer, Integer> checkAndGetBiometricModality(int userId) {
        int modality = TYPE_NONE;

        // No biometric features, send error
        if (mAuthenticators.isEmpty()) {
            return new Pair<>(TYPE_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT);
        }

        // Assuming that authenticators are listed in priority-order, the rest of this function
        // will go through and find the first authenticator that's available, enrolled, and enabled.
        // The tricky part is returning the correct error. Error strings that are modality-specific
        // should also respect the priority-order.

        // Find first authenticator that's detected, enrolled, and enabled.
        boolean isHardwareDetected = false;
        boolean hasTemplatesEnrolled = false;
        boolean enabledForApps = false;

        int firstHwAvailable = TYPE_NONE;
        for (int i = 0; i < mAuthenticators.size(); i++) {
            modality = mAuthenticators.get(i).getType();
            BiometricAuthenticator authenticator = mAuthenticators.get(i).getAuthenticator();
            if (authenticator.isHardwareDetected()) {
                isHardwareDetected = true;
                if (firstHwAvailable == TYPE_NONE) {
                    // Store the first one since we want to return the error in correct priority
                    // order.
                    firstHwAvailable = modality;
                }
                if (authenticator.hasEnrolledTemplates(userId)) {
                    hasTemplatesEnrolled = true;
                    if (isEnabledForApp(modality)) {
                        // TODO(b/110907543): When face settings (and other settings) have both a
                        // user toggle as well as a work profile settings page, this needs to be
                        // updated to reflect the correct setting.
                        enabledForApps = true;
                        break;
                    }
                }
            }
        }

        // Check error conditions
        if (!isHardwareDetected) {
            return new Pair<>(TYPE_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE);
        } else if (!hasTemplatesEnrolled) {
            // Return the modality here so the correct error string can be sent. This error is
            // preferred over !enabledForApps
            return new Pair<>(firstHwAvailable, BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS);
        } else if (!enabledForApps) {
            return new Pair<>(TYPE_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE);
        }

        return new Pair<>(modality, BiometricConstants.BIOMETRIC_SUCCESS);
    }

    private boolean isEnabledForApp(int modality) {
        switch(modality) {
            case TYPE_FINGERPRINT:
                return true;
            case TYPE_IRIS:
                return true;
            case TYPE_FACE:
                return mSettingObserver.getFaceEnabledForApps();
            default:
                Slog.w(TAG, "Unsupported modality: " + modality);
                return false;
        }
    }

    private String getErrorString(int type, int error, int vendorCode) {
        switch (type) {
            case TYPE_FINGERPRINT:
                return FingerprintManager.getErrorString(getContext(), error, vendorCode);
            case TYPE_IRIS:
                Slog.w(TAG, "Modality not supported");
                return null; // not supported
            case TYPE_FACE:
                return FaceManager.getErrorString(getContext(), error, vendorCode);
            default:
                Slog.w(TAG, "Unable to get error string for modality: " + type);
                return null;
        }
    }

    private BiometricAuthenticator getAuthenticator(int type) {
        switch (type) {
            case TYPE_FINGERPRINT:
                return (FingerprintManager)
                        getContext().getSystemService(Context.FINGERPRINT_SERVICE);
            case TYPE_IRIS:
                return null;
            case TYPE_FACE:
                return (FaceManager)
                        getContext().getSystemService(Context.FACE_SERVICE);
            default:
                return null;
        }
    }

    private boolean hasFeature(int type) {
        switch (type) {
            case TYPE_FINGERPRINT:
                return mHasFeatureFingerprint;
            case TYPE_IRIS:
                return mHasFeatureIris;
            case TYPE_FACE:
                return mHasFeatureFace;
            default:
                return false;
        }
    }
}
