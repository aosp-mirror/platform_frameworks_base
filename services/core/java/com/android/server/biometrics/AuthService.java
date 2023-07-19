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
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import static com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider.getWorkaroundSensorProps;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IAuthService;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.iris.IIrisService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * System service that provides an interface for authenticating with biometrics and
 * PIN/pattern/password to BiometricPrompt and lock screen.
 */
public class AuthService extends SystemService {
    private static final String TAG = "AuthService";
    private static final String SETTING_HIDL_DISABLED =
            "com.android.server.biometrics.AuthService.hidlDisabled";
    private static final int DEFAULT_HIDL_DISABLED = 0;
    private static final String SYSPROP_FIRST_API_LEVEL = "ro.board.first_api_level";
    private static final String SYSPROP_API_LEVEL = "ro.board.api_level";

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
        public long authenticate(IBinder token, long sessionId, int userId,
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
                authenticateFastFail("Denied by app ops: " + opPackageName, receiver);
                return -1;
            }

            if (token == null || receiver == null || opPackageName == null || promptInfo == null) {
                authenticateFastFail(
                        "Unable to authenticate, one or more null arguments", receiver);
                return -1;
            }

            if (!Utils.isForeground(callingUid, callingPid)) {
                authenticateFastFail("Caller is not foreground: " + opPackageName, receiver);
                return -1;
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
                return mBiometricService.authenticate(
                        token, sessionId, userId, receiver, opPackageName, promptInfo);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void authenticateFastFail(String message, IBiometricServiceReceiver receiver) {
            // notify caller in cases where authentication is aborted before calling into
            // IBiometricService without raising an exception
            Slog.e(TAG, "authenticateFastFail: " + message);
            try {
                receiver.onError(TYPE_NONE, BIOMETRIC_ERROR_CANCELED, 0 /*vendorCode */);
            } catch (RemoteException e) {
                Slog.e(TAG, "authenticateFastFail failed to notify caller", e);
            }
        }

        @Override
        public void cancelAuthentication(IBinder token, String opPackageName, long requestId)
                throws RemoteException {
            checkPermission();

            if (token == null || opPackageName == null) {
                Slog.e(TAG, "Unable to cancel authentication, one or more null arguments");
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mBiometricService.cancelAuthentication(token, opPackageName, requestId);
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
        public long[] getAuthenticatorIds(int userId) throws RemoteException {
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
            if (userId != callingUserId) {
                getContext().enforceCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL,
                        "Must have " + USE_BIOMETRIC_INTERNAL + " permission.");
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return mBiometricService.getAuthenticatorIds(userId);
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


    /**
     * Registration of all HIDL and AIDL biometric HALs starts here.
     * The flow looks like this:
     * AuthService
     * └── .onStart()
     *     └── .registerAuthenticators(...)
     *         ├── FaceService.registerAuthenticators(...)
     *         │   └── for (p : serviceProviders)
     *         │       └── for (s : p.sensors)
     *         │           └── BiometricService.registerAuthenticator(s)
     *         │
     *         ├── FingerprintService.registerAuthenticators(...)
     *         │   └── for (p : serviceProviders)
     *         │       └── for (s : p.sensors)
     *         │           └── BiometricService.registerAuthenticator(s)
     *         │
     *         └── IrisService.registerAuthenticators(...)
     *             └── for (p : serviceProviders)
     *                 └── for (s : p.sensors)
     *                     └── BiometricService.registerAuthenticator(s)
     */
    @Override
    public void onStart() {
        mBiometricService = mInjector.getBiometricService();

        final SensorConfig[] hidlConfigs;
        if (!mInjector.isHidlDisabled(getContext())) {
            final int firstApiLevel = SystemProperties.getInt(SYSPROP_FIRST_API_LEVEL, 0);
            final int apiLevel = SystemProperties.getInt(SYSPROP_API_LEVEL, firstApiLevel);
            String[] configStrings = mInjector.getConfiguration(getContext());
            if (configStrings.length == 0 && apiLevel == Build.VERSION_CODES.R) {
                // For backwards compatibility with R where biometrics could work without being
                // configured in config_biometric_sensors. In the absence of a vendor provided
                // configuration, we assume the weakest biometric strength (i.e. convenience).
                Slog.w(TAG, "Found R vendor partition without config_biometric_sensors");
                configStrings = generateRSdkCompatibleConfiguration();
            }
            hidlConfigs = new SensorConfig[configStrings.length];
            for (int i = 0; i < configStrings.length; ++i) {
                hidlConfigs[i] = new SensorConfig(configStrings[i]);
            }
        } else {
            hidlConfigs = null;
        }

        // Registers HIDL and AIDL authenticators, but only HIDL configs need to be provided.
        registerAuthenticators(hidlConfigs);

        mInjector.publishBinderService(this, mImpl);
    }

    /**
     * Generates an array of string configs with entries that correspond to the biometric features
     * declared on the device. Returns an empty array if no biometric features are declared.
     * Biometrics are assumed to be of the weakest strength class, i.e. convenience.
     */
    private @NonNull String[] generateRSdkCompatibleConfiguration() {
        final PackageManager pm = getContext().getPackageManager();
        final ArrayList<String> modalities = new ArrayList<>();
        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            modalities.add(String.valueOf(BiometricAuthenticator.TYPE_FINGERPRINT));
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            modalities.add(String.valueOf(BiometricAuthenticator.TYPE_FACE));
        }
        final String strength = String.valueOf(Authenticators.BIOMETRIC_CONVENIENCE);
        final String[] configStrings = new String[modalities.size()];
        for (int i = 0; i < modalities.size(); ++i) {
            final String id = String.valueOf(i);
            final String modality = modalities.get(i);
            configStrings[i] = String.join(":" /* delimiter */, id, modality, strength);
        }
        Slog.d(TAG, "Generated config_biometric_sensors: " + Arrays.toString(configStrings));
        return configStrings;
    }

    /**
     * Registers HIDL and AIDL authenticators for all of the available modalities.
     *
     * @param hidlSensors Array of {@link SensorConfig} configuration for all of the HIDL sensors
     *                    available on the device. This array may contain configuration for
     *                    different modalities and different sensors of the same modality in
     *                    arbitrary order. Can be null if no HIDL sensors exist on the device.
     */
    private void registerAuthenticators(@Nullable SensorConfig[] hidlSensors) {
        List<FingerprintSensorPropertiesInternal> hidlFingerprintSensors = new ArrayList<>();
        List<FaceSensorPropertiesInternal> hidlFaceSensors = new ArrayList<>();
        // Iris doesn't have IrisSensorPropertiesInternal, using SensorPropertiesInternal instead.
        List<SensorPropertiesInternal> hidlIrisSensors = new ArrayList<>();

        if (hidlSensors != null) {
            for (SensorConfig sensor : hidlSensors) {
                Slog.d(TAG, "Registering HIDL ID: " + sensor.id + " Modality: " + sensor.modality
                        + " Strength: " + sensor.strength);
                switch (sensor.modality) {
                    case TYPE_FINGERPRINT:
                        hidlFingerprintSensors.add(
                                getHidlFingerprintSensorProps(sensor.id, sensor.strength));
                        break;

                    case TYPE_FACE:
                        hidlFaceSensors.add(getHidlFaceSensorProps(sensor.id, sensor.strength));
                        break;

                    case TYPE_IRIS:
                        hidlIrisSensors.add(getHidlIrisSensorProps(sensor.id, sensor.strength));
                        break;

                    default:
                        Slog.e(TAG, "Unknown modality: " + sensor.modality);
                }
            }
        }

        final IFingerprintService fingerprintService = mInjector.getFingerprintService();
        if (fingerprintService != null) {
            try {
                fingerprintService.registerAuthenticators(hidlFingerprintSensors);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException when registering fingerprint authenticators", e);
            }
        } else if (hidlFingerprintSensors.size() > 0) {
            Slog.e(TAG, "HIDL fingerprint configuration exists, but FingerprintService is null.");
        }

        final IFaceService faceService = mInjector.getFaceService();
        if (faceService != null) {
            try {
                faceService.registerAuthenticators(hidlFaceSensors);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException when registering face authenticators", e);
            }
        } else if (hidlFaceSensors.size() > 0) {
            Slog.e(TAG, "HIDL face configuration exists, but FaceService is null.");
        }

        final IIrisService irisService = mInjector.getIrisService();
        if (irisService != null) {
            try {
                irisService.registerAuthenticators(hidlIrisSensors);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException when registering iris authenticators", e);
            }
        } else if (hidlIrisSensors.size() > 0) {
            Slog.e(TAG, "HIDL iris configuration exists, but IrisService is null.");
        }
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


    private FingerprintSensorPropertiesInternal getHidlFingerprintSensorProps(int sensorId,
            @BiometricManager.Authenticators.Types int strength) {
        // The existence of config_udfps_sensor_props indicates that the sensor is UDFPS.
        final int[] udfpsProps = getContext().getResources().getIntArray(
                com.android.internal.R.array.config_udfps_sensor_props);

        // Non-empty workaroundLocations indicates that the sensor is SFPS.
        final List<SensorLocationInternal> workaroundLocations =
                getWorkaroundSensorProps(getContext());

        final boolean isUdfps = !ArrayUtils.isEmpty(udfpsProps);

        // config_is_powerbutton_fps indicates whether device has a power button fingerprint sensor.
        final boolean isPowerbuttonFps = getContext().getResources().getBoolean(
                R.bool.config_is_powerbutton_fps);

        final @FingerprintSensorProperties.SensorType int sensorType;
        if (isUdfps) {
            sensorType = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;
        } else if (isPowerbuttonFps) {
            sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON;
        } else {
            sensorType = FingerprintSensorProperties.TYPE_REAR;
        }

        // IBiometricsFingerprint@2.1 does not manage timeout below the HAL, so the Gatekeeper HAT
        // cannot be checked.
        final boolean resetLockoutRequiresHardwareAuthToken = false;
        final int maxEnrollmentsPerUser = getContext().getResources().getInteger(
                R.integer.config_fingerprintMaxTemplatesPerUser);

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        if (isUdfps && udfpsProps.length == 3) {
            return new FingerprintSensorPropertiesInternal(sensorId,
                    Utils.authenticatorStrengthToPropertyStrength(strength), maxEnrollmentsPerUser,
                    componentInfo, sensorType, true /* halControlsIllumination */,
                    resetLockoutRequiresHardwareAuthToken,
                    List.of(new SensorLocationInternal("" /* display */, udfpsProps[0],
                            udfpsProps[1], udfpsProps[2])));
        } else if (!workaroundLocations.isEmpty()) {
            return new FingerprintSensorPropertiesInternal(sensorId,
                    Utils.authenticatorStrengthToPropertyStrength(strength), maxEnrollmentsPerUser,
                    componentInfo, sensorType, true /* halControlsIllumination */,
                    resetLockoutRequiresHardwareAuthToken,
                    workaroundLocations);
        } else {
            return new FingerprintSensorPropertiesInternal(sensorId,
                    Utils.authenticatorStrengthToPropertyStrength(strength), maxEnrollmentsPerUser,
                    componentInfo, sensorType, resetLockoutRequiresHardwareAuthToken);
        }
    }

    private FaceSensorPropertiesInternal getHidlFaceSensorProps(int sensorId,
            @BiometricManager.Authenticators.Types int strength) {
        final boolean supportsSelfIllumination = getContext().getResources().getBoolean(
                R.bool.config_faceAuthSupportsSelfIllumination);
        final int maxTemplatesAllowed = getContext().getResources().getInteger(
                R.integer.config_faceMaxTemplatesPerUser);
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        final boolean supportsFaceDetect = false;
        final boolean resetLockoutRequiresChallenge = true;
        return new FaceSensorPropertiesInternal(sensorId,
                Utils.authenticatorStrengthToPropertyStrength(strength), maxTemplatesAllowed,
                componentInfo, FaceSensorProperties.TYPE_UNKNOWN, supportsFaceDetect,
                supportsSelfIllumination, resetLockoutRequiresChallenge);
    }

    private SensorPropertiesInternal getHidlIrisSensorProps(int sensorId,
            @BiometricManager.Authenticators.Types int strength) {
        final int maxEnrollmentsPerUser = 1;
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        final boolean resetLockoutRequiresHardwareAuthToken = false;
        final boolean resetLockoutRequiresChallenge = false;
        return new SensorPropertiesInternal(sensorId,
                Utils.authenticatorStrengthToPropertyStrength(strength), maxEnrollmentsPerUser,
                componentInfo, resetLockoutRequiresHardwareAuthToken,
                resetLockoutRequiresChallenge);
    }
}
