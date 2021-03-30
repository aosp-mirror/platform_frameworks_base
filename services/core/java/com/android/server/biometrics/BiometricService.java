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
import static android.hardware.biometrics.BiometricManager.Authenticators;

import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_IDLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.UserSwitchObserver;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.net.Uri;
import android.os.Binder;
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
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
    private static final int MSG_CLIENT_DIED = 14;
    private static final int MSG_ON_DIALOG_ANIMATED_IN = 15;

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

    // The current authentication session, null if idle/done.
    @VisibleForTesting
    AuthSession mCurrentAuthSession;

    @VisibleForTesting
    final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_AUTHENTICATION_SUCCEEDED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleAuthenticationSucceeded(
                            args.argi1 /* sensorId */,
                            (byte[]) args.arg1 /* token */);
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
                            args.argi1 /* sensorId */,
                            args.argi2 /* cookie */,
                            args.argi3 /* error */,
                            args.argi4 /* vendorCode */);
                    args.recycle();
                    break;
                }

                case MSG_ON_ACQUIRED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleOnAcquired(
                            args.argi1 /* sensorId */,
                            args.argi2 /* acquiredInfo */,
                            args.argi3 /* vendorCode */);
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
                            args.argi1 /* cookie */);
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
                            (PromptInfo) args.arg5 /* promptInfo */);
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
                            args.argi1 /* sensorId */,
                            args.argi2 /* cookie */,
                            args.argi3 /* error */,
                            args.argi4 /* vendorCode */);
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

                case MSG_CLIENT_DIED: {
                    handleClientDied();
                    break;
                }

                case MSG_ON_DIALOG_ANIMATED_IN: {
                    handleOnDialogAnimatedIn();
                    break;
                }

                default:
                    Slog.e(TAG, "Unknown message: " + msg);
                    break;
            }
        }
    };

    /**
     * Tracks authenticatorId invalidation. For more details, see
     * {@link com.android.server.biometrics.sensors.InvalidationRequesterClient}.
     */
    @VisibleForTesting
    static class InvalidationTracker {
        @NonNull private final IInvalidationCallback mClientCallback;
        @NonNull private final Set<Integer> mSensorsPendingInvalidation;

        public static InvalidationTracker start(@NonNull Context context,
                @NonNull ArrayList<BiometricSensor> sensors,
                int userId, int fromSensorId, @NonNull IInvalidationCallback clientCallback) {
            return new InvalidationTracker(context, sensors, userId, fromSensorId, clientCallback);
        }

        private InvalidationTracker(@NonNull Context context,
                @NonNull ArrayList<BiometricSensor> sensors, int userId,
                int fromSensorId, @NonNull IInvalidationCallback clientCallback) {
            mClientCallback = clientCallback;
            mSensorsPendingInvalidation = new ArraySet<>();

            for (BiometricSensor sensor : sensors) {
                if (sensor.id == fromSensorId) {
                    continue;
                }

                if (!Utils.isAtLeastStrength(sensor.oemStrength, Authenticators.BIOMETRIC_STRONG)) {
                    continue;
                }

                try {
                    if (!sensor.impl.hasEnrolledTemplates(userId, context.getOpPackageName())) {
                        continue;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote Exception", e);
                }

                Slog.d(TAG, "Requesting authenticatorId invalidation for sensor: " + sensor.id);

                synchronized (this) {
                    mSensorsPendingInvalidation.add(sensor.id);
                }

                try {
                    sensor.impl.invalidateAuthenticatorId(userId, new IInvalidationCallback.Stub() {
                        @Override
                        public void onCompleted() {
                            onInvalidated(sensor.id);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.d(TAG, "RemoteException", e);
                }
            }

            synchronized (this) {
                if (mSensorsPendingInvalidation.isEmpty()) {
                    try {
                        Slog.d(TAG, "No sensors require invalidation");
                        mClientCallback.onCompleted();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote Exception", e);
                    }
                }
            }
        }

        @VisibleForTesting
        void onInvalidated(int sensorId) {
            synchronized (this) {
                mSensorsPendingInvalidation.remove(sensorId);

                Slog.d(TAG, "Sensor " + sensorId + " invalidated, remaining size: "
                        + mSensorsPendingInvalidation.size());

                if (mSensorsPendingInvalidation.isEmpty()) {
                    try {
                        mClientCallback.onCompleted();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote Exception", e);
                    }
                }
            }
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

        public boolean getConfirmationAlwaysRequired(@BiometricAuthenticator.Modality int modality,
                int userId) {
            switch (modality) {
                case BiometricAuthenticator.TYPE_FACE:
                    if (!mFaceAlwaysRequireConfirmation.containsKey(userId)) {
                        onChange(true /* selfChange */,
                                FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
                                userId);
                    }
                    return mFaceAlwaysRequireConfirmation.get(userId);

                default:
                    return false;
            }
        }

        void notifyEnabledOnKeyguardCallbacks(int userId) {
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
        public void onAuthenticationSucceeded(int sensorId, byte[] token) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = sensorId;
            args.arg1 = token;
            mHandler.obtainMessage(MSG_ON_AUTHENTICATION_SUCCEEDED, args).sendToTarget();
        }

        @Override
        public void onAuthenticationFailed(int sensorId) {
            Slog.v(TAG, "onAuthenticationFailed");
            mHandler.obtainMessage(MSG_ON_AUTHENTICATION_REJECTED).sendToTarget();
        }

        @Override
        public void onError(int sensorId, int cookie, @BiometricConstants.Errors int error,
                int vendorCode) {
            // Determine if error is hard or soft error. Certain errors (such as TIMEOUT) are
            // soft errors and we should allow the user to try authenticating again instead of
            // dismissing BiometricPrompt.
            if (error == BiometricConstants.BIOMETRIC_ERROR_TIMEOUT) {
                SomeArgs args = SomeArgs.obtain();
                args.argi1 = sensorId;
                args.argi2 = cookie;
                args.argi3 = error;
                args.argi4 = vendorCode;
                mHandler.obtainMessage(MSG_ON_AUTHENTICATION_TIMED_OUT, args).sendToTarget();
            } else {
                SomeArgs args = SomeArgs.obtain();
                args.argi1 = sensorId;
                args.argi2 = cookie;
                args.argi3 = error;
                args.argi4 = vendorCode;
                mHandler.obtainMessage(MSG_ON_ERROR, args).sendToTarget();
            }
        }

        @Override
        public void onAcquired(int sensorId, int acquiredInfo, int vendorCode) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = sensorId;
            args.argi2 = acquiredInfo;
            args.argi3 = vendorCode;
            mHandler.obtainMessage(MSG_ON_ACQUIRED, args).sendToTarget();
        }
    };

    final IBiometricSysuiReceiver mSysuiReceiver = new IBiometricSysuiReceiver.Stub() {
        @Override
        public void onDialogDismissed(@BiometricPrompt.DismissedReason int reason,
                @Nullable byte[] credentialAttestation) {
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

        @Override
        public void onDialogAnimatedIn() {
            mHandler.obtainMessage(MSG_ON_DIALOG_ANIMATED_IN).sendToTarget();
        }
    };

    private final AuthSession.ClientDeathReceiver mClientDeathReceiver = () -> {
        mHandler.sendEmptyMessage(MSG_CLIENT_DIED);
    };

    /**
     * Implementation of the BiometricPrompt/BiometricManager APIs. Handles client requests,
     * sensor arbitration, threading, etc.
     */
    private final class BiometricServiceWrapper extends IBiometricService.Stub {
        @Override // Binder call
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) throws RemoteException {
            checkInternalPermission();

            for (BiometricSensor sensor : mSensors) {
                if (sensor.id == sensorId) {
                    return sensor.impl.createTestSession(callback, opPackageName);
                }
            }

            Slog.e(TAG, "Unknown sensor for createTestSession: " + sensorId);
            return null;
        }

        @Override // Binder call
        public List<SensorPropertiesInternal> getSensorProperties(String opPackageName)
                throws RemoteException {
            checkInternalPermission();

            final List<SensorPropertiesInternal> sensors = new ArrayList<>();
            for (BiometricSensor sensor : mSensors) {
                // Explicitly re-create as the super class, since AIDL doesn't play nicely with
                // "List<? extends SensorPropertiesInternal> ...
                final SensorPropertiesInternal prop = SensorPropertiesInternal
                        .from(sensor.impl.getSensorProperties(opPackageName));
                sensors.add(prop);
            }

            return sensors;
        }

        @Override // Binder call
        public void onReadyForAuthentication(int cookie) {
            checkInternalPermission();

            SomeArgs args = SomeArgs.obtain();
            args.argi1 = cookie;
            mHandler.obtainMessage(MSG_ON_READY_FOR_AUTHENTICATION, args).sendToTarget();
        }

        @Override // Binder call
        public void authenticate(IBinder token, long operationId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo) {
            checkInternalPermission();

            if (token == null || receiver == null || opPackageName == null || promptInfo == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return;
            }

            if (!Utils.isValidAuthenticatorConfig(promptInfo)) {
                throw new SecurityException("Invalid authenticator configuration");
            }

            Utils.combineAuthenticatorBundles(promptInfo);

            // Set the default title if necessary.
            if (promptInfo.isUseDefaultTitle()) {
                if (TextUtils.isEmpty(promptInfo.getTitle())) {
                    promptInfo.setTitle(getContext()
                            .getString(R.string.biometric_dialog_default_title));
                }
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = token;
            args.arg2 = operationId;
            args.argi1 = userId;
            args.arg3 = receiver;
            args.arg4 = opPackageName;
            args.arg5 = promptInfo;

            mHandler.obtainMessage(MSG_AUTHENTICATE, args).sendToTarget();
        }

        @Override // Binder call
        public void cancelAuthentication(IBinder token, String opPackageName) {
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

            try {
                final PreAuthInfo preAuthInfo =
                        createPreAuthInfo(opPackageName, userId, authenticators);
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
        public synchronized void registerAuthenticator(int id, int modality,
                @Authenticators.Types int strength,
                @NonNull IBiometricAuthenticator authenticator) {
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

            mSensors.add(new BiometricSensor(id, modality, strength, authenticator) {
                @Override
                boolean confirmationAlwaysRequired(int userId) {
                    return mSettingObserver.getConfirmationAlwaysRequired(modality, userId);
                }

                @Override
                boolean confirmationSupported() {
                    return Utils.isConfirmationSupported(modality);
                }
            });

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
        public void invalidateAuthenticatorIds(int userId, int fromSensorId,
                IInvalidationCallback callback) {
            checkInternalPermission();

            InvalidationTracker.start(getContext(), mSensors, userId, fromSensorId, callback);
        }

        @Override // Binder call
        public long[] getAuthenticatorIds(int callingUserId) {
            checkInternalPermission();

            final List<Long> authenticatorIds = new ArrayList<>();
            for (BiometricSensor sensor : mSensors) {
                try {
                    final boolean hasEnrollments = sensor.impl.hasEnrolledTemplates(callingUserId,
                            getContext().getOpPackageName());
                    final long authenticatorId = sensor.impl.getAuthenticatorId(callingUserId);
                    if (hasEnrollments && Utils.isAtLeastStrength(sensor.getCurrentStrength(),
                            Authenticators.BIOMETRIC_STRONG)) {
                        authenticatorIds.add(authenticatorId);
                    } else {
                        Slog.d(TAG, "Sensor " + sensor + ", sensorId " + sensor.id
                                + ", hasEnrollments: " + hasEnrollments
                                + " cannot participate in Keystore operations");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "RemoteException", e);
                }
            }

            long[] result = new long[authenticatorIds.size()];
            for (int i = 0; i < authenticatorIds.size(); i++) {
                result[i] = authenticatorIds.get(i);
            }
            return result;
        }

        @Override // Binder call
        public void resetLockoutTimeBound(IBinder token, String opPackageName, int fromSensorId,
                int userId, byte[] hardwareAuthToken) {
            checkInternalPermission();

            // Check originating strength
            if (!Utils.isAtLeastStrength(getSensorForId(fromSensorId).getCurrentStrength(),
                    Authenticators.BIOMETRIC_STRONG)) {
                Slog.w(TAG, "Sensor: " + fromSensorId + " is does not meet the required strength to"
                        + " request resetLockout");
                return;
            }

            // Request resetLockout for applicable sensors
            for (BiometricSensor sensor : mSensors) {
                if (sensor.id == fromSensorId) {
                    continue;
                }
                try {
                    final SensorPropertiesInternal props = sensor.impl
                            .getSensorProperties(getContext().getOpPackageName());
                    final boolean supportsChallengelessHat =
                            props.resetLockoutRequiresHardwareAuthToken
                            && !props.resetLockoutRequiresChallenge;
                    final boolean doesNotRequireHat = !props.resetLockoutRequiresHardwareAuthToken;

                    if (supportsChallengelessHat || doesNotRequireHat) {
                        Slog.d(TAG, "resetLockout from: " + fromSensorId
                                + ", for: " + sensor.id
                                + ", userId: " + userId);
                        sensor.impl.resetLockout(token, opPackageName, userId,
                                hardwareAuthToken);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
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

        @Override // Binder call
        public int getCurrentModality(
                String opPackageName,
                int userId,
                int callingUserId,
                @Authenticators.Types int authenticators) {

            checkInternalPermission();

            Slog.d(TAG, "getCurrentModality: User=" + userId
                    + ", Caller=" + callingUserId
                    + ", Authenticators=" + authenticators);

            if (!Utils.isValidAuthenticatorConfig(authenticators)) {
                throw new SecurityException("Invalid authenticator configuration");
            }

            try {
                final PreAuthInfo preAuthInfo =
                        createPreAuthInfo(opPackageName, userId, authenticators);
                return preAuthInfo.getPreAuthenticateStatus().first;
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                return BiometricAuthenticator.TYPE_NONE;
            }
        }

        @Override // Binder call
        public int getSupportedModalities(@Authenticators.Types int authenticators) {
            checkInternalPermission();

            Slog.d(TAG, "getSupportedModalities: Authenticators=" + authenticators);

            if (!Utils.isValidAuthenticatorConfig(authenticators)) {
                throw new SecurityException("Invalid authenticator configuration");
            }

            @BiometricAuthenticator.Modality int modality =
                    Utils.isCredentialRequested(authenticators)
                            ? BiometricAuthenticator.TYPE_CREDENTIAL
                            : BiometricAuthenticator.TYPE_NONE;

            if (Utils.isBiometricRequested(authenticators)) {
                @Authenticators.Types final int requestedStrength =
                        Utils.getPublicBiometricStrength(authenticators);

                // Add modalities of all biometric sensors that meet the authenticator requirements.
                for (final BiometricSensor sensor : mSensors) {
                    @Authenticators.Types final int sensorStrength = sensor.getCurrentStrength();
                    if (Utils.isAtLeastStrength(sensorStrength, requestedStrength)) {
                        modality |= sensor.modality;
                    }
                }
            }

            return modality;
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 0 && "--proto".equals(args[0])) {
                    final boolean clearSchedulerBuffer = args.length > 1
                            && "--clear-scheduler-buffer".equals(args[1]);
                    Slog.d(TAG, "ClearSchedulerBuffer: " + clearSchedulerBuffer);
                    final ProtoOutputStream proto = new ProtoOutputStream(fd);
                    proto.write(BiometricServiceStateProto.AUTH_SESSION_STATE,
                            mCurrentAuthSession != null ? mCurrentAuthSession.getState()
                                    : STATE_AUTH_IDLE);
                    for (BiometricSensor sensor : mSensors) {
                        byte[] serviceState = sensor.impl
                                .dumpSensorServiceStateProto(clearSchedulerBuffer);
                        proto.write(BiometricServiceStateProto.SENSOR_SERVICE_STATES, serviceState);
                    }
                    proto.flush();
                } else {
                    dumpInternal(pw);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void checkInternalPermission() {
        getContext().enforceCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL,
                "Must have USE_BIOMETRIC_INTERNAL permission");
    }

    @NonNull
    private PreAuthInfo createPreAuthInfo(
            @NonNull String opPackageName,
            int userId,
            @Authenticators.Types int authenticators) throws RemoteException {

        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(authenticators);

        return PreAuthInfo.create(mTrustManager, mDevicePolicyManager, mSettingObserver, mSensors,
                userId, promptInfo, opPackageName, false /* checkDevicePolicyManager */);
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

        public List<FingerprintSensorPropertiesInternal> getFingerprintSensorProperties(
                Context context) {
            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                final FingerprintManager fpm = context.getSystemService(FingerprintManager.class);
                if (fpm != null) {
                    return fpm.getSensorPropertiesInternal();
                }
            }
            return new ArrayList<>();
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

    private void handleAuthenticationSucceeded(int sensorId, byte[] token) {
        Slog.v(TAG, "handleAuthenticationSucceeded(), sensorId: " + sensorId);
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleAuthenticationSucceeded: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onAuthenticationSucceeded(sensorId, isStrongBiometric(sensorId), token);
    }

    private void handleAuthenticationRejected() {
        Slog.v(TAG, "handleAuthenticationRejected()");

        // Should never happen, log this to catch bad HAL behavior (e.g. auth rejected
        // after user dismissed/canceled dialog).
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleAuthenticationRejected: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onAuthenticationRejected();
    }

    private void handleAuthenticationTimedOut(int sensorId, int cookie, int error, int vendorCode) {
        Slog.v(TAG, "handleAuthenticationTimedOut(), sensorId: " + sensorId
                + ", cookie: " + cookie
                + ", error: " + error
                + ", vendorCode: " + vendorCode);
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleAuthenticationTimedOut: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onAuthenticationTimedOut(sensorId, cookie, error, vendorCode);
    }

    private void handleOnError(int sensorId, int cookie, @BiometricConstants.Errors int error,
            int vendorCode) {
        Slog.d(TAG, "handleOnError() sensorId: " + sensorId
                + ", cookie: " + cookie
                + ", error: " + error
                + ", vendorCode: " + vendorCode);

        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleOnError: AuthSession is null");
            return;
        }

        try {
            final boolean finished = mCurrentAuthSession
                    .onErrorReceived(sensorId, cookie, error, vendorCode);
            if (finished) {
                Slog.d(TAG, "handleOnError: AuthSession finished");
                mCurrentAuthSession = null;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    private void handleOnAcquired(int sensorId, int acquiredInfo, int vendorCode) {
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "onAcquired: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onAcquired(sensorId, acquiredInfo, vendorCode);
    }

    private void handleOnDismissed(@BiometricPrompt.DismissedReason int reason,
            @Nullable byte[] credentialAttestation) {
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "onDismissed: " + reason + ", AuthSession is null");
            return;
        }

        mCurrentAuthSession.onDialogDismissed(reason, credentialAttestation);
        mCurrentAuthSession = null;
    }

    private void handleOnTryAgainPressed() {
        Slog.d(TAG, "onTryAgainPressed");
        // No need to check permission, since it can only be invoked by SystemUI
        // (or system server itself).
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleOnTryAgainPressed: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onTryAgainPressed();
    }

    private void handleOnDeviceCredentialPressed() {
        Slog.d(TAG, "onDeviceCredentialPressed");
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleOnDeviceCredentialPressed: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onDeviceCredentialPressed();
    }

    private void handleOnSystemEvent(int event) {
        Slog.d(TAG, "onSystemEvent: " + event);

        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleOnSystemEvent: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onSystemEvent(event);
    }

    private void handleClientDied() {
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleClientDied: AuthSession is null");
            return;
        }

        Slog.e(TAG, "Session: " + mCurrentAuthSession);
        final boolean finished = mCurrentAuthSession.onClientDied();
        if (finished) {
            mCurrentAuthSession = null;
        }
    }

    private void handleOnDialogAnimatedIn() {
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleOnDialogAnimatedIn: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onDialogAnimatedIn();
    }

    /**
     * Invoked when each service has notified that its client is ready to be started. When
     * all biometrics are ready, this invokes the SystemUI dialog through StatusBar.
     */
    private void handleOnReadyForAuthentication(int cookie) {
        if (mCurrentAuthSession == null) {
            // Only should happen if a biometric was locked out when authenticate() was invoked.
            // In that case, if device credentials are allowed, the UI is already showing. If not
            // allowed, the error has already been returned to the caller.
            Slog.w(TAG, "handleOnReadyForAuthentication: AuthSession is null");
            return;
        }

        mCurrentAuthSession.onCookieReceived(cookie);
    }

    private void handleAuthenticate(IBinder token, long operationId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo) {

        mHandler.post(() -> {
            try {
                final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager,
                        mDevicePolicyManager, mSettingObserver, mSensors, userId, promptInfo,
                        opPackageName, promptInfo.isDisallowBiometricsIfPolicyExists());

                final Pair<Integer, Integer> preAuthStatus = preAuthInfo.getPreAuthenticateStatus();

                Slog.d(TAG, "handleAuthenticate: modality(" + preAuthStatus.first
                        + "), status(" + preAuthStatus.second + "), preAuthInfo: " + preAuthInfo);

                if (preAuthStatus.second == BiometricConstants.BIOMETRIC_SUCCESS) {
                    // If BIOMETRIC_WEAK or BIOMETRIC_STRONG are allowed, but not enrolled, but
                    // CREDENTIAL is requested and available, set the bundle to only request
                    // CREDENTIAL.
                    // TODO: We should clean this up, as well as the interface with SystemUI
                    if (preAuthInfo.credentialRequested && preAuthInfo.credentialAvailable
                            && preAuthInfo.eligibleSensors.isEmpty()) {
                        promptInfo.setAuthenticators(Authenticators.DEVICE_CREDENTIAL);
                    }

                    authenticateInternal(token, operationId, userId, receiver, opPackageName,
                            promptInfo, preAuthInfo);
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
            IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo,
            PreAuthInfo preAuthInfo) {
        Slog.d(TAG, "Creating authSession with authRequest: " + preAuthInfo);

        // No need to dismiss dialog / send error yet if we're continuing authentication, e.g.
        // "Try again" is showing due to something like ERROR_TIMEOUT.
        if (mCurrentAuthSession != null) {
            // Forcefully cancel authentication. Dismiss the UI, and immediately send
            // ERROR_CANCELED to the client. Note that we should/will ignore HAL ERROR_CANCELED.
            // Expect to see some harmless "unknown cookie" errors.
            Slog.w(TAG, "Existing AuthSession: " + mCurrentAuthSession);
            mCurrentAuthSession.onCancelAuthSession(true /* force */);
            mCurrentAuthSession = null;
        }

        final boolean debugEnabled = mInjector.isDebugEnabled(getContext(), userId);
        mCurrentAuthSession = new AuthSession(getContext(), mStatusBarService, mSysuiReceiver,
                mKeyStore, mRandom, mClientDeathReceiver, preAuthInfo, token, operationId, userId,
                mBiometricSensorReceiver, receiver, opPackageName, promptInfo, debugEnabled,
                mInjector.getFingerprintSensorProperties(getContext()));
        try {
            mCurrentAuthSession.goToInitialState();
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    private void handleCancelAuthentication() {
        if (mCurrentAuthSession == null) {
            Slog.e(TAG, "handleCancelAuthentication: AuthSession is null");
            return;
        }

        final boolean finished = mCurrentAuthSession.onCancelAuthSession(false /* force */);
        if (finished) {
            Slog.d(TAG, "handleCancelAuthentication: AuthSession finished");
            mCurrentAuthSession = null;
        }
    }

    @Nullable
    private BiometricSensor getSensorForId(int sensorId) {
        for (BiometricSensor sensor : mSensors) {
            if (sensor.id == sensorId) {
                return sensor;
            }
        }
        return null;
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("Sensors:");
        for (BiometricSensor sensor : mSensors) {
            pw.println(" " + sensor);
        }
        pw.println("CurrentSession: " + mCurrentAuthSession);

        final List<FingerprintSensorPropertiesInternal> fpProps =
                mInjector.getFingerprintSensorProperties(getContext());
        pw.println("FingerprintSensorProperties: " + fpProps.size());
        for (FingerprintSensorPropertiesInternal prop : fpProps) {
            pw.println(" " + prop);
        }
    }
}
