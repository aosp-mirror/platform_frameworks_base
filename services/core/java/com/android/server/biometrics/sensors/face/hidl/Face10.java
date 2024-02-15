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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.AuthenticationStatsBroadcastReceiver;
import com.android.server.biometrics.AuthenticationStatsCollector;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.face.FaceUtils;
import com.android.server.biometrics.sensors.face.LockoutHalImpl;
import com.android.server.biometrics.sensors.face.ServiceProvider;
import com.android.server.biometrics.sensors.face.UsageStats;
import com.android.server.biometrics.sensors.face.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.face.aidl.AidlSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Supports a single instance of the {@link android.hardware.biometrics.face.V1_0} or its extended
 * minor versions.
 */
public class Face10 implements IHwBinder.DeathRecipient, ServiceProvider {

    private static final String TAG = "Face10";

    private static final int ENROLL_TIMEOUT_SEC = 75;
    private static final int GENERATE_CHALLENGE_REUSE_INTERVAL_MILLIS = 60 * 1000;
    private static final int GENERATE_CHALLENGE_COUNTER_TTL_MILLIS =
            FaceGenerateChallengeClient.CHALLENGE_TIMEOUT_SEC * 1000;
    @VisibleForTesting
    public static Clock sSystemClock = Clock.systemUTC();

    private boolean mTestHalEnabled;

    @NonNull private final FaceSensorPropertiesInternal mSensorProperties;
    @NonNull private final BiometricStateCallback mBiometricStateCallback;
    @NonNull
    private final AuthenticationStateListeners mAuthenticationStateListeners;
    @NonNull private final Context mContext;
    @NonNull private final BiometricScheduler<IBiometricsFace, AidlSession> mScheduler;
    @NonNull private final Handler mHandler;
    @NonNull private final Supplier<IBiometricsFace> mLazyDaemon;
    @NonNull private final LockoutHalImpl mLockoutTracker;
    @NonNull private final UsageStats mUsageStats;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;
    @Nullable private IBiometricsFace mDaemon;
    @NonNull private final HalResultController mHalResultController;
    @NonNull private final BiometricContext mBiometricContext;
    @Nullable private AuthenticationStatsCollector mAuthenticationStatsCollector;
    // for requests that do not use biometric prompt
    @NonNull private final AtomicLong mRequestCounter = new AtomicLong(0);
    private int mCurrentUserId = UserHandle.USER_NULL;
    private final int mSensorId;
    private final List<Long> mGeneratedChallengeCount = new ArrayList<>();
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private FaceGenerateChallengeClient mGeneratedChallengeCache = null;
    private AidlSession mSession;

    private final UserSwitchObserver mUserSwitchObserver = new SynchronousUserSwitchObserver() {
        @Override
        public void onUserSwitching(int newUserId) {
            scheduleInternalCleanup(newUserId, null /* callback */);
            scheduleGetFeature(mSensorId, new Binder(), newUserId,
                    BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION,
                    null, mContext.getOpPackageName());
        }
    };

    public static class HalResultController extends IBiometricsFaceClientCallback.Stub {
        /**
         * Interface to sends results to the HalResultController's owner.
         */
        public interface Callback {
            /**
             * Invoked when the HAL sends ERROR_HW_UNAVAILABLE.
             */
            void onHardwareUnavailable();
        }

        private final int mSensorId;
        @NonNull private final Context mContext;
        @NonNull private final Handler mHandler;
        @NonNull private final BiometricScheduler<IBiometricsFace, AidlSession> mScheduler;
        @Nullable private Callback mCallback;
        @NonNull private final LockoutHalImpl mLockoutTracker;
        @NonNull private final LockoutResetDispatcher mLockoutResetDispatcher;


        HalResultController(int sensorId, @NonNull Context context, @NonNull Handler handler,
                @NonNull BiometricScheduler<IBiometricsFace, AidlSession> scheduler,
                @NonNull LockoutHalImpl lockoutTracker,
                @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
            mSensorId = sensorId;
            mContext = context;
            mHandler = handler;
            mScheduler = scheduler;
            mLockoutTracker = lockoutTracker;
            mLockoutResetDispatcher = lockoutResetDispatcher;
        }

        public void setCallback(@Nullable Callback callback) {
            mCallback = callback;
        }

        @Override
        public void onEnrollResult(long deviceId, int faceId, int userId, int remaining) {
            mHandler.post(() -> {
                final CharSequence name = FaceUtils.getLegacyInstance(mSensorId)
                        .getUniqueName(mContext, userId);
                final Face face = new Face(name, faceId, deviceId);

                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceEnrollClient)) {
                    Slog.e(TAG, "onEnrollResult for non-enroll client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceEnrollClient enrollClient = (FaceEnrollClient) client;
                enrollClient.onEnrollResult(face, remaining);
            });
        }

