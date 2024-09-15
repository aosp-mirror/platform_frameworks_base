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

import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.common.ComponentInfo;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
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
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.StopUserClient;
import com.android.server.biometrics.sensors.UserSwitchProvider;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maintains the state of a single sensor within an instance of the {@link IFace} HAL.
 */
public class Sensor {

    private static final String TAG = "Sensor";

    private boolean mTestHalEnabled;

    @NonNull private final FaceProvider mProvider;
    @NonNull private final Context mContext;
    @NonNull private final IBinder mToken;
    @NonNull private final Handler mHandler;
    @NonNull private final FaceSensorPropertiesInternal mSensorProperties;
    @NonNull private BiometricScheduler<IFace, ISession> mScheduler;
    @Nullable private LockoutTracker mLockoutTracker;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;

    @NonNull private Supplier<AidlSession> mLazySession;
    @Nullable AidlSession mCurrentSession;
    @NonNull BiometricContext mBiometricContext;

    Sensor(@NonNull FaceProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull FaceSensorPropertiesInternal sensorProperties,
            @NonNull BiometricContext biometricContext) {
        mProvider = provider;
        mContext = context;
        mToken = new Binder();
        mHandler = handler;
        mSensorProperties = sensorProperties;
        mBiometricContext = biometricContext;
        mAuthenticatorIds = new HashMap<>();
    }

    public Sensor(@NonNull FaceProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull SensorProps prop,
            @NonNull BiometricContext biometricContext,
            boolean resetLockoutRequiresChallenge) {
        this(provider, context, handler,
                getFaceSensorPropertiesInternal(prop, resetLockoutRequiresChallenge),
                biometricContext);
    }

    /**
     * Initialize biometric scheduler, lockout tracker and session for the sensor.
     */
    public void init(@NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull FaceProvider provider) {
        setScheduler(getBiometricSchedulerForInit(lockoutResetDispatcher, provider));
        mLazySession = () -> mCurrentSession != null ? mCurrentSession : null;
        mLockoutTracker = new LockoutCache();
    }

