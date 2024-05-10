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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.common.ComponentInfo;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.AuthenticationStatsBroadcastReceiver;
import com.android.server.biometrics.AuthenticationStatsCollector;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.InvalidationRequesterClient;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.SensorList;
import com.android.server.biometrics.sensors.face.FaceUtils;
import com.android.server.biometrics.sensors.face.ServiceProvider;
import com.android.server.biometrics.sensors.face.UsageStats;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider for a single instance of the {@link IFace} HAL.
 */
public class FaceProvider implements IBinder.DeathRecipient, ServiceProvider {
    private static final int ENROLL_TIMEOUT_SEC = 75;

    private boolean mTestHalEnabled;

    @NonNull
    @VisibleForTesting
    final SensorList<Sensor> mFaceSensors;
    @NonNull
    private final Context mContext;
    @NonNull
    private final BiometricStateCallback mBiometricStateCallback;
    @NonNull
    private final String mHalInstanceName;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    @NonNull
    private final UsageStats mUsageStats;
    @NonNull
    private final ActivityTaskManager mActivityTaskManager;
    @NonNull
    private final BiometricTaskStackListener mTaskStackListener;
    // for requests that do not use biometric prompt
    @NonNull
    private final AtomicLong mRequestCounter = new AtomicLong(0);
    @NonNull
    private final BiometricContext mBiometricContext;
    @NonNull
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    @Nullable
    private AuthenticationStatsCollector mAuthenticationStatsCollector;
    @Nullable
    private IFace mDaemon;

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> {
                for (int i = 0; i < mFaceSensors.size(); i++) {
                    final BaseClientMonitor client = mFaceSensors.valueAt(i).getScheduler()
                            .getCurrentClient();
                    if (!(client instanceof AuthenticationClient)) {
                        Slog.e(getTag(), "Task stack changed for client: " + client);
                        continue;
                    }
                    if (Utils.isKeyguard(mContext, client.getOwnerString())
                            || Utils.isSystem(mContext, client.getOwnerString())) {
                        continue; // Keyguard is always allowed
                    }

                    if (Utils.isBackground(client.getOwnerString())
                            && !client.isAlreadyDone()) {
                        Slog.e(getTag(), "Stopping background authentication,"
                                + " currentClient: " + client);
                        mFaceSensors.valueAt(i).getScheduler().cancelAuthenticationOrDetection(
                                client.getToken(), client.getRequestId());
                    }
                }
            });
        }
    }

    public FaceProvider(@NonNull Context context,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull SensorProps[] props,
            @NonNull String halInstanceName,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext) {
        this(context, biometricStateCallback, props, halInstanceName, lockoutResetDispatcher,
                biometricContext, null /* daemon */);
    }

    @VisibleForTesting FaceProvider(@NonNull Context context,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull SensorProps[] props,
            @NonNull String halInstanceName,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext,
            IFace daemon) {
        mContext = context;
        mBiometricStateCallback = biometricStateCallback;
        mHalInstanceName = halInstanceName;
        mFaceSensors = new SensorList<>(ActivityManager.getService());
        mHandler = new Handler(Looper.getMainLooper());
        mUsageStats = new UsageStats(context);
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mActivityTaskManager = ActivityTaskManager.getInstance();
        mTaskStackListener = new BiometricTaskStackListener();
        mBiometricContext = biometricContext;
        mAuthSessionCoordinator = mBiometricContext.getAuthSessionCoordinator();
        mDaemon = daemon;

        AuthenticationStatsBroadcastReceiver mBroadcastReceiver =
                new AuthenticationStatsBroadcastReceiver(
                        mContext,
                        BiometricsProtoEnums.MODALITY_FACE,
                        (AuthenticationStatsCollector collector) -> {
                            Slog.d(getTag(), "Initializing AuthenticationStatsCollector");
                            mAuthenticationStatsCollector = collector;
                        });

        for (SensorProps prop : props) {
            final int sensorId = prop.commonProps.sensorId;

            final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
            if (prop.commonProps.componentInfo != null) {
                for (ComponentInfo info : prop.commonProps.componentInfo) {
                    componentInfo.add(new ComponentInfoInternal(info.componentId,
                            info.hardwareVersion, info.firmwareVersion, info.serialNumber,
                            info.softwareVersion));
                }
            }

            final FaceSensorPropertiesInternal internalProp = new FaceSensorPropertiesInternal(
                    prop.commonProps.sensorId, prop.commonProps.sensorStrength,
                    prop.commonProps.maxEnrollmentsPerUser, componentInfo, prop.sensorType,
                    prop.supportsDetectInteraction, prop.halControlsPreview,
                    false /* resetLockoutRequiresChallenge */);
            final Sensor sensor = new Sensor(getTag() + "/" + sensorId, this, mContext, mHandler,
                    internalProp, lockoutResetDispatcher, mBiometricContext);
            final int userId = sensor.getLazySession().get() == null ? UserHandle.USER_NULL :
                    sensor.getLazySession().get().getUserId();
            mFaceSensors.addSensor(sensorId, sensor, userId,
                    new SynchronousUserSwitchObserver() {
                        @Override
                        public void onUserSwitching(int newUserId) {
                            scheduleInternalCleanup(sensorId, newUserId, null /* callback */);
                        }
                    });
            Slog.d(getTag(), "Added: " + internalProp);
        }
    }

    private String getTag() {
        return "FaceProvider/" + mHalInstanceName;
    }

    boolean hasHalInstance() {
        if (mTestHalEnabled) {
            return true;
        }
        return ServiceManager.checkService(IFace.DESCRIPTOR + "/" + mHalInstanceName) != null;
    }

    @Nullable
    @VisibleForTesting
    synchronized IFace getHalInstance() {
        if (mTestHalEnabled) {
            return new TestHal();
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(getTag(), "Daemon was null, reconnecting");

        mDaemon = IFace.Stub.asInterface(
                Binder.allowBlocking(
                        ServiceManager.waitForDeclaredService(
                                IFace.DESCRIPTOR + "/" + mHalInstanceName)));
        if (mDaemon == null) {
            Slog.e(getTag(), "Unable to get daemon");
            return null;
        }

        try {
            mDaemon.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(getTag(), "Unable to linkToDeath", e);
        }

        for (int i = 0; i < mFaceSensors.size(); i++) {
            final int sensorId = mFaceSensors.keyAt(i);
            scheduleLoadAuthenticatorIds(sensorId);
            scheduleInternalCleanup(sensorId, ActivityManager.getCurrentUser(),
                    null /* callback */);
        }

        return mDaemon;
    }

    private void scheduleForSensor(int sensorId, @NonNull BaseClientMonitor client) {
        if (!mFaceSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mFaceSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
    }

    private void scheduleForSensor(int sensorId, @NonNull BaseClientMonitor client,
            ClientMonitorCallback callback) {
        if (!mFaceSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mFaceSensors.get(sensorId).getScheduler().scheduleClientMonitor(client, callback);
    }

    private void scheduleLoadAuthenticatorIds(int sensorId) {
        for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
            scheduleLoadAuthenticatorIdsForUser(sensorId, user.id);
        }
    }

    private void scheduleLoadAuthenticatorIdsForUser(int sensorId, int userId) {
        mHandler.post(() -> {
            final FaceGetAuthenticatorIdClient client = new FaceGetAuthenticatorIdClient(
                    mContext, mFaceSensors.get(sensorId).getLazySession(), userId,
                    mContext.getOpPackageName(), sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext,
                    mFaceSensors.get(sensorId).getAuthenticatorIds());

            scheduleForSensor(sensorId, client);
        });
    }

    void scheduleInvalidationRequest(int sensorId, int userId) {
        mHandler.post(() -> {
            final InvalidationRequesterClient<Face> client =
                    new InvalidationRequesterClient<>(mContext, userId, sensorId,
                            BiometricLogger.ofUnknown(mContext),
                            mBiometricContext,
                            FaceUtils.getInstance(sensorId));
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mFaceSensors.contains(sensorId);
    }

    @NonNull
    @Override
    public List<FaceSensorPropertiesInternal> getSensorProperties() {
        final List<FaceSensorPropertiesInternal> props = new ArrayList<>();
        for (int i = 0; i < mFaceSensors.size(); ++i) {
            props.add(mFaceSensors.valueAt(i).getSensorProperties());
        }
        return props;
    }

    @NonNull
    @Override
    public FaceSensorPropertiesInternal getSensorProperties(int sensorId) {
        return mFaceSensors.get(sensorId).getSensorProperties();
    }

    @NonNull
    @Override
    public List<Face> getEnrolledFaces(int sensorId, int userId) {
        return FaceUtils.getInstance(sensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    public boolean hasEnrollments(int sensorId, int userId) {
        return !getEnrolledFaces(sensorId, userId).isEmpty();
    }

    @Override
    public void scheduleInvalidateAuthenticatorId(int sensorId, int userId,
            @NonNull IInvalidationCallback callback) {
        mHandler.post(() -> {
            final FaceInvalidationClient client = new FaceInvalidationClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), userId, sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext,
                    mFaceSensors.get(sensorId).getAuthenticatorIds(), callback);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public int getLockoutModeForUser(int sensorId, int userId) {
        return mBiometricContext.getAuthSessionCoordinator().getLockoutStateFor(userId,
                Utils.getCurrentStrength(sensorId));
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return mFaceSensors.get(sensorId).getAuthenticatorIds().getOrDefault(userId, 0L);
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return hasHalInstance();
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            final FaceGenerateChallengeClient client = new FaceGenerateChallengeClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), userId, opPackageName, sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            final FaceRevokeChallengeClient client = new FaceRevokeChallengeClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), token, userId,
                    opPackageName, sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext, challenge);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public long scheduleEnroll(int sensorId, @NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName, @NonNull int[] disabledFeatures,
            @Nullable Surface previewSurface, boolean debugConsent) {
        final long id = mRequestCounter.incrementAndGet();
        mHandler.post(() -> {
            final int maxTemplatesPerUser = mFaceSensors.get(
                    sensorId).getSensorProperties().maxEnrollmentsPerUser;
            final FaceEnrollClient client = new FaceEnrollClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                    opPackageName, id, FaceUtils.getInstance(sensorId), disabledFeatures,
                    ENROLL_TIMEOUT_SEC, previewSurface, sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_ENROLL,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext, maxTemplatesPerUser, debugConsent);
            scheduleForSensor(sensorId, client, new ClientMonitorCompositeCallback(
                            mBiometricStateCallback, new ClientMonitorCallback() {
                        @Override
                        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                                boolean success) {
                            ClientMonitorCallback.super.onClientFinished(clientMonitor, success);
                            if (success) {
                                scheduleLoadAuthenticatorIdsForUser(sensorId, userId);
                                scheduleInvalidationRequest(sensorId, userId);
                            }
                        }
                    }));
        });
        return id;
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token, long requestId) {
        mHandler.post(() ->
                mFaceSensors.get(sensorId).getScheduler().cancelEnrollment(token, requestId));
    }

    @Override
    public long scheduleFaceDetect(@NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options, int statsClient) {
        final long id = mRequestCounter.incrementAndGet();
        final int sensorId = options.getSensorId();

        mHandler.post(() -> {
            final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
            final FaceDetectClient client = new FaceDetectClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(),
                    token, id, callback, options,
                    createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                            mAuthenticationStatsCollector),
                    mBiometricContext, isStrongBiometric);
            scheduleForSensor(sensorId, client, mBiometricStateCallback);
        });

        return id;
    }

    @Override
    public void cancelFaceDetect(int sensorId, @NonNull IBinder token, long requestId) {
        mHandler.post(() -> mFaceSensors.get(sensorId).getScheduler()
                .cancelAuthenticationOrDetection(token, requestId));
    }

    @Override
    public void scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options,
            long requestId, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication) {
        mHandler.post(() -> {
            final int userId = options.getUserId();
            final int sensorId = options.getSensorId();
            final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
            final FaceAuthenticationClient client = new FaceAuthenticationClient(
                    mContext, mFaceSensors.get(sensorId).getLazySession(), token, requestId,
                    callback, operationId, restricted, options, cookie,
                    false /* requireConfirmation */,
                    createLogger(BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient,
                            mAuthenticationStatsCollector),
                    mBiometricContext, isStrongBiometric,
                    mUsageStats, null /* lockoutTracker */,
                    allowBackgroundAuthentication, Utils.getCurrentStrength(sensorId));
            scheduleForSensor(sensorId, client, new ClientMonitorCallback() {
                @Override
                public void onClientStarted(
                         BaseClientMonitor clientMonitor) {
                    mAuthSessionCoordinator.authStartedFor(userId, sensorId, requestId);
                }

                @Override
                public void onClientFinished(
                        BaseClientMonitor clientMonitor,
                        boolean success) {
                    mAuthSessionCoordinator.authEndedFor(userId, Utils.getCurrentStrength(sensorId),
                            sensorId, requestId, client.wasAuthSuccessful());
                }
            });
        });
    }

    @Override
    public long scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication) {
        final long id = mRequestCounter.incrementAndGet();

        scheduleAuthenticate(token, operationId, cookie, callback,
                options, id, restricted, statsClient, allowBackgroundAuthentication);

        return id;
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token, long requestId) {
        mHandler.post(() -> mFaceSensors.get(sensorId).getScheduler()
                .cancelAuthenticationOrDetection(token, requestId));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token, int faceId, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        scheduleRemoveSpecifiedIds(sensorId, token, new int[]{faceId}, userId, receiver,
                opPackageName);
    }

    @Override
    public void scheduleRemoveAll(int sensorId, @NonNull IBinder token, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        final List<Face> faces = FaceUtils.getInstance(sensorId)
                .getBiometricsForUser(mContext, userId);
        final int[] faceIds = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            faceIds[i] = faces.get(i).getBiometricId();
        }

        scheduleRemoveSpecifiedIds(sensorId, token, faceIds, userId, receiver, opPackageName);
    }

    private void scheduleRemoveSpecifiedIds(int sensorId, @NonNull IBinder token, int[] faceIds,
            int userId, @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FaceRemovalClient client = new FaceRemovalClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), faceIds, userId,
                    opPackageName, FaceUtils.getInstance(sensorId), sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_REMOVE,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext,
                    mFaceSensors.get(sensorId).getAuthenticatorIds());
            scheduleForSensor(sensorId, client, mBiometricStateCallback);
        });
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @NonNull byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            final FaceResetLockoutClient client = new FaceResetLockoutClient(
                    mContext, mFaceSensors.get(sensorId).getLazySession(), userId,
                    mContext.getOpPackageName(), sensorId,
                    createLogger(BiometricsProtoEnums.ACTION_UNKNOWN,
                            BiometricsProtoEnums.CLIENT_UNKNOWN,
                            mAuthenticationStatsCollector),
                    mBiometricContext, hardwareAuthToken,
                    mFaceSensors.get(sensorId).getLockoutCache(), mLockoutResetDispatcher,
                    Utils.getCurrentStrength(sensorId));

            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleSetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final List<Face> faces = FaceUtils.getInstance(sensorId)
                    .getBiometricsForUser(mContext, userId);
            if (faces.isEmpty()) {
                Slog.w(getTag(), "Ignoring setFeature, no templates enrolled for user: " + userId);
                return;
            }
            final FaceSetFeatureClient client = new FaceSetFeatureClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), userId,
                    mContext.getOpPackageName(), sensorId,
                    BiometricLogger.ofUnknown(mContext), mBiometricContext,
                    feature, enabled, hardwareAuthToken);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleGetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final List<Face> faces = FaceUtils.getInstance(sensorId)
                    .getBiometricsForUser(mContext, userId);
            if (faces.isEmpty()) {
                Slog.w(getTag(), "Ignoring getFeature, no templates enrolled for user: " + userId);
                return;
            }
            final FaceGetFeatureClient client = new FaceGetFeatureClient(mContext,
                    mFaceSensors.get(sensorId).getLazySession(), token, callback, userId,
                    mContext.getOpPackageName(), sensorId, BiometricLogger.ofUnknown(mContext),
                    mBiometricContext, feature);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> {
            mFaceSensors.get(sensorId).getScheduler().startPreparedClient(cookie);
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback) {
        scheduleInternalCleanup(sensorId, userId, callback, false /* favorHalEnrollments */);
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback, boolean favorHalEnrollments) {
        mHandler.post(() -> {
            final FaceInternalCleanupClient client =
                    new FaceInternalCleanupClient(mContext,
                            mFaceSensors.get(sensorId).getLazySession(), userId,
                            mContext.getOpPackageName(), sensorId,
                            createLogger(BiometricsProtoEnums.ACTION_ENUMERATE,
                                    BiometricsProtoEnums.CLIENT_UNKNOWN,
                                    mAuthenticationStatsCollector),
                            mBiometricContext,
                            FaceUtils.getInstance(sensorId),
                            mFaceSensors.get(sensorId).getAuthenticatorIds());
            if (favorHalEnrollments) {
                client.setFavorHalEnrollments();
            }
            scheduleForSensor(sensorId, client, new ClientMonitorCompositeCallback(callback,
                    mBiometricStateCallback));
        });
    }

    private BiometricLogger createLogger(int statsAction, int statsClient,
            AuthenticationStatsCollector authenticationStatsCollector) {
        return new BiometricLogger(mContext, BiometricsProtoEnums.MODALITY_FACE,
                statsAction, statsClient, authenticationStatsCollector);
    }

    @Override
    public void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
            boolean clearSchedulerBuffer) {
        if (mFaceSensors.contains(sensorId)) {
            mFaceSensors.get(sensorId).dumpProtoState(sensorId, proto, clearSchedulerBuffer);
        }
    }

    @Override
    public void dumpProtoMetrics(int sensorId, @NonNull FileDescriptor fd) {

    }

    @Override
    public void dumpInternal(int sensorId, @NonNull PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(sensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", getTag());

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int c = FaceUtils.getInstance(sensorId)
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
            Slog.e(getTag(), "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + performanceTracker.getHALDeathCount());
        pw.println("---AuthSessionCoordinator logs begin---");
        pw.println(mBiometricContext.getAuthSessionCoordinator());
        pw.println("---AuthSessionCoordinator logs end  ---");

        mFaceSensors.get(sensorId).getScheduler().dump(pw);
        mUsageStats.print(pw);
    }

    @NonNull
    @Override
    public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull String opPackageName) {
        return mFaceSensors.get(sensorId).createTestSession(callback);
    }

    @Override
    public void dumpHal(int sensorId, @NonNull FileDescriptor fd, @NonNull String[] args) {
    }

    @Override
    public void binderDied() {
        Slog.e(getTag(), "HAL died");
        mHandler.post(() -> {
            mDaemon = null;
            for (int i = 0; i < mFaceSensors.size(); i++) {
                final Sensor sensor = mFaceSensors.valueAt(i);
                final int sensorId = mFaceSensors.keyAt(i);
                PerformanceTracker.getInstanceForSensorId(sensorId).incrementHALDeathCount();
                sensor.onBinderDied();
            }
        });
    }

    void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }

    @Override
    public void scheduleWatchdog(int sensorId) {
        Slog.d(getTag(), "Starting watchdog for face");
        final BiometricScheduler biometricScheduler = mFaceSensors.get(sensorId).getScheduler();
        if (biometricScheduler == null) {
            return;
        }
        biometricScheduler.startWatchdog();
    }
}
