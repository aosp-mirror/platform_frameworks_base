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

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_IRIS;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.UserSwitchObserver;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FrameworkStatsLog;
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

    static final String TAG = "BiometricService";
    private static final boolean DEBUG = true;

    private static final int BIOMETRIC_NO_HARDWARE = 0;
    private static final int BIOMETRIC_OK = 1;
    private static final int BIOMETRIC_DISABLED_BY_DEVICE_POLICY = 2;
    private static final int BIOMETRIC_INSUFFICIENT_STRENGTH = 3;
    private static final int BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE = 4;
    private static final int BIOMETRIC_HARDWARE_NOT_DETECTED = 5;
    private static final int BIOMETRIC_NOT_ENROLLED = 6;
    private static final int BIOMETRIC_NOT_ENABLED_FOR_APPS = 7;

    @IntDef({BIOMETRIC_NO_HARDWARE,
            BIOMETRIC_OK,
            BIOMETRIC_DISABLED_BY_DEVICE_POLICY,
            BIOMETRIC_INSUFFICIENT_STRENGTH,
            BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE,
            BIOMETRIC_HARDWARE_NOT_DETECTED,
            BIOMETRIC_NOT_ENROLLED,
            BIOMETRIC_NOT_ENABLED_FOR_APPS})
    @interface BiometricStatus {}

    private static final int MSG_ON_AUTHENTICATION_SUCCEEDED = 2;
    private static final int MSG_ON_AUTHENTICATION_REJECTED = 3;
    private static final int MSG_ON_ERROR = 4;
    private static final int MSG_ON_ACQUIRED = 5;
    private static final int MSG_ON_DISMISSED = 6;
    private static final int MSG_ON_TRY_AGAIN_PRESSED = 7;
    private static final int MSG_ON_READY_FOR_AUTHENTICATION = 8;
    private static final int MSG_AUTHENTICATE = 9;
    private static final int MSG_CANCEL_AUTHENTICATION = 10;
    private static final int MSG_ON_AUTHENTICATION_TIMED_OUT = 11;
    private static final int MSG_ON_DEVICE_CREDENTIAL_PRESSED = 12;

    /**
     * Authentication either just called and we have not transitioned to the CALLED state, or
     * authentication terminated (success or error).
     */
    static final int STATE_AUTH_IDLE = 0;
    /**
     * Authentication was called and we are waiting for the <Biometric>Services to return their
     * cookies before starting the hardware and showing the BiometricPrompt.
     */
    static final int STATE_AUTH_CALLED = 1;
    /**
     * Authentication started, BiometricPrompt is showing and the hardware is authenticating.
     */
    static final int STATE_AUTH_STARTED = 2;
    /**
     * Authentication is paused, waiting for the user to press "try again" button. Only
     * passive modalities such as Face or Iris should have this state. Note that for passive
     * modalities, the HAL enters the idle state after onAuthenticated(false) which differs from
     * fingerprint.
     */
    static final int STATE_AUTH_PAUSED = 3;
    /**
     * Authentication is successful, but we're waiting for the user to press "confirm" button.
     */
    static final int STATE_AUTH_PENDING_CONFIRM = 5;
    /**
     * Biometric authenticated, waiting for SysUI to finish animation
     */
    static final int STATE_AUTHENTICATED_PENDING_SYSUI = 6;
    /**
     * Biometric error, waiting for SysUI to finish animation
     */
    static final int STATE_ERROR_PENDING_SYSUI = 7;
    /**
     * Device credential in AuthController is showing
     */
    static final int STATE_SHOWING_DEVICE_CREDENTIAL = 8;

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
        int mState = STATE_AUTH_IDLE;
        // For explicit confirmation, do not send to keystore until the user has confirmed
        // the authentication.
        byte[] mTokenEscrow;
        // Waiting for SystemUI to complete animation
        int mErrorEscrow;
        int mVendorCodeEscrow;

        // Timestamp when authentication started
        private long mStartTimeMs;
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

        boolean isAllowDeviceCredential() {
            return Utils.isCredentialRequested(mBundle);
        }
    }

    private final Injector mInjector;
    private final DevicePolicyManager mDevicePolicyManager;
    @VisibleForTesting
    final IBiometricService.Stub mImpl;
    @VisibleForTesting
    final SettingObserver mSettingObserver;
    private final List<EnabledOnKeyguardCallback> mEnabledOnKeyguardCallbacks;
    private final Random mRandom = new Random();

    @VisibleForTesting
    IStatusBarService mStatusBarService;
    @VisibleForTesting
    KeyStore mKeyStore;
    @VisibleForTesting
    ITrustManager mTrustManager;

    // Get and cache the available authenticator (manager) classes. Used since aidl doesn't support
    // polymorphism :/
    final ArrayList<AuthenticatorWrapper> mAuthenticators = new ArrayList<>();

    BiometricStrengthController mBiometricStrengthController;

    // The current authentication session, null if idle/done. We need to track both the current
    // and pending sessions since errors may be sent to either.
    @VisibleForTesting
    AuthSession mCurrentAuthSession;
    @VisibleForTesting
    AuthSession mPendingAuthSession;

    @VisibleForTesting
    final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_AUTHENTICATION_SUCCEEDED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleAuthenticationSucceeded(
                            (boolean) args.arg1 /* requireConfirmation */,
                            (byte[]) args.arg2 /* token */,
                            (boolean) args.arg3 /* isStrongBiometric */);
                    args.recycle();
                    break;
                }

                case MSG_ON_AUTHENTICATION_REJECTED: {
                    handleAuthenticationRejected();
                    break;
                }

                case MSG_ON_ERROR: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleOnError(
                            args.argi1 /* cookie */,
                            args.argi2 /* modality */,
                            args.argi3 /* error */,
                            args.argi4 /* vendorCode */);
                    args.recycle();
                    break;
                }

                case MSG_ON_ACQUIRED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleOnAcquired(
                            args.argi1 /* acquiredInfo */,
                            (String) args.arg1 /* message */);
                    args.recycle();
                    break;
                }

                case MSG_ON_DISMISSED: {
                    handleOnDismissed(msg.arg1, (byte[]) msg.obj);
                    break;
                }

                case MSG_ON_TRY_AGAIN_PRESSED: {
                    handleOnTryAgainPressed();
                    break;
                }

                case MSG_ON_READY_FOR_AUTHENTICATION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleOnReadyForAuthentication(
                            args.argi1 /* cookie */,
                            (boolean) args.arg1 /* requireConfirmation */,
                            args.argi2 /* userId */);
                    args.recycle();
                    break;
                }

                case MSG_AUTHENTICATE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleAuthenticate(
                            (IBinder) args.arg1 /* token */,
                            (long) args.arg2 /* sessionId */,
                            args.argi1 /* userid */,
                            (IBiometricServiceReceiver) args.arg3 /* receiver */,
                            (String) args.arg4 /* opPackageName */,
                            (Bundle) args.arg5 /* bundle */,
                            args.argi2 /* callingUid */,
                            args.argi3 /* callingPid */,
                            args.argi4 /* callingUserId */);
                    args.recycle();
                    break;
                }

                case MSG_CANCEL_AUTHENTICATION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleCancelAuthentication(
                            (IBinder) args.arg1 /* token */,
                            (String) args.arg2 /* opPackageName */,
                            args.argi1 /* callingUid */,
                            args.argi2 /* callingPid */,
                            args.argi3 /* callingUserId */);
                    args.recycle();
                    break;
                }

                case MSG_ON_AUTHENTICATION_TIMED_OUT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleAuthenticationTimedOut(
                            args.argi1 /* modality */,
                            args.argi2 /* error */,
                            args.argi3 /* vendorCode */);
                    args.recycle();
                    break;
                }

                case MSG_ON_DEVICE_CREDENTIAL_PRESSED: {
                    handleOnDeviceCredentialPressed();
                    break;
                }

                default:
                    Slog.e(TAG, "Unknown message: " + msg);
                    break;
            }
        }
    };

    /**
     * Wraps IBiometricAuthenticator implementation and stores information about the authenticator.
     * TODO(b/141025588): Consider refactoring the tests to not rely on this implementation detail.
     */
    @VisibleForTesting
    public static final class AuthenticatorWrapper {
        public final int id;
        public final int OEMStrength; // strength as configured by the OEM
        private int updatedStrength; // strength updated by BiometricStrengthController
        public final int modality;
        public final IBiometricAuthenticator impl;

        AuthenticatorWrapper(int id, int modality, int strength,
                IBiometricAuthenticator impl) {
            this.id = id;
            this.modality = modality;
            this.OEMStrength = strength;
            this.updatedStrength = strength;
            this.impl = impl;
        }

        /**
         * Returns the actual strength, taking any updated strengths into effect. Since more bits
         * means lower strength, the resulting strength is never stronger than the OEM's configured
         * strength.
         * @return a bitfield, see {@link Authenticators}
         */
        int getActualStrength() {
            return OEMStrength | updatedStrength;
        }

        boolean isDowngraded() {
            return OEMStrength != updatedStrength;
        }

        /**
         * Stores the updated strength, which takes effect whenever {@link #getActualStrength()}
         * is checked.
         * @param newStrength
         */
        void updateStrength(int newStrength) {
            String log = "updateStrength: Before(" + toString() + ")";
            updatedStrength = newStrength;
            log += " After(" + toString() + ")";
            Slog.d(TAG, log);
        }

        @Override
        public String toString() {
            return "ID(" + id + ")"
                    + " OEMStrength: " + OEMStrength
                    + " updatedStrength: " + updatedStrength
                    + " modality " + modality
                    + " authenticator: " + impl;
        }
    }

    @VisibleForTesting
    public static class SettingObserver extends ContentObserver {

        private static final boolean DEFAULT_KEYGUARD_ENABLED = true;
        private static final boolean DEFAULT_APP_ENABLED = true;
        private static final boolean DEFAULT_ALWAYS_REQUIRE_CONFIRMATION = false;

        private final Uri FACE_UNLOCK_KEYGUARD_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED);
        private final Uri FACE_UNLOCK_APP_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_APP_ENABLED);
        private final Uri FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION);

        private final ContentResolver mContentResolver;
        private final List<BiometricService.EnabledOnKeyguardCallback> mCallbacks;

        private final Map<Integer, Boolean> mFaceEnabledOnKeyguard = new HashMap<>();
        private final Map<Integer, Boolean> mFaceEnabledForApps = new HashMap<>();
        private final Map<Integer, Boolean> mFaceAlwaysRequireConfirmation = new HashMap<>();

        /**
         * Creates a content observer.
         *
         * @param handler The handler to run {@link #onChange} on, or null if none.
         */
        public SettingObserver(Context context, Handler handler,
                List<BiometricService.EnabledOnKeyguardCallback> callbacks) {
            super(handler);
            mContentResolver = context.getContentResolver();
            mCallbacks = callbacks;
            updateContentObserver();
        }

        public void updateContentObserver() {
            mContentResolver.unregisterContentObserver(this);
            mContentResolver.registerContentObserver(FACE_UNLOCK_KEYGUARD_ENABLED,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(FACE_UNLOCK_APP_ENABLED,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (FACE_UNLOCK_KEYGUARD_ENABLED.equals(uri)) {
                mFaceEnabledOnKeyguard.put(userId, Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED,
                                DEFAULT_KEYGUARD_ENABLED ? 1 : 0 /* default */,
                                userId) != 0);

                if (userId == ActivityManager.getCurrentUser() && !selfChange) {
                    notifyEnabledOnKeyguardCallbacks(userId);
                }
            } else if (FACE_UNLOCK_APP_ENABLED.equals(uri)) {
                mFaceEnabledForApps.put(userId, Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_APP_ENABLED,
                                DEFAULT_APP_ENABLED ? 1 : 0 /* default */,
                                userId) != 0);
            } else if (FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION.equals(uri)) {
                mFaceAlwaysRequireConfirmation.put(userId, Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
                                DEFAULT_ALWAYS_REQUIRE_CONFIRMATION ? 1 : 0 /* default */,
                                userId) != 0);
            }
        }

        public boolean getFaceEnabledOnKeyguard() {
            final int user = ActivityManager.getCurrentUser();
            if (!mFaceEnabledOnKeyguard.containsKey(user)) {
                onChange(true /* selfChange */, FACE_UNLOCK_KEYGUARD_ENABLED, user);
            }
            return mFaceEnabledOnKeyguard.get(user);
        }

        public boolean getFaceEnabledForApps(int userId) {
            if (!mFaceEnabledForApps.containsKey(userId)) {
                onChange(true /* selfChange */, FACE_UNLOCK_APP_ENABLED, userId);
            }
            return mFaceEnabledForApps.getOrDefault(userId, DEFAULT_APP_ENABLED);
        }

        public boolean getFaceAlwaysRequireConfirmation(int userId) {
            if (!mFaceAlwaysRequireConfirmation.containsKey(userId)) {
                onChange(true /* selfChange */, FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION, userId);
            }
            return mFaceAlwaysRequireConfirmation.get(userId);
        }

        public void notifyEnabledOnKeyguardCallbacks(int userId) {
            List<EnabledOnKeyguardCallback> callbacks = mCallbacks;
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).notify(BiometricSourceType.FACE,
                        mFaceEnabledOnKeyguard.getOrDefault(userId, DEFAULT_KEYGUARD_ENABLED),
                        userId);
            }
        }
    }

    final class EnabledOnKeyguardCallback implements IBinder.DeathRecipient {

        private final IBiometricEnabledOnKeyguardCallback mCallback;

        EnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
            mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(EnabledOnKeyguardCallback.this, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to linkToDeath", e);
            }
        }

        void notify(BiometricSourceType sourceType, boolean enabled, int userId) {
            try {
                mCallback.onChanged(sourceType, enabled, userId);
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

    // Wrap the client's receiver so we can do things with the BiometricDialog first
    @VisibleForTesting
    final IBiometricServiceReceiverInternal mInternalReceiver =
            new IBiometricServiceReceiverInternal.Stub() {
        @Override
        public void onAuthenticationSucceeded(boolean requireConfirmation, byte[] token,
                boolean isStrongBiometric) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = requireConfirmation;
            args.arg2 = token;
            args.arg3 = isStrongBiometric;
            mHandler.obtainMessage(MSG_ON_AUTHENTICATION_SUCCEEDED, args).sendToTarget();
        }

        @Override
        public void onAuthenticationFailed() {
            Slog.v(TAG, "onAuthenticationFailed");
            mHandler.obtainMessage(MSG_ON_AUTHENTICATION_REJECTED).sendToTarget();
        }

        @Override
        public void onError(int cookie, int modality, int error, int vendorCode)
                throws RemoteException {
            // Determine if error is hard or soft error. Certain errors (such as TIMEOUT) are
            // soft errors and we should allow the user to try authenticating again instead of
            // dismissing BiometricPrompt.
            if (error == BiometricConstants.BIOMETRIC_ERROR_TIMEOUT) {
                SomeArgs args = SomeArgs.obtain();
                args.argi1 = modality;
                args.argi2 = error;
                args.argi3 = vendorCode;
                mHandler.obtainMessage(MSG_ON_AUTHENTICATION_TIMED_OUT, args).sendToTarget();
            } else {
                SomeArgs args = SomeArgs.obtain();
                args.argi1 = cookie;
                args.argi2 = modality;
                args.argi3 = error;
                args.argi4 = vendorCode;
                mHandler.obtainMessage(MSG_ON_ERROR, args).sendToTarget();
            }
        }

        @Override
        public void onAcquired(int acquiredInfo, String message) throws RemoteException {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = acquiredInfo;
            args.arg1 = message;
            mHandler.obtainMessage(MSG_ON_ACQUIRED, args).sendToTarget();
        }

        @Override
        public void onDialogDismissed(int reason, @Nullable byte[] credentialAttestation)
                throws RemoteException {
            mHandler.obtainMessage(MSG_ON_DISMISSED,
                    reason,
                    0 /* arg2 */,
                    credentialAttestation /* obj */).sendToTarget();
        }

        @Override
        public void onTryAgainPressed() {
            mHandler.sendEmptyMessage(MSG_ON_TRY_AGAIN_PRESSED);
        }

        @Override
        public void onDeviceCredentialPressed() {
            mHandler.sendEmptyMessage(MSG_ON_DEVICE_CREDENTIAL_PRESSED);
        }
    };


    /**
     * This is just a pass-through service that wraps Fingerprint, Iris, Face services. This service
     * should not carry any state. The reality is we need to keep a tiny amount of state so that
     * cancelAuthentication() can go to the right place.
     */
    private final class BiometricServiceWrapper extends IBiometricService.Stub {
        @Override // Binder call
        public void onReadyForAuthentication(int cookie, boolean requireConfirmation, int userId) {
            checkInternalPermission();

            SomeArgs args = SomeArgs.obtain();
            args.argi1 = cookie;
            args.arg1 = requireConfirmation;
            args.argi2 = userId;
            mHandler.obtainMessage(MSG_ON_READY_FOR_AUTHENTICATION, args).sendToTarget();
        }

        @Override // Binder call
        public void authenticate(IBinder token, long sessionId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle,
                int callingUid, int callingPid, int callingUserId) {
            checkInternalPermission();

            if (token == null || receiver == null || opPackageName == null || bundle == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return;
            }

            if (!Utils.isValidAuthenticatorConfig(bundle)) {
                throw new SecurityException("Invalid authenticator configuration");
            }

            Utils.combineAuthenticatorBundles(bundle);

            // Set the default title if necessary.
            if (bundle.getBoolean(BiometricPrompt.KEY_USE_DEFAULT_TITLE, false)) {
                if (TextUtils.isEmpty(bundle.getCharSequence(BiometricPrompt.KEY_TITLE))) {
                    bundle.putCharSequence(BiometricPrompt.KEY_TITLE,
                            getContext().getString(R.string.biometric_dialog_default_title));
                }
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = token;
            args.arg2 = sessionId;
            args.argi1 = userId;
            args.arg3 = receiver;
            args.arg4 = opPackageName;
            args.arg5 = bundle;
            args.argi2 = callingUid;
            args.argi3 = callingPid;
            args.argi4 = callingUserId;

            mHandler.obtainMessage(MSG_AUTHENTICATE, args).sendToTarget();
        }

        @Override // Binder call
        public void cancelAuthentication(IBinder token, String opPackageName,
                int callingUid, int callingPid, int callingUserId) {
            checkInternalPermission();

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = token;
            args.arg2 = opPackageName;
            args.argi1 = callingUid;
            args.argi2 = callingPid;
            args.argi3 = callingUserId;
            mHandler.obtainMessage(MSG_CANCEL_AUTHENTICATION, args).sendToTarget();
        }

        @Override // Binder call
        public int canAuthenticate(String opPackageName, int userId, int callingUserId,
                @Authenticators.Types int authenticators) {
            checkInternalPermission();

            Slog.d(TAG, "canAuthenticate: User=" + userId
                    + ", Caller=" + callingUserId
                    + ", Authenticators=" + authenticators);

            if (!Utils.isValidAuthenticatorConfig(authenticators)) {
                throw new SecurityException("Invalid authenticator configuration");
            }

            final Bundle bundle = new Bundle();
            bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);

            int biometricConstantsResult = BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
            try {
                biometricConstantsResult = checkAndGetAuthenticators(userId, bundle, opPackageName,
                        false /* checkDevicePolicyManager */).second;
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }

            return Utils.biometricConstantsToBiometricManager(biometricConstantsResult);
        }

        @Override
        public boolean hasEnrolledBiometrics(int userId, String opPackageName) {
            checkInternalPermission();

            try {
                for (AuthenticatorWrapper authenticator : mAuthenticators) {
                    if (authenticator.impl.hasEnrolledTemplates(userId, opPackageName)) {
                        return true;
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }

            return false;
        }

        @Override
        public void registerAuthenticator(int id, int modality, int strength,
                IBiometricAuthenticator authenticator) {
            checkInternalPermission();

            Slog.d(TAG, "Registering ID: " + id
                    + " Modality: " + modality
                    + " Strength: " + strength);

            if (authenticator == null) {
                throw new IllegalArgumentException("Authenticator must not be null."
                        + " Did you forget to modify the core/res/res/values/xml overlay for"
                        + " config_biometric_sensors?");
            }

            // Note that we allow BIOMETRIC_CONVENIENCE to register because BiometricService
            // also does / will do other things such as keep track of lock screen timeout, etc.
            // Just because a biometric is registered does not mean it can participate in
            // the android.hardware.biometrics APIs.
            if (strength != Authenticators.BIOMETRIC_STRONG
                    && strength != Authenticators.BIOMETRIC_WEAK
                    && strength != Authenticators.BIOMETRIC_CONVENIENCE) {
                throw new IllegalStateException("Unsupported strength");
            }

            for (AuthenticatorWrapper wrapper : mAuthenticators) {
                if (wrapper.id == id) {
                    throw new IllegalStateException("Cannot register duplicate authenticator");
                }
            }

            // This happens infrequently enough, not worth caching.
            final String[] configs = mInjector.getConfiguration(getContext());
            boolean idFound = false;
            for (int i = 0; i < configs.length; i++) {
                SensorConfig config = new SensorConfig(configs[i]);
                if (config.mId == id) {
                    idFound = true;
                    break;
                }
            }
            if (!idFound) {
                throw new IllegalStateException("Cannot register unknown id");
            }

            mAuthenticators.add(new AuthenticatorWrapper(id, modality, strength, authenticator));

            mBiometricStrengthController.updateStrengths();
        }

        @Override // Binder call
        public void registerEnabledOnKeyguardCallback(
                IBiometricEnabledOnKeyguardCallback callback, int callingUserId) {
            checkInternalPermission();

            mEnabledOnKeyguardCallbacks.add(new EnabledOnKeyguardCallback(callback));
            try {
                callback.onChanged(BiometricSourceType.FACE,
                        mSettingObserver.getFaceEnabledOnKeyguard(), callingUserId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception", e);
            }
        }

        @Override // Binder call
        public void setActiveUser(int userId) {
            checkInternalPermission();

            try {
                for (AuthenticatorWrapper authenticator : mAuthenticators) {
                    authenticator.impl.setActiveUser(userId);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }

        @Override // Binder call
        public void resetLockout(byte[] token) {
            checkInternalPermission();

            try {
                for (AuthenticatorWrapper authenticator : mAuthenticators) {
                    authenticator.impl.resetLockout(token);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }

        @Override // Binder call
        public long[] getAuthenticatorIds() {
            checkInternalPermission();

            final List<Long> ids = new ArrayList<>();
            for (AuthenticatorWrapper authenticator : mAuthenticators) {
                try {
                    final long id = authenticator.impl.getAuthenticatorId();
                    if (Utils.isAtLeastStrength(authenticator.getActualStrength(),
                            Authenticators.BIOMETRIC_STRONG) && id != 0) {
                        ids.add(id);
                    } else {
                        Slog.d(TAG, "Authenticator " + authenticator + ", authenticatorID " + id
                                + " cannot participate in Keystore operations");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "RemoteException", e);
                }
            }

            long[] result = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                result[i] = ids.get(i);
            }
            return result;
        }
    }

    private void checkInternalPermission() {
        getContext().enforceCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL,
                "Must have USE_BIOMETRIC_INTERNAL permission");
    }

    /**
     * Class for injecting dependencies into BiometricService.
     * TODO(b/141025588): Replace with a dependency injection framework (e.g. Guice, Dagger).
     */
    @VisibleForTesting
    public static class Injector {

        @VisibleForTesting
        public IActivityManager getActivityManagerService() {
            return ActivityManager.getService();
        }

        @VisibleForTesting
        public ITrustManager getTrustManager() {
            return ITrustManager.Stub.asInterface(ServiceManager.getService(Context.TRUST_SERVICE));
        }

        @VisibleForTesting
        public IStatusBarService getStatusBarService() {
            return IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        }

        /**
         * Allows to mock SettingObserver for testing.
         */
        @VisibleForTesting
        public SettingObserver getSettingObserver(Context context, Handler handler,
                List<EnabledOnKeyguardCallback> callbacks) {
            return new SettingObserver(context, handler, callbacks);
        }

        @VisibleForTesting
        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        /**
         * Allows to enable/disable debug logs.
         */
        @VisibleForTesting
        public boolean isDebugEnabled(Context context, int userId) {
            return Utils.isDebugEnabled(context, userId);
        }

        /**
         * Allows to stub publishBinderService(...) for testing.
         */
        @VisibleForTesting
        public void publishBinderService(BiometricService service, IBiometricService.Stub impl) {
            service.publishBinderService(Context.BIOMETRIC_SERVICE, impl);
        }

        /**
         * Allows to mock BiometricStrengthController for testing.
         */
        @VisibleForTesting
        public BiometricStrengthController getBiometricStrengthController(
                BiometricService service) {
            return new BiometricStrengthController(service);
        }

        /**
         * Allows to test with various device sensor configurations.
         * @param context System Server context
         * @return the sensor configuration from core/res/res/values/config.xml
         */
        @VisibleForTesting
        public String[] getConfiguration(Context context) {
            return context.getResources().getStringArray(R.array.config_biometric_sensors);
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
        this(context, new Injector());
    }

    @VisibleForTesting
    BiometricService(Context context, Injector injector) {
        super(context);

        mInjector = injector;
        mDevicePolicyManager = (DevicePolicyManager) context
                .getSystemService(context.DEVICE_POLICY_SERVICE);
        mImpl = new BiometricServiceWrapper();
        mEnabledOnKeyguardCallbacks = new ArrayList<>();
        mSettingObserver = mInjector.getSettingObserver(context, mHandler,
                mEnabledOnKeyguardCallbacks);

        try {
            injector.getActivityManagerService().registerUserSwitchObserver(
                    new UserSwitchObserver() {
                        @Override
                        public void onUserSwitchComplete(int newUserId) {
                            mSettingObserver.updateContentObserver();
                            mSettingObserver.notifyEnabledOnKeyguardCallbacks(newUserId);
                        }
                    }, BiometricService.class.getName()
            );
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register user switch observer", e);
        }
    }

    @Override
    public void onStart() {
        mKeyStore = mInjector.getKeyStore();
        mStatusBarService = mInjector.getStatusBarService();
        mTrustManager = mInjector.getTrustManager();
        mInjector.publishBinderService(this, mImpl);
        mBiometricStrengthController = mInjector.getBiometricStrengthController(this);
        mBiometricStrengthController.startListening();
    }

    /**
     * @param modality one of {@link BiometricAuthenticator#TYPE_FINGERPRINT},
     * {@link BiometricAuthenticator#TYPE_IRIS} or {@link BiometricAuthenticator#TYPE_FACE}
     * @return
     */
    private int mapModalityToDevicePolicyType(int modality) {
        switch (modality) {
            case TYPE_FINGERPRINT:
                return DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;
            case TYPE_IRIS:
                return DevicePolicyManager.KEYGUARD_DISABLE_IRIS;
            case TYPE_FACE:
                return DevicePolicyManager.KEYGUARD_DISABLE_FACE;
            default:
                Slog.e(TAG, "Error modality=" + modality);
                return DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
        }
    }

    // TODO(joshmccloskey): Update this to throw an error if a new modality is added and this
    // logic is not updated.
    private boolean isBiometricDisabledByDevicePolicy(int modality, int effectiveUserId) {
        final int biometricToCheck = mapModalityToDevicePolicyType(modality);
        if (biometricToCheck == DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE) {
            Slog.e(TAG, "Allowing unknown modality " + modality + " to pass Device Policy check");
            return false;
        }
        final int devicePolicyDisabledFeatures =
                mDevicePolicyManager.getKeyguardDisabledFeatures(null, effectiveUserId);
        final boolean isBiometricDisabled =
                (biometricToCheck & devicePolicyDisabledFeatures) != 0;
        Slog.w(TAG, "isBiometricDisabledByDevicePolicy(" + modality + "," + effectiveUserId
                + ")=" + isBiometricDisabled);
        return isBiometricDisabled;
    }

    private static int biometricStatusToBiometricConstant(@BiometricStatus int status) {
        switch (status) {
            case BIOMETRIC_NO_HARDWARE:
                return BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;
            case BIOMETRIC_OK:
                return BiometricConstants.BIOMETRIC_SUCCESS;
            case BIOMETRIC_DISABLED_BY_DEVICE_POLICY:
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
            case BIOMETRIC_INSUFFICIENT_STRENGTH:
                return BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;
            case BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE:
                return BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;
            case BIOMETRIC_HARDWARE_NOT_DETECTED:
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
            case BIOMETRIC_NOT_ENROLLED:
                return BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;
            case BIOMETRIC_NOT_ENABLED_FOR_APPS:
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
            default:
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
    }

    /**
     * Returns the status of the authenticator, with errors returned in a specific priority order.
     * For example, {@link #BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE} is only returned
     * if it has enrollments, and is enabled for apps.
     *
     * We should only return the modality if the authenticator should be exposed. e.g.
     * BIOMETRIC_NOT_ENROLLED_FOR_APPS should not expose the authenticator's type.
     *
     * @return A Pair with `first` being modality, and `second` being @BiometricStatus
     */
    private Pair<Integer, Integer> getStatusForBiometricAuthenticator(
            AuthenticatorWrapper authenticator, int userId, String opPackageName,
            boolean checkDevicePolicyManager, int requestedStrength) {
        if (checkDevicePolicyManager) {
            if (isBiometricDisabledByDevicePolicy(authenticator.modality, userId)) {
                return new Pair<>(TYPE_NONE, BIOMETRIC_DISABLED_BY_DEVICE_POLICY);
            }
        }

        final boolean wasStrongEnough =
                Utils.isAtLeastStrength(authenticator.OEMStrength, requestedStrength);
        final boolean isStrongEnough =
                Utils.isAtLeastStrength(authenticator.getActualStrength(), requestedStrength);

        if (wasStrongEnough && !isStrongEnough) {
            return new Pair<>(authenticator.modality,
                    BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE);
        } else if (!wasStrongEnough) {
            return new Pair<>(TYPE_NONE, BIOMETRIC_INSUFFICIENT_STRENGTH);
        }

        try {
            if (!authenticator.impl.isHardwareDetected(opPackageName)) {
                return new Pair<>(authenticator.modality, BIOMETRIC_HARDWARE_NOT_DETECTED);
            }

            if (!authenticator.impl.hasEnrolledTemplates(userId, opPackageName)) {
                return new Pair<>(authenticator.modality, BIOMETRIC_NOT_ENROLLED);
            }
        } catch (RemoteException e) {
            return new Pair<>(authenticator.modality, BIOMETRIC_HARDWARE_NOT_DETECTED);
        }

        if (!isEnabledForApp(authenticator.modality, userId)) {
            return new Pair<>(TYPE_NONE, BIOMETRIC_NOT_ENABLED_FOR_APPS);
        }

        return new Pair<>(authenticator.modality, BIOMETRIC_OK);
    }

    /**
     * Depending on the requested authentication (credential/biometric combination), checks their
     * availability.
     *
     * @param userId the user to check for
     * @param bundle passed from {@link BiometricPrompt}
     * @param opPackageName see {@link android.app.AppOpsManager}
     *
     * @return A pair [Modality, Error] with Modality being one of
     * {@link BiometricAuthenticator#TYPE_NONE},
     * {@link BiometricAuthenticator#TYPE_FINGERPRINT},
     * {@link BiometricAuthenticator#TYPE_IRIS},
     * {@link BiometricAuthenticator#TYPE_FACE}
     * and the error containing one of the {@link BiometricConstants} errors.
     *
     * TODO(kchyn) should return Pair<Integer, Integer> with `first` being an actual bitfield
     * taking BiometricAuthenticator#TYPE_CREDENTIAL as well.
     *
     */
    private Pair<Integer, Integer> checkAndGetAuthenticators(int userId, Bundle bundle,
            String opPackageName, boolean checkDevicePolicyManager) throws RemoteException {

        final boolean biometricRequested = Utils.isBiometricRequested(bundle);
        final boolean credentialRequested = Utils.isCredentialRequested(bundle);

        final boolean credentialOk = mTrustManager.isDeviceSecure(userId);

        // Assuming that biometric authenticators are listed in priority-order, the rest of this
        // function will attempt to find the first authenticator that's as strong or stronger than
        // the requested strength, available, enrolled, and enabled. The tricky part is returning
        // the correct error. Error strings that are modality-specific should also respect the
        // priority-order.

        int firstBiometricModality = TYPE_NONE;
        @BiometricStatus int firstBiometricStatus = BIOMETRIC_NO_HARDWARE;

        int biometricModality = TYPE_NONE;
        @BiometricStatus int biometricStatus = BIOMETRIC_NO_HARDWARE;

        for (AuthenticatorWrapper authenticator : mAuthenticators) {
            final int requestedStrength = Utils.getPublicBiometricStrength(bundle);
            Pair<Integer, Integer> result = getStatusForBiometricAuthenticator(
                    authenticator, userId, opPackageName, checkDevicePolicyManager,
                    requestedStrength);

            biometricStatus = result.second;

            Slog.d(TAG, "Package: " + opPackageName
                    + " Authenticator ID: " + authenticator.id
                    + " Modality: " + authenticator.modality
                    + " Reported Modality: " + result.first
                    + " Status: " + biometricStatus);

            if (firstBiometricModality == TYPE_NONE) {
                firstBiometricModality = result.first;
                firstBiometricStatus = biometricStatus;
            }

            if (biometricStatus == BIOMETRIC_OK) {
                biometricModality = result.first;
                break;
            }
        }

        if (biometricRequested && credentialRequested) {
            if (credentialOk || biometricStatus == BIOMETRIC_OK) {
                if (biometricStatus != BIOMETRIC_OK) {
                    // If there's a problem with biometrics but device credential is
                    // allowed, only show credential UI.
                    bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED,
                            Authenticators.DEVICE_CREDENTIAL);
                }
                return new Pair<>(biometricModality, BiometricConstants.BIOMETRIC_SUCCESS);
            } else {
                return new Pair<>(firstBiometricModality,
                        BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS);
            }
        } else if (biometricRequested) {
            if (biometricStatus == BIOMETRIC_OK) {
                return new Pair<>(biometricModality,
                        biometricStatusToBiometricConstant(biometricStatus));
            } else {
                return new Pair<>(firstBiometricModality,
                        biometricStatusToBiometricConstant(firstBiometricStatus));
            }
        } else if (credentialRequested) {
            if (credentialOk) {
                return new Pair<>(TYPE_NONE, BiometricConstants.BIOMETRIC_SUCCESS);
            } else {
                return new Pair<>(TYPE_NONE,
                        BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL);
            }
        } else {
            // This should not be possible via the public API surface and is here mainly for
            // "correctness". An exception should have been thrown before getting here.
            Slog.e(TAG, "No authenticators requested");
            return new Pair<>(TYPE_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT);
        }
    }

    private boolean isEnabledForApp(int modality, int userId) {
        switch (modality) {
            case TYPE_FINGERPRINT:
                return true;
            case TYPE_IRIS:
                return true;
            case TYPE_FACE:
                return mSettingObserver.getFaceEnabledForApps(userId);
            default:
                Slog.w(TAG, "Unsupported modality: " + modality);
                return false;
        }
    }

    private void logDialogDismissed(int reason) {
        if (reason == BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED) {
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
                        + ", State: " + FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED
                        + ", Latency: " + latency);
            }

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED,
                    statsModality(),
                    mCurrentAuthSession.mUserId,
                    mCurrentAuthSession.isCrypto(),
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    mCurrentAuthSession.mRequireConfirmation,
                    FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED,
                    latency,
                    mInjector.isDebugEnabled(getContext(), mCurrentAuthSession.mUserId));
        } else {

            final long latency = System.currentTimeMillis() - mCurrentAuthSession.mStartTimeMs;

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
                        + ", Error: " + error
                        + ", Latency: " + latency);
            }
            // Auth canceled
            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED,
                    statsModality(),
                    mCurrentAuthSession.mUserId,
                    mCurrentAuthSession.isCrypto(),
                    BiometricsProtoEnums.ACTION_AUTHENTICATE,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    error,
                    0 /* vendorCode */,
                    mInjector.isDebugEnabled(getContext(), mCurrentAuthSession.mUserId),
                    latency);
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

    private void handleAuthenticationSucceeded(boolean requireConfirmation, byte[] token,
            boolean isStrongBiometric) {
        try {
            // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
            // after user dismissed/canceled dialog).
            if (mCurrentAuthSession == null) {
                Slog.e(TAG, "handleAuthenticationSucceeded: Auth session is null");
                return;
            }

            if (isStrongBiometric) {
                // Store the auth token and submit it to keystore after the dialog is confirmed /
                // animating away.
                mCurrentAuthSession.mTokenEscrow = token;
            } else {
                if (token != null) {
                    Slog.w(TAG, "Dropping authToken for non-strong biometric");
                }
            }

            if (!requireConfirmation) {
                mCurrentAuthSession.mState = STATE_AUTHENTICATED_PENDING_SYSUI;
            } else {
                mCurrentAuthSession.mAuthenticatedTimeMs = System.currentTimeMillis();
                mCurrentAuthSession.mState = STATE_AUTH_PENDING_CONFIRM;
            }

            // Notify SysUI that the biometric has been authenticated. SysUI already knows
            // the implicit/explicit state and will react accordingly.
            mStatusBarService.onBiometricAuthenticated();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleAuthenticationRejected() {
        Slog.v(TAG, "handleAuthenticationRejected()");
        try {
            // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
            // after user dismissed/canceled dialog).
            if (mCurrentAuthSession == null) {
                Slog.e(TAG, "handleAuthenticationRejected: Auth session is null");
                return;
            }

            mStatusBarService.onBiometricError(TYPE_NONE,
                    BiometricConstants.BIOMETRIC_PAUSED_REJECTED, 0 /* vendorCode */);

            // TODO: This logic will need to be updated if BP is multi-modal
            if ((mCurrentAuthSession.mModality & TYPE_FACE) != 0) {
                // Pause authentication. onBiometricAuthenticated(false) causes the
                // dialog to show a "try again" button for passive modalities.
                mCurrentAuthSession.mState = STATE_AUTH_PAUSED;
            }

            mCurrentAuthSession.mClientReceiver.onAuthenticationFailed();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleAuthenticationTimedOut(int modality, int error, int vendorCode) {
        Slog.v(TAG, String.format("handleAuthenticationTimedOut(%d, %d, %d)", modality, error,
                vendorCode));
        try {
            // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
            // after user dismissed/canceled dialog).
            if (mCurrentAuthSession == null) {
                Slog.e(TAG, "handleAuthenticationTimedOut: Auth session is null");
                return;
            }

            mStatusBarService.onBiometricError(modality, error, vendorCode);
            mCurrentAuthSession.mState = STATE_AUTH_PAUSED;
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleOnError(int cookie, int modality, int error, int vendorCode) {
        Slog.d(TAG, "handleOnError: " + error + " cookie: " + cookie);
        // Errors can either be from the current auth session or the pending auth session.
        // The pending auth session may receive errors such as ERROR_LOCKOUT before
        // it becomes the current auth session. Similarly, the current auth session may
        // receive errors such as ERROR_CANCELED while the pending auth session is preparing
        // to be started. Thus we must match error messages with their cookies to be sure
        // of their intended receivers.
        try {
            if (mCurrentAuthSession != null && mCurrentAuthSession.containsCookie(cookie)) {
                mCurrentAuthSession.mErrorEscrow = error;
                mCurrentAuthSession.mVendorCodeEscrow = vendorCode;

                if (mCurrentAuthSession.mState == STATE_AUTH_STARTED) {
                    final boolean errorLockout = error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                            || error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                    if (mCurrentAuthSession.isAllowDeviceCredential() && errorLockout) {
                        // SystemUI handles transition from biometric to device credential.
                        mCurrentAuthSession.mState = STATE_SHOWING_DEVICE_CREDENTIAL;
                        mStatusBarService.onBiometricError(modality, error, vendorCode);
                    } else if (error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
                        mStatusBarService.hideAuthenticationDialog();
                        // TODO: If multiple authenticators are simultaneously running, this will
                        // need to be modified. Send the error to the client here, instead of doing
                        // a round trip to SystemUI.
                        mCurrentAuthSession.mClientReceiver.onError(modality, error, vendorCode);
                        mCurrentAuthSession = null;
                    } else {
                        mCurrentAuthSession.mState = STATE_ERROR_PENDING_SYSUI;
                        mStatusBarService.onBiometricError(modality, error, vendorCode);
                    }
                } else if (mCurrentAuthSession.mState == STATE_AUTH_PAUSED) {
                    // In the "try again" state, we should forward canceled errors to
                    // the client and and clean up. The only error we should get here is
                    // ERROR_CANCELED due to another client kicking us out.
                    mCurrentAuthSession.mClientReceiver.onError(modality, error, vendorCode);
                    mStatusBarService.hideAuthenticationDialog();
                    mCurrentAuthSession = null;
                } else if (mCurrentAuthSession.mState == STATE_SHOWING_DEVICE_CREDENTIAL) {
                    Slog.d(TAG, "Biometric canceled, ignoring from state: "
                            + mCurrentAuthSession.mState);
                } else {
                    Slog.e(TAG, "Impossible session error state: "
                            + mCurrentAuthSession.mState);
                }
            } else if (mPendingAuthSession != null
                    && mPendingAuthSession.containsCookie(cookie)) {
                if (mPendingAuthSession.mState == STATE_AUTH_CALLED) {
                    // If any error is received while preparing the auth session (lockout, etc),
                    // and if device credential is allowed, just show the credential UI.
                    if (mPendingAuthSession.isAllowDeviceCredential()) {
                        @Authenticators.Types int authenticators =
                                mPendingAuthSession.mBundle.getInt(
                                        BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, 0);
                        // Disallow biometric and notify SystemUI to show the authentication prompt.
                        authenticators &= ~Authenticators.BIOMETRIC_WEAK;
                        mPendingAuthSession.mBundle.putInt(
                                BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED,
                                authenticators);

                        mCurrentAuthSession = mPendingAuthSession;
                        mCurrentAuthSession.mState = STATE_SHOWING_DEVICE_CREDENTIAL;
                        mPendingAuthSession = null;

                        mStatusBarService.showAuthenticationDialog(
                                mCurrentAuthSession.mBundle,
                                mInternalReceiver,
                                0 /* biometricModality */,
                                false /* requireConfirmation */,
                                mCurrentAuthSession.mUserId,
                                mCurrentAuthSession.mOpPackageName,
                                mCurrentAuthSession.mSessionId);
                    } else {
                        mPendingAuthSession.mClientReceiver.onError(modality, error, vendorCode);
                        mPendingAuthSession = null;
                    }
                } else {
                    Slog.e(TAG, "Impossible pending session error state: "
                            + mPendingAuthSession.mState);
                }
            } else {
                Slog.e(TAG, "Unknown cookie: " + cookie);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleOnAcquired(int acquiredInfo, String message) {
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "onAcquired(): Auth session is null");
            return;
        }

        if (message == null) {
            Slog.w(TAG, "Ignoring null message: " + acquiredInfo);
            return;
        }
        try {
            mStatusBarService.onBiometricHelp(message);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleOnDismissed(int reason, @Nullable byte[] credentialAttestation) {
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "onDismissed: " + reason + ", auth session null");
            return;
        }

        logDialogDismissed(reason);

        try {
            switch (reason) {
                case BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED:
                    mKeyStore.addAuthToken(credentialAttestation);
                case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED:
                case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED:
                    if (mCurrentAuthSession.mTokenEscrow != null) {
                        mKeyStore.addAuthToken(mCurrentAuthSession.mTokenEscrow);
                    }
                    mCurrentAuthSession.mClientReceiver.onAuthenticationSucceeded(
                            Utils.getAuthenticationTypeForResult(reason));
                    break;

                case BiometricPrompt.DISMISSED_REASON_NEGATIVE:
                    mCurrentAuthSession.mClientReceiver.onDialogDismissed(reason);
                    // Cancel authentication. Skip the token/package check since we are cancelling
                    // from system server. The interface is permission protected so this is fine.
                    cancelInternal(null /* token */, null /* package */,
                            mCurrentAuthSession.mCallingUid, mCurrentAuthSession.mCallingPid,
                            mCurrentAuthSession.mCallingUserId, false /* fromClient */);
                    break;

                case BiometricPrompt.DISMISSED_REASON_USER_CANCEL:
                    mCurrentAuthSession.mClientReceiver.onError(
                            mCurrentAuthSession.mModality,
                            BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED,
                            0 /* vendorCode */
                    );
                    // Cancel authentication. Skip the token/package check since we are cancelling
                    // from system server. The interface is permission protected so this is fine.
                    cancelInternal(null /* token */, null /* package */, Binder.getCallingUid(),
                            Binder.getCallingPid(), UserHandle.getCallingUserId(),
                            false /* fromClient */);
                    break;

                case BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED:
                case BiometricPrompt.DISMISSED_REASON_ERROR:
                    mCurrentAuthSession.mClientReceiver.onError(
                            mCurrentAuthSession.mModality,
                            mCurrentAuthSession.mErrorEscrow,
                            mCurrentAuthSession.mVendorCodeEscrow
                    );
                    break;

                default:
                    Slog.w(TAG, "Unhandled reason: " + reason);
                    break;
            }

            // Dialog is gone, auth session is done.
            mCurrentAuthSession = null;

        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleOnTryAgainPressed() {
        Slog.d(TAG, "onTryAgainPressed");
        // No need to check permission, since it can only be invoked by SystemUI
        // (or system server itself).
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
    }

    private void handleOnDeviceCredentialPressed() {
        Slog.d(TAG, "onDeviceCredentialPressed");
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "Auth session null");
            return;
        }

        // Cancel authentication. Skip the token/package check since we are cancelling
        // from system server. The interface is permission protected so this is fine.
        cancelInternal(null /* token */, null /* package */, Binder.getCallingUid(),
                Binder.getCallingPid(), UserHandle.getCallingUserId(),
                false /* fromClient */);

        mCurrentAuthSession.mState = STATE_SHOWING_DEVICE_CREDENTIAL;
    }

    /**
     * Invoked when each service has notified that its client is ready to be started. When
     * all biometrics are ready, this invokes the SystemUI dialog through StatusBar.
     */
    private void handleOnReadyForAuthentication(int cookie, boolean requireConfirmation,
            int userId) {
        if (mPendingAuthSession == null) {
            // Only should happen if a biometric was locked out when authenticate() was invoked.
            // In that case, if device credentials are allowed, the UI is already showing. If not
            // allowed, the error has already been returned to the caller.
            Slog.w(TAG, "Pending auth session null");
            return;
        }

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
            final boolean continuing = mCurrentAuthSession != null
                    && mCurrentAuthSession.mState == STATE_AUTH_PAUSED;

            mCurrentAuthSession = mPendingAuthSession;

            // Time starts when lower layers are ready to start the client.
            mCurrentAuthSession.mStartTimeMs = System.currentTimeMillis();
            mPendingAuthSession = null;

            mCurrentAuthSession.mState = STATE_AUTH_STARTED;
            int modality = TYPE_NONE;
            it = mCurrentAuthSession.mModalitiesMatched.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Integer> pair = (Map.Entry) it.next();
                boolean foundAuthenticator = false;
                for (AuthenticatorWrapper authenticator : mAuthenticators) {
                    if (authenticator.modality == pair.getKey()) {
                        foundAuthenticator = true;
                        try {
                            authenticator.impl.startPreparedClient(pair.getValue());
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Remote exception", e);
                        }
                    }
                }
                if (!foundAuthenticator) {
                    Slog.e(TAG, "Unknown modality: " + pair.getKey());
                }
                modality |= pair.getKey();
            }

            if (!continuing) {
                try {
                    mStatusBarService.showAuthenticationDialog(mCurrentAuthSession.mBundle,
                            mInternalReceiver, modality, requireConfirmation, userId,
                            mCurrentAuthSession.mOpPackageName,
                            mCurrentAuthSession.mSessionId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        }
    }

    private void handleAuthenticate(IBinder token, long sessionId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle,
            int callingUid, int callingPid, int callingUserId) {

        mHandler.post(() -> {
            try {
                final boolean checkDevicePolicyManager = bundle.getBoolean(
                        BiometricPrompt.EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS, false);
                final Pair<Integer, Integer> pair = checkAndGetAuthenticators(userId, bundle,
                        opPackageName, checkDevicePolicyManager);
                final int modality = pair.first;
                final int result = pair.second;

                if (result == BiometricConstants.BIOMETRIC_SUCCESS) {
                    authenticateInternal(token, sessionId, userId, receiver, opPackageName,
                            bundle, callingUid, callingPid, callingUserId, modality);
                } else {
                    receiver.onError(modality, result, 0 /* vendorCode */);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        });
    }

    /**
     * handleAuthenticate() (above) which is called from BiometricPrompt determines which
     * modality/modalities to start authenticating with. authenticateInternal() should only be
     * used for:
     * 1) Preparing <Biometric>Services for authentication when BiometricPrompt#authenticate is,
     * invoked, shortly after which BiometricPrompt is shown and authentication starts
     * 2) Preparing <Biometric>Services for authentication when BiometricPrompt is already shown
     * and the user has pressed "try again"
     */
    private void authenticateInternal(IBinder token, long sessionId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle,
            int callingUid, int callingPid, int callingUserId, int modality) {
        boolean requireConfirmation = bundle.getBoolean(
                BiometricPrompt.KEY_REQUIRE_CONFIRMATION, true /* default */);
        if ((modality & TYPE_FACE) != 0) {
            // Check if the user has forced confirmation to be required in Settings.
            requireConfirmation = requireConfirmation
                    || mSettingObserver.getFaceAlwaysRequireConfirmation(userId);
        }
        // Generate random cookies to pass to the services that should prepare to start
        // authenticating. Store the cookie here and wait for all services to "ack"
        // with the cookie. Once all cookies are received, we can show the prompt
        // and let the services start authenticating. The cookie should be non-zero.
        final int cookie = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
        final @Authenticators.Types int authenticators = bundle.getInt(
                BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, 0);
        Slog.d(TAG, "Creating auth session. Modality: " + modality
                + ", cookie: " + cookie
                + ", authenticators: " + authenticators);
        final HashMap<Integer, Integer> modalities = new HashMap<>();

        // If it's only device credential, we don't need to wait - LockSettingsService is
        // always ready to check credential (SystemUI invokes that path).
        if ((authenticators & ~Authenticators.DEVICE_CREDENTIAL) != 0) {
            modalities.put(modality, cookie);
        }
        mPendingAuthSession = new AuthSession(modalities, token, sessionId, userId,
                receiver, opPackageName, bundle, callingUid, callingPid, callingUserId,
                modality, requireConfirmation);

        try {
            if (authenticators == Authenticators.DEVICE_CREDENTIAL) {
                mPendingAuthSession.mState = STATE_SHOWING_DEVICE_CREDENTIAL;
                mCurrentAuthSession = mPendingAuthSession;
                mPendingAuthSession = null;

                mStatusBarService.showAuthenticationDialog(
                        mCurrentAuthSession.mBundle,
                        mInternalReceiver,
                        0 /* biometricModality */,
                        false /* requireConfirmation */,
                        mCurrentAuthSession.mUserId,
                        mCurrentAuthSession.mOpPackageName,
                        sessionId);
            } else {
                mPendingAuthSession.mState = STATE_AUTH_CALLED;
                for (AuthenticatorWrapper authenticator : mAuthenticators) {
                    // TODO(b/141025588): use ids instead of modalities to avoid ambiguity.
                    if (authenticator.modality == modality) {
                        authenticator.impl.prepareForAuthentication(requireConfirmation, token,
                                sessionId, userId, mInternalReceiver, opPackageName, cookie,
                                callingUid, callingPid, callingUserId);
                        break;
                    }
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to start authentication", e);
        }
    }

    private void handleCancelAuthentication(IBinder token, String opPackageName, int callingUid,
            int callingPid, int callingUserId) {
        if (token == null || opPackageName == null) {
            Slog.e(TAG, "Unable to cancel, one or more null arguments");
            return;
        }

        if (mCurrentAuthSession != null && mCurrentAuthSession.mState != STATE_AUTH_STARTED) {
            // We need to check the current authenticators state. If we're pending confirm
            // or idle, we need to dismiss the dialog and send an ERROR_CANCELED to the client,
            // since we won't be getting an onError from the driver.
            try {
                // Send error to client
                mCurrentAuthSession.mClientReceiver.onError(
                        mCurrentAuthSession.mModality,
                        BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                        0 /* vendorCode */
                );
                mCurrentAuthSession = null;
                mStatusBarService.hideAuthenticationDialog();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        } else {
            cancelInternal(token, opPackageName, callingUid, callingPid, callingUserId,
                    true /* fromClient */);
        }
    }

    void cancelInternal(IBinder token, String opPackageName, int callingUid, int callingPid,
            int callingUserId, boolean fromClient) {

        if (mCurrentAuthSession == null) {
            Slog.w(TAG, "Skipping cancelInternal");
            return;
        } else if (mCurrentAuthSession.mState != STATE_AUTH_STARTED) {
            Slog.w(TAG, "Skipping cancelInternal, state: " + mCurrentAuthSession.mState);
            return;
        }

        // TODO: For multiple modalities, send a single ERROR_CANCELED only when all
        // drivers have canceled authentication.
        for (AuthenticatorWrapper authenticator : mAuthenticators) {
            if ((authenticator.modality & mCurrentAuthSession.mModality) != 0) {
                try {
                    authenticator.impl.cancelAuthenticationFromService(token, opPackageName,
                            callingUid, callingPid, callingUserId, fromClient);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to cancel authentication");
                }
            }
        }
    }
}
