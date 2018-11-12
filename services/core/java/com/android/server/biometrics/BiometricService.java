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

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.UserSwitchObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
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
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * System service that arbitrates the modality for BiometricPrompt to use.
 */
public class BiometricService extends SystemService {

    private static final String TAG = "BiometricService";

    /**
     * No biometric methods or nothing has been enrolled.
     * Move/expose these in BiometricPrompt if we ever want to allow applications to "blacklist"
     * modalities when calling authenticate().
     */
    private static final int BIOMETRIC_NONE = 0;

    /**
     * Constant representing fingerprint.
     */
    private static final int BIOMETRIC_FINGERPRINT = 1 << 0;

    /**
     * Constant representing iris.
     */
    private static final int BIOMETRIC_IRIS = 1 << 1;

    /**
     * Constant representing face.
     */
    private static final int BIOMETRIC_FACE = 1 << 2;

    private static final int[] FEATURE_ID = {
            BIOMETRIC_FINGERPRINT,
            BIOMETRIC_IRIS,
            BIOMETRIC_FACE
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

        private final ContentResolver mContentResolver;
        private boolean mFaceEnabledOnKeyguard;
        private boolean mFaceEnabledForApps;

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

            // Update the value immediately
            onChange(true /* selfChange */, FACE_UNLOCK_KEYGUARD_ENABLED);
            onChange(true /* selfChange */, FACE_UNLOCK_APP_ENABLED);
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
            }
        }

        boolean getFaceEnabledOnKeyguard() {
            return mFaceEnabledOnKeyguard;
        }

        boolean getFaceEnabledForApps() {
            return mFaceEnabledForApps;
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

        @Override // Binder call
        public void authenticate(IBinder token, long sessionId, int userId,
                IBiometricServiceReceiver receiver, int flags, String opPackageName,
                Bundle bundle, IBiometricPromptReceiver dialogReceiver) throws RemoteException {
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

            if (token == null || receiver == null || opPackageName == null || bundle == null
                    || dialogReceiver == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return;
            }

            // Check the usage of this in system server. Need to remove this check if it becomes
            // a public API.
            if (bundle.getBoolean(BiometricPrompt.KEY_USE_DEFAULT_TITLE, false)) {
                checkInternalPermission();
            }

            mHandler.post(() -> {
                final Pair<Integer, Integer> result = checkAndGetBiometricModality(callingUserId);
                final int modality = result.first;
                final int error = result.second;

                // Check for errors, notify callback, and return
                if (error != BiometricConstants.BIOMETRIC_SUCCESS) {
                    try {
                        final String hardwareUnavailable =
                                getContext().getString(R.string.biometric_error_hw_unavailable);
                        switch (error) {
                            case BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                                receiver.onError(0 /* deviceId */, error, hardwareUnavailable);
                                break;
                            case BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                                receiver.onError(0 /* deviceId */, error, hardwareUnavailable);
                                break;
                            case BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS:
                                receiver.onError(0 /* deviceId */, error,
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

                // Actually start authentication
                mCurrentModality = modality;
                try {
                    // No polymorphism :(
                    if (mCurrentModality == BIOMETRIC_FINGERPRINT) {
                        mFingerprintService.authenticateFromService(token, sessionId, userId,
                                receiver, flags, opPackageName, bundle, dialogReceiver,
                                callingUid, callingPid, callingUserId);
                    } else if (mCurrentModality == BIOMETRIC_IRIS) {
                        Slog.w(TAG, "Unsupported modality");
                    } else if (mCurrentModality == BIOMETRIC_FACE) {
                        mFaceService.authenticateFromService(true /* requireConfirmation */,
                                token, sessionId, userId, receiver, flags, opPackageName,
                                bundle, dialogReceiver, callingUid, callingPid, callingUserId);
                    } else {
                        Slog.w(TAG, "Unsupported modality");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to start authentication", e);
                }
            });
        }

        @Override // Binder call
        public void cancelAuthentication(IBinder token, String opPackageName)
                throws RemoteException {
            checkPermission();

            if (token == null || opPackageName == null) {
                Slog.e(TAG, "Unable to cancel, one or more null arguments");
                return;
            }

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            mHandler.post(() -> {
                try {
                    if (mCurrentModality == BIOMETRIC_FINGERPRINT) {
                        mFingerprintService.cancelAuthenticationFromService(token, opPackageName,
                                callingUid, callingPid, callingUserId);
                    } else if (mCurrentModality == BIOMETRIC_IRIS) {
                        Slog.w(TAG, "Unsupported modality");
                    } else if (mCurrentModality == BIOMETRIC_FACE) {
                        mFaceService.cancelAuthenticationFromService(token, opPackageName,
                                callingUid, callingPid, callingUserId);
                    } else {
                        Slog.w(TAG, "Unsupported modality");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to cancel authentication");
                }
            });
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
    }

    private void checkAppOp(String opPackageName, int callingUid) {
        if (mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, callingUid,
                opPackageName) != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; permission denied");
            throw new SecurityException("Permission denied");
        }
    }

    private void checkInternalPermission() {
        getContext().enforceCallingPermission(USE_BIOMETRIC_INTERNAL,
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
     * @Returns A pair [Modality, Error] with Modality being one of {@link #BIOMETRIC_NONE},
     * {@link #BIOMETRIC_FINGERPRINT}, {@link #BIOMETRIC_IRIS}, {@link #BIOMETRIC_FACE}
     * and the error containing one of the {@link BiometricConstants} errors.
     */
    private Pair<Integer, Integer> checkAndGetBiometricModality(int callingUid) {
        int modality = BIOMETRIC_NONE;

        // No biometric features, send error
        if (mAuthenticators.isEmpty()) {
            return new Pair<>(BIOMETRIC_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT);
        }

        // Assuming that authenticators are listed in priority-order, the rest of this function
        // will go through and find the first authenticator that's available, enrolled, and enabled.
        // The tricky part is returning the correct error. Error strings that are modality-specific
        // should also respect the priority-order.

        // Find first authenticator that's detected, enrolled, and enabled.
        boolean isHardwareDetected = false;
        boolean hasTemplatesEnrolled = false;
        boolean enabledForApps = false;

        int firstHwAvailable = BIOMETRIC_NONE;
        for (int i = 0; i < mAuthenticators.size(); i++) {
            modality = mAuthenticators.get(i).getType();
            BiometricAuthenticator authenticator = mAuthenticators.get(i).getAuthenticator();
            if (authenticator.isHardwareDetected()) {
                isHardwareDetected = true;
                if (firstHwAvailable == BIOMETRIC_NONE) {
                    // Store the first one since we want to return the error in correct priority
                    // order.
                    firstHwAvailable = modality;
                }
                if (authenticator.hasEnrolledTemplates(callingUid)) {
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
            return new Pair<>(BIOMETRIC_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE);
        } else if (!hasTemplatesEnrolled) {
            // Return the modality here so the correct error string can be sent. This error is
            // preferred over !enabledForApps
            return new Pair<>(firstHwAvailable, BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS);
        } else if (!enabledForApps) {
            return new Pair<>(BIOMETRIC_NONE, BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE);
        }

        return new Pair<>(modality, BiometricConstants.BIOMETRIC_SUCCESS);
    }

    private boolean isEnabledForApp(int modality) {
        switch(modality) {
            case BIOMETRIC_FINGERPRINT:
                return true;
            case BIOMETRIC_IRIS:
                return true;
            case BIOMETRIC_FACE:
                return mSettingObserver.getFaceEnabledForApps();
            default:
                Slog.w(TAG, "Unsupported modality: " + modality);
                return false;
        }
    }

    private String getErrorString(int type, int error, int vendorCode) {
        switch (type) {
            case BIOMETRIC_FINGERPRINT:
                return FingerprintManager.getErrorString(getContext(), error, vendorCode);
            case BIOMETRIC_IRIS:
                Slog.w(TAG, "Modality not supported");
                return null; // not supported
            case BIOMETRIC_FACE:
                return FaceManager.getErrorString(getContext(), error, vendorCode);
            default:
                Slog.w(TAG, "Unable to get error string for modality: " + type);
                return null;
        }
    }

    private BiometricAuthenticator getAuthenticator(int type) {
        switch (type) {
            case BIOMETRIC_FINGERPRINT:
                return (FingerprintManager)
                        getContext().getSystemService(Context.FINGERPRINT_SERVICE);
            case BIOMETRIC_IRIS:
                return null;
            case BIOMETRIC_FACE:
                return (FaceManager)
                        getContext().getSystemService(Context.FACE_SERVICE);
            default:
                return null;
        }
    }

    private boolean hasFeature(int type) {
        switch (type) {
            case BIOMETRIC_FINGERPRINT:
                return mHasFeatureFingerprint;
            case BIOMETRIC_IRIS:
                return mHasFeatureIris;
            case BIOMETRIC_FACE:
                return mHasFeatureFace;
            default:
                return false;
        }
    }
}
