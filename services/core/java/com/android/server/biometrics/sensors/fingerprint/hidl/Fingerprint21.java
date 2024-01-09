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
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_2.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.AuthenticationStatsBroadcastReceiver;
import com.android.server.biometrics.AuthenticationStatsCollector;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.fingerprint.FingerprintServiceDumpProto;
import com.android.server.biometrics.fingerprint.FingerprintUserStatsProto;
import com.android.server.biometrics.fingerprint.PerformanceStatsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationClient;
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
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.ServiceProvider;
import com.android.server.biometrics.sensors.fingerprint.Udfps;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlSession;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Supports a single instance of the {@link android.hardware.biometrics.fingerprint.V2_1} or
 * its extended minor versions.
 */
public class Fingerprint21 implements IHwBinder.DeathRecipient, ServiceProvider {

    private static final String TAG = "Fingerprint21";
    private static final int ENROLL_TIMEOUT_SEC = 60;

    private boolean mTestHalEnabled;

    final Context mContext;
    @NonNull private final BiometricStateCallback mBiometricStateCallback;
    @NonNull private final AuthenticationStateListeners mAuthenticationStateListeners;
    private final ActivityTaskManager mActivityTaskManager;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    private final BiometricScheduler<IBiometricsFingerprint, AidlSession> mScheduler;
    private final Handler mHandler;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockoutFrameworkImpl mLockoutTracker;
    private final BiometricTaskStackListener mTaskStackListener;
    private final Supplier<IBiometricsFingerprint> mLazyDaemon;
    private final Map<Integer, Long> mAuthenticatorIds;

    @Nullable private IBiometricsFingerprint mDaemon;
    @NonNull private final HalResultController mHalResultController;
    @Nullable private IUdfpsOverlayController mUdfpsOverlayController;

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    @Nullable private ISidefpsController mSidefpsController;
    @NonNull private final BiometricContext mBiometricContext;
    @Nullable private AuthenticationStatsCollector mAuthenticationStatsCollector;
    // for requests that do not use biometric prompt
    @NonNull private final AtomicLong mRequestCounter = new AtomicLong(0);
    private int mCurrentUserId = UserHandle.USER_NULL;
    private final boolean mIsUdfps;
    private final int mSensorId;
    private final boolean mIsPowerbuttonFps;
    private AidlSession mSession;

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

