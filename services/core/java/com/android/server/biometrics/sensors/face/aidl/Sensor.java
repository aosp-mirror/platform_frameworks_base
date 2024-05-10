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
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
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
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.StopUserClient;
import com.android.server.biometrics.sensors.UserAwareBiometricScheduler;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maintains the state of a single sensor within an instance of the {@link IFace} HAL.
 */
public class Sensor {

    private boolean mTestHalEnabled;

    @NonNull private final String mTag;
    @NonNull private final FaceProvider mProvider;
    @NonNull private final Context mContext;
    @NonNull private final IBinder mToken;
    @NonNull private final Handler mHandler;
    @NonNull private final FaceSensorPropertiesInternal mSensorProperties;
    @NonNull private final UserAwareBiometricScheduler mScheduler;
    @NonNull private final LockoutCache mLockoutCache;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;

    @NonNull private final Supplier<AidlSession> mLazySession;
    @Nullable AidlSession mCurrentSession;


    Sensor(@NonNull String tag, @NonNull FaceProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull FaceSensorPropertiesInternal sensorProperties,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext, AidlSession session) {
        mTag = tag;
        mProvider = provider;
        mContext = context;
        mToken = new Binder();
        mHandler = handler;
        mSensorProperties = sensorProperties;
        mScheduler = new UserAwareBiometricScheduler(tag,
                BiometricScheduler.SENSOR_TYPE_FACE, null /* gestureAvailabilityDispatcher */,
                () -> mCurrentSession != null ? mCurrentSession.getUserId() : UserHandle.USER_NULL,
                new UserAwareBiometricScheduler.UserSwitchCallback() {
                    @NonNull
                    @Override
                    public StopUserClient<?> getStopUserClient(int userId) {
                        return new FaceStopUserClient(mContext, mLazySession, mToken, userId,
                                mSensorProperties.sensorId,
                                BiometricLogger.ofUnknown(mContext), biometricContext,
                                () -> mCurrentSession = null);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<?, ?> getStartUserClient(int newUserId) {
                        final int sensorId = mSensorProperties.sensorId;

                        final AidlResponseHandler resultController = new AidlResponseHandler(
                                mContext, mScheduler, sensorId, newUserId,
                                mLockoutCache, lockoutResetDispatcher,
                                biometricContext.getAuthSessionCoordinator(), () -> {
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
                                    if (FaceUtils.getLegacyInstance(sensorId)
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

                        return new FaceStartUserClient(mContext, provider::getHalInstance,
                                mToken, newUserId, mSensorProperties.sensorId,
                                BiometricLogger.ofUnknown(mContext), biometricContext,
                                resultController, userStartedCallback);
                    }
                });
        mLockoutCache = new LockoutCache();
        mAuthenticatorIds = new HashMap<>();
        mLazySession = () -> mCurrentSession != null ? mCurrentSession : null;
    }

    Sensor(@NonNull String tag, @NonNull FaceProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull FaceSensorPropertiesInternal sensorProperties,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext) {
        this(tag, provider, context, handler, sensorProperties, lockoutResetDispatcher,
                biometricContext, null);
    }

    @NonNull Supplier<AidlSession> getLazySession() {
        return mLazySession;
    }

    @NonNull FaceSensorPropertiesInternal getSensorProperties() {
        return mSensorProperties;
    }

    @VisibleForTesting @Nullable AidlSession getSessionForUser(int userId) {
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
            Slog.e(mTag, "Sending ERROR_HW_UNAVAILABLE for client: " + client);
            final ErrorConsumer errorConsumer = (ErrorConsumer) client;
            errorConsumer.onError(FaceManager.FACE_ERROR_HW_UNAVAILABLE,
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
}
