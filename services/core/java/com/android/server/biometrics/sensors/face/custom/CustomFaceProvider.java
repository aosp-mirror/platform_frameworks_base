/*
* Copyright (C) 2022 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import android.app.ActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import android.annotation.NonNull;

import com.android.internal.util.custom.faceunlock.FaceUnlockUtils;
import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.face.FaceUtils;
import com.android.server.biometrics.sensors.face.LockoutHalImpl;
import com.android.server.biometrics.sensors.face.ServiceProvider;
import com.android.server.biometrics.sensors.face.UsageStats;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class CustomFaceProvider implements ServiceProvider {
    public static final int DEVICE_ID = 1008;
    private static final int ENROLL_TIMEOUT_SEC = 75;
    private static final int GENERATE_CHALLENGE_COUNTER_TTL_MILLIS = 600000;
    private static final int GENERATE_CHALLENGE_REUSE_INTERVAL_MILLIS = 60000;
    private static final String TAG = CustomFaceProvider.class.getSimpleName();
    public static Clock sSystemClock = Clock.systemUTC();
    final SparseArray<IFaceService> mFaceServices;
    private final Map<Integer, Long> mAuthenticatorIds;
    private final Context mContext;
    private final List<Long> mGeneratedChallengeCount;
    private final HalResultController mHalResultController;
    private final Handler mHandler;
    private final Supplier<IFaceService> mLazyDaemon;
    private final LockoutHalImpl mLockoutTracker;
    private final BiometricScheduler mScheduler;
    private final int mSensorId;
    private final FaceSensorPropertiesInternal mSensorProperties;
    private final UsageStats mUsageStats;
    @NonNull
    private final AtomicLong mRequestCounter = new AtomicLong(0);
    private int mCurrentUserId;
    private FaceGenerateChallengeClient mGeneratedChallengeCache;
    private boolean mIsServiceBinding;
    private TestHal mTestHal;
    private boolean mTestHalEnabled;

    private BiometricContext mBiometricContext;

    CustomFaceProvider(Context context, FaceSensorPropertiesInternal sensorProps, LockoutResetDispatcher lockoutResetDispatcher, BiometricScheduler scheduler) {
        mBiometricContext = BiometricContext.getInstance(context);
        mTestHalEnabled = false;
        mCurrentUserId = -10000;
        mGeneratedChallengeCount = new ArrayList<>();
        mGeneratedChallengeCache = null;
        mFaceServices = new SparseArray<>();
        mIsServiceBinding = false;
        mSensorProperties = sensorProps;
        mContext = context;
        mSensorId = sensorProps.sensorId;
        mScheduler = scheduler;
        Handler handler = new Handler(Looper.getMainLooper());
        mHandler = handler;
        mUsageStats = new UsageStats(context);
        mAuthenticatorIds = new HashMap<>();
        mLazyDaemon = CustomFaceProvider.this::getDaemon;
        LockoutHalImpl lockoutHalImpl = new LockoutHalImpl();
        mLockoutTracker = lockoutHalImpl;
        HalResultController halResultController = new HalResultController(sensorProps.sensorId, context, handler, scheduler, lockoutHalImpl, lockoutResetDispatcher);
        mHalResultController = halResultController;
        halResultController.setCallback(() -> {
            mCurrentUserId = -10000;
        });
        mCurrentUserId = ActivityManager.getCurrentUser();
        try {
            ActivityManager.getService().registerUserSwitchObserver(new SynchronousUserSwitchObserver() {
                public void onUserSwitching(int newUserId) {
                    Slog.d(TAG, "user switch : newUserId = " + newUserId);
                    mCurrentUserId = newUserId;
                    if (getDaemon() == null) {
                        bindFaceAuthService(mCurrentUserId);
                    }
                }
            }, TAG);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register user switch observer");
        }
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context2, Intent intent) {
                if (getDaemon() == null) {
                    bindFaceAuthService(mCurrentUserId);
                }
            }
        }, new IntentFilter("android.intent.action.USER_UNLOCKED"));
    }

    public CustomFaceProvider(Context context, FaceSensorPropertiesInternal sensorProps, LockoutResetDispatcher lockoutResetDispatcher) {
        this(context, sensorProps, lockoutResetDispatcher, new BiometricScheduler(context, TAG, 0, null));
    }

    synchronized IFaceService getDaemon() {
        if (mTestHalEnabled) {
            if (mTestHal == null) {
                mTestHal = new TestHal(mCurrentUserId, mContext, mSensorId);
            }
            try {
                mTestHal.setCallback(mHalResultController);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return mTestHal;
        }
        IFaceService service = getFaceService(mCurrentUserId);
        if (service == null) {
            bindFaceAuthService(mCurrentUserId);
        }
        return service;
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensorId == sensorId;
    }

    @Override
    public List<FaceSensorPropertiesInternal> getSensorProperties() {
        List<FaceSensorPropertiesInternal> properties = new ArrayList<>();
        properties.add(mSensorProperties);
        return properties;
    }

    @Override
    public FaceSensorPropertiesInternal getSensorProperties(int sensorId) {
        return mSensorProperties;
    }

    @Override
    public List<Face> getEnrolledFaces(int sensorId, int userId) {
        return FaceUtils.getLegacyInstance(mSensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    public int getLockoutModeForUser(int sensorId, int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return mAuthenticatorIds.getOrDefault(Integer.valueOf(userId), 0L).longValue();
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return getDaemon() != null;
    }

    private boolean isGeneratedChallengeCacheValid() {
        return mGeneratedChallengeCache != null && sSystemClock.millis() - mGeneratedChallengeCache.getCreatedAt() < GENERATE_CHALLENGE_REUSE_INTERVAL_MILLIS;
    }

    private void incrementChallengeCount() {
        mGeneratedChallengeCount.add(0, sSystemClock.millis());
    }

    private int decrementChallengeCount() {
        mGeneratedChallengeCount.removeIf(aLong -> sSystemClock.millis() - aLong > GENERATE_CHALLENGE_COUNTER_TTL_MILLIS);
        if (!mGeneratedChallengeCount.isEmpty()) {
            mGeneratedChallengeCount.remove(0);
        }
        return mGeneratedChallengeCount.size();
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, IBinder token, IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                try {
                    receiver.onChallengeGenerated(sensorId, userId, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                incrementChallengeCount();
                if (isGeneratedChallengeCacheValid()) {
                    Slog.d(TAG, "Current challenge is cached and will be reused");
                    mGeneratedChallengeCache.reuseResult(receiver);
                    return;
                }
                scheduleUpdateActiveUserWithoutHandler(userId);
                final FaceGenerateChallengeClient client = new FaceGenerateChallengeClient(mContext, mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId, opPackageName, mSensorId, createLogger(BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext, sSystemClock.millis());
                mGeneratedChallengeCache = client;
                mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
                    @Override
                    public void onClientStarted(BaseClientMonitor clientMonitor) {
                        if (client != clientMonitor) {
                            Slog.e(TAG, "scheduleGenerateChallenge onClientStarted, mismatched client. Expecting: " + client + ", received: " + clientMonitor);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, IBinder token, String opPackageName, long challenge) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                return;
            }
            if (!(decrementChallengeCount() == 0)) {
                Slog.w(TAG, "scheduleRevokeChallenge skipped - challenge still in use: " + mGeneratedChallengeCount);
                return;
            }
            Slog.d(TAG, "scheduleRevokeChallenge executing - no active clients");
            mGeneratedChallengeCache = null;
            final FaceRevokeChallengeClient client = new FaceRevokeChallengeClient(mContext, mLazyDaemon, token, userId, opPackageName, mSensorId, createLogger(BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext);
            mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
                @Override
                public void onClientFinished(BaseClientMonitor clientMonitor, boolean success) {
                    if (client != clientMonitor) {
                        Slog.e(TAG, "scheduleRevokeChallenge, mismatched client.Expecting: " + client + ", received: " + clientMonitor);
                    }
                }
            });
        });
    }

    @Override
    public long scheduleEnroll(int sensorId, IBinder token, byte[] hardwareAuthToken, int userId, IFaceServiceReceiver receiver, String opPackageName, int[] disabledFeatures, Surface previewSurface, boolean debugConsent) {
        final long id = mRequestCounter.incrementAndGet();
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                try {
                    receiver.onError(2, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                scheduleUpdateActiveUserWithoutHandler(userId);
                BiometricNotificationUtils.cancelReEnrollNotification(mContext);
                final FaceEnrollClient client = new FaceEnrollClient(mContext, mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken, opPackageName, FaceUtils.getLegacyInstance(mSensorId), disabledFeatures, ENROLL_TIMEOUT_SEC, previewSurface, mSensorId, createLogger(BiometricsProtoEnums.ACTION_ENROLL, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext);
                mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
                    @Override
                    public void onClientFinished(BaseClientMonitor clientMonitor, boolean success) {
                        if (success) {
                            scheduleUpdateActiveUserWithoutHandler(client.getTargetUserId());
                        }
                    }
                });
            }
        });
        return id;
    }

    @Override
    public void cancelEnrollment(int sensorId, IBinder token, long requestId) {
        mHandler.post(() -> mScheduler.cancelEnrollment(token, requestId));
    }

    @Override
    public long scheduleFaceDetect(int sensorId, IBinder token, int userId, ClientMonitorCallbackConverter callback, String opPackageName, int statsClient) {
        throw new IllegalStateException("Face detect not supported by IBiometricsFace@1.0. Did youforget to check the supportsFaceDetection flag?");
    }

    @Override
    public void cancelFaceDetect(int sensorId, IBinder token, long requestId) {
        throw new IllegalStateException("Face detect not supported by IBiometricsFace@1.0. Did youforget to check the supportsFaceDetection flag?");
    }

    @Override
    public long scheduleAuthenticate(int sensorId, IBinder token, long operationId,
                                     int userId, int cookie, ClientMonitorCallbackConverter receiver,
                                     String opPackageName, boolean restricted, int statsClient,
                                     boolean allowBackgroundAuthentication, boolean isKeyguardBypassEnabled) {
        final long id = mRequestCounter.incrementAndGet();
        scheduleAuthenticate(sensorId, token, operationId, userId, cookie, receiver,
                opPackageName, id, restricted, statsClient,
                allowBackgroundAuthentication, isKeyguardBypassEnabled);
        return id;
    }

    @Override
    public void scheduleAuthenticate(int sensorId, IBinder token, long operationId, int userId, int cookie, ClientMonitorCallbackConverter receiver, String opPackageName, long requestId, boolean restricted, int statsClient, boolean allowBackgroundAuthentication, boolean isKeyguardBypassEnabled) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                try {
                    receiver.onError(DEVICE_ID, 0, 1, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                scheduleUpdateActiveUserWithoutHandler(userId);
                mScheduler.scheduleClientMonitor(new FaceAuthenticationClient(mContext, mLazyDaemon, token, requestId, receiver, userId, operationId, restricted, opPackageName, cookie, false, mSensorId, createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient), mBiometricContext, Utils.isStrongBiometric(mSensorId), mLockoutTracker, mUsageStats, allowBackgroundAuthentication));
            }
        });
    }

    @Override
    public void cancelAuthentication(int sensorId, IBinder token, long requestId) {
        mHandler.post(() -> mScheduler.cancelAuthenticationOrDetection(token, requestId));
    }

    @Override
    public void scheduleRemove(int sensorId, IBinder token, int faceId, int userId, IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                try {
                    receiver.onError(1, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                scheduleUpdateActiveUserWithoutHandler(userId);
                mScheduler.scheduleClientMonitor(new FaceRemovalClient(mContext, mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), faceId, userId, opPackageName, FaceUtils.getLegacyInstance(mSensorId), mSensorId, createLogger(BiometricsProtoEnums.ACTION_REMOVE, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext, mAuthenticatorIds));
            }
        });
    }

    @Override
    public void scheduleRemoveAll(int sensorId, IBinder token, int userId, IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                try {
                    receiver.onError(1, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                scheduleUpdateActiveUserWithoutHandler(userId);
                mScheduler.scheduleClientMonitor(new FaceRemovalClient(mContext, mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), 0, userId, opPackageName, FaceUtils.getLegacyInstance(mSensorId), mSensorId, createLogger(BiometricsProtoEnums.ACTION_REMOVE, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext, mAuthenticatorIds));
            }
        });
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
            } else if (getEnrolledFaces(sensorId, userId).isEmpty()) {
                Slog.w(TAG, "Ignoring lockout reset, no templates enrolled for user: " + userId);
            } else {
                scheduleUpdateActiveUserWithoutHandler(userId);
                mScheduler.scheduleClientMonitor(new FaceResetLockoutClient(mContext, mLazyDaemon, userId, mContext.getOpPackageName(), mSensorId, createLogger(BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext, hardwareAuthToken));
            }
        });
    }

    @Override
    public void scheduleSetFeature(int sensorId, IBinder token, int userId, int feature, boolean enabled, byte[] hardwareAuthToken, IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                return;
            }
            List<Face> faces = getEnrolledFaces(sensorId, userId);
            if (faces.isEmpty()) {
                Slog.w(TAG, "Ignoring setFeature, no templates enrolled for user: " + userId);
                return;
            }
            scheduleUpdateActiveUserWithoutHandler(userId);
            mScheduler.scheduleClientMonitor(new FaceSetFeatureClient(mContext, mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId, opPackageName, mSensorId, BiometricLogger.ofUnknown(mContext), mBiometricContext, feature, enabled, hardwareAuthToken, faces.get(0).getBiometricId()));
        });
    }

    @Override
    public void scheduleGetFeature(int sensorId, IBinder token, int userId, int feature, ClientMonitorCallbackConverter listener, String opPackageName) {
        mHandler.post(() -> {
            if (getDaemon() == null) {
                bindFaceAuthService(mCurrentUserId);
                if (listener != null) {
                    try {
                        listener.onError(DEVICE_ID, 0, 1, 0);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                List<Face> faces = getEnrolledFaces(sensorId, userId);
                if (faces.isEmpty()) {
                    Slog.w(TAG, "Ignoring getFeature, no templates enrolled for user: " + userId);
                    return;
                }
                scheduleUpdateActiveUserWithoutHandler(userId);
                final FaceGetFeatureClient client = new FaceGetFeatureClient(mContext, mLazyDaemon, token, listener, userId, opPackageName, mSensorId, BiometricLogger.ofUnknown(mContext), mBiometricContext, feature, faces.get(0).getBiometricId());
                mScheduler.scheduleClientMonitor(client, new ClientMonitorCallback() {
                    @Override
                    public void onClientFinished(BaseClientMonitor clientMonitor, boolean success) {
                        if (success && feature == 1) {
                            final int settingsValue = client.getValue() ? 1 : 0;
                            Slog.d(TAG, "Updating attention value for user: " + userId + " to value: " + settingsValue);
                            Settings.Secure.putIntForUser(mContext.getContentResolver(), "face_unlock_attention_required", settingsValue, userId);
                        }
                    }
                });
            }
        });
    }

    void scheduleInternalCleanup(int userId, ClientMonitorCallback callback) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);
            List<Face> enrolledList = getEnrolledFaces(mSensorId, userId);
            String opPackageName = mContext.getOpPackageName();
            mScheduler.scheduleClientMonitor(new FaceInternalCleanupClient(mContext, mLazyDaemon, userId, opPackageName, mSensorId, createLogger(BiometricsProtoEnums.ACTION_ENUMERATE, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext, enrolledList, FaceUtils.getLegacyInstance(mSensorId), mAuthenticatorIds), callback);
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId, ClientMonitorCallback callback) {
        scheduleInternalCleanup(userId, callback);
    }

    @Override
    public void scheduleInvalidateAuthenticatorId(int i, int i1, IInvalidationCallback iInvalidationCallback) {
        ServiceProvider.super.scheduleInvalidateAuthenticatorId(i, i1, iInvalidationCallback);
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> mScheduler.startPreparedClient(cookie));
    }

    @Override
    public void dumpProtoState(int sensorId, ProtoOutputStream proto, boolean clearSchedulerBuffer) {
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
        mHandler.post(() -> {
            for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
                int targetUserId = user.id;
                if (!mAuthenticatorIds.containsKey(Integer.valueOf(targetUserId))) {
                    scheduleUpdateActiveUserWithoutHandler(targetUserId);
                }
            }
        });
    }

    void scheduleUpdateActiveUserWithoutHandler(final int targetUserId) {
        mScheduler.scheduleClientMonitor(new FaceUpdateActiveUserClient(mContext, mLazyDaemon, targetUserId, mContext.getOpPackageName(), mSensorId, createLogger(BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN), mBiometricContext, mCurrentUserId, !getEnrolledFaces(mSensorId, targetUserId).isEmpty(), mAuthenticatorIds), new ClientMonitorCallback() {
            @Override
            public void onClientFinished(BaseClientMonitor clientMonitor, boolean success) {
                if (success) {
                    mCurrentUserId = targetUserId;
                }
            }
        });
    }

    private boolean isFaceServiceEnabled() {
        if (!FaceUnlockUtils.isFaceUnlockSupported()) {
            return false;
        }
        PackageManager pm = mContext.getPackageManager();
        ResolveInfo info = pm.resolveService(FaceUnlockUtils.getServiceIntent(), 131072);
        return info != null && info.serviceInfo.isEnabled();
    }

    public static boolean useCustomFaceUnlockService() {
        return FaceUnlockUtils.isFaceUnlockSupported();
    }

    private IFaceService getFaceService(int userId) {
        if (userId == -10000) {
            scheduleUpdateActiveUserWithoutHandler(ActivityManager.getCurrentUser());
        }
        return mFaceServices.get(mCurrentUserId);
    }

    void bindFaceAuthService(int userId) {
        Slog.d(TAG, "bindFaceAuthService " + userId);
        if (!isFaceServiceEnabled()) {
            Slog.d(TAG, "FaceService disabled");
        } else if (mIsServiceBinding) {
            Slog.d(TAG, "FaceService is binding");
        } else {
            if (userId != -10000 && getFaceService(userId) == null) {
                try {
                    Intent intent = FaceUnlockUtils.getServiceIntent();
                    boolean result = mContext.bindServiceAsUser(intent, new FaceServiceConnection(userId), 1, UserHandle.of(userId));
                    if (result) {
                        mIsServiceBinding = true;
                    }
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void dumpHal(int sensorId, FileDescriptor fd, String[] args) {
    }

    protected void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }

    @Override
    public ITestSession createTestSession(int sensorId, ITestSessionCallback callback, String opPackageName) {
        return new BiometricTestSessionImpl(mContext, mSensorId, callback, this, mHalResultController);
    }

    public static class HalResultController extends com.android.internal.util.custom.faceunlock.IFaceServiceReceiver.Stub {
        private final Context mContext;
        private final Handler mHandler;
        private final LockoutResetDispatcher mLockoutResetDispatcher;
        private final LockoutHalImpl mLockoutTracker;
        private final BiometricScheduler mScheduler;
        private final int mSensorId;
        private Callback mCallback;

        HalResultController(int sensorId, Context context, Handler handler, BiometricScheduler scheduler, LockoutHalImpl lockoutTracker, LockoutResetDispatcher lockoutResetDispatcher) {
            mSensorId = sensorId;
            mContext = context;
            mHandler = handler;
            mScheduler = scheduler;
            mLockoutTracker = lockoutTracker;
            mLockoutResetDispatcher = lockoutResetDispatcher;
        }

        public void setCallback(Callback callback) {
            mCallback = callback;
        }

        public void onEnrollResult(int faceId, int userId, int remaining) {
            mHandler.post(() -> {
                Face face = new Face(FaceUtils.getLegacyInstance(mSensorId).getUniqueName(mContext, userId), faceId, DEVICE_ID);
                BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceEnrollClient)) {
                    Slog.e(TAG, "onEnrollResult for non-enroll client: " + Utils.getClientName(client));
                    return;
                }
                ((FaceEnrollClient) client).onEnrollResult(face, remaining);
            });
        }

        public void onAuthenticated(int faceId, int userId, byte[] token) {
            mHandler.post(() -> {
                BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(TAG, "onAuthenticated for non-authentication consumer: " + Utils.getClientName(client));
                    return;
                }
                ((AuthenticationConsumer) client).onAuthenticated(new Face("", faceId, DEVICE_ID), faceId != 0, ArrayUtils.toByteArrayList(token));
            });
        }

        public void onAcquired(int userId, int acquiredInfo, int vendorCode) {
            mHandler.post(() -> {
                BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(TAG, "onAcquired for non-acquire client: " + Utils.getClientName(client));
                    return;
                }
                final AcquisitionClient<?> acquisitionClient =
                        (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(acquiredInfo, vendorCode);
            });
        }

        public void onError(int error, int vendorCode) {
            mHandler.post(() -> {
                BaseClientMonitor client = mScheduler.getCurrentClient();
                String log = "handleError, client: " +
                        (client != null ? client.getOwnerString() : null) +
                        ", error: " +
                        error +
                        ", vendorCode: " +
                        vendorCode;
                Slog.d(TAG, log);
                if (!(client instanceof ErrorConsumer)) {
                    Slog.e(TAG, "onError for non-error consumer: " + Utils.getClientName(client));
                    return;
                }
                ((ErrorConsumer) client).onError(error, vendorCode);
                if (error == 1) {
                    Slog.e(TAG, "Got ERROR_HW_UNAVAILABLE");
                    if (mCallback != null) {
                        mCallback.onHardwareUnavailable();
                    }
                }
            });
        }

        public void onRemoved(int[] removed, int userId) {
            mHandler.post(() -> {
                BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof RemovalConsumer)) {
                    Slog.e(TAG, "onRemoved for non-removal consumer: " + Utils.getClientName(client));
                    return;
                }
                RemovalConsumer removalConsumer = (RemovalConsumer) client;
                if (removed.length > 0) {
                    for (int i = 0; i < removed.length; i++) {
                        int id = removed[i];
                        Face face = new Face("", id, DEVICE_ID);
                        int remaining = (removed.length - i) - 1;
                        Slog.d(TAG, "Removed, faceId: " + id + ", remaining: " + remaining);
                        removalConsumer.onRemoved(face, remaining);
                    }
                } else {
                    removalConsumer.onRemoved(null, 0);
                }
                Settings.Secure.putIntForUser(mContext.getContentResolver(), "face_unlock_re_enroll", 0, -2);
            });
        }

        public void onEnumerate(int[] faceIds, int userId) {
            mHandler.post(() -> {
                BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof EnumerateConsumer)) {
                    Slog.e(TAG, "onEnumerate for non-enumerate consumer: " + Utils.getClientName(client));
                    return;
                }
                EnumerateConsumer enumerateConsumer = (EnumerateConsumer) client;
                if (faceIds.length > 0) {
                    for (int i = 0; i < faceIds.length; i++) {
                        enumerateConsumer.onEnumerationResult(new Face("", faceIds[i], DEVICE_ID), (faceIds.length - i) - 1);
                    }
                    return;
                }
                enumerateConsumer.onEnumerationResult(null, 0);
            });
        }

        public void onLockoutChanged(long duration) {
            mHandler.post(() -> {
                int lockoutMode;
                Slog.d(TAG, "onLockoutChanged: " + duration);
                if (duration == 0) {
                    lockoutMode = 0;
                } else if (duration == -1 || duration == Long.MAX_VALUE) {
                    lockoutMode = 2;
                } else {
                    lockoutMode = 1;
                }
                mLockoutTracker.setCurrentUserLockoutMode(lockoutMode);
                if (duration == 0) {
                    mLockoutResetDispatcher.notifyLockoutResetCallbacks(mSensorId);
                }
            });
        }

        public interface Callback {
            void onHardwareUnavailable();
        }
    }

    class FaceServiceConnection implements ServiceConnection {
        private final int mUserId;

        public FaceServiceConnection(int userId) {
            mUserId = userId;
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Slog.d(TAG, "FaceService connected : " + mUserId);
            IFaceService faceService = IFaceService.Stub.asInterface(service);
            if (faceService != null) {
                synchronized (mFaceServices) {
                    try {
                        faceService.setCallback(mHalResultController);
                        mFaceServices.put(mUserId, faceService);
                        mHandler.post(() -> {
                            scheduleInternalCleanup(mUserId, null);
                            scheduleGetFeature(mSensorId, new Binder(), mUserId, 1, null, mContext.getOpPackageName());
                        });
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    mIsServiceBinding = false;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Slog.d(TAG, "FaceService disconnected : " + mUserId);
            mFaceServices.remove(mUserId);
            mIsServiceBinding = false;
            if (mUserId == mCurrentUserId) {
                mHandler.postDelayed(() -> {
                    BaseClientMonitor client = mScheduler.getCurrentClient();
                    if (client instanceof ErrorConsumer) {
                        ((ErrorConsumer) client).onError(5, 0);
                    }
                    bindFaceAuthService(mUserId);
                    mScheduler.recordCrashState();
                    mScheduler.reset();
                }, 100);
            }
            mContext.unbindService(this);
        }
    }

    private BiometricLogger createLogger(int statsAction, int statsClient) {
        return new BiometricLogger(mContext, BiometricsProtoEnums.MODALITY_FACE,
                statsAction, statsClient);
    }
}
