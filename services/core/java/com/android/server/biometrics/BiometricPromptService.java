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
import static android.Manifest.permission.USE_FINGERPRINT;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.IBiometricPromptService;
import android.hardware.biometrics.IBiometricPromptServiceReceiver;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.SystemService;

import java.util.ArrayList;

/**
 * System service that arbitrates the modality for BiometricPrompt to use.
 */
public class BiometricPromptService extends SystemService {

    private static final String TAG = "BiometricPromptService";

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

    private final Handler mHandler;
    private final boolean mHasFeatureFingerprint;
    private final boolean mHasFeatureIris;
    private final boolean mHasFeatureFace;

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

    /**
     * This is just a pass-through service that wraps Fingerprint, Iris, Face services. This service
     * should not carry any state. The reality is we need to keep a tiny amount of state so that
     * cancelAuthentication() can go to the right place.
     */
    private final class BiometricPromptServiceWrapper extends IBiometricPromptService.Stub {

        @Override // Binder call
        public void authenticate(IBinder token, long sessionId, int userId,
                IBiometricPromptServiceReceiver receiver, int flags, String opPackageName,
                Bundle bundle, IBiometricPromptReceiver dialogReceiver) throws RemoteException {
            // Check the USE_BIOMETRIC permission here. In the BiometricService, check do the
            // AppOps and foreground check.
            checkPermission();

            if (token == null || receiver == null || opPackageName == null || bundle == null
                    || dialogReceiver == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return;
            }

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            mHandler.post(() -> {
                mCurrentModality = checkAndGetBiometricModality(receiver);

                try {
                    // No polymorphism :(
                    if (mCurrentModality == BIOMETRIC_FINGERPRINT) {
                        mFingerprintService.authenticateFromService(token, sessionId, userId,
                                receiver, flags, opPackageName, bundle, dialogReceiver,
                                callingUid, callingPid, callingUserId);
                    } else if (mCurrentModality == BIOMETRIC_IRIS) {
                        Slog.w(TAG, "Unsupported modality");
                    } else if (mCurrentModality == BIOMETRIC_FACE) {
                        mFaceService.authenticateFromService(true /* requireConfirmation */, token,
                                sessionId, userId, receiver, flags, opPackageName, bundle,
                                dialogReceiver, callingUid, callingPid, callingUserId);
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
    public BiometricPromptService(Context context) {
        super(context);

        mHandler = new Handler(Looper.getMainLooper());

        final PackageManager pm = context.getPackageManager();
        mHasFeatureFingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        mHasFeatureIris = pm.hasSystemFeature(PackageManager.FEATURE_IRIS);
        mHasFeatureFace = pm.hasSystemFeature(PackageManager.FEATURE_FACE);
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

        publishBinderService(Context.BIOMETRIC_PROMPT_SERVICE, new BiometricPromptServiceWrapper());
    }

    /**
     * Checks if there are any available biometrics, and returns the modality. This method also
     * returns errors through the callback (no biometric feature, hardware not detected, no
     * templates enrolled, etc). This service must not start authentication if errors are sent.
     */
    private int checkAndGetBiometricModality(IBiometricPromptServiceReceiver receiver) {
        int modality = BIOMETRIC_NONE;
        final String hardwareUnavailable =
                getContext().getString(R.string.biometric_error_hw_unavailable);

        // No biometric features, send error
        if (mAuthenticators.isEmpty()) {
            try {
                receiver.onError(0 /* deviceId */,
                        BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                        hardwareUnavailable);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to send error", e);
            }
            return BIOMETRIC_NONE;
        }

        // Find first authenticator that's both detected and enrolled
        boolean isHardwareDetected = false;
        boolean hasTemplatesEnrolled = false;
        for (int i = 0; i < mAuthenticators.size(); i++) {
            int featureId = mAuthenticators.get(i).getType();
            BiometricAuthenticator authenticator = mAuthenticators.get(i).getAuthenticator();
            if (authenticator.isHardwareDetected()) {
                isHardwareDetected = true;
                if (authenticator.hasEnrolledTemplates()) {
                    hasTemplatesEnrolled = true;
                    modality = featureId;
                    break;
                }
            }
        }

        // Check error conditions
        if (!isHardwareDetected) {
            try {
                receiver.onError(0 /* deviceId */,
                        BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        hardwareUnavailable);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to send error", e);
            }
            return BIOMETRIC_NONE;
        }
        if (!hasTemplatesEnrolled) {
            try {
                receiver.onError(0 /* deviceId */,
                        BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS,
                        FaceManager.getErrorString(getContext(),
                                BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS,
                                0 /* vendorCode */));
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to send error", e);
            }
            return BIOMETRIC_NONE;
        }

        return modality;
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
