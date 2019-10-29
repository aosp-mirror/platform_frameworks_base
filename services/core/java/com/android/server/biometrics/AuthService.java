/*
 * Copyright (C) 2019 The Android Open Source Project
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


// TODO(b/141025588): Create separate internal and external permissions for AuthService.
// TODO(b/141025588): Get rid of the USE_FINGERPRINT permission.

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_IRIS;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.IAuthService;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.iris.IIrisService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import com.android.server.biometrics.face.FaceAuthenticator;
import com.android.server.biometrics.fingerprint.FingerprintAuthenticator;
import com.android.server.biometrics.iris.IrisAuthenticator;

/**
 * System service that provides an interface for authenticating with biometrics and
 * PIN/pattern/password to BiometricPrompt and lock screen.
 */
public class AuthService extends SystemService {
    private static final String TAG = "AuthService";
    private static final boolean DEBUG = false;

    private final Injector mInjector;

    private IBiometricService mBiometricService;
    @VisibleForTesting
    final IAuthService.Stub mImpl;

    /**
     * Class for injecting dependencies into AuthService.
     * TODO(b/141025588): Replace with a dependency injection framework (e.g. Guice, Dagger).
     */
    @VisibleForTesting
    public static class Injector {

        /**
         * Allows to mock BiometricService for testing.
         */
        @VisibleForTesting
        public IBiometricService getBiometricService() {
            return IBiometricService.Stub.asInterface(
                    ServiceManager.getService(Context.BIOMETRIC_SERVICE));
        }

        /**
         * Allows to stub publishBinderService(...) for testing.
         */
        @VisibleForTesting
        public void publishBinderService(AuthService service, IAuthService.Stub impl) {
            service.publishBinderService(Context.AUTH_SERVICE, impl);
        }

        /**
         * Allows to test with various device sensor configurations.
         * @param context
         * @return
         */
        @VisibleForTesting
        public String[] getConfiguration(Context context) {
            return context.getResources().getStringArray(R.array.config_biometric_sensors);
        }
    }

    private final class AuthServiceImpl extends IAuthService.Stub {
        @Override
        public void authenticate(IBinder token, long sessionId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle)
                throws RemoteException {
            final int callingUserId = UserHandle.getCallingUserId();

            // In the BiometricServiceBase, do the AppOps and foreground check.
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

            mBiometricService.authenticate(token, sessionId, userId, receiver, opPackageName,
                    bundle);
        }

        @Override
        public int canAuthenticate(String opPackageName, int userId,
                @Authenticators.Types int authenticators) throws RemoteException {
            final int callingUserId = UserHandle.getCallingUserId();
            Slog.d(TAG, "canAuthenticate, userId: " + userId + ", callingUserId: " + callingUserId
                    + ", authenticators: " + authenticators);
            if (userId != callingUserId) {
                checkInternalPermission();
            } else {
                checkPermission();
            }
            return mBiometricService.canAuthenticate(opPackageName, userId, authenticators);
        }

        @Override
        public boolean hasEnrolledBiometrics(int userId, String opPackageName)
                throws RemoteException {
            checkInternalPermission();
            return mBiometricService.hasEnrolledBiometrics(userId, opPackageName);
        }

        @Override
        public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback)
                throws RemoteException {
            checkInternalPermission();
            mBiometricService.registerEnabledOnKeyguardCallback(callback);
        }

        @Override
        public void setActiveUser(int userId) throws RemoteException {
            checkInternalPermission();
            mBiometricService.setActiveUser(userId);
        }

        @Override
        public void resetLockout(byte[] token) throws RemoteException {
            checkInternalPermission();
            mBiometricService.resetLockout(token);
        }
    }

    public AuthService(Context context) {
        this(context, new Injector());
    }

    public AuthService(Context context, Injector injector) {
        super(context);

        mInjector = injector;
        mImpl = new AuthServiceImpl();
        final PackageManager pm = context.getPackageManager();
    }

    /**
     * @param id must be unique per device
     * @param modality one of {@link BiometricAuthenticator} types
     * @param strength as defined in {@link Authenticators}
     */
    private void registerAuthenticator(int id, int modality, int strength) throws RemoteException {

        Slog.d(TAG, "Registering ID: " + id + " Modality: " + modality + " Strength: " + strength);

        final IBiometricAuthenticator.Stub authenticator;

        switch (modality) {
            case TYPE_FINGERPRINT:
                authenticator = new FingerprintAuthenticator(IFingerprintService.Stub.asInterface(
                        ServiceManager.getService(Context.FINGERPRINT_SERVICE)));
                break;

            case TYPE_FACE:
                authenticator = new FaceAuthenticator(IFaceService.Stub.asInterface(
                        ServiceManager.getService(Context.FACE_SERVICE)));
                break;

            case TYPE_IRIS:
                authenticator = new IrisAuthenticator(IIrisService.Stub.asInterface(
                        ServiceManager.getService(Context.IRIS_SERVICE)));
                break;

            default:
                Slog.e(TAG, "Unknown modality: " + modality);
                return;
        }

        mBiometricService.registerAuthenticator(id, modality, strength, authenticator);
    }

    @Override
    public void onStart() {
        mBiometricService = mInjector.getBiometricService();

        final String[] configs = mInjector.getConfiguration(getContext());

        for (int i = 0; i < configs.length; i++) {
            String[] elems = configs[i].split(":");
            final int id = Integer.parseInt(elems[0]);
            final int modality = Integer.parseInt(elems[1]);
            final int strength = Integer.parseInt(elems[2]);

            try {
                registerAuthenticator(id, modality, strength);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }

        }

        mInjector.publishBinderService(this, mImpl);
    }

    private void checkInternalPermission() {
        getContext().enforceCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL,
                "Must have USE_BIOMETRIC_INTERNAL permission");
    }

    private void checkPermission() {
        if (getContext().checkCallingOrSelfPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            getContext().enforceCallingOrSelfPermission(USE_BIOMETRIC,
                    "Must have USE_BIOMETRIC permission");
        }
    }
}
