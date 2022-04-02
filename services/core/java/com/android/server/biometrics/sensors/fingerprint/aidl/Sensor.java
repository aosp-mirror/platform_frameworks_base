/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.fingerprint.Error;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.StopUserClient;
import com.android.server.biometrics.sensors.UserAwareBiometricScheduler;
import com.android.server.biometrics.sensors.fingerprint.FingerprintStateCallback;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maintains the state of a single sensor within an instance of the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} HAL.
 */
@SuppressWarnings("deprecation")
public class Sensor {

    private boolean mTestHalEnabled;

    @NonNull private final String mTag;
    @NonNull private final FingerprintProvider mProvider;
    @NonNull private final Context mContext;
    @NonNull private final IBinder mToken;
    @NonNull private final Handler mHandler;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    @NonNull private final UserAwareBiometricScheduler mScheduler;
    @NonNull private final LockoutCache mLockoutCache;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;

    @Nullable private AidlSession mCurrentSession;
    @NonNull private final Supplier<AidlSession> mLazySession;

    private final UserSwitchObserver mUserSwitchObserver = new SynchronousUserSwitchObserver() {
        @Override
        public void onUserSwitching(int newUserId) {
            mProvider.scheduleInternalCleanup(
                    mSensorProperties.sensorId, newUserId, null /* callback */);
        }
    };

    @VisibleForTesting
    public static class HalSessionCallback extends ISessionCallback.Stub {

        /**
         * Interface to sends results to the HalSessionCallback's owner.
         */
        public interface Callback {
            /**
             * Invoked when the HAL sends ERROR_HW_UNAVAILABLE.
             */
            void onHardwareUnavailable();
        }

        @NonNull
        private final Context mContext;
        @NonNull
        private final Handler mHandler;
        @NonNull
        private final String mTag;
        @NonNull
        private final UserAwareBiometricScheduler mScheduler;
        private final int mSensorId;
        private final int mUserId;
        @NonNull
        private final LockoutCache mLockoutCache;
        @NonNull
        private final LockoutResetDispatcher mLockoutResetDispatcher;
        @NonNull
        private final Callback mCallback;

        HalSessionCallback(@NonNull Context context, @NonNull Handler handler, @NonNull String tag,
                @NonNull UserAwareBiometricScheduler scheduler, int sensorId, int userId,
                @NonNull LockoutCache lockoutTracker,
                @NonNull LockoutResetDispatcher lockoutResetDispatcher,
                @NonNull Callback callback) {
            mContext = context;
            mHandler = handler;
            mTag = tag;
            mScheduler = scheduler;
            mSensorId = sensorId;
            mUserId = userId;
            mLockoutCache = lockoutTracker;
            mLockoutResetDispatcher = lockoutResetDispatcher;
            mCallback = callback;
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }

