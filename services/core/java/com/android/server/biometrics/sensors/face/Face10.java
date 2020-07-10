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

package com.android.server.biometrics.sensors.face;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.face.Face;
import android.hardware.face.IFaceServiceReceiver;
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

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.Interruptable;
import com.android.server.biometrics.sensors.LockoutResetTracker;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUpdateActiveUserClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supports a single instance of the {@link android.hardware.biometrics.face.V1_0} or
 * its extended minor versions.
 */
class Face10 implements IHwBinder.DeathRecipient {

    private static final String TAG = "Face10";
    private static final int ENROLL_TIMEOUT_SEC = 75;
    static final String NOTIFICATION_TAG = "FaceService";
    static final int NOTIFICATION_ID = 1;

    @NonNull private final Context mContext;
    @NonNull private final BiometricScheduler mScheduler;
    @NonNull private final Handler mHandler;
    @NonNull private final ClientMonitor.LazyDaemon<IBiometricsFace> mLazyDaemon;
    @NonNull private final LockoutResetTracker mLockoutResetTracker;
    @NonNull private final LockoutHalImpl mLockoutTracker;
    @NonNull private final UsageStats mUsageStats;
    @NonNull private NotificationManager mNotificationManager;
    private final int mSensorId;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;

    @Nullable private IBiometricsFace mDaemon;
    private int mCurrentUserId = UserHandle.USER_NULL;

    private final UserSwitchObserver mUserSwitchObserver = new SynchronousUserSwitchObserver() {
        @Override
        public void onUserSwitching(int newUserId) {
            scheduleInternalCleanup(newUserId);
        }
    };

    private final IBiometricsFaceClientCallback mDaemonCallback =
            new IBiometricsFaceClientCallback.Stub() {
        @Override
        public void onEnrollResult(long deviceId, int faceId, int userId, int remaining) {
            mHandler.post(() -> {
                final CharSequence name = FaceUtils.getInstance()
                        .getUniqueName(mContext, userId);
                final Face face = new Face(name, faceId, deviceId);

                final ClientMonitor<?> client = mScheduler.getCurrentClient();
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
        public void onAuthenticated(long deviceId, int faceId, int userId, ArrayList<Byte> token) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceAuthenticationClient)) {
                    Slog.e(TAG, "onAuthenticated for non-authentication client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceAuthenticationClient authenticationClient =
                        (FaceAuthenticationClient) client;
                final boolean authenticated = faceId != 0;
                final Face face = new Face("", faceId, deviceId);
                authenticationClient.onAuthenticated(face, authenticated, token);
            });
        }

        @Override
        public void onAcquired(long deviceId, int userId, int acquiredInfo, int vendorCode) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(TAG, "onAcquired for non-acquire client: "
                            + Utils.getClientName(client));
                    return;
                }

                final AcquisitionClient<?> acquisitionClient = (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onError(long deviceId, int userId, int error, int vendorCode) {
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
        public void onRemoved(long deviceId, ArrayList<Integer> removed, int userId) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
                if (!(client instanceof RemovalConsumer)) {
                    Slog.e(TAG, "onRemoved for non-removal consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final RemovalConsumer removalConsumer = (RemovalConsumer) client;

                if (!removed.isEmpty()) {
                    // Convert to old fingerprint-like behavior, where remove() receives one removal
                    // at a time. This way, remove can share some more common code.
                    for (int i = 0; i < removed.size(); i++) {
                        final int id = removed.get(i);
                        final Face face = new Face("", id, deviceId);
                        final int remaining = removed.size() - i - 1;
                        Slog.d(TAG, "Removed, faceId: " + id + ", remaining: " + remaining);
                        removalConsumer.onRemoved(face, remaining);
                    }
                } else {
                    final Face face = new Face("", 0 /* identifier */, deviceId);
                    removalConsumer.onRemoved(face, 0 /* remaining */);
                }

                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_RE_ENROLL, 0, UserHandle.USER_CURRENT);
            });
        }