                if (Utils.isBackground(client.getOwnerString())
                        && !client.isAlreadyDone()) {
                    Slog.e(TAG, "Stopping background authentication,"
                            + " currentClient: " + client);
                    mScheduler.cancelAuthenticationOrDetection(
                            client.getToken(), client.getRequestId());
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
        @NonNull final BiometricScheduler<IBiometricsFingerprint, AidlSession> mScheduler;
        @Nullable private Callback mCallback;

        HalResultController(int sensorId, @NonNull Context context, @NonNull Handler handler,
                @NonNull BiometricScheduler<IBiometricsFingerprint, AidlSession> scheduler) {
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

    @VisibleForTesting
    Fingerprint21(@NonNull Context context,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull BiometricScheduler<IBiometricsFingerprint, AidlSession> scheduler,
            @NonNull Handler handler,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull HalResultController controller,
            @NonNull BiometricContext biometricContext) {
        mContext = context;
        mBiometricStateCallback = biometricStateCallback;
        mAuthenticationStateListeners = authenticationStateListeners;
        mBiometricContext = biometricContext;

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

        AuthenticationStatsBroadcastReceiver mBroadcastReceiver =
                new AuthenticationStatsBroadcastReceiver(
                        mContext,
                        BiometricsProtoEnums.MODALITY_FINGERPRINT,
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

    public static Fingerprint21 newInstance(@NonNull Context context,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull Handler handler,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        final BiometricScheduler<IBiometricsFingerprint, AidlSession> scheduler =
                new BiometricScheduler<>(
                        BiometricScheduler.sensorTypeFromFingerprintProperties(sensorProps),
                        gestureAvailabilityDispatcher);
        final HalResultController controller = new HalResultController(sensorProps.sensorId,
                context, handler, scheduler);
        return new Fingerprint21(context, biometricStateCallback, authenticationStateListeners,
                sensorProps, scheduler, handler, lockoutResetDispatcher, controller,
                BiometricContext.getInstance(context));
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

    synchronized AidlSession getSession() {
        if (mDaemon != null && mSession != null) {
            return mSession;
        } else {
            return mSession = new AidlSession(this::getDaemon,
                    mCurrentUserId, new AidlResponseHandler(mContext,
                    mScheduler, mSensorId, mCurrentUserId, new LockoutCache(),
                    mLockoutResetDispatcher, new AuthSessionCoordinator(), () -> {
                        mDaemon = null;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }));
        }
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
        final FingerprintUpdateActiveUserClientLegacy client =
                new FingerprintUpdateActiveUserClientLegacy(mContext, mLazyDaemon, targetUserId,
                        mContext.getOpPackageName(), mSensorProperties.sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext,
                        this::getCurrentUser, hasEnrolled, mAuthenticatorIds, force);
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

    private int getCurrentUser() {
        return mCurrentUserId;
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
            if (Flags.deHidl()) {
                scheduleResetLockoutAidl(sensorId, userId, hardwareAuthToken);
            } else {
                scheduleResetLockoutHidl(sensorId, userId);
            }
        });
    }

    private void scheduleResetLockoutAidl(int sensorId, int userId,
            @Nullable byte[] hardwareAuthToken) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintResetLockoutClient client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintResetLockoutClient(
                        mContext, this::getSession, userId, mContext.getOpPackageName(),
                        sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext, hardwareAuthToken, mLockoutTracker,
                        mLockoutResetDispatcher,
                        Utils.getCurrentStrength(sensorId));
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleResetLockoutHidl(int sensorId, int userId) {
        final FingerprintResetLockoutClient client = new FingerprintResetLockoutClient(mContext,
                userId, mContext.getOpPackageName(), sensorId,
                createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                        BiometricsProtoEnums.CLIENT_UNKNOWN,
                        mAuthenticationStatsCollector),
                mBiometricContext, mLockoutTracker);
        mScheduler.scheduleClientMonitor(client);
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            if (Flags.deHidl()) {
                scheduleGenerateChallengeAidl(userId, token, receiver, opPackageName);
            } else {
                scheduleGenerateChallengeHidl(userId, token, receiver, opPackageName);
            }
        });
    }

    private void scheduleGenerateChallengeAidl(int userId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintGenerateChallengeClient client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintGenerateChallengeClient(
                        mContext, this::getSession, token,
                        new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                        mSensorProperties.sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext);
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleGenerateChallengeHidl(int userId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName) {
        final FingerprintGenerateChallengeClient client =
                new FingerprintGenerateChallengeClient(mContext, mLazyDaemon, token,
                        new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                        mSensorProperties.sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext);
        mScheduler.scheduleClientMonitor(client);
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
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintRevokeChallengeClient client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintRevokeChallengeClient(
                        mContext, this::getSession,
                        token, userId, opPackageName,
                        mSensorProperties.sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext, 0L);
        mScheduler.scheduleClientMonitor(client);
    }

    private void scheduleRevokeChallengeHidl(int userId, @NonNull IBinder token,
            @NonNull String opPackageName) {
        final FingerprintRevokeChallengeClient client = new FingerprintRevokeChallengeClient(
                mContext, mLazyDaemon, token, userId, opPackageName,
                mSensorProperties.sensorId,
                createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                        BiometricsProtoEnums.CLIENT_UNKNOWN,
                        mAuthenticationStatsCollector),
                mBiometricContext);
        mScheduler.scheduleClientMonitor(client);
    }

    @Override
    public long scheduleEnroll(int sensorId, @NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName,
            @FingerprintManager.EnrollReason int enrollReason) {
        final long id = mRequestCounter.incrementAndGet();
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            if (Flags.deHidl()) {
                scheduleEnrollAidl(token, hardwareAuthToken, userId, receiver,
                        opPackageName, enrollReason, id);
            } else {
                scheduleEnrollHidl(token, hardwareAuthToken, userId, receiver,
                        opPackageName, enrollReason, id);
            }
        });
        return id;
    }

    private void scheduleEnrollHidl(@NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName,
            @FingerprintManager.EnrollReason int enrollReason, long id) {
        final FingerprintEnrollClient client = new FingerprintEnrollClient(mContext,
                mLazyDaemon, token, id, new ClientMonitorCallbackConverter(receiver),
                userId, hardwareAuthToken, opPackageName,
                FingerprintUtils.getLegacyInstance(mSensorId), ENROLL_TIMEOUT_SEC,
                mSensorProperties.sensorId,
                createLogger(BiometricsProtoEnums.ACTION_ENROLL,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, mUdfpsOverlayController,
                // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
                mSidefpsController,
                mAuthenticationStateListeners, enrollReason);
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
                    scheduleUpdateActiveUserWithoutHandler(clientMonitor.getTargetUserId(),
                            true /* force */);
                }
            }
        });
    }

    private void scheduleEnrollAidl(@NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId,
            @NonNull IFingerprintServiceReceiver receiver, @NonNull String opPackageName,
            @FingerprintManager.EnrollReason int enrollReason, long id) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintEnrollClient
                client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintEnrollClient(
                        mContext,
                        this::getSession, token, id,
                        new ClientMonitorCallbackConverter(receiver),
                        userId, hardwareAuthToken, opPackageName,
                        FingerprintUtils.getLegacyInstance(mSensorId),
                        mSensorProperties.sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_ENROLL,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext, null /* sensorProps */,
                        mUdfpsOverlayController,
                        // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
                        mSidefpsController,
                        mAuthenticationStateListeners,
                        mContext.getResources().getInteger(
                                com.android.internal.R.integer.config_fingerprintMaxTemplatesPerUser),
                        enrollReason);

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
                    scheduleUpdateActiveUserWithoutHandler(clientMonitor.getTargetUserId(),
                            true /* force */);
                }
            }
        });
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token, long requestId) {
        mHandler.post(() -> mScheduler.cancelEnrollment(token, requestId));
    }

    @Override
    public long scheduleFingerDetect(@NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            int statsClient) {
        final long id = mRequestCounter.incrementAndGet();
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(options.getUserId());

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorProperties.sensorId);

            if (Flags.deHidl()) {
                scheduleFingerDetectAidl(token, listener, options, statsClient, id,
                        isStrongBiometric);
            } else {
                scheduleFingerDetectHidl(token, listener, options, statsClient, id,
                        isStrongBiometric);
            }
        });

        return id;
    }

    private void scheduleFingerDetectHidl(@NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            int statsClient, long id, boolean isStrongBiometric) {
        final FingerprintDetectClient client = new FingerprintDetectClient(mContext,
                mLazyDaemon, token, id, listener, options,
                createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                        mAuthenticationStatsCollector),
                mBiometricContext, mUdfpsOverlayController, isStrongBiometric);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    private void scheduleFingerDetectAidl(@NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            int statsClient, long id, boolean isStrongBiometric) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintDetectClient
                client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintDetectClient(
                        mContext,
                        this::getSession, token, id, listener, options,
                        createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                                mAuthenticationStatsCollector),
                        mBiometricContext, mUdfpsOverlayController, isStrongBiometric);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    @Override
    public void scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            long requestId, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(options.getUserId());

            final boolean isStrongBiometric = Utils.isStrongBiometric(mSensorProperties.sensorId);

            if (Flags.deHidl()) {
                scheduleAuthenticateAidl(token, operationId, cookie, listener, options, requestId,
                        restricted, statsClient, allowBackgroundAuthentication, isStrongBiometric);
            } else {
                scheduleAuthenticateHidl(token, operationId, cookie, listener, options, requestId,
                        restricted, statsClient, allowBackgroundAuthentication, isStrongBiometric);
            }
        });
    }

    private void scheduleAuthenticateAidl(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            long requestId, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication, boolean isStrongBiometric) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintAuthenticationClient
                client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintAuthenticationClient(
                        mContext, this::getSession, token, requestId, listener, operationId,
                        restricted, options, cookie, false /* requireConfirmation */,
                        createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                                mAuthenticationStatsCollector),
                        mBiometricContext, isStrongBiometric,
                        mTaskStackListener,
                        mUdfpsOverlayController, mSidefpsController,
                        mAuthenticationStateListeners,
                        allowBackgroundAuthentication, mSensorProperties, mHandler,
                        Utils.getCurrentStrength(mSensorId), null /* clock */, mLockoutTracker);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    private void scheduleAuthenticateHidl(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            long requestId, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication, boolean isStrongBiometric) {
        final FingerprintAuthenticationClient client = new FingerprintAuthenticationClient(
                mContext, mLazyDaemon, token, requestId, listener, operationId,
                restricted, options, cookie, false /* requireConfirmation */,
                createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                        mAuthenticationStatsCollector),
                mBiometricContext, isStrongBiometric,
                mTaskStackListener, mLockoutTracker,
                mUdfpsOverlayController, mSidefpsController,
                mAuthenticationStateListeners,
                allowBackgroundAuthentication, mSensorProperties,
                Utils.getCurrentStrength(mSensorId));
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    @Override
    public long scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication) {
        final long id = mRequestCounter.incrementAndGet();

        scheduleAuthenticate(token, operationId, cookie, listener,
                options, id, restricted, statsClient, allowBackgroundAuthentication);

        return id;
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> mScheduler.startPreparedClient(cookie));
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token, long requestId) {
        Slog.d(TAG, "cancelAuthentication, sensorId: " + sensorId);
        mHandler.post(() -> mScheduler.cancelAuthenticationOrDetection(token, requestId));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            if (Flags.deHidl()) {
                scheduleRemoveAidl(token, receiver, fingerId, userId, opPackageName);
            } else {
                scheduleRemoveHidl(token, receiver, fingerId, userId, opPackageName);
            }
        });
    }

    private void scheduleRemoveHidl(@NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {
        final FingerprintRemovalClient client = new FingerprintRemovalClient(mContext,
                mLazyDaemon, token, new ClientMonitorCallbackConverter(receiver), fingerId,
                userId, opPackageName, FingerprintUtils.getLegacyInstance(mSensorId),
                mSensorProperties.sensorId,
                createLogger(BiometricsProtoEnums.ACTION_REMOVE,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext, mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    private void scheduleRemoveAidl(@NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintRemovalClient client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintRemovalClient(
                        mContext, this::getSession, token,
                        new ClientMonitorCallbackConverter(receiver), new int[]{fingerId}, userId,
                        opPackageName, FingerprintUtils.getLegacyInstance(mSensorId), mSensorId,
                        createLogger(BiometricsProtoEnums.ACTION_REMOVE,
                                BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                        mBiometricContext, mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, mBiometricStateCallback);
    }

    @Override
    public void scheduleRemoveAll(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int userId,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            scheduleUpdateActiveUserWithoutHandler(userId);

            // For IBiometricsFingerprint@2.1, remove(0) means remove all enrollments
            if (Flags.deHidl()) {
                scheduleRemoveAidl(token, receiver, 0 /* fingerId */, userId, opPackageName);
            } else {
                scheduleRemoveHidl(token, receiver, 0 /* fingerId */, userId, opPackageName);
            }
        });
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
        final FingerprintInternalCleanupClient client = new FingerprintInternalCleanupClient(
                mContext, mLazyDaemon, userId, mContext.getOpPackageName(),
                mSensorProperties.sensorId,
                createLogger(BiometricsProtoEnums.ACTION_ENUMERATE,
                        BiometricsProtoEnums.CLIENT_UNKNOWN, mAuthenticationStatsCollector),
                mBiometricContext,
                FingerprintUtils.getLegacyInstance(mSensorId), mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, callback);
    }

    private void scheduleInternalCleanupAidl(int userId,
            @Nullable ClientMonitorCallback callback) {
        final com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintInternalCleanupClient
                client =
                new com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintInternalCleanupClient(
                        mContext, this::getSession, userId, mContext.getOpPackageName(),
                        mSensorProperties.sensorId,
                        createLogger(BiometricsProtoEnums.ACTION_ENUMERATE,
                                BiometricsProtoEnums.CLIENT_UNKNOWN,
                                mAuthenticationStatsCollector),
                        mBiometricContext,
                        FingerprintUtils.getLegacyInstance(mSensorId), mAuthenticatorIds);
        mScheduler.scheduleClientMonitor(client, callback);
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback) {
        scheduleInternalCleanup(userId, new ClientMonitorCompositeCallback(callback,
                mBiometricStateCallback));
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback, boolean favorHalEnrollments) {
        scheduleInternalCleanup(userId, new ClientMonitorCompositeCallback(callback,
                mBiometricStateCallback));
    }

    private BiometricLogger createLogger(int statsAction, int statsClient,
            AuthenticationStatsCollector authenticationStatsCollector) {
        return new BiometricLogger(mContext, BiometricsProtoEnums.MODALITY_FINGERPRINT,
                statsAction, statsClient, authenticationStatsCollector);
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
    public boolean hasEnrollments(int sensorId, int userId) {
        return !getEnrolledFingerprints(sensorId, userId).isEmpty();
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
    public void onPointerDown(long requestId, int sensorId, PointerContext pc) {
        mScheduler.getCurrentClientIfMatches(requestId, (client) -> {
            if (!(client instanceof Udfps)) {
                Slog.w(TAG, "onFingerDown received during client: " + client);
                return;
            }
            ((Udfps) client).onPointerDown(pc);
        });
    }

    @Override
    public void onPointerUp(long requestId, int sensorId, PointerContext pc) {
        mScheduler.getCurrentClientIfMatches(requestId, (client) -> {
            if (!(client instanceof Udfps)) {
                Slog.w(TAG, "onFingerDown received during client: " + client);
                return;
            }
            ((Udfps) client).onPointerUp(pc);
        });
    }

    @Override
    public void onUdfpsUiEvent(@FingerprintManager.UdfpsUiEvent int event, long requestId,
            int sensorId) {
        mScheduler.getCurrentClientIfMatches(requestId, (client) -> {
            if (!(client instanceof Udfps)) {
                Slog.w(TAG, "onUdfpsUiEvent received during client: " + client);
                return;
            }
            ((Udfps) client).onUdfpsUiEvent(event);
        });
    }

    @Override
    public void onPowerPressed() {
        Slog.e(TAG, "onPowerPressed not supported for HIDL clients");
    }

    @Override
    public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
        mUdfpsOverlayController = controller;
    }

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
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
            @NonNull String opPackageName) {
        return new BiometricTestSessionImpl(mContext, mSensorProperties.sensorId, callback,
                mBiometricStateCallback, this, mHalResultController);
    }
}