    private BiometricScheduler<IFace, ISession> getBiometricSchedulerForInit(
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull FaceProvider provider) {
        return new BiometricScheduler<>(mHandler,
                BiometricScheduler.SENSOR_TYPE_FACE,
                null /* gestureAvailabilityDispatcher */,
                () -> mCurrentSession != null ? mCurrentSession.getUserId() : UserHandle.USER_NULL,
                new UserSwitchProvider<IFace, ISession>() {
                    @NonNull
                    @Override
                    public StopUserClient<ISession> getStopUserClient(int userId) {
                        return new FaceStopUserClient(mContext,
                                () -> mLazySession.get().getSession(), mToken, userId,
                                mSensorProperties.sensorId, BiometricLogger.ofUnknown(mContext),
                                mBiometricContext, () -> mCurrentSession = null);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<IFace, ISession> getStartUserClient(int newUserId) {
                        final int sensorId = mSensorProperties.sensorId;
                        final AidlResponseHandler resultController = new AidlResponseHandler(
                                mContext, mScheduler, sensorId, newUserId,
                                mLockoutTracker, lockoutResetDispatcher,
                                mBiometricContext.getAuthSessionCoordinator(),
                                new AidlResponseHandler.AidlResponseHandlerCallback() {
                                    @Override
                                    public void onEnrollSuccess() {
                                        mProvider.scheduleLoadAuthenticatorIdsForUser(sensorId,
                                                newUserId);
                                        mProvider.scheduleInvalidationRequest(sensorId,
                                                newUserId);
                                    }

                                    @Override
                                    public void onHardwareUnavailable() {
                                        Slog.e(TAG, "Face sensor hardware unavailable.");
                                        mCurrentSession = null;
                                    }
                                });

                        return Sensor.this.getStartUserClient(resultController, sensorId,
                                newUserId, provider);
                    }
                });
    }

    private FaceStartUserClient getStartUserClient(@NonNull AidlResponseHandler resultController,
            int sensorId, int newUserId, @NonNull FaceProvider provider) {
        final StartUserClient.UserStartedCallback<ISession> userStartedCallback =
                (userIdStarted, newSession, halInterfaceVersion) -> {
                    Slog.d(TAG, "New face session created for user: "
                            + userIdStarted + " with hal version: "
                            + halInterfaceVersion);
                    mCurrentSession = new AidlSession(halInterfaceVersion,
                            newSession, userIdStarted, resultController);
                    if (FaceUtils.getLegacyInstance(sensorId)
                            .isInvalidationInProgress(mContext, userIdStarted)) {
                        Slog.w(TAG,
                                "Scheduling unfinished invalidation request for "
                                        + "face sensor: "
                                        + sensorId
                                        + ", user: " + userIdStarted);
                        provider.scheduleInvalidationRequest(sensorId,
                                userIdStarted);
                    }
                };

        return new FaceStartUserClient(mContext, provider::getHalInstance, mToken, newUserId,
                mSensorProperties.sensorId, BiometricLogger.ofUnknown(mContext), mBiometricContext,
                resultController, userStartedCallback);
    }

    private static FaceSensorPropertiesInternal getFaceSensorPropertiesInternal(SensorProps prop,
            boolean resetLockoutRequiresChallenge) {
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        if (prop.commonProps.componentInfo != null) {
            for (ComponentInfo info : prop.commonProps.componentInfo) {
                componentInfo.add(new ComponentInfoInternal(info.componentId,
                        info.hardwareVersion, info.firmwareVersion, info.serialNumber,
                        info.softwareVersion));
            }
        }
        return new FaceSensorPropertiesInternal(
                prop.commonProps.sensorId, prop.commonProps.sensorStrength,
                prop.commonProps.maxEnrollmentsPerUser, componentInfo, prop.sensorType,
                prop.supportsDetectInteraction, prop.halControlsPreview,
                resetLockoutRequiresChallenge);
    }

    @NonNull public Supplier<AidlSession> getLazySession() {
        return mLazySession;
    }

    @NonNull protected FaceSensorPropertiesInternal getSensorProperties() {
        return mSensorProperties;
    }

    @VisibleForTesting @Nullable protected AidlSession getSessionForUser(int userId) {
        Slog.d(TAG, "getSessionForUser: mCurrentSession: " + mCurrentSession);
        if (mCurrentSession != null && mCurrentSession.getUserId() == userId) {
            return mCurrentSession;
        } else {
            return null;
        }
    }

    @NonNull ITestSession createTestSession(@NonNull ITestSessionCallback callback) {
        return new BiometricTestSessionImpl(mContext, mSensorProperties.sensorId, callback,
                mProvider, this);
    }

    @NonNull public BiometricScheduler<IFace, ISession> getScheduler() {
        return mScheduler;
    }

    @NonNull protected LockoutTracker getLockoutTracker(boolean forAuth) {
        if (forAuth) {
            return null;
        }
        return mLockoutTracker;
    }

    @NonNull protected Map<Integer, Long> getAuthenticatorIds() {
        return mAuthenticatorIds;
    }

    void setTestHalEnabled(boolean enabled) {
        Slog.w(TAG, "Face setTestHalEnabled: " + enabled);
        if (enabled != mTestHalEnabled) {
            // The framework should retrieve a new session from the HAL.
            try {
                if (mCurrentSession != null) {
                    // TODO(181984005): This should be scheduled instead of directly invoked
                    Slog.d(TAG, "Closing old face session");
                    mCurrentSession.getSession().close();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException", e);
            }
            mCurrentSession = null;
        }
        mTestHalEnabled = enabled;
    }

    void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
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
            proto.write(UserStateProto.NUM_ENROLLED,
                    FaceUtils.getInstance(mSensorProperties.sensorId)
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
        if (client != null && client.isInterruptable()) {
            Slog.e(TAG, "Sending face hardware unavailable error for client: " + client);
            final ErrorConsumer errorConsumer = (ErrorConsumer) client;
            errorConsumer.onError(FACE_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                    BiometricsProtoEnums.MODALITY_FACE,
                    BiometricsProtoEnums.ISSUE_HAL_DEATH,
                    -1 /* sensorId */);
        } else if (client != null) {
            client.cancel();
        }

        mScheduler.recordCrashState();
        mScheduler.reset();
        mCurrentSession = null;
    }

    protected BiometricContext getBiometricContext() {
        return mBiometricContext;
    }

    protected Handler getHandler() {
        return mHandler;
    }

    protected Context getContext() {
        return mContext;
    }

    /**
     * Schedules FaceUpdateActiveUserClient for user id.
     */
    public void scheduleFaceUpdateActiveUserClient(int userId) {}

    /**
     * Returns true if the sensor hardware is detected.
     */
    public boolean isHardwareDetected(String halInstanceName) {
        if (mTestHalEnabled) {
            return true;
        }
        return ServiceManager.checkService(IFace.DESCRIPTOR + "/" + halInstanceName) != null;
    }

    /**
     * Returns lockout mode of this sensor.
     */
    @LockoutTracker.LockoutMode
    public int getLockoutModeForUser(int userId) {
        return mBiometricContext.getAuthSessionCoordinator().getLockoutStateFor(userId,
                Utils.getCurrentStrength(mSensorProperties.sensorId));
    }

    public void setScheduler(BiometricScheduler scheduler) {
        mScheduler = scheduler;
    }

    public void setLazySession(
            Supplier<AidlSession> lazySession) {
        mLazySession = lazySession;
    }
}
