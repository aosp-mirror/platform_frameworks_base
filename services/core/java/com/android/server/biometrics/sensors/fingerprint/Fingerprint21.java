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

package com.android.server.biometrics.sensors.fingerprint;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_2.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.fingerprint.FingerprintServiceDumpProto;
import com.android.server.biometrics.fingerprint.FingerprintUserStatsProto;
import com.android.server.biometrics.fingerprint.PerformanceStatsProto;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.Interruptable;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supports a single instance of the {@link android.hardware.biometrics.fingerprint.V2_1} or
 * its extended minor versions.
 */
class Fingerprint21 implements IHwBinder.DeathRecipient {

    private static final String TAG = "Fingerprint21";
    private static final int ENROLL_TIMEOUT_SEC = 60;

    private final Context mContext;
    private final IActivityTaskManager mActivityTaskManager;
    private final SensorProperties mSensorProperties;
    private final BiometricScheduler mScheduler;
    private final Handler mHandler;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockoutFrameworkImpl mLockoutTracker;
    private final BiometricTaskStackListener mTaskStackListener;
    private final ClientMonitor.LazyDaemon<IBiometricsFingerprint> mLazyDaemon;
    private final Map<Integer, Long> mAuthenticatorIds;

    @Nullable private IBiometricsFingerprint mDaemon;
    @Nullable private IUdfpsOverlayController mUdfpsOverlayController;
    private int mCurrentUserId = UserHandle.USER_NULL;

    /**
     * Static properties that never change for a given sensor.
     */
    private static final class SensorProperties {
        // Unique sensorId
        final int sensorId;
        // Is the sensor under-display
        final boolean isUdfps;
        // Supports finger detection without exposing accept/reject and without incrementing the
        // lockout counter
        final boolean supportsFingerDetectOnly;

