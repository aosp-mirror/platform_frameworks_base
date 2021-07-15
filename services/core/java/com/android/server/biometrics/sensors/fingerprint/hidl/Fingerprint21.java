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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_2.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.ISidefpsController;
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.fingerprint.FingerprintServiceDumpProto;
import com.android.server.biometrics.fingerprint.FingerprintUserStatsProto;
import com.android.server.biometrics.fingerprint.PerformanceStatsProto;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.fingerprint.FingerprintStateCallback;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.ServiceProvider;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

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
public class Fingerprint21 implements IHwBinder.DeathRecipient, ServiceProvider {

    private static final String TAG = "Fingerprint21";
    private static final int ENROLL_TIMEOUT_SEC = 60;

    private boolean mTestHalEnabled;

    final Context mContext;
    private final ActivityTaskManager mActivityTaskManager;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    private final BiometricScheduler mScheduler;
    private final Handler mHandler;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockoutFrameworkImpl mLockoutTracker;
    private final BiometricTaskStackListener mTaskStackListener;
    private final HalClientMonitor.LazyDaemon<IBiometricsFingerprint> mLazyDaemon;
    private final Map<Integer, Long> mAuthenticatorIds;

    @Nullable private IBiometricsFingerprint mDaemon;
    @NonNull private final HalResultController mHalResultController;
    @Nullable private IUdfpsOverlayController mUdfpsOverlayController;
    @Nullable private ISidefpsController mSidefpsController;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private final boolean mIsUdfps;
    private final int mSensorId;
    private final boolean mIsPowerbuttonFps;

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationClient)) {
                    Slog.e(TAG, "Task stack changed for client: " + client);
                    return;
                }
                if (Utils.isKeyguard(mContext, client.getOwnerString())
                        || Utils.isSystem(mContext, client.getOwnerString())) {
                    return; // Keyguard is always allowed
                }

                final List<ActivityManager.RunningTaskInfo> runningTasks =
                        mActivityTaskManager.getTasks(1);
                if (!runningTasks.isEmpty()) {
                    final String topPackage = runningTasks.get(0).topActivity.getPackageName();
                    if (!topPackage.contentEquals(client.getOwnerString())
                            && !client.isAlreadyDone()) {
                        Slog.e(TAG, "Stopping background authentication, top: "
                                + topPackage + " currentClient: " + client);
                        mScheduler.cancelAuthenticationOrDetection(client.getToken());
                    }
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
            scheduleInternalCleanup(newUserId, null /* callback */);
        }
    };

    public static class HalResultController extends IBiometricsFingerprintClientCallback.Stub {

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
        @NonNull final Handler mHandler;
        @NonNull final BiometricScheduler mScheduler;
        @Nullable private Callback mCallback;

        HalResultController(int sensorId, @NonNull Context context, @NonNull Handler handler,
                @NonNull BiometricScheduler scheduler) {
            mSensorId = sensorId;
            mContext = context;
            mHandler = handler;
            mScheduler = scheduler;
        }

        public void setCallback(@Nullable Callback callback) {
            mCallback = callback;
        }

        @Override
        public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FingerprintEnrollClient)) {
                    Slog.e(TAG, "onEnrollResult for non-enroll client: "
                            + Utils.getClientName(client));
                    return;
                }

                final int currentUserId = client.getTargetUserId();
                final CharSequence name = FingerprintUtils.getLegacyInstance(mSensorId)
                        .getUniqueName(mContext, currentUserId);
                final Fingerprint fingerprint = new Fingerprint(name, groupId, fingerId, deviceId);

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
                final BaseClientMonitor client = mScheduler.getCurrentClient();
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
                final BaseClientMonitor client = mScheduler.getCurrentClient();
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
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                Slog.d(TAG, "handleError"
                        + ", client: " + Utils.getClientName(client)
                        + ", error: " + error
                        + ", vendorCode: " + vendorCode);
                if (!(client instanceof ErrorConsumer)) {
                    Slog.e(TAG, "onError for non-error consumer: " + Utils.getClientName(client));
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
        public void onRemoved(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.post(() -> {
                Slog.d(TAG, "Removed, fingerId: " + fingerId + ", remaining: " + remaining);
                final BaseClientMonitor client = mScheduler.getCurrentClient();
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
                final BaseClientMonitor client = mScheduler.getCurrentClient();
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
    }

    Fingerprint21(@NonNull Context context,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull BiometricScheduler scheduler, @NonNull Handler handler,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull HalResultController controller) {
        mContext = context;

        mSensorProperties = sensorProps;
        mSensorId = sensorProps.sensorId;
        mIsUdfps = sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
                || sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC;
        mIsPowerbuttonFps = sensorProps.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON;

        mScheduler = scheduler;
        mHandler = handler;
        mActivityTaskManager = ActivityTaskManager.getInstance();
        mTaskStackListener = new BiometricTaskStackListener();
        mAuthenticatorIds = Collections.synchronizedMap(new HashMap<>());
        mLazyDaemon = Fingerprint21.this::getDaemon;
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mLockoutTracker = new LockoutFrameworkImpl(context, mLockoutResetCallback);
        mHalResultController = controller;
        mHalResultController.setCallback(() -> {
            mDaemon = null;
            mCurrentUserId = UserHandle.USER_NULL;
        });

        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, TAG);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register user switch observer");
        }
    }

    public static Fingerprint21 newInstance(@NonNull Context context,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final BiometricScheduler scheduler =
                new BiometricScheduler(TAG, gestureAvailabilityDispatcher);
        final HalResultController controller = new HalResultController(sensorProps.sensorId,
                context, handler,
                scheduler);
        return new Fingerprint21(context, sensorProps, scheduler, handler, lockoutResetDispatcher,
                controller);
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.e(TAG, "HAL died");
        mHandler.post(() -> {
            PerformanceTracker.getInstanceForSensorId(mSensorProperties.sensorId)
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
                        BiometricsProtoEnums.MODALITY_FINGERPRINT,
                        BiometricsProtoEnums.ISSUE_HAL_DEATH,
                        -1 /* sensorId */);
            }

            mScheduler.recordCrashState();
            mScheduler.reset();
        });
    }

    @VisibleForTesting
    synchronized IBiometricsFingerprint getDaemon() {
        if (mTestHalEnabled) {
            final TestHal testHal = new TestHal(mContext, mSensorId);
            testHal.setNotify(mHalResultController);
            return testHal;
        }

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
            halId = mDaemon.setNotify(mHalResultController);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set callback for fingerprint HAL", e);
            mDaemon = null;
        }

        Slog.d(TAG, "Fingerprint HAL ready, HAL ID: " + halId);
        if (halId != 0) {
            scheduleLoadAuthenticatorIds();
            scheduleInternalCleanup(ActivityManager.getCurrentUser(), null /* callback */);
        } else {
            Slog.e(TAG, "Unable to set callback");
            mDaemon = null;
        }

        return mDaemon;
    }

    @Nullable IUdfpsOverlayController getUdfpsOverlayController() {
        return mUdfpsOverlayController;
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
                    scheduleUpdateActiveUserWithoutHandler(targetUserId, true /* force */);
                }
            }
        });
    }

    private void scheduleUpdateActiveUserWithoutHandler(int targetUserId) {
        scheduleUpdateActiveUserWithoutHandler(targetUserId, false /* force */);
    }

    /**
     * Schedules the {@link FingerprintUpdateActiveUserClient} without posting the work onto the
     * handler. Many/most APIs are user-specific. However, the HAL requires explicit "setActiveUser"
     * invocation prior to authenticate/enroll/etc. Thus, internally we usually want to schedule
     * this operation on the same lambda/runnable as those operations so that the ordering is
     * correct.
     *
     * @param targetUserId Switch to this user, and update their authenticatorId
     * @param force Always retrieve the authenticatorId, even if we are already the targetUserId
     */
    private void scheduleUpdateActiveUserWithoutHandler(int targetUserId, boolean force) {
        final boolean hasEnrolled =
                !getEnrolledFingerprints(mSensorProperties.sensorId, targetUserId).isEmpty();
        final FingerprintUpdateActiveUserClient client =
                new FingerprintUpdateActiveUserClient(mContext, mLazyDaemon, targetUserId,
                        mContext.getOpPackageName(), mSensorProperties.sensorId, mCurrentUserId,
                        hasEnrolled, mAuthenticatorIds, force);
        mScheduler.scheduleClientMonitor(client, new BaseClientMonitor.Callback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                if (success) {
                    mCurrentUserId = targetUserId;
                }
            }
        });
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensorProperties.sensorId == sensorId;
    }

    @Override
    @NonNull
    public List<FingerprintSensorPropertiesInternal> getSensorProperties() {
        final List<FingerprintSensorPropertiesInternal> properties = new ArrayList<>();
        properties.add(mSensorProperties);
        return properties;
    }

    @Nullable
    @Override
    public FingerprintSensorPropertiesInternal getSensorProperties(int sensorId) {
        return mSensorProperties;
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @Nullable byte[] hardwareAuthToken) {
        // Fingerprint2.1 keeps track of lockout in the framework. Let's just do it on the handler
        // thread.
        mHandler.post(() -> {
            final FingerprintResetLockoutClient client = new FingerprintResetLockoutClient(mContext,
                    userId, mContext.getOpPackageName(), sensorId, mLockoutTracker);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FingerprintGenerateChallengeClient client =
                    new FingerprintGenerateChallengeClient(mContext, mLazyDaemon, token,
                            new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                            mSensorProperties.sensorId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            final FingerprintRevokeChallengeClient client = new FingerprintRevokeChallengeClient(
                    mContext, mLazyDaemon, token, userId, opPackageName,
                    mSensorProperties.sensorId);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    @Override
    public void scheduleEnroll(int sensorId, @NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName,
            @FingerprintManager.EnrollReason int enrollReason,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final FingerprintEnrollClient client = new FingerprintEnrollClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), userId,
                    hardwareAuthToken, opPackageName, FingerprintUtils.getLegacyInstance(mSensorId),
                    ENROLL_TIMEOUT_SEC, mSensorProperties.sensorId, mUdfpsOverlayController,
                    mSidefpsController, enrollReason);
            mScheduler.scheduleClientMonitor(client, new BaseClientMonitor.Callback() {
                @Override
                public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                    fingerprintStateCallback.onClientStarted(clientMonitor);
                }

                @Override
                public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                        boolean success) {
                    fingerprintStateCallback.onClientFinished(clientMonitor, success);
                    if (success) {
                        // Update authenticatorIds
                        scheduleUpdateActiveUserWithoutHandler(clientMonitor.getTargetUserId(),
                                true /* force */);
                    }
                }
            });
        });
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> {
            mScheduler.cancelEnrollment(token);
        });
    }

    @Override
    public void scheduleFingerDetect(int sensorId, @NonNull IBinder token, int userId,
            @NonNull ClientMonitorCallbackConverter listener, @NonNull String opPackageName,
            int statsClient,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorProperties.sensorId);
            final FingerprintDetectClient client = new FingerprintDetectClient(mContext,
                    mLazyDaemon, token, listener, userId, opPackageName,
                    mSensorProperties.sensorId, mUdfpsOverlayController, isStrongBiometric,
                    statsClient);
            mScheduler.scheduleClientMonitor(client, fingerprintStateCallback);
        });
    }

    @Override
    public void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId,
            int userId, int cookie, @NonNull ClientMonitorCallbackConverter listener,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorProperties.sensorId);
            final FingerprintAuthenticationClient client = new FingerprintAuthenticationClient(
                    mContext, mLazyDaemon, token, listener, userId, operationId, restricted,
                    opPackageName, cookie, false /* requireConfirmation */,
                    mSensorProperties.sensorId, isStrongBiometric, statsClient,
                    mTaskStackListener, mLockoutTracker, mUdfpsOverlayController,
                    allowBackgroundAuthentication);
            mScheduler.scheduleClientMonitor(client, fingerprintStateCallback);
        });
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> mScheduler.startPreparedClient(cookie));
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token) {
        Slog.d(TAG, "cancelAuthentication, sensorId: " + sensorId);
        mHandler.post(() -> mScheduler.cancelAuthenticationOrDetection(token));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final FingerprintRemovalClient client = new FingerprintRemovalClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), fingerId,
                    userId, opPackageName, FingerprintUtils.getLegacyInstance(mSensorId),
                    mSensorProperties.sensorId, mAuthenticatorIds);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    @Override
    public void scheduleRemoveAll(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int userId,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            // For IBiometricsFingerprint@2.1, remove(0) means remove all enrollments
            final FingerprintRemovalClient client = new FingerprintRemovalClient(mContext,
                    mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver),
                    0 /* fingerprintId */, userId, opPackageName,
                    FingerprintUtils.getLegacyInstance(mSensorId),
                    mSensorProperties.sensorId, mAuthenticatorIds);
            mScheduler.scheduleClientMonitor(client);
        });
    }

    private void scheduleInternalCleanup(int userId,
            @Nullable BaseClientMonitor.Callback callback) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            final List<Fingerprint> enrolledList = getEnrolledFingerprints(
                    mSensorProperties.sensorId, userId);
            final FingerprintInternalCleanupClient client = new FingerprintInternalCleanupClient(
                    mContext, mLazyDaemon, userId, mContext.getOpPackageName(),
                    mSensorProperties.sensorId, enrolledList,
                    FingerprintUtils.getLegacyInstance(mSensorId), mAuthenticatorIds);
            mScheduler.scheduleClientMonitor(client, callback);
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable BaseClientMonitor.Callback callback) {
        scheduleInternalCleanup(userId, callback);
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return getDaemon() != null;
    }

    @Override
    public void rename(int sensorId, int fingerId, int userId, @NonNull String name) {
        mHandler.post(() -> {
            FingerprintUtils.getLegacyInstance(mSensorId)
                    .renameBiometricForUser(mContext, userId, fingerId, name);
        });
    }

    @Override
    @NonNull
    public List<Fingerprint> getEnrolledFingerprints(int sensorId, int userId) {
        return FingerprintUtils.getLegacyInstance(mSensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    @LockoutTracker.LockoutMode public int getLockoutModeForUser(int sensorId, int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return mAuthenticatorIds.getOrDefault(userId, 0L);
    }

    @Override
    public void onPointerDown(int sensorId, int x, int y, float minor, float major) {
        final BaseClientMonitor client = mScheduler.getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.w(TAG, "onFingerDown received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onPointerDown(x, y, minor, major);
    }

    @Override
    public void onPointerUp(int sensorId) {
        final BaseClientMonitor client = mScheduler.getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.w(TAG, "onFingerDown received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onPointerUp();
    }

    @Override
    public void onUiReady(int sensorId) {
        final BaseClientMonitor client = mScheduler.getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.w(TAG, "onUiReady received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onUiReady();
    }

    @Override
    public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
        mUdfpsOverlayController = controller;
    }

    @Override
    public void setSidefpsController(@NonNull ISidefpsController controller) {
        mSidefpsController = controller;
    }

    @Override
    public void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
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
            proto.write(UserStateProto.NUM_ENROLLED, FingerprintUtils.getLegacyInstance(mSensorId)
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
        PerformanceTracker tracker =
                PerformanceTracker.getInstanceForSensorId(mSensorProperties.sensorId);

        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(FingerprintServiceDumpProto.USERS);

            proto.write(FingerprintUserStatsProto.USER_ID, userId);
            proto.write(FingerprintUserStatsProto.NUM_FINGERPRINTS,
                    FingerprintUtils.getLegacyInstance(mSensorId)
                            .getBiometricsForUser(mContext, userId).size());

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

    @Override
    public void scheduleInvalidateAuthenticatorId(int sensorId, int userId,
            @NonNull IInvalidationCallback callback) {
        // TODO (b/179101888): Remove this temporary workaround.
        try {
            callback.onCompleted();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to complete InvalidateAuthenticatorId");
        }
    }

    @Override
    public void dumpInternal(int sensorId, @NonNull PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(mSensorProperties.sensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", TAG);
            dump.put("isUdfps", mIsUdfps);
            dump.put("isPowerbuttonFps", mIsPowerbuttonFps);

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = FingerprintUtils.getLegacyInstance(mSensorId)
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

    void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }

    @NonNull
    @Override
    public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull FingerprintStateCallback fingerprintStateCallback,
            @NonNull String opPackageName) {
        return new BiometricTestSessionImpl(mContext, mSensorProperties.sensorId, callback,
                fingerprintStateCallback, this, mHalResultController);
    }
}