        @Override
        public void onEnumerate(long deviceId, ArrayList<Integer> faceIds, int userId) {
            mHandler.post(() -> {
                final ClientMonitor<?> client = mScheduler.getCurrentClient();
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
                    // anywhere, and send remaining == 0 so this code can be shared with
                    // Fingerprint@2.1
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
                    mLockoutResetTracker.notifyLockoutResetCallbacks(mSensorId);
                }
            });
        }
    };

    Face10(@NonNull Context context, int sensorId,
            @NonNull LockoutResetTracker lockoutResetTracker) {
        mContext = context;
        mSensorId = sensorId;
        mScheduler = new BiometricScheduler(TAG, null /* gestureAvailabilityTracker */);
        mHandler = new Handler(Looper.getMainLooper());
        mUsageStats = new UsageStats(context);
        mAuthenticatorIds = new HashMap<>();
        mLazyDaemon = Face10.this::getDaemon;
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        mLockoutTracker = new LockoutHalImpl();
        mLockoutResetTracker = lockoutResetTracker;

        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, TAG);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register user switch observer");
        }
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.e(TAG, "HAL died");
        mHandler.post(() -> {
            PerformanceTracker.getInstanceForSensorId(mSensorId)
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
                        BiometricsProtoEnums.MODALITY_FACE,
                        BiometricsProtoEnums.ISSUE_HAL_DEATH);
            }
        });
    }

    private synchronized IBiometricsFace getDaemon() {
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
            halId = mDaemon.setCallback(mDaemonCallback).value;
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set callback for face HAL", e);
            mDaemon = null;
        }

        Slog.d(TAG, "Face HAL ready, HAL ID: " + halId);
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
        final boolean hasEnrolled = !getEnrolledFaces(targetUserId).isEmpty();
        final FaceUpdateActiveUserClient client = new FaceUpdateActiveUserClient(mContext,
                mLazyDaemon, targetUserId, mContext.getOpPackageName(), mSensorId, mCurrentUserId,
                hasEnrolled, mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, (clientMonitor, success) -> {
            if (success) {
                mCurrentUserId = targetUserId;
            }
        });
    }

    void scheduleResetLockout(int userId, @NonNull byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            if (getEnrolledFaces(userId).isEmpty()) {
                Slog.w(TAG, "Ignoring lockout reset, no templates enrolled for user: " + userId);
                return;
            }

            scheduleUpdateActiveUserWithoutHandler(userId);

            final FaceResetLockoutClient client = new FaceResetLockoutClient(mContext,
                    mLazyDaemon, userId, mContext.getOpPackageName(), mSensorId,
                    hardwareAuthToken);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleSetFeature(@NonNull IBinder token, int userId, int feature, boolean enabled,
            @NonNull byte[] hardwareAuthToken, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            final List<Face> faces = getEnrolledFaces(userId);
            if (faces.isEmpty()) {
                Slog.w(TAG, "Ignoring setFeature, no templates enrolled for user: " + userId);
                return;
            }

            scheduleUpdateActiveUserWithoutHandler(userId);

            final int faceId = faces.get(0).getBiometricId();
            final FaceSetFeatureClient client = new FaceSetFeatureClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId,
                    opPackageName, mSensorId, feature, enabled, hardwareAuthToken, faceId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleGetFeature(@NonNull IBinder token, int userId, int feature,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final List<Face> faces = getEnrolledFaces(userId);
            if (faces.isEmpty()) {
                Slog.w(TAG, "Ignoring getFeature, no templates enrolled for user: " + userId);
                return;
            }

            scheduleUpdateActiveUserWithoutHandler(userId);

            final int faceId = faces.get(0).getBiometricId();
            final FaceGetFeatureClient client = new FaceGetFeatureClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId,
                    opPackageName, mSensorId, feature, faceId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleGenerateChallenge(@NonNull IBinder token, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FaceGenerateChallengeClient client = new FaceGenerateChallengeClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), opPackageName,
                    mSensorId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleRevokeChallenge(@NonNull IBinder token, @NonNull String owner) {
        mHandler.post(() -> {
            final FaceRevokeChallengeClient client = new FaceRevokeChallengeClient(mContext,
                    mLazyDaemon, token, owner, mSensorId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    void scheduleEnroll(@NonNull IBinder token, @NonNull byte[] hardwareAuthToken, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName,
            @NonNull int[] disabledFeatures, @Nullable NativeHandle surfaceHandle) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            mNotificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID,
                    UserHandle.CURRENT);

            final FaceEnrollClient client = new FaceEnrollClient(mContext, mLazyDaemon, token,
                    new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                    opPackageName, FaceUtils.getInstance(), disabledFeatures, ENROLL_TIMEOUT_SEC,
                    surfaceHandle, mSensorId);

            mScheduler.scheduleClientMonitor(client, ((clientMonitor, success) -> {
                if (success) {
                    // Update authenticatorIds
                    scheduleUpdateActiveUserWithoutHandler(client.getTargetUserId());
                }
            }));
        });
    }

    void cancelEnrollment(@NonNull IBinder token) {
        mHandler.post(() -> {
            mScheduler.cancelEnrollment(token);
        });
    }

    void scheduleAuthenticate(@NonNull IBinder token, long operationId, int userId, int cookie,
            @NonNull ClientMonitorCallbackConverter receiver, @NonNull String opPackageName,
            boolean restricted, int statsClient) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorId);
            final FaceAuthenticationClient client = new FaceAuthenticationClient(mContext,
                    mLazyDaemon, token, receiver, userId, operationId, restricted, opPackageName,
                    cookie, false /* requireConfirmation */, mSensorId, isStrongBiometric,
                    statsClient, mLockoutTracker, mUsageStats);
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

    void scheduleRemove(@NonNull IBinder token, int faceId, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final FaceRemovalClient client = new FaceRemovalClient(mContext, mLazyDaemon, token,
                    new ClientMonitorCallbackConverter(receiver), faceId, userId, opPackageName,
                    FaceUtils.getInstance(), mSensorId, mAuthenticatorIds);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    private void scheduleInternalCleanup(int userId) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final List<Face> enrolledList = getEnrolledFaces(userId);
            final FaceInternalCleanupClient client = new FaceInternalCleanupClient(mContext,
                    mLazyDaemon, userId, mContext.getOpPackageName(), mSensorId, enrolledList,
                    FaceUtils.getInstance(), mAuthenticatorIds);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    boolean isHardwareDetected() {
        final IBiometricsFace daemon = getDaemon();
        return daemon != null;
    }

    List<Face> getEnrolledFaces(int userId) {
        return FaceUtils.getInstance().getBiometricsForUser(mContext, userId);
    }

    long getAuthenticatorId(int userId) {
        return mAuthenticatorIds.get(userId);
    }

    public void dump(@NonNull PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(mSensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Face Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = FaceUtils.getInstance().getBiometricsForUser(mContext, userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
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

        mUsageStats.print(pw);
    }

    public void dumpHal(@NonNull FileDescriptor fd, @NonNull String[] args) {
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
                        new FileDescriptor[] { devnull.getFD(), fd },
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
}