        SensorProperties(int sensorId, boolean isUdfps, boolean supportsFingerDetectOnly) {
            this.sensorId = sensorId;
            this.isUdfps = isUdfps;
            this.supportsFingerDetectOnly = supportsFingerDetectOnly;
        }
    }

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationClient)) {
                    Slog.e(TAG, "Task stack changed for client: " + client);
                    return;
                }
                if (Utils.isKeyguard(mContext, client.getOwnerString())) {
                    return; // Keyguard is always allowed
                }

                try {
                    final List<ActivityManager.RunningTaskInfo> runningTasks =
                            mActivityTaskManager.getTasks(1);
                    if (!runningTasks.isEmpty()) {
                        final String topPackage = runningTasks.get(0).topActivity.getPackageName();
                        if (!topPackage.contentEquals(client.getOwnerString())
                                && !client.isAlreadyDone()) {
                            Slog.e(TAG, "Stopping background authentication, top: "
                                    + topPackage + " currentClient: " + client);
                            mScheduler.cancelAuthentication(client.getToken());
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to get running tasks", e);
                }
            });
        }
    }

    private final LockoutFrameworkImpl.LockoutResetCallback mLockoutResetCallback =
            new LockoutFrameworkImpl.LockoutResetCallback() {
        @Override
        public void onLockoutReset(int userId) {
            mLockoutResetDispatcher.notifyLockoutResetCallbacks(mSensorProperties.sensorId);
        }
    };

    private final UserSwitchObserver mUserSwitchObserver = new SynchronousUserSwitchObserver() {
        @Override
        public void onUserSwitching(int newUserId) {
            scheduleInternalCleanup(newUserId);
        }
    };

    private final IBiometricsFingerprintClientCallback mDaemonCallback =
            new IBiometricsFingerprintClientCallback.Stub() {
        @Override
        public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.post(() -> {
                final CharSequence name = FingerprintUtils.getInstance()
                        .getUniqueName(mContext, mCurrentUserId);
                final Fingerprint fingerprint = new Fingerprint(name, groupId, fingerId, deviceId);

                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintEnrollClient)) {
                    Slog.e(TAG, "onEnrollResult for non-enroll client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FingerprintEnrollClient enrollClient = (FingerprintEnrollClient) client;
                enrollClient.onEnrollResult(fingerprint, remaining);
            });
        }

        @Override
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode) {
            onAcquired_2_2(deviceId, acquiredInfo, vendorCode);
        }

        @Override
        public void onAcquired_2_2(long deviceId, int acquiredInfo, int vendorCode) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(TAG, "onAcquired for non-acquisition client: "
                            + Utils.getClientName(client));
                    return;
                }

                final AcquisitionClient<?> acquisitionClient = (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onAuthenticated(long deviceId, int fingerId, int groupId,
                ArrayList<Byte> token) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(TAG, "onAuthenticated for non-authentication consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                final boolean authenticated = fingerId != 0;
                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                authenticationConsumer.onAuthenticated(fp, authenticated, token);
            });
        }

        @Override
        public void onError(long deviceId, int error, int vendorCode) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                Slog.d(TAG, "handleError"
                        + ", client: " + (client != null ? client.getOwnerString() : null)
                        + ", error: " + error
                        + ", vendorCode: " + vendorCode);
                if (!(client instanceof Interruptable)) {
                    Slog.e(TAG, "onError for non-error consumer: " + Utils.getClientName(client));
                    return;
                }

                final Interruptable interruptable = (Interruptable) client;
                interruptable.onError(error, vendorCode);

                if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                    Slog.e(TAG, "Got ERROR_HW_UNAVAILABLE");
                    mDaemon = null;
                    mCurrentUserId = UserHandle.USER_NULL;
                }
            });
        }

        @Override
        public void onRemoved(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.post(() -> {
                Slog.d(TAG, "Removed, fingerId: " + fingerId + ", remaining: " + remaining);
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof RemovalConsumer)) {
                    Slog.e(TAG, "onRemoved for non-removal consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                final RemovalConsumer removalConsumer = (RemovalConsumer) client;
                removalConsumer.onRemoved(fp, remaining);
            });
        }

        @Override
        public void onEnumerate(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof EnumerateConsumer)) {
                    Slog.e(TAG, "onEnumerate for non-enumerate consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                final EnumerateConsumer enumerateConsumer = (EnumerateConsumer) client;
                enumerateConsumer.onEnumerationResult(fp, remaining);
            });
        }
    };

    Fingerprint21(@NonNull Context context, int sensorId,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        mContext = context;
        mActivityTaskManager = ActivityTaskManager.getService();
        mHandler = new Handler(Looper.getMainLooper());
        mTaskStackListener = new BiometricTaskStackListener();
        mAuthenticatorIds = Collections.synchronizedMap(new HashMap<>());
        mLazyDaemon = Fingerprint21.this::getDaemon;
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mLockoutTracker = new LockoutFrameworkImpl(context, mLockoutResetCallback);
        mScheduler = new BiometricScheduler(TAG, gestureAvailabilityDispatcher);

        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, TAG);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register user switch observer");
        }

        final IBiometricsFingerprint daemon = getDaemon();
        boolean isUdfps = false;
        android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint extension =
                android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint.castFrom(
                        daemon);
        if (extension != null) {
            try {
                isUdfps = extension.isUdfps(sensorId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception while quering udfps", e);
                isUdfps = false;
            }
        }
        // Fingerprint2.1 supports finger-detect only since lockout is controlled in the framework.
        mSensorProperties = new SensorProperties(sensorId, isUdfps,
                true /* supportsFingerDetectOnly */);
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.e(TAG, "HAL died");
        mHandler.post(() -> {
            PerformanceTracker.getInstanceForSensorId(mSensorProperties.sensorId)
                    .incrementHALDeathCount();
            mDaemon = null;
            mCurrentUserId = UserHandle.USER_NULL;

            final ClientMonitor<?> client = mScheduler.getCurrentClient();
            if (client instanceof Interruptable) {
                Slog.e(TAG, "Sending ERROR_HW_UNAVAILABLE for client: " + client);
                final Interruptable interruptable = (Interruptable) client;
                interruptable.onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);

                mScheduler.recordCrashState();

                FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                        BiometricsProtoEnums.MODALITY_FINGERPRINT,
                        BiometricsProtoEnums.ISSUE_HAL_DEATH);
            }
        });
    }

    private synchronized IBiometricsFingerprint getDaemon() {
        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(TAG, "Daemon was null, reconnecting, current operation: "
                + mScheduler.getCurrentClient());
        try {
            mDaemon = IBiometricsFingerprint.getService();
        } catch (java.util.NoSuchElementException e) {
            // Service doesn't exist or cannot be opened.
            Slog.w(TAG, "NoSuchElementException", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get fingerprint HAL", e);
        }

        if (mDaemon == null) {
            Slog.w(TAG, "Fingerprint HAL not available");
            return null;
        }

        mDaemon.asBinder().linkToDeath(this, 0 /* flags */);

        // HAL ID for these HIDL versions are only used to determine if callbacks have been
        // successfully set.
        long halId = 0;
        try {
            halId = mDaemon.setNotify(mDaemonCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set callback for fingerprint HAL", e);
            mDaemon = null;
        }

        Slog.d(TAG, "Fingerprint HAL ready, HAL ID: " + halId);
        if (halId != 0) {
            scheduleLoadAuthenticatorIds();
            scheduleInternalCleanup(ActivityManager.getCurrentUser());
        } else {
            Slog.e(TAG, "Unable to set callback");
            mDaemon = null;
        }

        return mDaemon;
    }

    @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
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
            for (UserInfo user : UserManager.get(mContext).getUsers(true /* excludeDying */)) {
                final int targetUserId = user.id;
                if (!mAuthenticatorIds.containsKey(targetUserId)) {
                    scheduleUpdateActiveUserWithoutHandler(targetUserId);
                }
            }
        });
    }

    /**
     * Schedules the {@link FingerprintUpdateActiveUserClient} without posting the work onto the
     * handler. Many/most APIs are user-specific. However, the HAL requires explicit "setActiveUser"
     * invocation prior to authenticate/enroll/etc. Thus, internally we usually want to schedule
     * this operation on the same lambda/runnable as those operations so that the ordering is
     * correct.
     */
    private void scheduleUpdateActiveUserWithoutHandler(int targetUserId) {
        final boolean hasEnrolled = !getEnrolledFingerprints(targetUserId).isEmpty();
        final FingerprintUpdateActiveUserClient client =
                new FingerprintUpdateActiveUserClient(mContext, mLazyDaemon, targetUserId,
                        mContext.getOpPackageName(), mSensorProperties.sensorId, mCurrentUserId,
                        hasEnrolled, mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, (clientMonitor, success) -> {
            if (success) {
                mCurrentUserId = targetUserId;
            }
        });
    }

    void scheduleResetLockout(int userId, byte[] hardwareAuthToken) {
        // Fingerprint2.1 keeps track of lockout in the framework. Let's just do it on the handler
        // thread.
        mHandler.post(() -> {
            mLockoutTracker.resetFailedAttemptsForUser(true /* clearAttemptCounter */, userId);
        });
    }

    void scheduleGenerateChallenge(@NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FingerprintGenerateChallengeClient client =
                    new FingerprintGenerateChallengeClient(mContext, mLazyDaemon, token,
                            new ClientMonitorCallbackConverter(receiver), opPackageName,
                            mSensorProperties.sensorId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleRevokeChallenge(@NonNull IBinder token, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FingerprintRevokeChallengeClient client = new FingerprintRevokeChallengeClient(
                    mContext, mLazyDaemon, token, opPackageName, mSensorProperties.sensorId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleEnroll(@NonNull IBinder token, @NonNull byte[] hardwareAuthToken, int userId,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName,
            @Nullable Surface surface) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final FingerprintEnrollClient client = new FingerprintEnrollClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId,
                    hardwareAuthToken, opPackageName, FingerprintUtils.getInstance(),
                    ENROLL_TIMEOUT_SEC, mSensorProperties.sensorId, mUdfpsOverlayController);
            mScheduler.scheduleClientMonitor(client, ((clientMonitor, success) -> {
                if (success) {
                    // Update authenticatorIds
                    scheduleUpdateActiveUserWithoutHandler(clientMonitor.getTargetUserId());
                }
            }));
        });
    }

    void cancelEnrollment(@NonNull IBinder token) {
        mHandler.post(() -> {
            mScheduler.cancelEnrollment(token);
        });
    }

    void scheduleFingerDetect(@NonNull IBinder token, int userId,
            @NonNull ClientMonitorCallbackConverter listener, @NonNull String opPackageName,
            @Nullable Surface surface, int statsClient) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorProperties.sensorId);
            final FingerprintDetectClient client = new FingerprintDetectClient(mContext,
                    mLazyDaemon, token, listener, userId, opPackageName,
                    mSensorProperties.sensorId, mUdfpsOverlayController, isStrongBiometric,
                    statsClient);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleAuthenticate(@NonNull IBinder token, long operationId, int userId, int cookie,
            @NonNull ClientMonitorCallbackConverter listener, @NonNull String opPackageName,
            @Nullable Surface surface, boolean restricted, int statsClient) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorProperties.sensorId);
            final FingerprintAuthenticationClient client = new FingerprintAuthenticationClient(
                    mContext, mLazyDaemon, token, listener, userId, operationId, restricted,
                    opPackageName, cookie, false /* requireConfirmation */,
                    mSensorProperties.sensorId, isStrongBiometric, surface, statsClient,
                    mTaskStackListener, mLockoutTracker, mUdfpsOverlayController);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void startPreparedClient(int cookie) {
        mHandler.post(() -> {
            mScheduler.startPreparedClient(cookie);
        });
    }

    void cancelAuthentication(@NonNull IBinder token) {
        mHandler.post(() -> {
            mScheduler.cancelAuthentication(token);
        });
    }

    void scheduleRemove(@NonNull IBinder token, @NonNull IFingerprintServiceReceiver receiver,
            int fingerId, int userId, @NonNull String opPackageName) {
        mHandler.post(() -> {
           scheduleUpdateActiveUserWithoutHandler(userId);

           final FingerprintRemovalClient client = new FingerprintRemovalClient(mContext,
                   mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), fingerId,
                   userId, opPackageName, FingerprintUtils.getInstance(),
                   mSensorProperties.sensorId, mAuthenticatorIds);
           mScheduler.scheduleClientMonitor(client);
        });
    }

    private void scheduleInternalCleanup(int userId) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final List<Fingerprint> enrolledList = getEnrolledFingerprints(userId);
            final FingerprintInternalCleanupClient client = new FingerprintInternalCleanupClient(
                    mContext, mLazyDaemon, userId, mContext.getOpPackageName(),
                    mSensorProperties.sensorId, enrolledList, FingerprintUtils.getInstance(),
                    mAuthenticatorIds);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    boolean isHardwareDetected() {
        final IBiometricsFingerprint daemon = getDaemon();
        return daemon != null;
    }

    void rename(int fingerId, int userId, String name) {
        mHandler.post(() -> {
            FingerprintUtils.getInstance().renameBiometricForUser(mContext, userId, fingerId, name);
        });
    }

    List<Fingerprint> getEnrolledFingerprints(int userId) {
        return FingerprintUtils.getInstance().getBiometricsForUser(mContext, userId);
    }

    long getAuthenticatorId(int userId) {
        return mAuthenticatorIds.get(userId);
    }

    void onFingerDown(int x, int y, float minor, float major) {
        final ClientMonitor<?> client = mScheduler.getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.w(TAG, "onFingerDown received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onFingerDown(x, y, minor, major);
    }

    void onFingerUp() {
        final ClientMonitor<?> client = mScheduler.getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.w(TAG, "onFingerDown received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onFingerUp();
    }

    boolean isUdfps() {
        return mSensorProperties.isUdfps;
    }

    void setUdfpsOverlayController(IUdfpsOverlayController controller) {
        mUdfpsOverlayController = controller;
    }

    void dumpProto(FileDescriptor fd) {
        PerformanceTracker tracker =
                PerformanceTracker.getInstanceForSensorId(mSensorProperties.sensorId);

        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(FingerprintServiceDumpProto.USERS);

            proto.write(FingerprintUserStatsProto.USER_ID, userId);
            proto.write(FingerprintUserStatsProto.NUM_FINGERPRINTS,
                    FingerprintUtils.getInstance().getBiometricsForUser(mContext, userId).size());

            // Normal fingerprint authentications (e.g. lockscreen)
            long countsToken = proto.start(FingerprintUserStatsProto.NORMAL);
            proto.write(PerformanceStatsProto.ACCEPT, tracker.getAcceptForUser(userId));
            proto.write(PerformanceStatsProto.REJECT, tracker.getRejectForUser(userId));
            proto.write(PerformanceStatsProto.ACQUIRE, tracker.getAcquireForUser(userId));
            proto.write(PerformanceStatsProto.LOCKOUT, tracker.getTimedLockoutForUser(userId));
            proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT,
                    tracker.getPermanentLockoutForUser(userId));
            proto.end(countsToken);

            // Statistics about secure fingerprint transactions (e.g. to unlock password
            // storage, make secure purchases, etc.)
            countsToken = proto.start(FingerprintUserStatsProto.CRYPTO);
            proto.write(PerformanceStatsProto.ACCEPT, tracker.getAcceptCryptoForUser(userId));
            proto.write(PerformanceStatsProto.REJECT, tracker.getRejectCryptoForUser(userId));
            proto.write(PerformanceStatsProto.ACQUIRE, tracker.getAcquireCryptoForUser(userId));
            proto.write(PerformanceStatsProto.LOCKOUT, 0); // meaningless for crypto
            proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT, 0); // meaningless for crypto
            proto.end(countsToken);

            proto.end(userToken);
        }
        proto.flush();
        tracker.clear();
    }

    void dumpInternal(@NonNull PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(mSensorProperties.sensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Fingerprint Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = FingerprintUtils.getInstance()
                        .getBiometricsForUser(mContext, userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
                set.put("accept", performanceTracker.getAcceptForUser(userId));
                set.put("reject", performanceTracker.getRejectForUser(userId));
                set.put("acquire", performanceTracker.getAcquireForUser(userId));
                set.put("lockout", performanceTracker.getTimedLockoutForUser(userId));
                set.put("permanentLockout", performanceTracker.getPermanentLockoutForUser(userId));
                // cryptoStats measures statistics about secure fingerprint transactions
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
    }
}
