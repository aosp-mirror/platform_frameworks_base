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
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;
import static android.hardware.biometrics.BiometricManager.Authenticators;

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
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.net.Uri;
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
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * System service that arbitrates the modality for BiometricPrompt to use.
 */
public class BiometricService extends SystemService {

    static final String TAG = "BiometricService";

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
    private static final int MSG_ON_SYSTEM_EVENT = 13;

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

    // Get and cache the available biometric authenticators and their associated info.
    final ArrayList<BiometricSensor> mSensors = new ArrayList<>();

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
                            args.argi1 /* sensorId */,
                            (boolean) args.arg1 /* requireConfirmation */,
                            (byte[]) args.arg2 /* token */);
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
                            (long) args.arg2 /* operationId */,
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
                    handleCancelAuthentication();
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

                case MSG_ON_SYSTEM_EVENT: {
                    handleOnSystemEvent((int) msg.obj);
                    break;
                }

                default:
                    Slog.e(TAG, "Unknown message: " + msg);
                    break;
            }
        }
    };

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

    // Receives events from individual biometric sensors.
    @VisibleForTesting
    final IBiometricSensorReceiver mBiometricSensorReceiver = new IBiometricSensorReceiver.Stub() {
        @Override
        public void onAuthenticationSucceeded(int sensorId, boolean requireConfirmation,
                byte[] token) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = sensorId;
            args.arg1 = requireConfirmation;
            args.arg2 = token;
            mHandler.obtainMessage(MSG_ON_AUTHENTICATION_SUCCEEDED, args).sendToTarget();
        }

        @Override
        public void onAuthenticationFailed() {
            Slog.v(TAG, "onAuthenticationFailed");
            mHandler.obtainMessage(MSG_ON_AUTHENTICATION_REJECTED).sendToTarget();
        }

        @Override
        public void onError(int cookie, @BiometricAuthenticator.Modality int modality,
                @BiometricConstants.Errors int error, int vendorCode) {
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
        public void onAcquired(int acquiredInfo, String message) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = acquiredInfo;
            args.arg1 = message;
            mHandler.obtainMessage(MSG_ON_ACQUIRED, args).sendToTarget();
        }
    };

    final IBiometricSysuiReceiver mSysuiReceiver = new IBiometricSysuiReceiver.Stub() {
        @Override
        public void onDialogDismissed(int reason, @Nullable byte[] credentialAttestation) {
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

        @Override
        public void onSystemEvent(int event) {
            mHandler.obtainMessage(MSG_ON_SYSTEM_EVENT, event).sendToTarget();
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
        public void authenticate(IBinder token, long operationId, int userId,
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
            args.arg2 = operationId;
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

            mHandler.obtainMessage(MSG_CANCEL_AUTHENTICATION).sendToTarget();
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

            try {
                PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager,
                        mDevicePolicyManager, mSettingObserver, mSensors, userId, bundle,
                        opPackageName,
                        false /* checkDevicePolicyManager */);
                return preAuthInfo.getCanAuthenticateResult();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
            }
        }

        @Override
        public boolean hasEnrolledBiometrics(int userId, String opPackageName) {
            checkInternalPermission();

            try {
                for (BiometricSensor sensor : mSensors) {
                    if (sensor.impl.hasEnrolledTemplates(userId, opPackageName)) {
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

            for (BiometricSensor sensor : mSensors) {
                if (sensor.id == id) {
                    throw new IllegalStateException("Cannot register duplicate authenticator");
                }
            }

            // This happens infrequently enough, not worth caching.
            final String[] configs = mInjector.getConfiguration(getContext());
            boolean idFound = false;
            for (int i = 0; i < configs.length; i++) {
                SensorConfig config = new SensorConfig(configs[i]);
                if (config.id == id) {
                    idFound = true;
                    break;
                }
            }
            if (!idFound) {
                throw new IllegalStateException("Cannot register unknown id");
            }

            mSensors.add(new BiometricSensor(id, modality, strength, authenticator));

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
                for (BiometricSensor sensor : mSensors) {
                    sensor.impl.setActiveUser(userId);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }

        @Override // Binder call
        public void resetLockout(byte[] token) {
            checkInternalPermission();

            try {
                for (BiometricSensor sensor : mSensors) {
                    sensor.impl.resetLockout(token);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }

        @Override // Binder call
        public long[] getAuthenticatorIds() {
            checkInternalPermission();

            final List<Long> ids = new ArrayList<>();
            for (BiometricSensor sensor : mSensors) {
                try {
                    final long id = sensor.impl.getAuthenticatorId();
                    if (Utils.isAtLeastStrength(sensor.getCurrentStrength(),
                            Authenticators.BIOMETRIC_STRONG) && id != 0) {
                        ids.add(id);
                    } else {
                        Slog.d(TAG, "Sensor " + sensor + ", sensorId " + id
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

        @Override // Binder call
        public int getCurrentStrength(int sensorId) {
            checkInternalPermission();

            for (BiometricSensor sensor : mSensors) {
                if (sensor.id == sensorId) {
                    return sensor.getCurrentStrength();
                }
            }
            Slog.e(TAG, "Unknown sensorId: " + sensorId);
            return Authenticators.EMPTY_SET;
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

        public IActivityManager getActivityManagerService() {
            return ActivityManager.getService();
        }

        public ITrustManager getTrustManager() {
            return ITrustManager.Stub.asInterface(ServiceManager.getService(Context.TRUST_SERVICE));
        }

        public IStatusBarService getStatusBarService() {
            return IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        }

        /**
         * Allows to mock SettingObserver for testing.
         */
        public SettingObserver getSettingObserver(Context context, Handler handler,
                List<EnabledOnKeyguardCallback> callbacks) {
            return new SettingObserver(context, handler, callbacks);
        }

        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        /**
         * Allows to enable/disable debug logs.
         */
        public boolean isDebugEnabled(Context context, int userId) {
            return Utils.isDebugEnabled(context, userId);
        }

        /**
         * Allows to stub publishBinderService(...) for testing.
         */
        public void publishBinderService(BiometricService service, IBiometricService.Stub impl) {
            service.publishBinderService(Context.BIOMETRIC_SERVICE, impl);
        }

        /**
         * Allows to mock BiometricStrengthController for testing.
         */
        public BiometricStrengthController getBiometricStrengthController(
                BiometricService service) {
            return new BiometricStrengthController(service);
        }

        /**
         * Allows to test with various device sensor configurations.
         * @param context System Server context
         * @return the sensor configuration from core/res/res/values/config.xml
         */
        public String[] getConfiguration(Context context) {
            return context.getResources().getStringArray(R.array.config_biometric_sensors);
        }

        public DevicePolicyManager getDevicePolicyManager(Context context) {
            return context.getSystemService(DevicePolicyManager.class);
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
        mDevicePolicyManager = mInjector.getDevicePolicyManager(context);
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

    private boolean isStrongBiometric(int id) {
        for (BiometricSensor sensor : mSensors) {
            if (sensor.id == id) {
                return Utils.isAtLeastStrength(sensor.getCurrentStrength(),
                        Authenticators.BIOMETRIC_STRONG);
            }
        }
        Slog.e(TAG, "Unknown sensorId: " + id);
        return false;
    }

    private int biometricIdToModality(int id) {
        for (BiometricSensor sensor : mSensors) {
            if (sensor.id == id) {
                return sensor.modality;
            }
        }
        return TYPE_NONE;
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

        for (BiometricSensor sensor :
                mCurrentAuthSession.mPreAuthInfo.eligibleSensors) {
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

    private void handleAuthenticationSucceeded(int sensorId, boolean requireConfirmation,
            byte[] token) {
        try {
            // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
            // after user dismissed/canceled dialog).
            if (mCurrentAuthSession == null) {
                Slog.e(TAG, "handleAuthenticationSucceeded: Auth session is null");
                return;
            }

            if (isStrongBiometric(sensorId)) {
                // Store the auth token and submit it to keystore after the dialog is confirmed /
                // animating away.
                mCurrentAuthSession.mTokenEscrow = token;
            } else {
                if (token != null) {
                    Slog.w(TAG, "Dropping authToken for non-strong biometric, id: " + sensorId);
                }
            }

            if (!requireConfirmation) {
                mCurrentAuthSession.mState = AuthSession.STATE_AUTHENTICATED_PENDING_SYSUI;
            } else {
                mCurrentAuthSession.mAuthenticatedTimeMs = System.currentTimeMillis();
                mCurrentAuthSession.mState = AuthSession.STATE_AUTH_PENDING_CONFIRM;
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
            if (mCurrentAuthSession.hasPausableBiometric()) {
                // Pause authentication. onBiometricAuthenticated(false) causes the
                // dialog to show a "try again" button for passive modalities.
                mCurrentAuthSession.mState = AuthSession.STATE_AUTH_PAUSED;
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
            mCurrentAuthSession.mState = AuthSession.STATE_AUTH_PAUSED;
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    private void handleOnError(int cookie, @BiometricAuthenticator.Modality int modality,
            @BiometricConstants.Errors int error, int vendorCode) {
        Slog.d(TAG, "handleOnError: " + error + " cookie: " + cookie);
        // Errors can either be from the current auth session or the pending auth session.
        // The pending auth session may receive errors such as ERROR_LOCKOUT before
        // it becomes the current auth session. Similarly, the current auth session may
        // receive errors such as ERROR_CANCELED while the pending auth session is preparing
        // to be started. Thus we must match error messages with their cookies to be sure
        // of their intended receivers.

        // Update state if cookie matches, that the sensor is now stopped.
        // TODO: The sensor-specific state is not currently used, this would need to be updated if
        // multiple authenticators are running.
        if (mCurrentAuthSession != null) {
            mCurrentAuthSession.onErrorReceived(cookie, error);
        } else if (mPendingAuthSession != null) {
            mPendingAuthSession.onErrorReceived(cookie, error);
        }

        try {
            if (mCurrentAuthSession != null && mCurrentAuthSession.containsCookie(cookie)) {
                mCurrentAuthSession.mErrorEscrow = error;
                mCurrentAuthSession.mVendorCodeEscrow = vendorCode;

                if (mCurrentAuthSession.mState == AuthSession.STATE_AUTH_STARTED) {
                    final boolean errorLockout = error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                            || error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                    if (mCurrentAuthSession.isAllowDeviceCredential() && errorLockout) {
                        // SystemUI handles transition from biometric to device credential.
                        mCurrentAuthSession.mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;
                        mStatusBarService.onBiometricError(modality, error, vendorCode);
                    } else if (error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
                        mStatusBarService.hideAuthenticationDialog();
                        // TODO: If multiple authenticators are simultaneously running, this will
                        // need to be modified. Send the error to the client here, instead of doing
                        // a round trip to SystemUI.
                        mCurrentAuthSession.mClientReceiver.onError(modality, error, vendorCode);
                        mCurrentAuthSession = null;
                    } else {
                        mCurrentAuthSession.mState = AuthSession.STATE_ERROR_PENDING_SYSUI;
                        mStatusBarService.onBiometricError(modality, error, vendorCode);
                    }
                } else if (mCurrentAuthSession.mState == AuthSession.STATE_AUTH_PAUSED) {
                    // In the "try again" state, we should forward canceled errors to
                    // the client and and clean up. The only error we should get here is
                    // ERROR_CANCELED due to another client kicking us out.
                    mCurrentAuthSession.mClientReceiver.onError(modality, error, vendorCode);
                    mStatusBarService.hideAuthenticationDialog();
                    mCurrentAuthSession = null;
                } else if (mCurrentAuthSession.mState == AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL) {
                    Slog.d(TAG, "Biometric canceled, ignoring from state: "
                            + mCurrentAuthSession.mState);
                } else {
                    Slog.e(TAG, "Impossible session error state: "
                            + mCurrentAuthSession.mState);
                }
            } else if (mPendingAuthSession != null
                    && mPendingAuthSession.containsCookie(cookie)) {
                if (mPendingAuthSession.mState == AuthSession.STATE_AUTH_CALLED) {
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
                        mCurrentAuthSession.mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;
                        mPendingAuthSession = null;

                        mStatusBarService.showAuthenticationDialog(
                                mCurrentAuthSession.mBundle,
                                mSysuiReceiver,
                                0 /* biometricModality */,
                                false /* requireConfirmation */,
                                mCurrentAuthSession.mUserId,
                                mCurrentAuthSession.mOpPackageName,
                                mCurrentAuthSession.mOperationId);
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
                    cancelInternal(false /* fromClient */);
                    break;

                case BiometricPrompt.DISMISSED_REASON_USER_CANCEL:
                    mCurrentAuthSession.mClientReceiver.onError(
                            mCurrentAuthSession.getEligibleModalities(),
                            BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED,
                            0 /* vendorCode */
                    );
                    // Cancel authentication. Skip the token/package check since we are cancelling
                    // from system server. The interface is permission protected so this is fine.
                    cancelInternal(false /* fromClient */);
                    break;

                case BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED:
                case BiometricPrompt.DISMISSED_REASON_ERROR:
                    mCurrentAuthSession.mClientReceiver.onError(
                            mCurrentAuthSession.getEligibleModalities(),
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
        mCurrentAuthSession.setSensorsToStateUnknown();
        authenticateInternal(mCurrentAuthSession.mToken,
                mCurrentAuthSession.mOperationId,
                mCurrentAuthSession.mUserId,
                mCurrentAuthSession.mClientReceiver,
                mCurrentAuthSession.mOpPackageName,
                mCurrentAuthSession.mBundle,
                mCurrentAuthSession.mCallingUid,
                mCurrentAuthSession.mCallingPid,
                mCurrentAuthSession.mCallingUserId,
                mCurrentAuthSession.mPreAuthInfo);
    }

    private void handleOnDeviceCredentialPressed() {
        Slog.d(TAG, "onDeviceCredentialPressed");
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "Auth session null");
            return;
        }

        // Cancel authentication. Skip the token/package check since we are cancelling
        // from system server. The interface is permission protected so this is fine.
        cancelInternal(false /* fromClient */);

        mCurrentAuthSession.mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;
    }

    private void handleOnSystemEvent(int event) {
        final boolean shouldReceive = mCurrentAuthSession.mBundle
                .getBoolean(BiometricPrompt.KEY_RECEIVE_SYSTEM_EVENTS, false);
        Slog.d(TAG, "onSystemEvent: " + event + ", shouldReceive: " + shouldReceive);

        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "Auth session null");
            return;
        }

        if (!shouldReceive) {
            return;
        }

        try {
            mCurrentAuthSession.mClientReceiver.onSystemEvent(event);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
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

        mPendingAuthSession.onCookieReceived(cookie);

        if (mPendingAuthSession.allCookiesReceived()) {
            final boolean continuing = mCurrentAuthSession != null
                    && mCurrentAuthSession.mState == AuthSession.STATE_AUTH_PAUSED;

            mCurrentAuthSession = mPendingAuthSession;

            // Time starts when lower layers are ready to start the client.
            mCurrentAuthSession.mStartTimeMs = System.currentTimeMillis();
            mPendingAuthSession = null;

            mCurrentAuthSession.mState = AuthSession.STATE_AUTH_STARTED;
            mCurrentAuthSession.startAllPreparedSensors();

            if (!continuing) {
                try {
                    final @BiometricAuthenticator.Modality int modality =
                            mCurrentAuthSession.getEligibleModalities();
                    mStatusBarService.showAuthenticationDialog(mCurrentAuthSession.mBundle,
                            mSysuiReceiver, modality, requireConfirmation, userId,
                            mCurrentAuthSession.mOpPackageName,
                            mCurrentAuthSession.mOperationId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        }
    }

    private void handleAuthenticate(IBinder token, long operationId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle,
            int callingUid, int callingPid, int callingUserId) {

        mHandler.post(() -> {
            try {
                final boolean checkDevicePolicyManager = bundle.getBoolean(
                        BiometricPrompt.EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS, false);
                final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager,
                        mDevicePolicyManager, mSettingObserver, mSensors, userId, bundle,
                        opPackageName, checkDevicePolicyManager);

                final Pair<Integer, Integer> preAuthStatus = preAuthInfo.getPreAuthenticateStatus();

                Slog.d(TAG, "handleAuthenticate: modality(" + preAuthStatus.first
                        + "), status(" + preAuthStatus.second + ")");

                if (preAuthStatus.second == BiometricConstants.BIOMETRIC_SUCCESS) {
                    // If BIOMETRIC_WEAK or BIOMETRIC_STRONG are allowed, but not enrolled, but
                    // CREDENTIAL is requested and available, set the bundle to only request
                    // CREDENTIAL.
                    // TODO: We should clean this up, as well as the interface with SystemUI
                    if (preAuthInfo.credentialRequested && preAuthInfo.credentialAvailable
                            && preAuthInfo.eligibleSensors.isEmpty()) {
                        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED,
                                Authenticators.DEVICE_CREDENTIAL);
                    }

                    authenticateInternal(token, operationId, userId, receiver, opPackageName,
                            bundle, callingUid, callingPid, callingUserId, preAuthInfo);
                } else {
                    receiver.onError(preAuthStatus.first /* modality */,
                            preAuthStatus.second /* errorCode */,
                            0 /* vendorCode */);
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
    private void authenticateInternal(IBinder token, long operationId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle,
            int callingUid, int callingPid, int callingUserId, PreAuthInfo preAuthInfo) {
        boolean requireConfirmation = bundle.getBoolean(
                BiometricPrompt.KEY_REQUIRE_CONFIRMATION, true /* default */);
        // Assume at this point that if a sensor is contained in the eligible list, it will be
        // used for authentication and presented to the user.
        for (BiometricSensor sensor : preAuthInfo.eligibleSensors) {
            if (biometricIdToModality(sensor.id) == TYPE_FACE) {
                // Check if the user has forced confirmation to be required in Settings.
                requireConfirmation = requireConfirmation
                        || mSettingObserver.getFaceAlwaysRequireConfirmation(userId);
            }
        }

        Slog.d(TAG, "Creating authSession with authRequest: " + preAuthInfo);

        mPendingAuthSession = new AuthSession(mRandom, preAuthInfo, token, operationId, userId,
                mBiometricSensorReceiver, receiver, opPackageName, bundle, callingUid, callingPid,
                callingUserId, requireConfirmation);

        try {
            if (preAuthInfo.credentialRequested
                    && preAuthInfo.eligibleSensors.isEmpty()) {
                // Only device credential should be shown. In this case, we don't need to wait,
                // since LockSettingsService/Gatekeeper is always ready to check for credential.
                // SystemUI invokes that path.
                mPendingAuthSession.mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;
                mCurrentAuthSession = mPendingAuthSession;
                mPendingAuthSession = null;

                mStatusBarService.showAuthenticationDialog(
                        mCurrentAuthSession.mBundle,
                        mSysuiReceiver,
                        0 /* biometricModality */,
                        false /* requireConfirmation */,
                        mCurrentAuthSession.mUserId,
                        mCurrentAuthSession.mOpPackageName,
                        operationId);
            } else if (!preAuthInfo.eligibleSensors.isEmpty()) {
                // Some combination of biometric or biometric|credential is requested
                mPendingAuthSession.mState = AuthSession.STATE_AUTH_CALLED;
                mPendingAuthSession.prepareAllSensorsForAuthentication();
            } else {
                // No authenticators requested. This should never happen - an exception should have
                // been thrown earlier in the pipeline.
                throw new IllegalStateException("No authenticators requested");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to start authentication", e);
        }
    }

    private void handleCancelAuthentication() {
        if (mCurrentAuthSession != null
                && mCurrentAuthSession.mState != AuthSession.STATE_AUTH_STARTED) {
            // We need to check the current authenticators state. If we're pending confirm
            // or idle, we need to dismiss the dialog and send an ERROR_CANCELED to the client,
            // since we won't be getting an onError from the driver.
            try {
                // Send error to client
                mCurrentAuthSession.mClientReceiver.onError(
                        mCurrentAuthSession.getEligibleModalities(),
                        BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                        0 /* vendorCode */
                );
                mCurrentAuthSession = null;
                mStatusBarService.hideAuthenticationDialog();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        } else {
            cancelInternal(true /* fromClient */);
        }
    }

    private void cancelInternal(boolean fromClient) {
        if (mCurrentAuthSession == null) {
            Slog.w(TAG, "Skipping cancelInternal");
            return;
        } else if (mCurrentAuthSession.mState != AuthSession.STATE_AUTH_STARTED) {
            Slog.w(TAG, "Skipping cancelInternal, state: " + mCurrentAuthSession.mState);
            return;
        }

        mCurrentAuthSession.cancelAllSensors(fromClient);
    }
}