        @Override
        public void onChallengeGenerated(long challenge) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintGenerateChallengeClient)) {
                    Slog.e(mTag, "onChallengeGenerated for wrong client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FingerprintGenerateChallengeClient generateChallengeClient =
                        (FingerprintGenerateChallengeClient) client;
                generateChallengeClient.onChallengeGenerated(mSensorId, mUserId, challenge);
            });
        }

        @Override
        public void onChallengeRevoked(long challenge) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintRevokeChallengeClient)) {
                    Slog.e(mTag, "onChallengeRevoked for wrong client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FingerprintRevokeChallengeClient revokeChallengeClient =
                        (FingerprintRevokeChallengeClient) client;
                revokeChallengeClient.onChallengeRevoked(mSensorId, mUserId, challenge);
            });
        }

        @Override
        public void onAcquired(byte info, int vendorCode) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(mTag, "onAcquired for non-acquisition client: "
                            + Utils.getClientName(client));
                    return;
                }

                final AcquisitionClient<?> acquisitionClient = (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(AidlConversionUtils.toFrameworkAcquiredInfo(info),
                        vendorCode);
            });
        }

        @Override
        public void onError(byte error, int vendorCode) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                Slog.d(mTag, "onError"
                        + ", client: " + Utils.getClientName(client)
                        + ", error: " + error
                        + ", vendorCode: " + vendorCode);
                if (!(client instanceof ErrorConsumer)) {
                    Slog.e(mTag, "onError for non-error consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final ErrorConsumer errorConsumer = (ErrorConsumer) client;
                errorConsumer.onError(AidlConversionUtils.toFrameworkError(error), vendorCode);

                if (error == Error.HW_UNAVAILABLE) {
                    mCallback.onHardwareUnavailable();
                }
            });
        }

        @Override
        public void onEnrollmentProgress(int enrollmentId, int remaining) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintEnrollClient)) {
                    Slog.e(mTag, "onEnrollmentProgress for non-enroll client: "
                            + Utils.getClientName(client));
                    return;
                }

                final int currentUserId = client.getTargetUserId();
                final CharSequence name = FingerprintUtils.getInstance(mSensorId)
                        .getUniqueName(mContext, currentUserId);
                final Fingerprint fingerprint = new Fingerprint(name, enrollmentId, mSensorId);

                final FingerprintEnrollClient enrollClient = (FingerprintEnrollClient) client;
                enrollClient.onEnrollResult(fingerprint, remaining);
            });
        }

        @Override
        public void onAuthenticationSucceeded(int enrollmentId, HardwareAuthToken hat) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(mTag, "onAuthenticationSucceeded for non-authentication consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                final Fingerprint fp = new Fingerprint("", enrollmentId, mSensorId);
                final byte[] byteArray = HardwareAuthTokenUtils.toByteArray(hat);
                final ArrayList<Byte> byteList = new ArrayList<>();
                for (byte b : byteArray) {
                    byteList.add(b);
                }

                authenticationConsumer.onAuthenticated(fp, true /* authenticated */, byteList);
            });
        }

        @Override
        public void onAuthenticationFailed() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(mTag, "onAuthenticationFailed for non-authentication consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                final Fingerprint fp = new Fingerprint("", 0 /* enrollmentId */, mSensorId);
                authenticationConsumer
                        .onAuthenticated(fp, false /* authenticated */, null /* hat */);
            });
        }

        @Override
        public void onLockoutTimed(long durationMillis) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof LockoutConsumer)) {
                    Slog.e(mTag, "onLockoutTimed for non-lockout consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final LockoutConsumer lockoutConsumer = (LockoutConsumer) client;
                lockoutConsumer.onLockoutTimed(durationMillis);
            });
        }

        @Override
        public void onLockoutPermanent() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof LockoutConsumer)) {
                    Slog.e(mTag, "onLockoutPermanent for non-lockout consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final LockoutConsumer lockoutConsumer = (LockoutConsumer) client;
                lockoutConsumer.onLockoutPermanent();
            });
        }

        @Override
        public void onLockoutCleared() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintResetLockoutClient)) {
                    Slog.d(mTag, "onLockoutCleared outside of resetLockout by HAL");
                    FingerprintResetLockoutClient.resetLocalLockoutStateToNone(mSensorId, mUserId,
                            mLockoutCache, mLockoutResetDispatcher);
                } else {
                    Slog.d(mTag, "onLockoutCleared after resetLockout");
                    final FingerprintResetLockoutClient resetLockoutClient =
                            (FingerprintResetLockoutClient) client;
                    resetLockoutClient.onLockoutCleared();
                }
            });
        }

        @Override
        public void onInteractionDetected() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintDetectClient)) {
                    Slog.e(mTag, "onInteractionDetected for non-detect client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FingerprintDetectClient fingerprintDetectClient =
                        (FingerprintDetectClient) client;
                fingerprintDetectClient.onInteractionDetected();
            });
        }

        @Override
        public void onEnrollmentsEnumerated(int[] enrollmentIds) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof EnumerateConsumer)) {
                    Slog.e(mTag, "onEnrollmentsEnumerated for non-enumerate consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final EnumerateConsumer enumerateConsumer =
                        (EnumerateConsumer) client;
                if (enrollmentIds.length > 0) {
                    for (int i = 0; i < enrollmentIds.length; i++) {
                        final Fingerprint fp = new Fingerprint("", enrollmentIds[i], mSensorId);
                        enumerateConsumer.onEnumerationResult(fp, enrollmentIds.length - i - 1);
                    }
                } else {
                    enumerateConsumer.onEnumerationResult(null /* identifier */, 0);
                }
            });
        }

        @Override
        public void onEnrollmentsRemoved(int[] enrollmentIds) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof RemovalConsumer)) {
                    Slog.e(mTag, "onRemoved for non-removal consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final RemovalConsumer removalConsumer = (RemovalConsumer) client;
                if (enrollmentIds.length > 0) {
                    for (int i  = 0; i < enrollmentIds.length; i++) {
                        final Fingerprint fp = new Fingerprint("", enrollmentIds[i], mSensorId);
                        removalConsumer.onRemoved(fp, enrollmentIds.length - i - 1);
                    }
                } else {
                    removalConsumer.onRemoved(null, 0);
                }
            });
        }

        @Override
        public void onAuthenticatorIdRetrieved(long authenticatorId) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintGetAuthenticatorIdClient)) {
                    Slog.e(mTag, "onAuthenticatorIdRetrieved for wrong consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final FingerprintGetAuthenticatorIdClient getAuthenticatorIdClient =
                        (FingerprintGetAuthenticatorIdClient) client;
                getAuthenticatorIdClient.onAuthenticatorIdRetrieved(authenticatorId);
            });
        }

        @Override
        public void onAuthenticatorIdInvalidated(long newAuthenticatorId) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintInvalidationClient)) {
                    Slog.e(mTag, "onAuthenticatorIdInvalidated for wrong consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final FingerprintInvalidationClient invalidationClient =
                        (FingerprintInvalidationClient) client;
                invalidationClient.onAuthenticatorIdInvalidated(newAuthenticatorId);
            });
        }

        @Override
        public void onSessionClosed() {
            mHandler.post(mScheduler::onUserStopped);
        }
    }

    Sensor(@NonNull String tag, @NonNull FingerprintProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull FingerprintSensorPropertiesInternal sensorProperties,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull BiometricContext biometricContext) {
        mTag = tag;
        mProvider = provider;
        mContext = context;
        mToken = new Binder();
        mHandler = handler;
        mSensorProperties = sensorProperties;
        mLockoutCache = new LockoutCache();
        mScheduler = new UserAwareBiometricScheduler(tag,
                BiometricScheduler.sensorTypeFromFingerprintProperties(mSensorProperties),
                gestureAvailabilityDispatcher,
                () -> mCurrentSession != null ? mCurrentSession.getUserId() : UserHandle.USER_NULL,
                new UserAwareBiometricScheduler.UserSwitchCallback() {
                    @NonNull
                    @Override
                    public StopUserClient<?> getStopUserClient(int userId) {
                        return new FingerprintStopUserClient(mContext, mLazySession, mToken,
                                userId, mSensorProperties.sensorId,
                                BiometricLogger.ofUnknown(mContext), biometricContext,
                                () -> mCurrentSession = null);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<?, ?> getStartUserClient(int newUserId) {
                        final int sensorId = mSensorProperties.sensorId;

                        final HalSessionCallback resultController = new HalSessionCallback(mContext,
                                mHandler, mTag, mScheduler, sensorId, newUserId, mLockoutCache,
                                lockoutResetDispatcher, () -> {
                            Slog.e(mTag, "Got ERROR_HW_UNAVAILABLE");
                            mCurrentSession = null;
                        });

                        final StartUserClient.UserStartedCallback<ISession> userStartedCallback =
                                (userIdStarted, newSession, halInterfaceVersion) -> {
                                    Slog.d(mTag, "New session created for user: "
                                            + userIdStarted + " with hal version: "
                                            + halInterfaceVersion);
                                    mCurrentSession = new AidlSession(halInterfaceVersion,
                                            newSession, userIdStarted, resultController);
                                    if (FingerprintUtils.getInstance(sensorId)
                                            .isInvalidationInProgress(mContext, userIdStarted)) {
                                        Slog.w(mTag,
                                                "Scheduling unfinished invalidation request for "
                                                        + "sensor: "
                                                        + sensorId
                                                        + ", user: " + userIdStarted);
                                        provider.scheduleInvalidationRequest(sensorId,
                                                userIdStarted);
                                    }
                                };

                        return new FingerprintStartUserClient(mContext, provider::getHalInstance,
                                mToken, newUserId, mSensorProperties.sensorId,
                                BiometricLogger.ofUnknown(mContext), biometricContext,
                                resultController, userStartedCallback);
                    }
                });
        mAuthenticatorIds = new HashMap<>();
        mLazySession = () -> mCurrentSession != null ? mCurrentSession : null;

        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, mTag);
        } catch (RemoteException e) {
            Slog.e(mTag, "Unable to register user switch observer");
        }
    }

    @NonNull Supplier<AidlSession> getLazySession() {
        return mLazySession;
    }

    @NonNull FingerprintSensorPropertiesInternal getSensorProperties() {
        return mSensorProperties;
    }

    @Nullable AidlSession getSessionForUser(int userId) {
        if (mCurrentSession != null && mCurrentSession.getUserId() == userId) {
            return mCurrentSession;
        } else {
            return null;
        }
    }

    @NonNull ITestSession createTestSession(@NonNull ITestSessionCallback callback,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        return new BiometricTestSessionImpl(mContext, mSensorProperties.sensorId, callback,
                fingerprintStateCallback, mProvider, this);
    }

    @NonNull BiometricScheduler getScheduler() {
        return mScheduler;
    }

    @NonNull LockoutCache getLockoutCache() {
        return mLockoutCache;
    }

    @NonNull Map<Integer, Long> getAuthenticatorIds() {
        return mAuthenticatorIds;
    }

    void setTestHalEnabled(boolean enabled) {
        Slog.w(mTag, "setTestHalEnabled: " + enabled);
        if (enabled != mTestHalEnabled) {
            // The framework should retrieve a new session from the HAL.
            try {
                if (mCurrentSession != null) {
                    // TODO(181984005): This should be scheduled instead of directly invoked
                    Slog.d(mTag, "Closing old session");
                    mCurrentSession.getSession().close();
                }
            } catch (RemoteException e) {
                Slog.e(mTag, "RemoteException", e);
            }
            mCurrentSession = null;
        }
        mTestHalEnabled = enabled;
    }

    void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
            boolean clearSchedulerBuffer) {
        final long sensorToken = proto.start(SensorServiceStateProto.SENSOR_STATES);

        proto.write(SensorStateProto.SENSOR_ID, mSensorProperties.sensorId);
        proto.write(SensorStateProto.MODALITY, SensorStateProto.FINGERPRINT);
        if (mSensorProperties.isAnyUdfpsType()) {
            proto.write(SensorStateProto.MODALITY_FLAGS, SensorStateProto.FINGERPRINT_UDFPS);
        }
        proto.write(SensorStateProto.CURRENT_STRENGTH,
                Utils.getCurrentStrength(mSensorProperties.sensorId));
        proto.write(SensorStateProto.SCHEDULER, mScheduler.dumpProtoState(clearSchedulerBuffer));

        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(SensorStateProto.USER_STATES);
            proto.write(UserStateProto.USER_ID, userId);
            proto.write(UserStateProto.NUM_ENROLLED,
                    FingerprintUtils.getInstance(mSensorProperties.sensorId)
                            .getBiometricsForUser(mContext, userId).size());
            proto.end(userToken);
        }

        proto.write(SensorStateProto.RESET_LOCKOUT_REQUIRES_HARDWARE_AUTH_TOKEN,
                mSensorProperties.resetLockoutRequiresHardwareAuthToken);
        proto.write(SensorStateProto.RESET_LOCKOUT_REQUIRES_CHALLENGE,
                mSensorProperties.resetLockoutRequiresChallenge);

        proto.end(sensorToken);
    }

    public void onBinderDied() {
        final BaseClientMonitor client = mScheduler.getCurrentClient();
        if (client instanceof ErrorConsumer) {
            Slog.e(mTag, "Sending ERROR_HW_UNAVAILABLE for client: " + client);
            final ErrorConsumer errorConsumer = (ErrorConsumer) client;
            errorConsumer.onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                    BiometricsProtoEnums.MODALITY_FINGERPRINT,
                    BiometricsProtoEnums.ISSUE_HAL_DEATH,
                    -1 /* sensorId */);
        }

        mScheduler.recordCrashState();
        mScheduler.reset();
        mCurrentSession = null;
    }
}
