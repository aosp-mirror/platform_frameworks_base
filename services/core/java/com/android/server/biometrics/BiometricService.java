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
import static android.hardware.biometrics.BiometricManager.Authenticators;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_NO_AUTHENTICATION;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;

import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_IDLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.UserSwitchObserver;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.Flags;
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
import android.hardware.camera2.CameraManager;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.security.keymint.HardwareAuthenticatorType;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.GateKeeper;
import android.security.KeyStoreAuthorization;
import android.service.gatekeeper.IGateKeeperService;
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
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * System service that arbitrates the modality for BiometricPrompt to use.
 */
public class BiometricService extends SystemService {

    static final String TAG = "BiometricService";

    private final Injector mInjector;
    private final DevicePolicyManager mDevicePolicyManager;
    @VisibleForTesting
    final IBiometricService.Stub mImpl;
    @VisibleForTesting
    final SettingObserver mSettingObserver;
    private final List<EnabledOnKeyguardCallback> mEnabledOnKeyguardCallbacks;
    private final Random mRandom = new Random();
    @NonNull private final Supplier<Long> mRequestCounter;
    @NonNull private final BiometricContext mBiometricContext;
    private final UserManager mUserManager;

    @VisibleForTesting
    IStatusBarService mStatusBarService;
    @VisibleForTesting
    ITrustManager mTrustManager;
    @VisibleForTesting
    KeyStoreAuthorization mKeyStoreAuthorization;
    @VisibleForTesting
    IGateKeeperService mGateKeeper;

    // Get and cache the available biometric authenticators and their associated info.
    final ArrayList<BiometricSensor> mSensors = new ArrayList<>();

    @VisibleForTesting
    BiometricStrengthController mBiometricStrengthController;

    // The current authentication session, null if idle/done.
    @VisibleForTesting
    AuthSession mAuthSession;
    private final Handler mHandler;

    private final BiometricCameraManager mBiometricCameraManager;

    private final BiometricNotificationLogger mBiometricNotificationLogger;

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
        private static final boolean DEFAULT_MANDATORY_BIOMETRICS_STATUS = false;
        private static final boolean DEFAULT_MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED_STATUS =
                true;

        // Some devices that shipped before S already have face-specific settings. Instead of
        // migrating, which is complicated, let's just keep using the existing settings.
        private final boolean mUseLegacyFaceOnlySettings;

