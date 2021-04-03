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

import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_IRIS;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IAuthService;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.iris.IIrisService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import com.android.server.biometrics.sensors.face.FaceAuthenticator;
import com.android.server.biometrics.sensors.fingerprint.FingerprintAuthenticator;
import com.android.server.biometrics.sensors.iris.IrisAuthenticator;

import java.util.List;

/**
 * System service that provides an interface for authenticating with biometrics and
 * PIN/pattern/password to BiometricPrompt and lock screen.
 */
public class AuthService extends SystemService {
    private static final String TAG = "AuthService";
    private static final boolean DEBUG = false;
    private static final String SETTING_HIDL_DISABLED =
            "com.android.server.biometrics.AuthService.hidlDisabled";
    private static final int DEFAULT_HIDL_DISABLED = 0;

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

        /**
         * Allows us to mock FingerprintService for testing
         */
        @VisibleForTesting
        public IFingerprintService getFingerprintService() {
            return IFingerprintService.Stub.asInterface(
                    ServiceManager.getService(Context.FINGERPRINT_SERVICE));
        }

        /**
         * Allows us to mock FaceService for testing
         */
        @VisibleForTesting
        public IFaceService getFaceService() {
            return IFaceService.Stub.asInterface(
                    ServiceManager.getService(Context.FACE_SERVICE));
        }

        /**
         * Allows us to mock IrisService for testing
         */
        @VisibleForTesting
        public IIrisService getIrisService() {
            return IIrisService.Stub.asInterface(
                    ServiceManager.getService(Context.IRIS_SERVICE));
        }

        @VisibleForTesting
        public AppOpsManager getAppOps(Context context) {
            return context.getSystemService(AppOpsManager.class);
        }