        @Override
        public void onAuthenticated(long deviceId, int faceId, int userId,
                ArrayList<Byte> token) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(TAG, "onAuthenticated for non-authentication consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                final boolean authenticated = faceId != 0;
                final Face face = new Face("", faceId, deviceId);
                authenticationConsumer.onAuthenticated(face, authenticated, token);
            });
        }

        @Override
        public void onAcquired(long deviceId, int userId, int acquiredInfo,
                int vendorCode) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(TAG, "onAcquired for non-acquire client: "
                            + Utils.getClientName(client));
                    return;
                }

                final AcquisitionClient<?> acquisitionClient =
                        (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onError(long deviceId, int userId, int error, int vendorCode) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                Slog.d(TAG, "handleError"
                        + ", client: " + (client != null ? client.getOwnerString() : null)
                        + ", error: " + error
                        + ", vendorCode: " + vendorCode);
                if (!(client instanceof ErrorConsumer)) {
                    Slog.e(TAG, "onError for non-error consumer: " + Utils.getClientName(
                            client));
                    return;
                }

                final ErrorConsumer errorConsumer = (ErrorConsumer) client;
                errorConsumer.onError(error, vendorCode);

                if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                    Slog.e(TAG, "Got ERROR_HW_UNAVAILABLE");
                    if (mCallback != null) {
                        mCallback.onHardwareUnavailable();
                    }
                }
            });
        }

        @Override
        public void onRemoved(long deviceId, ArrayList<Integer> removed, int userId) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof RemovalConsumer)) {
                    Slog.e(TAG, "onRemoved for non-removal consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final RemovalConsumer removalConsumer = (RemovalConsumer) client;

                if (!removed.isEmpty()) {
                    // Convert to old fingerprint-like behavior, where remove() receives
                    // one removal at a time. This way, remove can share some more common code.
                    for (int i = 0; i < removed.size(); i++) {
                        final int id = removed.get(i);
                        final Face face = new Face("", id, deviceId);
                        final int remaining = removed.size() - i - 1;
                        Slog.d(TAG, "Removed, faceId: " + id + ", remaining: " + remaining);
                        removalConsumer.onRemoved(face, remaining);
                    }
                } else {
                    removalConsumer.onRemoved(null, 0 /* remaining */);
                }

                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_RE_ENROLL, 0, UserHandle.USER_CURRENT);
            });
        }

        @Override
        public void onEnumerate(long deviceId, ArrayList<Integer> faceIds, int userId) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof EnumerateConsumer)) {
                    Slog.e(TAG, "onEnumerate for non-enumerate consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final EnumerateConsumer enumerateConsumer = (EnumerateConsumer) client;

                if (!faceIds.isEmpty()) {
                    // Convert to old fingerprint-like behavior, where enumerate() receives one
                    // template at a time. This way, enumerate can share some more common code.
                    for (int i = 0; i < faceIds.size(); i++) {
                        final Face face = new Face("", faceIds.get(i), deviceId);
                        enumerateConsumer.onEnumerationResult(face, faceIds.size() - i - 1);
                    }
                } else {
                    // For face, the HIDL contract is to receive an empty list when there are no
                    // templates enrolled. Send a null identifier since we don't consume them
                    // anywhere, and send remaining == 0 so this code can be shared with Face@1.1
                    enumerateConsumer.onEnumerationResult(null /* identifier */, 0);
                }
            });
        }

        @Override
        public void onLockoutChanged(long duration) {
            mHandler.post(() -> {
                Slog.d(TAG, "onLockoutChanged: " + duration);
                final @LockoutTracker.LockoutMode int lockoutMode;
                if (duration == 0) {
                    lockoutMode = LockoutTracker.LOCKOUT_NONE;
                } else if (duration == -1 || duration == Long.MAX_VALUE) {
                    lockoutMode = LockoutTracker.LOCKOUT_PERMANENT;
                } else {
                    lockoutMode = LockoutTracker.LOCKOUT_TIMED;
                }

                mLockoutTracker.setCurrentUserLockoutMode(lockoutMode);

                if (duration == 0) {
                    mLockoutResetDispatcher.notifyLockoutResetCallbacks(mSensorId);
                }
            });
        }
    }

    @VisibleForTesting
    Face10(@NonNull Context context,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            @NonNull FaceSensorPropertiesInternal sensorProps,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull Handler handler,
            @NonNull BiometricScheduler<IBiometricsFace, AidlSession> scheduler,
            @NonNull BiometricContext biometricContext) {
        mSensorProperties = sensorProps;
        mContext = context;
        mBiometricStateCallback = biometricStateCallback;
        mAuthenticationStateListeners = authenticationStateListeners;
        mSensorId = sensorProps.sensorId;
        mScheduler = scheduler;
        mHandler = handler;
        mBiometricContext = biometricContext;
        mUsageStats = new UsageStats(context);
        mAuthenticatorIds = new HashMap<>();
        mLazyDaemon = Face10.this::getDaemon;
        mLockoutTracker = new LockoutHalImpl();
        mHalResultController = new HalResultController(sensorProps.sensorId, context, mHandler,
                mScheduler, mLockoutTracker, lockoutResetDispatcher);
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mHalResultController.setCallback(() -> {
            mDaemon = null;
            mCurrentUserId = UserHandle.USER_NULL;
        });

        AuthenticationStatsBroadcastReceiver mBroadcastReceiver =
                new AuthenticationStatsBroadcastReceiver(
                        mContext,
                        BiometricsProtoEnums.MODALITY_FACE,
                        (AuthenticationStatsCollector collector) -> {
                            Slog.d(TAG, "Initializing AuthenticationStatsCollector");
                            mAuthenticationStatsCollector = collector;
                        });

        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, TAG);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register user switch observer");
        }
    }

    public static Face10 newInstance(@NonNull Context context,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            @NonNull FaceSensorPropertiesInternal sensorProps,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        final Handler handler = new Handler(Looper.getMainLooper());
        return new Face10(context, biometricStateCallback, authenticationStateListeners,
                sensorProps, lockoutResetDispatcher, handler, new BiometricScheduler<>(
                        BiometricScheduler.SENSOR_TYPE_FACE,
                        null /* gestureAvailabilityTracker */),
                BiometricContext.getInstance(context));
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.e(TAG, "HAL died");
        mHandler.post(() -> {
            PerformanceTracker.getInstanceForSensorId(mSensorId)
                    .incrementHALDeathCount();
            mDaemon = null;
            mCurrentUserId = UserHandle.USER_NULL;

            final BaseClientMonitor client = mScheduler.getCurrentClient();
            if (client instanceof ErrorConsumer) {
                Slog.e(TAG, "Sending ERROR_HW_UNAVAILABLE for client: " + client);
                final ErrorConsumer errorConsumer = (ErrorConsumer) client;
                errorConsumer.onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);

                FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                        BiometricsProtoEnums.MODALITY_FACE,
                        BiometricsProtoEnums.ISSUE_HAL_DEATH,
                        -1 /* sensorId */);
            }

            mScheduler.recordCrashState();
            mScheduler.reset();
        });
    }

    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    synchronized AidlSession getSession() {
        if (mDaemon != null && mSession != null) {
            return mSession;
        } else {
            return mSession = new AidlSession(mContext, this::getDaemon, mCurrentUserId,
                    new AidlResponseHandler(mContext, mScheduler, mSensorId,
                            mCurrentUserId, mLockoutTracker, mLockoutResetDispatcher,
                            new AuthSessionCoordinator(), () -> {
                        mDaemon = null;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }));
        }
    }

    private synchronized IBiometricsFace getDaemon() {
        if (mTestHalEnabled) {
            final TestHal testHal = new TestHal(mContext, mSensorId);
            testHal.setCallback(mHalResultController);
            return testHal;
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(TAG, "Daemon was null, reconnecting, current operation: "
                + mScheduler.getCurrentClient());

        try {
            mDaemon = IBiometricsFace.getService();
        } catch (java.util.NoSuchElementException e) {
            // Service doesn't exist or cannot be opened.
            Slog.w(TAG, "NoSuchElementException", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get face HAL", e);
        }

        if (mDaemon == null) {
            Slog.w(TAG, "Face HAL not available");
            return null;
        }

        mDaemon.asBinder().linkToDeath(this, 0 /* flags */);

        // HAL ID for these HIDL versions are only used to determine if callbacks have been
        // successfully set.
        long halId = 0;
        try {
            halId = mDaemon.setCallback(mHalResultController).value;
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set callback for face HAL", e);
            mDaemon = null;
        }

        Slog.d(TAG, "Face HAL ready, HAL ID: " + halId);
        if (halId != 0) {
            scheduleLoadAuthenticatorIds();
            scheduleInternalCleanup(ActivityManager.getCurrentUser(), null /* callback */);
            scheduleGetFeature(mSensorId, new Binder(),
                    ActivityManager.getCurrentUser(),
                    BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION, null,
                    mContext.getOpPackageName());
        } else {
            Slog.e(TAG, "Unable to set callback");
            mDaemon = null;
        }

        return mDaemon;
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensorId == sensorId;
    }

    @Override
    @NonNull
    public List<FaceSensorPropertiesInternal> getSensorProperties() {
        final List<FaceSensorPropertiesInternal> properties = new ArrayList<>();
        properties.add(mSensorProperties);
        return properties;
    }

    @NonNull
    @Override
    public FaceSensorPropertiesInternal getSensorProperties(int sensorId) {
        return mSensorProperties;
    }

    @Override
    @NonNull
    public List<Face> getEnrolledFaces(int sensorId, int userId) {
        return FaceUtils.getLegacyInstance(mSensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    public boolean hasEnrollments(int sensorId, int userId) {
        return !getEnrolledFaces(sensorId, userId).isEmpty();
    }

    @Override
    @LockoutTracker.LockoutMode
    public int getLockoutModeForUser(int sensorId, int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return mAuthenticatorIds.getOrDefault(userId, 0L);
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return getDaemon() != null;
    }

    private boolean isGeneratedChallengeCacheValid() {
        return mGeneratedChallengeCache != null
                && sSystemClock.millis() - mGeneratedChallengeCache.getCreatedAt()
                < GENERATE_CHALLENGE_REUSE_INTERVAL_MILLIS;
    }

    private void incrementChallengeCount() {
        mGeneratedChallengeCount.add(0, sSystemClock.millis());
    }

    private int decrementChallengeCount() {
        final long now = sSystemClock.millis();
        // ignore values that are old in case generate/revoke calls are not matched
        // this doesn't ensure revoke if calls are mismatched but it keeps the list from growing
        mGeneratedChallengeCount.removeIf(x -> now - x > GENERATE_CHALLENGE_COUNTER_TTL_MILLIS);
        if (!mGeneratedChallengeCount.isEmpty()) {
            mGeneratedChallengeCount.remove(0);
        }
        return mGeneratedChallengeCount.size();
    }

    /**
     * {@link IBiometricsFace} only supports a single in-flight challenge but there are cases where
     * two callers both need challenges (e.g. resetLockout right before enrollment).
     */
    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            if (Flags.deHidl()) {
                scheduleGenerateChallengeAidl(userId, token, receiver, opPackageName);
            } else {
                scheduleGenerateChallengeHidl(userId, token, receiver, opPackageName);
            }
        });
    }

    private void scheduleGenerateChallengeAidl(int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        final com.android.server.biometrics.sensors.face.aidl.FaceGenerateChallengeClient client =
                new com.android.server.biometrics.sensors.face.aidl.FaceGenerateChallengeClient(
                        mContext, this::getSession, token,
                        new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                        mSensorId, createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                        mBiometricContext);
        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                if (client != clientMonitor) {
                    Slog.e(TAG,
                            "scheduleGenerateChallenge onClientStarted, mismatched client."
                                    + " Expecting: " + client + ", received: "
                                    + clientMonitor);
                }
            }
        });
    }

    private void scheduleGenerateChallengeHidl(int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        incrementChallengeCount();
        if (isGeneratedChallengeCacheValid()) {
            Slog.d(TAG, "Current challenge is cached and will be reused");
            mGeneratedChallengeCache.reuseResult(receiver);
            return;
        }

        final FaceGenerateChallengeClient client = new FaceGenerateChallengeClient(mContext,
                mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId,
                opPackageName, mSensorId, createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, sSystemClock.millis());
        mGeneratedChallengeCache = client;
        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                if (client != clientMonitor) {
                    Slog.e(TAG,
                            "scheduleGenerateChallenge onClientStarted, mismatched client."
                                    + " Expecting: " + client + ", received: "
                                    + clientMonitor);
                }
            }
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            if (Flags.deHidl()) {
                scheduleRevokeChallengeAidl(userId, token, opPackageName);
            } else {
                scheduleRevokeChallengeHidl(userId, token, opPackageName);
            }
        });
    }

    private void scheduleRevokeChallengeAidl(int userId, @NonNull IBinder token,
            @NonNull String opPackageName) {
        final com.android.server.biometrics.sensors.face.aidl.FaceRevokeChallengeClient
                client =
                new com.android.server.biometrics.sensors.face.aidl.FaceRevokeChallengeClient(
                        mContext, this::getSession, token, userId, opPackageName, mSensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector), mBiometricContext, 0L);
        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                if (client != clientMonitor) {
                    Slog.e(TAG,
                            "scheduleRevokeChallenge, mismatched client." + "Expecting: "
                                    + client + ", received: " + clientMonitor);
                }
            }
        });
    }

    private void scheduleRevokeChallengeHidl(int userId, @NonNull IBinder token,
            @NonNull String opPackageName) {
        final boolean shouldRevoke = decrementChallengeCount() == 0;
        if (!shouldRevoke) {
            Slog.w(TAG, "scheduleRevokeChallenge skipped - challenge still in use: "
                    + mGeneratedChallengeCount);
            return;
        }

        Slog.d(TAG, "scheduleRevokeChallenge executing - no active clients");
        mGeneratedChallengeCache = null;
        final FaceRevokeChallengeClient client = new FaceRevokeChallengeClient(mContext,
                mLazyDaemon, token, userId, opPackageName, mSensorId,
                createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext);
        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                if (client != clientMonitor) {
                    Slog.e(TAG,
                            "scheduleRevokeChallenge, mismatched client." + "Expecting: "
                                    + client + ", received: " + clientMonitor);
                }
            }
        });
    }

    @Override
    public long scheduleEnroll(int sensorId, @NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName, @NonNull int[] disabledFeatures,
            @Nullable Surface previewSurface, boolean debugConsent) {
        final long id = mRequestCounter.incrementAndGet();
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);
            if (Flags.deHidl()) {
                scheduleEnrollAidl(token, hardwareAuthToken, userId, receiver,
                        opPackageName, disabledFeatures, previewSurface, id);
            } else {
                scheduleEnrollHidl(token, hardwareAuthToken, userId, receiver,
                        opPackageName, disabledFeatures, previewSurface, id);
            }
        });
        return id;
    }

    private void scheduleEnrollAidl(@NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName, @NonNull int[] disabledFeatures,
            @Nullable Surface previewSurface, long id) {
        final com.android.server.biometrics.sensors.face.aidl.FaceEnrollClient client =
                new com.android.server.biometrics.sensors.face.aidl.FaceEnrollClient(
                        mContext, this::getSession, token,
                        new ClientMonitorCallbackConverter(receiver), userId,
                        hardwareAuthToken, opPackageName, id,
                        FaceUtils.getLegacyInstance(mSensorId), disabledFeatures,
                        ENROLL_TIMEOUT_SEC, previewSurface, mSensorId,
                        createLogger(BiometricsProtoEnums.ACTION_ENROLL,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector), mBiometricContext,
                        mContext.getResources().getInteger(
                                com.android.internal.R.integer.config_faceMaxTemplatesPerUser),
                        false);

        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                mBiometricStateCallback.onClientStarted(clientMonitor);
            }

            @Override
            public void onBiometricAction(int action) {
                mBiometricStateCallback.onBiometricAction(action);
            }

            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                mBiometricStateCallback.onClientFinished(clientMonitor, success);
                if (success) {
                    // Update authenticatorIds
                    scheduleUpdateActiveUserWithoutHandler(client.getTargetUserId());
                }
            }
        });
    }

    private void scheduleEnrollHidl(@NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName, @NonNull int[] disabledFeatures,
            @Nullable Surface previewSurface, long id) {
            final FaceEnrollClient client = new FaceEnrollClient(mContext, mLazyDaemon, token,
                    new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                    opPackageName, id, FaceUtils.getLegacyInstance(mSensorId), disabledFeatures,
                    ENROLL_TIMEOUT_SEC, previewSurface, mSensorId,
                    createLogger(BiometricsProtoEnums.ACTION_ENROLL,
                            BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                    mBiometricContext);
            mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
                @Override
                public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                    mBiometricStateCallback.onClientStarted(clientMonitor);
                }

                @Override
                public void onBiometricAction(int action) {
                    mBiometricStateCallback.onBiometricAction(action);
                }

                @Override
                public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                        boolean success) {
                    mBiometricStateCallback.onClientFinished(clientMonitor, success);
                    if (success) {
                        // Update authenticatorIds
                        scheduleUpdateActiveUserWithoutHandler(client.getTargetUserId());
                    }
                }
            });
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token, long requestId) {
        mHandler.post(() -> mScheduler.cancelEnrollment(token, requestId));
    }

    @Override
    public long scheduleFaceDetect(@NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options, int statsClient) {
        throw new IllegalStateException("Face detect not supported by IBiometricsFace@1.0. Did you"
                + "forget to check the supportsFaceDetection flag?");
    }

    @Override
    public void cancelFaceDetect(int sensorId, @NonNull IBinder token, long requestId) {
        throw new IllegalStateException("Face detect not supported by IBiometricsFace@1.0. Did you"
                + "forget to check the supportsFaceDetection flag?");
    }

    @Override
    public void scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter receiver,
            @NonNull FaceAuthenticateOptions options, long requestId, boolean restricted,
            int statsClient, boolean allowBackgroundAuthentication) {
        mHandler.post(() -> {
            final int userId = options.getUserId();
            scheduleUpdateActiveUserWithoutHandler(userId);

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorId);
            if (Flags.deHidl()) {
                scheduleAuthenticateAidl(token, operationId, cookie, receiver, options, requestId,
                        restricted, statsClient, allowBackgroundAuthentication, isStrongBiometric);
            } else {
                scheduleAuthenticateHidl(token, operationId, cookie, receiver, options, requestId,
                        restricted, statsClient, allowBackgroundAuthentication, isStrongBiometric);
            }
        });
    }

    private void scheduleAuthenticateAidl(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter receiver,
            @NonNull FaceAuthenticateOptions options, long requestId, boolean restricted,
            int statsClient, boolean allowBackgroundAuthentication, boolean isStrongBiometric) {
        final com.android.server.biometrics.sensors.face.aidl.FaceAuthenticationClient
                client =
                new com.android.server.biometrics.sensors.face.aidl.FaceAuthenticationClient(
                        mContext, this::getSession, token, requestId, receiver, operationId,
                        restricted, options, cookie, false /* requireConfirmation */,
                        createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                                mAuthenticationStatsCollector), mBiometricContext,
                        isStrongBiometric, mUsageStats, mLockoutTracker,
                        allowBackgroundAuthentication, Utils.getCurrentStrength(mSensorId),
                        mAuthenticationStateListeners);
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleAuthenticateHidl(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter receiver,
            @NonNull FaceAuthenticateOptions options, long requestId, boolean restricted,
            int statsClient, boolean allowBackgroundAuthentication, boolean isStrongBiometric) {
        final FaceAuthenticationClient client = new FaceAuthenticationClient(mContext,
                mLazyDaemon, token, requestId, receiver, operationId, restricted, options,
                cookie, false /* requireConfirmation */,
                createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                        mAuthenticationStatsCollector), mBiometricContext,
                isStrongBiometric, mLockoutTracker, mUsageStats,
                allowBackgroundAuthentication, Utils.getCurrentStrength(mSensorId),
                mAuthenticationStateListeners);
        mScheduler.scheduleClientMonitor(client);
    }

    @Override
    public long scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter receiver,
            @NonNull FaceAuthenticateOptions options, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication) {
        final long id = mRequestCounter.incrementAndGet();

        scheduleAuthenticate(token, operationId, cookie, receiver,
                options, id, restricted, statsClient, allowBackgroundAuthentication);

        return id;
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token, long requestId) {
        mHandler.post(() -> mScheduler.cancelAuthenticationOrDetection(token, requestId));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token, int faceId, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            if (Flags.deHidl()) {
                scheduleRemoveAidl(token, userId, receiver, opPackageName, faceId);
            } else {
                scheduleRemoveHidl(token, userId, receiver, opPackageName, faceId);
            }
        });
    }

    @Override
    public void scheduleRemoveAll(int sensorId, @NonNull IBinder token, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            // For IBiometricsFace@1.0, remove(0) means remove all enrollments
            if (Flags.deHidl()) {
                scheduleRemoveAidl(token, userId, receiver, opPackageName, 0);
            } else {
                scheduleRemoveHidl(token, userId, receiver, opPackageName, 0);
            }
        });
    }

    private void scheduleRemoveAidl(@NonNull IBinder token, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName, int faceId) {
        final com.android.server.biometrics.sensors.face.aidl.FaceRemovalClient client =
                new com.android.server.biometrics.sensors.face.aidl.FaceRemovalClient(
                        mContext, this::getSession, token,
                        new ClientMonitorCallbackConverter(receiver), new int[]{faceId}, userId,
                        opPackageName, FaceUtils.getLegacyInstance(mSensorId), mSensorId,
                        createLogger(BiometricsProtoEnums.ACTION_REMOVE,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector), mBiometricContext,
                        mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    private void scheduleRemoveHidl(@NonNull IBinder token, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName, int faceId) {
        final FaceRemovalClient client = new FaceRemovalClient(mContext, mLazyDaemon, token,
                new ClientMonitorCallbackConverter(receiver), faceId, userId,
                opPackageName, FaceUtils.getLegacyInstance(mSensorId), mSensorId,
                createLogger(BiometricsProtoEnums.ACTION_REMOVE,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @NonNull byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            if (getEnrolledFaces(sensorId, userId).isEmpty()) {
                Slog.w(TAG, "Ignoring lockout reset, no templates enrolled for user: " + userId);
                return;
            }

            scheduleUpdateActiveUserWithoutHandler(userId);
            if (Flags.deHidl()) {
                scheduleResetLockoutAidl(userId, hardwareAuthToken);
            } else {
                scheduleResetLockoutHidl(userId, hardwareAuthToken);
            }
        });
    }

    private void scheduleResetLockoutAidl(int userId,
            @NonNull byte[] hardwareAuthToken) {
        final com.android.server.biometrics.sensors.face.aidl.FaceResetLockoutClient client =
                new com.android.server.biometrics.sensors.face.aidl.FaceResetLockoutClient(
                        mContext, this::getSession, userId, mContext.getOpPackageName(),
                        mSensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext, hardwareAuthToken, mLockoutTracker,
                        mLockoutResetDispatcher, mSensorProperties.sensorStrength);
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleResetLockoutHidl(int userId,
            @NonNull byte[] hardwareAuthToken) {
        final FaceResetLockoutClient client = new FaceResetLockoutClient(mContext,
                mLazyDaemon,
                userId, mContext.getOpPackageName(), mSensorId,
                createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, hardwareAuthToken);
        mScheduler.scheduleClientMonitor(client);
    }

    @Override
    public void scheduleSetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);
            if (Flags.deHidl()) {
                scheduleSetFeatureAidl(sensorId, token, userId, feature, enabled, hardwareAuthToken,
                        receiver, opPackageName);
            } else {
                scheduleSetFeatureHidl(sensorId, token, userId, feature, enabled, hardwareAuthToken,
                        receiver, opPackageName);
            }
        });
    }

    private void scheduleSetFeatureHidl(int sensorId, @NonNull IBinder token, int userId,
            int feature, boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        final List<Face> faces = getEnrolledFaces(sensorId, userId);
        if (faces.isEmpty()) {
            Slog.w(TAG, "Ignoring setFeature, no templates enrolled for user: " + userId);
            return;
        }
        final int faceId = faces.get(0).getBiometricId();
        final FaceSetFeatureClient client = new FaceSetFeatureClient(mContext, mLazyDaemon,
                token, new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                mSensorId, BiometricLogger.ofUnknown(mContext), mBiometricContext, feature,
                enabled, hardwareAuthToken, faceId);
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleSetFeatureAidl(int sensorId, @NonNull IBinder token, int userId,
            int feature, boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        final com.android.server.biometrics.sensors.face.aidl.FaceSetFeatureClient client =
                new com.android.server.biometrics.sensors.face.aidl.FaceSetFeatureClient(
                        mContext, this::getSession, token,
                        new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                        mSensorId, BiometricLogger.ofUnknown(mContext), mBiometricContext,
                        feature, enabled, hardwareAuthToken);
        mScheduler.scheduleClientMonitor(client);
    }


    @Override
    public void scheduleGetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            @Nullable ClientMonitorCallbackConverter listener, @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            if (Flags.deHidl()) {
                scheduleGetFeatureAidl(token, userId, feature, listener,
                        opPackageName);
            } else {
                scheduleGetFeatureHidl(sensorId, token, userId, feature, listener,
                        opPackageName);
            }
        });
    }

    private void scheduleGetFeatureHidl(int sensorId, @NonNull IBinder token, int userId,
            int feature, @Nullable ClientMonitorCallbackConverter listener,
            @NonNull String opPackageName) {
        final List<Face> faces = getEnrolledFaces(sensorId, userId);
        if (faces.isEmpty()) {
            Slog.w(TAG, "Ignoring getFeature, no templates enrolled for user: " + userId);
            return;
        }

        final int faceId = faces.get(0).getBiometricId();
        final FaceGetFeatureClient client = new FaceGetFeatureClient(mContext, mLazyDaemon,
                token, listener, userId, opPackageName, mSensorId,
                BiometricLogger.ofUnknown(mContext), mBiometricContext, feature, faceId);
        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                if (success
                        && feature == BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION) {
                    final int settingsValue = client.getValue() ? 1 : 0;
                    Slog.d(TAG,
                            "Updating attention value for user: " + userId + " to value: "
                                    + settingsValue);
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                            Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED, settingsValue,
                            userId);
                }
            }
        });
    }

    private void scheduleGetFeatureAidl(@NonNull IBinder token, int userId,
            int feature, @Nullable ClientMonitorCallbackConverter listener,
            @NonNull String opPackageName) {
        final com.android.server.biometrics.sensors.face.aidl.FaceGetFeatureClient client =
                new com.android.server.biometrics.sensors.face.aidl.FaceGetFeatureClient(
                        mContext, this::getSession, token, listener, userId, opPackageName,
                        mSensorId, BiometricLogger.ofUnknown(mContext), mBiometricContext,
                        feature);
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleInternalCleanup(int userId,
            @Nullable ClientMonitorCallback callback) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);
            if (Flags.deHidl()) {
                scheduleInternalCleanupAidl(userId, callback);
            } else {
                scheduleInternalCleanupHidl(userId, callback);
            }
        });
    }

    private void scheduleInternalCleanupHidl(int userId,
            @Nullable ClientMonitorCallback callback) {
        final FaceInternalCleanupClient client = new FaceInternalCleanupClient(mContext,
                mLazyDaemon, userId, mContext.getOpPackageName(), mSensorId,
                createLogger(BiometricsProtoEnums.ACTION_ENUMERATE,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, FaceUtils.getLegacyInstance(mSensorId),
                mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client,
                new ClientMonitorCompositeCallback(callback, mBiometricStateCallback));
    }

    private void scheduleInternalCleanupAidl(int userId,
            @Nullable ClientMonitorCallback callback) {
        final com.android.server.biometrics.sensors.face.aidl.FaceInternalCleanupClient
                client =
                new com.android.server.biometrics.sensors.face.aidl.FaceInternalCleanupClient(
                        mContext, this::getSession, userId, mContext.getOpPackageName(),
                        mSensorId, createLogger(BiometricsProtoEnums.ACTION_ENUMERATE,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                        mBiometricContext, FaceUtils.getLegacyInstance(mSensorId),
                        mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client,
                new ClientMonitorCompositeCallback(callback, mBiometricStateCallback));
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback) {
        scheduleInternalCleanup(userId, mBiometricStateCallback);
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback, boolean favorHalEnrollments) {
        scheduleInternalCleanup(userId, callback);
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> {
            mScheduler.startPreparedClient(cookie);
        });
    }

    @Override
    public void dumpProtoState(int sensorId, ProtoOutputStream proto,
            boolean clearSchedulerBuffer) {
        final long sensorToken = proto.start(SensorServiceStateProto.SENSOR_STATES);

        proto.write(SensorStateProto.SENSOR_ID, mSensorProperties.sensorId);
        proto.write(SensorStateProto.MODALITY, SensorStateProto.FACE);
        proto.write(SensorStateProto.CURRENT_STRENGTH,
                Utils.getCurrentStrength(mSensorProperties.sensorId));
        proto.write(SensorStateProto.SCHEDULER, mScheduler.dumpProtoState(clearSchedulerBuffer));

        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(SensorStateProto.USER_STATES);
            proto.write(UserStateProto.USER_ID, userId);
            proto.write(UserStateProto.NUM_ENROLLED, FaceUtils.getLegacyInstance(mSensorId)
                    .getBiometricsForUser(mContext, userId).size());
            proto.end(userToken);
        }

        proto.write(SensorStateProto.RESET_LOCKOUT_REQUIRES_HARDWARE_AUTH_TOKEN,
                mSensorProperties.resetLockoutRequiresHardwareAuthToken);
        proto.write(SensorStateProto.RESET_LOCKOUT_REQUIRES_CHALLENGE,
                mSensorProperties.resetLockoutRequiresChallenge);

        proto.end(sensorToken);
    }

    @Override
    public void dumpProtoMetrics(int sensorId, FileDescriptor fd) {
    }

    @Override
    public void dumpInternal(int sensorId, PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(mSensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", TAG);

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int c = FaceUtils.getLegacyInstance(mSensorId)
                        .getBiometricsForUser(mContext, userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", c);
                set.put("accept", performanceTracker.getAcceptForUser(userId));
                set.put("reject", performanceTracker.getRejectForUser(userId));
                set.put("acquire", performanceTracker.getAcquireForUser(userId));
                set.put("lockout", performanceTracker.getTimedLockoutForUser(userId));
                set.put("permanentLockout", performanceTracker.getPermanentLockoutForUser(userId));
                // cryptoStats measures statistics about secure face transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", performanceTracker.getAcceptCryptoForUser(userId));
                set.put("rejectCrypto", performanceTracker.getRejectCryptoForUser(userId));
                set.put("acquireCrypto", performanceTracker.getAcquireCryptoForUser(userId));
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + performanceTracker.getHALDeathCount());

        mScheduler.dump(pw);
        mUsageStats.print(pw);
    }

    private void scheduleLoadAuthenticatorIds() {
        // Note that this can be performed on the scheduler (as opposed to being done immediately
        // when the HAL is (re)loaded, since
        // 1) If this is truly the first time it's being performed (e.g. system has just started),
        //    this will be run very early and way before any applications need to generate keys.
        // 2) If this is being performed to refresh the authenticatorIds (e.g. HAL crashed and has
        //    just been reloaded), the framework already has a cache of the authenticatorIds. This
        //    is safe because authenticatorIds only change when A) new template has been enrolled,
        //    or B) all templates are removed.
        mHandler.post(() -> {
            for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
                final int targetUserId = user.id;
                if (!mAuthenticatorIds.containsKey(targetUserId)) {
                    scheduleUpdateActiveUserWithoutHandler(targetUserId);
                }
            }
        });
    }

    /**
     * Schedules the {@link FaceUpdateActiveUserClient} without posting the work onto the handler.
     * Many/most APIs are user-specific. However, the HAL requires explicit "setActiveUser"
     * invocation prior to authenticate/enroll/etc. Thus, internally we usually want to schedule
     * this operation on the same lambda/runnable as those operations so that the ordering is
     * correct.
     */
    private void scheduleUpdateActiveUserWithoutHandler(int targetUserId) {
        final boolean hasEnrolled = !getEnrolledFaces(mSensorId, targetUserId).isEmpty();
        final FaceUpdateActiveUserClient client = new FaceUpdateActiveUserClient(mContext,
                mLazyDaemon, targetUserId, mContext.getOpPackageName(), mSensorId,
                createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, hasEnrolled, mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                if (success) {
                    if (mCurrentUserId != targetUserId) {
                        // Create new session with updated user ID
                        mSession = null;
                    }
                    mCurrentUserId = targetUserId;
                } else {
                    Slog.w(TAG, "Failed to change user, still: " + mCurrentUserId);
                }
            }
        });
    }

    private BiometricLogger createLogger(int statsAction, int statsClient,
            AuthenticationStatsCollector authenticationStatsCollector) {
        return new BiometricLogger(mContext, BiometricsProtoEnums.MODALITY_FACE,
                statsAction, statsClient, authenticationStatsCollector);
    }

    /**
     * Sends a debug message to the HAL with the provided FileDescriptor and arguments.
     */
    public void dumpHal(int sensorId, @NonNull FileDescriptor fd, @NonNull String[] args) {
        // WARNING: CDD restricts image data from leaving TEE unencrypted on
        //          production devices:
        // [C-1-10] MUST not allow unencrypted access to identifiable biometric
        //          data or any data derived from it (such as embeddings) to the
        //         Application Processor outside the context of the TEE.
        //  As such, this API should only be enabled for testing purposes on
        //  engineering and userdebug builds.  All modules in the software stack
        //  MUST enforce final build products do NOT have this functionality.
        //  Additionally, the following check MUST NOT be removed.
        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return;
        }

        // Additionally, this flag allows turning off face for a device
        // (either permanently through the build or on an individual device).
        if (SystemProperties.getBoolean("ro.face.disable_debug_data", false)
                || SystemProperties.getBoolean("persist.face.disable_debug_data", false)) {
            return;
        }

        // The debug method takes two file descriptors. The first is for text
        // output, which we will drop.  The second is for binary data, which
        // will be the protobuf data.
        final IBiometricsFace daemon = getDaemon();
        if (daemon != null) {
            FileOutputStream devnull = null;
            try {
                devnull = new FileOutputStream("/dev/null");
                final NativeHandle handle = new NativeHandle(
                        new FileDescriptor[]{devnull.getFD(), fd},
                        new int[0], false);
                daemon.debug(handle, new ArrayList<String>(Arrays.asList(args)));
            } catch (IOException | RemoteException ex) {
                Slog.d(TAG, "error while reading face debugging data", ex);
            } finally {
                if (devnull != null) {
                    try {
                        devnull.close();
                    } catch (IOException ex) {
                    }
                }
            }
        }
    }

    void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }

    @NonNull
    @Override
    public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull String opPackageName) {
        return new BiometricTestSessionImpl(mContext, mSensorId, callback,
                this, mHalResultController);
    }
}