        // Only used for legacy face-only devices
        private final Uri FACE_UNLOCK_KEYGUARD_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED);
        private final Uri FACE_UNLOCK_APP_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_APP_ENABLED);

        // Continues to be used, even though it's face-specific.
        private final Uri FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION =
                Settings.Secure.getUriFor(Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION);

        // Used for all devices other than legacy face-only devices
        private final Uri BIOMETRIC_KEYGUARD_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.BIOMETRIC_KEYGUARD_ENABLED);
        private final Uri BIOMETRIC_APP_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.BIOMETRIC_APP_ENABLED);
        private final Uri MANDATORY_BIOMETRICS_ENABLED =
                Settings.Secure.getUriFor(Settings.Secure.MANDATORY_BIOMETRICS);
        private final Uri MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED = Settings.Secure.getUriFor(
                Settings.Secure.MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED);

        private final ContentResolver mContentResolver;
        private final List<BiometricService.EnabledOnKeyguardCallback> mCallbacks;
        private final UserManager mUserManager;

        private final Map<Integer, Boolean> mBiometricEnabledOnKeyguard = new HashMap<>();
        private final Map<Integer, Boolean> mBiometricEnabledForApps = new HashMap<>();
        private final Map<Integer, Boolean> mFaceAlwaysRequireConfirmation = new HashMap<>();
        private final Map<Integer, Boolean> mMandatoryBiometricsEnabled = new HashMap<>();
        private final Map<Integer, Boolean> mMandatoryBiometricsRequirementsSatisfied =
                new HashMap<>();
        private final Map<Integer, Boolean> mFingerprintEnrolledForUser =
                new HashMap<>();
        private final Map<Integer, Boolean> mFaceEnrolledForUser =
                new HashMap<>();

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
            mUserManager = context.getSystemService(UserManager.class);

            final boolean hasFingerprint = context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
            final boolean hasFace = context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_FACE);

            // Use the legacy setting on face-only devices that shipped on or before Q
            mUseLegacyFaceOnlySettings =
                    Build.VERSION.DEVICE_INITIAL_SDK_INT <= Build.VERSION_CODES.Q
                    && hasFace && !hasFingerprint;

            addBiometricListenersForMandatoryBiometrics(context);
            updateContentObserver();
        }

        public void updateContentObserver() {
            mContentResolver.unregisterContentObserver(this);

            if (mUseLegacyFaceOnlySettings) {
                mContentResolver.registerContentObserver(FACE_UNLOCK_KEYGUARD_ENABLED,
                        false /* notifyForDescendants */,
                        this /* observer */,
                        UserHandle.USER_ALL);
                mContentResolver.registerContentObserver(FACE_UNLOCK_APP_ENABLED,
                        false /* notifyForDescendants */,
                        this /* observer */,
                        UserHandle.USER_ALL);
            } else {
                mContentResolver.registerContentObserver(BIOMETRIC_KEYGUARD_ENABLED,
                        false /* notifyForDescendants */,
                        this /* observer */,
                        UserHandle.USER_ALL);
                mContentResolver.registerContentObserver(BIOMETRIC_APP_ENABLED,
                        false /* notifyForDescendants */,
                        this /* observer */,
                        UserHandle.USER_ALL);
            }
            mContentResolver.registerContentObserver(FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
                    false /* notifyForDescendants */,
                    this /* observer */,
                    UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(MANDATORY_BIOMETRICS_ENABLED,
                    false /* notifyForDescendants */,
                    this /* observer */,
                    UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED,
                    false /* notifyForDescendants */,
                    this /* observer */,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (FACE_UNLOCK_KEYGUARD_ENABLED.equals(uri)) {
                mBiometricEnabledOnKeyguard.put(userId, Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED,
                                DEFAULT_KEYGUARD_ENABLED ? 1 : 0 /* default */,
                                userId) != 0);

                if (userId == ActivityManager.getCurrentUser() && !selfChange) {
                    notifyEnabledOnKeyguardCallbacks(userId);
                }
            } else if (FACE_UNLOCK_APP_ENABLED.equals(uri)) {
                mBiometricEnabledForApps.put(userId, Settings.Secure.getIntForUser(
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
            } else if (BIOMETRIC_KEYGUARD_ENABLED.equals(uri)) {
                mBiometricEnabledOnKeyguard.put(userId, Settings.Secure.getIntForUser(
                        mContentResolver,
                        Settings.Secure.BIOMETRIC_KEYGUARD_ENABLED,
                        DEFAULT_KEYGUARD_ENABLED ? 1 : 0 /* default */,
                        userId) != 0);

                if (userId == ActivityManager.getCurrentUser() && !selfChange) {
                    notifyEnabledOnKeyguardCallbacks(userId);
                }
            } else if (BIOMETRIC_APP_ENABLED.equals(uri)) {
                mBiometricEnabledForApps.put(userId, Settings.Secure.getIntForUser(
                        mContentResolver,
                        Settings.Secure.BIOMETRIC_APP_ENABLED,
                        DEFAULT_APP_ENABLED ? 1 : 0 /* default */,
                        userId) != 0);
            } else if (MANDATORY_BIOMETRICS_ENABLED.equals(uri)) {
                updateMandatoryBiometricsForAllProfiles(userId);
            } else if (MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED.equals(uri)) {
                updateMandatoryBiometricsRequirementsForAllProfiles(userId);
            }
        }

        public boolean getEnabledOnKeyguard(int userId) {
            if (!mBiometricEnabledOnKeyguard.containsKey(userId)) {
                if (mUseLegacyFaceOnlySettings) {
                    onChange(true /* selfChange */, FACE_UNLOCK_KEYGUARD_ENABLED, userId);
                } else {
                    onChange(true /* selfChange */, BIOMETRIC_KEYGUARD_ENABLED, userId);
                }
            }
            return mBiometricEnabledOnKeyguard.get(userId);
        }

        public boolean getEnabledForApps(int userId) {
            if (!mBiometricEnabledForApps.containsKey(userId)) {
                if (mUseLegacyFaceOnlySettings) {
                    onChange(true /* selfChange */, FACE_UNLOCK_APP_ENABLED, userId);
                } else {
                    onChange(true /* selfChange */, BIOMETRIC_APP_ENABLED, userId);
                }
            }
            return mBiometricEnabledForApps.getOrDefault(userId, DEFAULT_APP_ENABLED);
        }

        public boolean getConfirmationAlwaysRequired(@BiometricAuthenticator.Modality int modality,
                int userId) {
            switch (modality) {
                case TYPE_FACE:
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

        public boolean getMandatoryBiometricsEnabledAndRequirementsSatisfiedForUser(int userId) {
            if (!mMandatoryBiometricsEnabled.containsKey(userId)) {
                updateMandatoryBiometricsForAllProfiles(userId);
            }
            if (!mMandatoryBiometricsRequirementsSatisfied.containsKey(userId)) {
                updateMandatoryBiometricsRequirementsForAllProfiles(userId);
            }
            return mMandatoryBiometricsEnabled.getOrDefault(userId,
                    DEFAULT_MANDATORY_BIOMETRICS_STATUS)
                    && mMandatoryBiometricsRequirementsSatisfied.getOrDefault(userId,
                    DEFAULT_MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED_STATUS)
                    && getEnabledForApps(userId)
                    && (mFingerprintEnrolledForUser.getOrDefault(userId, false /* default */)
                    || mFaceEnrolledForUser.getOrDefault(userId, false /* default */));
        }

        void notifyEnabledOnKeyguardCallbacks(int userId) {
            List<EnabledOnKeyguardCallback> callbacks = mCallbacks;
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).notify(
                        mBiometricEnabledOnKeyguard.getOrDefault(userId, DEFAULT_KEYGUARD_ENABLED),
                        userId);
            }
        }

        private void updateMandatoryBiometricsForAllProfiles(int userId) {
            int effectiveUserId = userId;
            if (mUserManager.getMainUser() != null) {
                effectiveUserId = mUserManager.getMainUser().getIdentifier();
            }
            for (int profileUserId: mUserManager.getEnabledProfileIds(effectiveUserId)) {
                mMandatoryBiometricsEnabled.put(profileUserId,
                        Settings.Secure.getIntForUser(
                                mContentResolver, Settings.Secure.MANDATORY_BIOMETRICS,
                                DEFAULT_MANDATORY_BIOMETRICS_STATUS ? 1 : 0,
                                effectiveUserId) != 0);
            }
        }

        private void updateMandatoryBiometricsRequirementsForAllProfiles(int userId) {
            int effectiveUserId = userId;
            if (mUserManager.getMainUser() != null) {
                effectiveUserId = mUserManager.getMainUser().getIdentifier();
            }
            for (int profileUserId: mUserManager.getEnabledProfileIds(effectiveUserId)) {
                mMandatoryBiometricsRequirementsSatisfied.put(profileUserId,
                        Settings.Secure.getIntForUser(mContentResolver,
                                Settings.Secure.MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED,
                                DEFAULT_MANDATORY_BIOMETRICS_REQUIREMENTS_SATISFIED_STATUS ? 1 : 0,
                                effectiveUserId) != 0);
            }
        }

        private void addBiometricListenersForMandatoryBiometrics(Context context) {
            final FingerprintManager fingerprintManager = context.getSystemService(
                    FingerprintManager.class);
            final FaceManager faceManager = context.getSystemService(FaceManager.class);
            if (fingerprintManager != null) {
                fingerprintManager.addAuthenticatorsRegisteredCallback(
                        new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                            @Override
                            public void onAllAuthenticatorsRegistered(
                                    List<FingerprintSensorPropertiesInternal> list) {
                                if (list == null || list.isEmpty()) {
                                    Slog.d(TAG, "No fingerprint authenticators registered.");
                                    return;
                                }
                                final FingerprintSensorPropertiesInternal
                                        fingerprintSensorProperties = list.get(0);
                                if (fingerprintSensorProperties.sensorStrength
                                        == STRENGTH_STRONG) {
                                    fingerprintManager.registerBiometricStateListener(
                                            new BiometricStateListener() {
                                                @Override
                                                public void onEnrollmentsChanged(
                                                        int userId,
                                                        int sensorId,
                                                        boolean hasEnrollments
                                                ) {
                                                    if (sensorId == fingerprintSensorProperties
                                                            .sensorId) {
                                                        mFingerprintEnrolledForUser.put(userId,
                                                                hasEnrollments);
                                                    }
                                                }
                                            });
                                }
                            }
                        });
            }
            if (faceManager != null) {
                faceManager.addAuthenticatorsRegisteredCallback(
                        new IFaceAuthenticatorsRegisteredCallback.Stub() {
                            @Override
                            public void onAllAuthenticatorsRegistered(
                                    List<FaceSensorPropertiesInternal> list) {
                                if (list == null || list.isEmpty()) {
                                    Slog.d(TAG, "No face authenticators registered.");
                                    return;
                                }
                                final FaceSensorPropertiesInternal
                                        faceSensorPropertiesInternal = list.get(0);
                                if (faceSensorPropertiesInternal.sensorStrength
                                        == STRENGTH_STRONG) {
                                    faceManager.registerBiometricStateListener(
                                            new BiometricStateListener() {
                                                @Override
                                                public void onEnrollmentsChanged(
                                                        int userId,
                                                        int sensorId,
                                                        boolean hasEnrollments
                                                ) {
                                                    if (sensorId
                                                            == faceSensorPropertiesInternal
                                                            .sensorId) {
                                                        mFaceEnrolledForUser.put(userId,
                                                                hasEnrollments);
                                                    }
                                                }
                                            });
                                }
                            }
                        });
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

        void notify(boolean enabled, int userId) {
            try {
                mCallback.onChanged(enabled, userId);
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
    private IBiometricSensorReceiver createBiometricSensorReceiver(final long requestId) {
        return new IBiometricSensorReceiver.Stub() {
            @Override
            public void onAuthenticationSucceeded(int sensorId, byte[] token) {
                mHandler.post(() -> handleAuthenticationSucceeded(requestId, sensorId, token));
            }

            @Override
            public void onAuthenticationFailed(int sensorId) {
                Slog.v(TAG, "onAuthenticationFailed");
                mHandler.post(() -> handleAuthenticationRejected(requestId, sensorId));
            }

            @Override
            public void onError(int sensorId, int cookie, @BiometricConstants.Errors int error,
                    int vendorCode) {
                // Determine if error is hard or soft error. Certain errors (such as TIMEOUT) are
                // soft errors and we should allow the user to try authenticating again instead of
                // dismissing BiometricPrompt.
                if (error == BiometricConstants.BIOMETRIC_ERROR_TIMEOUT) {
                    mHandler.post(() -> handleAuthenticationTimedOut(
                            requestId, sensorId, cookie, error, vendorCode));
                } else {
                    mHandler.post(() -> handleOnError(
                            requestId, sensorId, cookie, error, vendorCode));
                }
            }

            @Override
            public void onAcquired(int sensorId, int acquiredInfo, int vendorCode) {
                mHandler.post(() -> handleOnAcquired(
                        requestId, sensorId, acquiredInfo, vendorCode));
            }
        };
    }

    private IBiometricSysuiReceiver createSysuiReceiver(final long requestId) {
        return new IBiometricSysuiReceiver.Stub() {
            @Override
            public void onDialogDismissed(@BiometricPrompt.DismissedReason int reason,
                    @Nullable byte[] credentialAttestation) {
                mHandler.post(() -> handleOnDismissed(requestId, reason, credentialAttestation));
            }

            @Override
            public void onTryAgainPressed() {
                mHandler.post(() -> handleOnTryAgainPressed(requestId));
            }

            @Override
            public void onDeviceCredentialPressed() {
                mHandler.post(() -> handleOnDeviceCredentialPressed(requestId));
            }

            @Override
            public void onSystemEvent(int event) {
                mHandler.post(() -> handleOnSystemEvent(requestId, event));
            }

            @Override
            public void onDialogAnimatedIn(boolean startFingerprintNow) {
                mHandler.post(() -> handleOnDialogAnimatedIn(requestId, startFingerprintNow));
            }

            @Override
            public void onStartFingerprintNow() {
                mHandler.post(() -> handleOnStartFingerprintNow(requestId));
            }
        };
    }

    private AuthSession.ClientDeathReceiver createClientDeathReceiver(final long requestId) {
        return () -> mHandler.post(() -> handleClientDied(requestId));
    };

    /**
     * Implementation of the BiometricPrompt/BiometricManager APIs. Handles client requests,
     * sensor arbitration, threading, etc.
     */
    private final class BiometricServiceWrapper extends IBiometricService.Stub {
        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) throws RemoteException {

            super.createTestSession_enforcePermission();

            for (BiometricSensor sensor : mSensors) {
                if (sensor.id == sensorId) {
                    return sensor.impl.createTestSession(callback, opPackageName);
                }
            }

            Slog.e(TAG, "Unknown sensor for createTestSession: " + sensorId);
            return null;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public List<SensorPropertiesInternal> getSensorProperties(String opPackageName)
                throws RemoteException {

            super.getSensorProperties_enforcePermission();

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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void onReadyForAuthentication(long requestId, int cookie) {

            super.onReadyForAuthentication_enforcePermission();

            mHandler.post(() -> handleOnReadyForAuthentication(requestId, cookie));
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public long authenticate(IBinder token, long operationId, int userId,
                IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo) {

            super.authenticate_enforcePermission();

            if (token == null || receiver == null || opPackageName == null || promptInfo == null) {
                Slog.e(TAG, "Unable to authenticate, one or more null arguments");
                return -1;
            }

            if (!Utils.isValidAuthenticatorConfig(getContext(), promptInfo)) {
                throw new SecurityException("Invalid authenticator configuration");
            }

            Utils.combineAuthenticatorBundles(promptInfo);

            final long requestId = mRequestCounter.get();
            mHandler.post(() -> handleAuthenticate(
                    token, requestId, operationId, userId, receiver, opPackageName, promptInfo));

            return requestId;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void cancelAuthentication(IBinder token, String opPackageName, long requestId) {

            super.cancelAuthentication_enforcePermission();

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = token;
            args.arg2 = opPackageName;
            args.arg3 = requestId;

            mHandler.post(() -> handleCancelAuthentication(requestId));
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public int canAuthenticate(String opPackageName, int userId, int callingUserId,
                @Authenticators.Types int authenticators) {

            super.canAuthenticate_enforcePermission();

            Slog.d(TAG, "canAuthenticate: User=" + userId
                    + ", Caller=" + callingUserId
                    + ", Authenticators=" + authenticators);

            if (!Utils.isValidAuthenticatorConfig(getContext(), authenticators)) {
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public long getLastAuthenticationTime(
                int userId, @Authenticators.Types int authenticators) {
            super.getLastAuthenticationTime_enforcePermission();

            if (!Flags.lastAuthenticationTime()) {
                throw new UnsupportedOperationException();
            }

            Slogf.d(TAG, "getLastAuthenticationTime(userId=%d, authenticators=0x%x)",
                    userId, authenticators);

            final long secureUserId;
            try {
                secureUserId = mGateKeeper.getSecureUserId(userId);
            } catch (RemoteException e) {
                Slogf.w(TAG, "Failed to get secure user id for " + userId, e);
                return BIOMETRIC_NO_AUTHENTICATION;
            }

            if (secureUserId == GateKeeper.INVALID_SECURE_USER_ID) {
                Slogf.w(TAG, "No secure user id for " + userId);
                return BIOMETRIC_NO_AUTHENTICATION;
            }

            ArrayList<Integer> hardwareAuthenticators = new ArrayList<>(2);

            if ((authenticators & Authenticators.DEVICE_CREDENTIAL) != 0) {
                hardwareAuthenticators.add(HardwareAuthenticatorType.PASSWORD);
            }

            if ((authenticators & Authenticators.BIOMETRIC_STRONG) != 0) {
                hardwareAuthenticators.add(HardwareAuthenticatorType.FINGERPRINT);
            }

            if (hardwareAuthenticators.isEmpty()) {
                throw new IllegalArgumentException("authenticators must not be empty");
            }

            int[] authTypesArray = hardwareAuthenticators.stream()
                    .mapToInt(Integer::intValue)
                    .toArray();
            return mKeyStoreAuthorization.getLastAuthTime(secureUserId, authTypesArray);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public boolean hasEnrolledBiometrics(int userId, String opPackageName) {

            super.hasEnrolledBiometrics_enforcePermission();

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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public synchronized void registerAuthenticator(int id, int modality,
                @Authenticators.Types int strength,
                @NonNull IBiometricAuthenticator authenticator) {

            super.registerAuthenticator_enforcePermission();

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

            mSensors.add(new BiometricSensor(getContext(), id, modality, strength, authenticator) {
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void registerEnabledOnKeyguardCallback(
                IBiometricEnabledOnKeyguardCallback callback) {

            super.registerEnabledOnKeyguardCallback_enforcePermission();

            mEnabledOnKeyguardCallbacks.add(new EnabledOnKeyguardCallback(callback));
            final List<UserInfo> aliveUsers = mUserManager.getAliveUsers();
            try {
                for (UserInfo userInfo: aliveUsers) {
                    final int userId = userInfo.id;
                    callback.onChanged(mSettingObserver.getEnabledOnKeyguard(userId),
                            userId);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception", e);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void invalidateAuthenticatorIds(int userId, int fromSensorId,
                IInvalidationCallback callback) {

            super.invalidateAuthenticatorIds_enforcePermission();

            InvalidationTracker.start(getContext(), mSensors, userId, fromSensorId, callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public long[] getAuthenticatorIds(int callingUserId) {

            super.getAuthenticatorIds_enforcePermission();

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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void resetLockoutTimeBound(IBinder token, String opPackageName, int fromSensorId,
                int userId, byte[] hardwareAuthToken) {

            // Check originating strength
            super.resetLockoutTimeBound_enforcePermission();

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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void resetLockout(
                int userId, byte[] hardwareAuthToken) {
            super.resetLockout_enforcePermission();

            Slog.d(TAG, "resetLockout(userId=" + userId
                    + ", hat=" + (hardwareAuthToken == null ? "null " : "present") + ")");
            mHandler.post(() -> {
                mBiometricContext.getAuthSessionCoordinator()
                    .resetLockoutFor(userId, Authenticators.BIOMETRIC_STRONG, -1);
            });
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public int getCurrentStrength(int sensorId) {

            super.getCurrentStrength_enforcePermission();

            for (BiometricSensor sensor : mSensors) {
                if (sensor.id == sensorId) {
                    return sensor.getCurrentStrength();
                }
            }
            Slog.e(TAG, "Unknown sensorId: " + sensorId);
            return Authenticators.EMPTY_SET;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public int getCurrentModality(
                String opPackageName,
                int userId,
                int callingUserId,
                @Authenticators.Types int authenticators) {


            super.getCurrentModality_enforcePermission();

            Slog.d(TAG, "getCurrentModality: User=" + userId
                    + ", Caller=" + callingUserId
                    + ", Authenticators=" + authenticators);

            if (!Utils.isValidAuthenticatorConfig(getContext(), authenticators)) {
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public int getSupportedModalities(@Authenticators.Types int authenticators) {

            super.getSupportedModalities_enforcePermission();

            Slog.d(TAG, "getSupportedModalities: Authenticators=" + authenticators);

            if (!Utils.isValidAuthenticatorConfig(getContext(), authenticators)) {
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
                            mAuthSession != null ? mAuthSession.getState() : STATE_AUTH_IDLE);
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
                userId, promptInfo, opPackageName, false /* checkDevicePolicyManager */,
                getContext(), mBiometricCameraManager);
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

        public KeyStoreAuthorization getKeyStoreAuthorization() {
            return KeyStoreAuthorization.getInstance();
        }

        public IGateKeeperService getGateKeeperService() {
            return GateKeeper.getService();
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

        public Supplier<Long> getRequestGenerator() {
            final AtomicLong generator = new AtomicLong(0);
            return () -> generator.incrementAndGet();
        }

        public BiometricContext getBiometricContext(Context context) {
            return BiometricContext.getInstance(context);
        }

        public UserManager getUserManager(Context context) {
            return context.getSystemService(UserManager.class);
        }

        public BiometricCameraManager getBiometricCameraManager(Context context) {
            return new BiometricCameraManagerImpl(context.getSystemService(CameraManager.class),
                    context.getSystemService(SensorPrivacyManager.class));
        }

        public BiometricNotificationLogger getNotificationLogger() {
            return new BiometricNotificationLogger();
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
        this(context, new Injector(), BiometricHandlerProvider.getInstance());
    }

    @VisibleForTesting
    BiometricService(Context context, Injector injector,
            BiometricHandlerProvider biometricHandlerProvider) {
        super(context);

        mInjector = injector;
        mHandler = biometricHandlerProvider.getBiometricCallbackHandler();
        mDevicePolicyManager = mInjector.getDevicePolicyManager(context);
        mImpl = new BiometricServiceWrapper();
        mEnabledOnKeyguardCallbacks = new ArrayList<>();
        mSettingObserver = mInjector.getSettingObserver(context, mHandler,
                mEnabledOnKeyguardCallbacks);
        mRequestCounter = mInjector.getRequestGenerator();
        mBiometricContext = injector.getBiometricContext(context);
        mUserManager = injector.getUserManager(context);
        mBiometricCameraManager = injector.getBiometricCameraManager(context);
        mKeyStoreAuthorization = injector.getKeyStoreAuthorization();
        mGateKeeper = injector.getGateKeeperService();
        mBiometricNotificationLogger = injector.getNotificationLogger();

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
        mStatusBarService = mInjector.getStatusBarService();
        mTrustManager = mInjector.getTrustManager();
        mInjector.publishBinderService(this, mImpl);
        mBiometricStrengthController = mInjector.getBiometricStrengthController(this);
        mBiometricStrengthController.startListening();

        mHandler.post(new Runnable(){
            @Override
            public void run() {
                try {
                    mBiometricNotificationLogger.registerAsSystemService(getContext(),
                            new ComponentName(getContext(), BiometricNotificationLogger.class),
                            UserHandle.USER_ALL);
                } catch (RemoteException e) {
                    // Intra-process call, should never happen.
                }
            }

        });
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

    @Nullable
    private AuthSession getAuthSessionIfCurrent(long requestId) {
        final AuthSession session = mAuthSession;
        if (session != null && session.getRequestId() == requestId) {
            return session;
        }
        return null;
    }

    private void handleAuthenticationSucceeded(long requestId, int sensorId, byte[] token) {
        Slog.v(TAG, "handleAuthenticationSucceeded(), sensorId: " + sensorId);
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.e(TAG, "handleAuthenticationSucceeded: AuthSession is null");
            return;
        }

        session.onAuthenticationSucceeded(sensorId, isStrongBiometric(sensorId), token);
    }

    private void handleAuthenticationRejected(long requestId, int sensorId) {
        Slog.v(TAG, "handleAuthenticationRejected()");

        // Should never happen, log this to catch bad HAL behavior (e.g. auth rejected
        // after user dismissed/canceled dialog).
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleAuthenticationRejected: AuthSession is not current");
            return;
        }

        session.onAuthenticationRejected(sensorId);
    }

    private void handleAuthenticationTimedOut(long requestId, int sensorId, int cookie, int error,
            int vendorCode) {
        Slog.v(TAG, "handleAuthenticationTimedOut(), sensorId: " + sensorId
                + ", cookie: " + cookie
                + ", error: " + error
                + ", vendorCode: " + vendorCode);
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleAuthenticationTimedOut: AuthSession is not current");
            return;
        }

        session.onAuthenticationTimedOut(sensorId, cookie, error, vendorCode);
    }

    private void handleOnError(long requestId, int sensorId, int cookie,
            @BiometricConstants.Errors int error, int vendorCode) {
        Slog.d(TAG, "handleOnError() sensorId: " + sensorId
                + ", cookie: " + cookie
                + ", error: " + error
                + ", vendorCode: " + vendorCode);

        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleOnError: AuthSession is not current");
            return;
        }

        try {
            final boolean finished = session.onErrorReceived(sensorId, cookie, error, vendorCode);
            if (finished) {
                Slog.d(TAG, "handleOnError: AuthSession finished");
                mAuthSession = null;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    private void handleOnAcquired(long requestId, int sensorId, int acquiredInfo, int vendorCode) {
        // Should never happen, log this to catch bad HAL behavior (e.g. auth succeeded
        // after user dismissed/canceled dialog).
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "onAcquired: AuthSession is not current");
            return;
        }

        session.onAcquired(sensorId, acquiredInfo, vendorCode);
    }

    private void handleOnDismissed(long requestId, @BiometricPrompt.DismissedReason int reason,
            @Nullable byte[] credentialAttestation) {
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.e(TAG, "onDismissed: " + reason + ", AuthSession is not current");
            return;
        }

        session.onDialogDismissed(reason, credentialAttestation);
        mAuthSession = null;
    }

    private void handleOnTryAgainPressed(long requestId) {
        Slog.d(TAG, "onTryAgainPressed");
        // No need to check permission, since it can only be invoked by SystemUI
        // (or system server itself).
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleOnTryAgainPressed: AuthSession is not current");
            return;
        }

        session.onTryAgainPressed();
    }

    private void handleOnDeviceCredentialPressed(long requestId) {
        Slog.d(TAG, "onDeviceCredentialPressed");
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleOnDeviceCredentialPressed: AuthSession is not current");
            return;
        }

        session.onDeviceCredentialPressed();
    }

    private void handleOnSystemEvent(long requestId, int event) {
        Slog.d(TAG, "onSystemEvent: " + event);

        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleOnSystemEvent: AuthSession is not current");
            return;
        }

        session.onSystemEvent(event);
    }

    private void handleClientDied(long requestId) {
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleClientDied: AuthSession is not current");
            return;
        }

        Slog.e(TAG, "Session: " + session);
        final boolean finished = session.onClientDied();
        if (finished) {
            mAuthSession = null;
        }
    }

    private void handleOnDialogAnimatedIn(long requestId, boolean startFingerprintNow) {
        Slog.d(TAG, "handleOnDialogAnimatedIn");

        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleOnDialogAnimatedIn: AuthSession is not current");
            return;
        }

        session.onDialogAnimatedIn(startFingerprintNow);
    }

    private void handleOnStartFingerprintNow(long requestId) {
        Slog.d(TAG, "handleOnStartFingerprintNow");

        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleOnStartFingerprintNow: AuthSession is not current");
            return;
        }

        session.onStartFingerprint();
    }

    /**
     * Invoked when each service has notified that its client is ready to be started. When
     * all biometrics are ready, this invokes the SystemUI dialog through StatusBar.
     */
    private void handleOnReadyForAuthentication(long requestId, int cookie) {
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            // Only should happen if a biometric was locked out when authenticate() was invoked.
            // In that case, if device credentials are allowed, the UI is already showing. If not
            // allowed, the error has already been returned to the caller.
            Slog.w(TAG, "handleOnReadyForAuthentication: AuthSession is not current");
            return;
        }

        session.onCookieReceived(cookie);
    }

    private void handleAuthenticate(IBinder token, long requestId, long operationId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo) {
        mHandler.post(() -> {
            try {
                final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager,
                        mDevicePolicyManager, mSettingObserver, mSensors, userId, promptInfo,
                        opPackageName, promptInfo.isDisallowBiometricsIfPolicyExists(),
                        getContext(), mBiometricCameraManager);

                // Set the default title if necessary.
                if (promptInfo.isUseDefaultTitle()) {
                    if (TextUtils.isEmpty(promptInfo.getTitle())) {
                        promptInfo.setTitle(getContext()
                                .getString(R.string.biometric_dialog_default_title));
                    }
                }

                final int eligible = preAuthInfo.getEligibleModalities();
                final boolean hasEligibleFingerprintSensor =
                        (eligible & TYPE_FINGERPRINT) == TYPE_FINGERPRINT;
                final boolean hasEligibleFaceSensor = (eligible & TYPE_FACE) == TYPE_FACE;

                // Set the subtitle according to the modality.
                if (promptInfo.isUseDefaultSubtitle()) {
                    if (hasEligibleFingerprintSensor && hasEligibleFaceSensor) {
                        promptInfo.setSubtitle(getContext()
                                .getString(R.string.biometric_dialog_default_subtitle));
                    } else if (hasEligibleFingerprintSensor) {
                        promptInfo.setSubtitle(getContext()
                                .getString(R.string.fingerprint_dialog_default_subtitle));
                    } else if (hasEligibleFaceSensor) {
                        promptInfo.setSubtitle(getContext()
                                .getString(R.string.face_dialog_default_subtitle));
                    } else {
                        promptInfo.setSubtitle(getContext()
                                .getString(R.string.screen_lock_dialog_default_subtitle));
                    }
                }

                final Pair<Integer, Integer> preAuthStatus = preAuthInfo.getPreAuthenticateStatus();

                Slog.d(TAG, "handleAuthenticate: modality(" + preAuthStatus.first
                        + "), status(" + preAuthStatus.second + "), preAuthInfo: " + preAuthInfo
                        + " requestId: " + requestId + " promptInfo.isIgnoreEnrollmentState: "
                        + promptInfo.isIgnoreEnrollmentState());
                // BIOMETRIC_ERROR_SENSOR_PRIVACY_ENABLED is added so that BiometricPrompt can
                // be shown for this case.
                if (preAuthStatus.second == BiometricConstants.BIOMETRIC_SUCCESS) {
                    // If BIOMETRIC_WEAK or BIOMETRIC_STRONG are allowed, but not enrolled, but
                    // CREDENTIAL is requested and available, set the bundle to only request
                    // CREDENTIAL.
                    // TODO: We should clean this up, as well as the interface with SystemUI
                    if (preAuthInfo.credentialRequested && preAuthInfo.credentialAvailable
                            && preAuthInfo.eligibleSensors.isEmpty()) {
                        promptInfo.setAuthenticators(Authenticators.DEVICE_CREDENTIAL);
                    }

                    authenticateInternal(token, requestId, operationId, userId, receiver,
                            opPackageName, promptInfo, preAuthInfo);
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
     * used for preparing <Biometric>Services for authentication when BiometricPrompt#authenticate
     * is invoked, shortly after which BiometricPrompt is shown and authentication starts.
     *
     * Note that this path is NOT invoked when the BiometricPrompt "Try again" button is pressed.
     * In that case, see {@link #handleOnTryAgainPressed()}.
     */
    private void authenticateInternal(IBinder token, long requestId, long operationId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, PromptInfo promptInfo,
            PreAuthInfo preAuthInfo) {
        Slog.d(TAG, "Creating authSession with authRequest: " + preAuthInfo);

        // No need to dismiss dialog / send error yet if we're continuing authentication, e.g.
        // "Try again" is showing due to something like ERROR_TIMEOUT.
        if (mAuthSession != null) {
            // Forcefully cancel authentication. Dismiss the UI, and immediately send
            // ERROR_CANCELED to the client. Note that we should/will ignore HAL ERROR_CANCELED.
            // Expect to see some harmless "unknown cookie" errors.
            Slog.w(TAG, "Existing AuthSession: " + mAuthSession);
            mAuthSession.onCancelAuthSession(true /* force */);
            mAuthSession = null;
        }

        final boolean debugEnabled = mInjector.isDebugEnabled(getContext(), userId);
        mAuthSession = new AuthSession(getContext(), mBiometricContext, mStatusBarService,
                createSysuiReceiver(requestId), mKeyStoreAuthorization, mRandom,
                createClientDeathReceiver(requestId), preAuthInfo, token, requestId,
                operationId, userId, createBiometricSensorReceiver(requestId), receiver,
                opPackageName, promptInfo, debugEnabled,
                mInjector.getFingerprintSensorProperties(getContext()));
        try {
            mAuthSession.goToInitialState();
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    private void handleCancelAuthentication(long requestId) {
        final AuthSession session = getAuthSessionIfCurrent(requestId);
        if (session == null) {
            Slog.w(TAG, "handleCancelAuthentication: AuthSession is not current");
            // TODO: actually cancel the operation?
            return;
        }

        final boolean finished = session.onCancelAuthSession(false /* force */);
        if (finished) {
            Slog.d(TAG, "handleCancelAuthentication: AuthSession finished");
            mAuthSession = null;
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
        pw.println("Legacy Settings: " + mSettingObserver.mUseLegacyFaceOnlySettings);
        pw.println();

        pw.println("Sensors:");
        for (BiometricSensor sensor : mSensors) {
            pw.println(" " + sensor);
        }
        pw.println();
        pw.println("CurrentSession: " + mAuthSession);
        pw.println();
    }

}