        /**
         * Allows to ignore HIDL HALs on debug builds based on a secure setting.
         */
        @VisibleForTesting
        public boolean isHidlDisabled(Context context) {
            if (Build.IS_ENG || Build.IS_USERDEBUG) {
                return Settings.Secure.getIntForUser(context.getContentResolver(),
                        SETTING_HIDL_DISABLED, DEFAULT_HIDL_DISABLED, UserHandle.USER_CURRENT) == 1;
            }
            return false;
        }
    }

    private final class AuthServiceImpl extends IAuthService.Stub {
        @Override
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) throws RemoteException {
            Utils.checkPermission(getContext(), TEST_BIOMETRIC);

            final long identity = Binder.clearCallingIdentity();
            try {
                return mInjector.getBiometricService()
                        .createTestSession(sensorId, callback, opPackageName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<SensorPropertiesInternal> getSensorProperties(String opPackageName)
                throws RemoteException {
            Utils.checkPermission(getContext(), TEST_BIOMETRIC);

            final long identity = Binder.clearCallingIdentity();
            try {
                // Get the result from BiometricService, since it is the source of truth for all
                // biometric sensors.
                return mInjector.getBiometricService().getSensorProperties(opPackageName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public String getUiPackage() {
            Utils.checkPermission(getContext(), TEST_BIOMETRIC);

            return getContext().getResources()
                    .getString(R.string.config_biometric_prompt_ui_package);
        }

        @Override
        public void authenticate(IBinder token, long sessionId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo)
                throws RemoteException {

            // Only allow internal clients to authenticate with a different userId.
            final int callingUserId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            if (userId == callingUserId) {
                checkPermission();
            } else {
                Slog.w(TAG, "User " + callingUserId + " is requesting authentication of userid: "
                        + userId);
                checkInternalPermission();
            }

            if (!checkAppOps(callingUid, opPackageName, "authenticate()")) {
                Slog.e(TAG, "Denied by app ops: " + opPackageName);
                return;
            }

            if (!Utils.isForeground(callingUid, callingPid)) {
                Slog.e(TAG, "Caller is not foreground: " + opPackageName);
                return;
            }

            if (token == null || receiver == null || opPackageName == null || promptInfo == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return;
            }

            if (promptInfo.containsTestConfigurations()) {
                if (getContext().checkCallingOrSelfPermission(TEST_BIOMETRIC)
                        != PackageManager.PERMISSION_GRANTED) {
                    checkInternalPermission();
                }
            }

            // Only allow internal clients to enable non-public options.
            if (promptInfo.containsPrivateApiConfigurations()) {
                checkInternalPermission();
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mBiometricService.authenticate(
                        token, sessionId, userId, receiver, opPackageName, promptInfo);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void cancelAuthentication(IBinder token, String opPackageName)
                throws RemoteException {
            checkPermission();

            if (token == null || opPackageName == null) {
                Slog.e(TAG, "Unable to cancel authentication, one or more null arguments");
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mBiometricService.cancelAuthentication(token, opPackageName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int canAuthenticate(String opPackageName, int userId,
                @Authenticators.Types int authenticators) throws RemoteException {

            // Only allow internal clients to call canAuthenticate with a different userId.
            final int callingUserId = UserHandle.getCallingUserId();

            if (userId != callingUserId) {
                checkInternalPermission();
            } else {
                checkPermission();
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                final int result = mBiometricService.canAuthenticate(
                        opPackageName, userId, callingUserId, authenticators);
                Slog.d(TAG, "canAuthenticate"
                        + ", userId: " + userId
                        + ", callingUserId: " + callingUserId
                        + ", authenticators: " + authenticators
                        + ", result: " + result);
                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean hasEnrolledBiometrics(int userId, String opPackageName)
                throws RemoteException {
            checkInternalPermission();
            final long identity = Binder.clearCallingIdentity();
            try {
                return mBiometricService.hasEnrolledBiometrics(userId, opPackageName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void registerEnabledOnKeyguardCallback(
                IBiometricEnabledOnKeyguardCallback callback) throws RemoteException {
            checkInternalPermission();
            final int callingUserId = UserHandle.getCallingUserId();
            final long identity = Binder.clearCallingIdentity();
            try {
                mBiometricService.registerEnabledOnKeyguardCallback(callback, callingUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void invalidateAuthenticatorIds(int userId, int fromSensorId,
                IInvalidationCallback callback) throws RemoteException {
            checkInternalPermission();

            final long identity = Binder.clearCallingIdentity();
            try {
                mBiometricService.invalidateAuthenticatorIds(userId, fromSensorId, callback);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public long[] getAuthenticatorIds() throws RemoteException {
            // In this method, we're not checking whether the caller is permitted to use face
            // API because current authenticator ID is leaked (in a more contrived way) via Android
            // Keystore (android.security.keystore package): the user of that API can create a key
            // which requires face authentication for its use, and then query the key's
            // characteristics (hidden API) which returns, among other things, face
            // authenticator ID which was active at key creation time.
            //
            // Reason: The part of Android Keystore which runs inside an app's process invokes this
            // method in certain cases. Those cases are not always where the developer demonstrates
            // explicit intent to use biometric functionality. Thus, to avoiding throwing an
            // unexpected SecurityException this method does not check whether its caller is
            // permitted to use face API.
            //
            // The permission check should be restored once Android Keystore no longer invokes this
            // method from inside app processes.

            final int callingUserId = UserHandle.getCallingUserId();
            final long identity = Binder.clearCallingIdentity();
            try {
                return mBiometricService.getAuthenticatorIds(callingUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void resetLockoutTimeBound(IBinder token, String opPackageName, int fromSensorId,
                int userId, byte[] hardwareAuthToken) throws RemoteException {
            checkInternalPermission();

            final long identity = Binder.clearCallingIdentity();
            try {
                mBiometricService.resetLockoutTimeBound(token, opPackageName, fromSensorId, userId,
                        hardwareAuthToken);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public CharSequence getButtonLabel(
                int userId,
                String opPackageName,
                @Authenticators.Types int authenticators) throws RemoteException {

            // Only allow internal clients to call getButtonLabel with a different userId.
            final int callingUserId = UserHandle.getCallingUserId();

            if (userId != callingUserId) {
                checkInternalPermission();
            } else {
                checkPermission();
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                @BiometricAuthenticator.Modality final int modality =
                        mBiometricService.getCurrentModality(
                                opPackageName, userId, callingUserId, authenticators);

                final String result;
                switch (getCredentialBackupModality(modality)) {
                    case BiometricAuthenticator.TYPE_NONE:
                        result = null;
                        break;
                    case BiometricAuthenticator.TYPE_CREDENTIAL:
                        result = getContext().getString(R.string.screen_lock_app_setting_name);
                        break;
                    case BiometricAuthenticator.TYPE_FINGERPRINT:
                        result = getContext().getString(R.string.fingerprint_app_setting_name);
                        break;
                    case BiometricAuthenticator.TYPE_FACE:
                        result = getContext().getString(R.string.face_app_setting_name);
                        break;
                    default:
                        result = getContext().getString(R.string.biometric_app_setting_name);
                        break;
                }

                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public CharSequence getPromptMessage(
                int userId,
                String opPackageName,
                @Authenticators.Types int authenticators) throws RemoteException {

            // Only allow internal clients to call getButtonLabel with a different userId.
            final int callingUserId = UserHandle.getCallingUserId();

            if (userId != callingUserId) {
                checkInternalPermission();
            } else {
                checkPermission();
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                @BiometricAuthenticator.Modality final int modality =
                        mBiometricService.getCurrentModality(
                                opPackageName, userId, callingUserId, authenticators);

                final boolean isCredentialAllowed = Utils.isCredentialRequested(authenticators);

                final String result;
                switch (getCredentialBackupModality(modality)) {
                    case BiometricAuthenticator.TYPE_NONE:
                        result = null;
                        break;

                    case BiometricAuthenticator.TYPE_CREDENTIAL:
                        result = getContext().getString(
                                R.string.screen_lock_dialog_default_subtitle);
                        break;

                    case BiometricAuthenticator.TYPE_FINGERPRINT:
                        if (isCredentialAllowed) {
                            result = getContext().getString(
                                    R.string.fingerprint_or_screen_lock_dialog_default_subtitle);
                        } else {
                            result = getContext().getString(
                                    R.string.fingerprint_dialog_default_subtitle);
                        }
                        break;

                    case BiometricAuthenticator.TYPE_FACE:
                        if (isCredentialAllowed) {
                            result = getContext().getString(
                                    R.string.face_or_screen_lock_dialog_default_subtitle);
                        } else {
                            result = getContext().getString(R.string.face_dialog_default_subtitle);
                        }
                        break;

                    default:
                        if (isCredentialAllowed) {
                            result = getContext().getString(
                                    R.string.biometric_or_screen_lock_dialog_default_subtitle);
                        } else {
                            result = getContext().getString(
                                    R.string.biometric_dialog_default_subtitle);
                        }
                        break;
                }

                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public CharSequence getSettingName(
                int userId,
                String opPackageName,
                @Authenticators.Types int authenticators) throws RemoteException {

            // Only allow internal clients to call getButtonLabel with a different userId.
            final int callingUserId = UserHandle.getCallingUserId();

            if (userId != callingUserId) {
                checkInternalPermission();
            } else {
                checkPermission();
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                @BiometricAuthenticator.Modality final int modality =
                        mBiometricService.getSupportedModalities(authenticators);

                final String result;
                switch (modality) {
                    // Handle the case of a single supported modality.
                    case BiometricAuthenticator.TYPE_NONE:
                        result = null;
                        break;
                    case BiometricAuthenticator.TYPE_CREDENTIAL:
                        result = getContext().getString(R.string.screen_lock_app_setting_name);
                        break;
                    case BiometricAuthenticator.TYPE_IRIS:
                        result = getContext().getString(R.string.biometric_app_setting_name);
                        break;
                    case BiometricAuthenticator.TYPE_FINGERPRINT:
                        result = getContext().getString(R.string.fingerprint_app_setting_name);
                        break;
                    case BiometricAuthenticator.TYPE_FACE:
                        result = getContext().getString(R.string.face_app_setting_name);
                        break;

                    // Handle other possible modality combinations.
                    default:
                        if ((modality & BiometricAuthenticator.TYPE_CREDENTIAL) == 0) {
                            // 2+ biometric modalities are supported (but not device credential).
                            result = getContext().getString(R.string.biometric_app_setting_name);
                        } else {
                            @BiometricAuthenticator.Modality final int biometricModality =
                                    modality & ~BiometricAuthenticator.TYPE_CREDENTIAL;
                            if (biometricModality == BiometricAuthenticator.TYPE_FINGERPRINT) {
                                // Only device credential and fingerprint are supported.
                                result = getContext().getString(
                                        R.string.fingerprint_or_screen_lock_app_setting_name);
                            } else if (biometricModality == BiometricAuthenticator.TYPE_FACE) {
                                // Only device credential and face are supported.
                                result = getContext().getString(
                                        R.string.face_or_screen_lock_app_setting_name);
                            } else {
                                // Device credential and 1+ other biometric(s) are supported.
                                result = getContext().getString(
                                        R.string.biometric_or_screen_lock_app_setting_name);
                            }
                        }
                        break;
                }
                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public AuthService(Context context) {
        this(context, new Injector());
    }

    public AuthService(Context context, Injector injector) {
        super(context);

        mInjector = injector;
        mImpl = new AuthServiceImpl();
    }

    @Override
    public void onStart() {
        mBiometricService = mInjector.getBiometricService();

        if (!mInjector.isHidlDisabled(getContext())) {
            final String[] configs = mInjector.getConfiguration(getContext());
            for (String config : configs) {
                try {
                    registerAuthenticator(new SensorConfig(config));
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        }

        mInjector.publishBinderService(this, mImpl);
    }

    private void registerAuthenticator(SensorConfig config) throws RemoteException {
        Slog.d(TAG, "Registering ID: " + config.id
                + " Modality: " + config.modality
                + " Strength: " + config.strength);

        final IBiometricAuthenticator.Stub authenticator;

        switch (config.modality) {
            case TYPE_FINGERPRINT:
                final IFingerprintService fingerprintService = mInjector.getFingerprintService();
                if (fingerprintService == null) {
                    Slog.e(TAG, "Attempting to register with null FingerprintService."
                            + " Please check your device configuration.");
                    return;
                }

                // Initialize this outside of FingerprintAuthenticator. Only HIDL HALs require
                // initialization from here. AIDL HALs are initialized by FingerprintService since
                // the HAL interface provides ID, strength, and other configuration information.
                fingerprintService.initializeConfiguration(config.id, config.strength);
                authenticator = new FingerprintAuthenticator(fingerprintService, config.id);
                break;

            case TYPE_FACE:
                final IFaceService faceService = mInjector.getFaceService();
                if (faceService == null) {
                    Slog.e(TAG, "Attempting to register with null FaceService. Please check "
                            + " your device configuration.");
                    return;
                }

                // Initialize this outside of FingerprintAuthenticator. Only HIDL HALs require
                // initialization from here. AIDL HALs are initialized by FaceService since
                // the HAL interface provides ID, strength, and other configuration information.
                faceService.initializeConfiguration(config.id, config.strength);
                authenticator = new FaceAuthenticator(faceService, config.id);
                break;

            case TYPE_IRIS:
                final IIrisService irisService = mInjector.getIrisService();
                if (irisService == null) {
                    Slog.e(TAG, "Attempting to register with null IrisService. Please check"
                            + " your device configuration.");
                    return;
                }

                irisService.initializeConfiguration(config.id, config.strength);
                authenticator = new IrisAuthenticator(irisService, config.id);
                break;

            default:
                Slog.e(TAG, "Unknown modality: " + config.modality);
                return;
        }

        mBiometricService.registerAuthenticator(config.id, config.modality, config.strength,
                authenticator);
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

    private boolean checkAppOps(int uid, String opPackageName, String reason) {
        return mInjector.getAppOps(getContext()).noteOp(AppOpsManager.OP_USE_BIOMETRIC, uid,
                opPackageName, null /* attributionTag */, reason) == AppOpsManager.MODE_ALLOWED;
    }

    @BiometricAuthenticator.Modality
    private static int getCredentialBackupModality(@BiometricAuthenticator.Modality int modality) {
        return modality == BiometricAuthenticator.TYPE_CREDENTIAL
                ? modality : (modality & ~BiometricAuthenticator.TYPE_CREDENTIAL);
    }
}
