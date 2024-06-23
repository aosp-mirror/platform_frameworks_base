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
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.biometrics.common.ComponentInfo;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.StopUserClient;
import com.android.server.biometrics.sensors.UserAwareBiometricScheduler;
import com.android.server.biometrics.sensors.UserSwitchProvider;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Maintains the state of a single sensor within an instance of the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} HAL.
 */
@SuppressWarnings("deprecation")
public class Sensor {

    private static final String TAG = "Sensor";

    private boolean mTestHalEnabled;

    @NonNull private final FingerprintProvider mProvider;
    @NonNull private final Context mContext;
    @NonNull private final IBinder mToken;
    @NonNull private final Handler mHandler;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    @NonNull private BiometricScheduler<IFingerprint, ISession> mScheduler;
    @NonNull private LockoutTracker mLockoutTracker;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;
    @NonNull private final BiometricContext mBiometricContext;

    @Nullable AidlSession mCurrentSession;
    @NonNull private Supplier<AidlSession> mLazySession;

    public Sensor(@NonNull FingerprintProvider provider,
            @NonNull Context context, @NonNull Handler handler,
            @NonNull FingerprintSensorPropertiesInternal sensorProperties,
            @NonNull BiometricContext biometricContext, AidlSession session) {
        mProvider = provider;
        mContext = context;
        mToken = new Binder();
        mHandler = handler;
        mSensorProperties = sensorProperties;
        mBiometricContext = biometricContext;
        mAuthenticatorIds = new HashMap<>();
        mCurrentSession = session;
    }

    Sensor(@NonNull FingerprintProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull FingerprintSensorPropertiesInternal sensorProperties,
            @NonNull BiometricContext biometricContext) {
        this(provider, context, handler, sensorProperties,
                biometricContext, null);
    }

    Sensor(@NonNull FingerprintProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull SensorProps sensorProp,
            @NonNull BiometricContext biometricContext,
            @NonNull List<SensorLocationInternal> workaroundLocation,
            boolean resetLockoutRequiresHardwareAuthToken) {
        this(provider, context, handler, getFingerprintSensorPropertiesInternal(sensorProp,
                        workaroundLocation, resetLockoutRequiresHardwareAuthToken),
                biometricContext, null);
    }

    /**
     * Initialize biometric scheduler, lockout tracker and session for the sensor.
     */
    public void init(@NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        if (Flags.deHidl()) {
            setScheduler(getBiometricSchedulerForInit(gestureAvailabilityDispatcher,
                    lockoutResetDispatcher));
        } else {
            setScheduler(getUserAwareBiometricSchedulerForInit(gestureAvailabilityDispatcher,
                    lockoutResetDispatcher));
        }
        mLockoutTracker = new LockoutCache();
        mLazySession = () -> mCurrentSession != null ? mCurrentSession : null;
    }

    private BiometricScheduler<IFingerprint, ISession> getBiometricSchedulerForInit(
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        return new BiometricScheduler<>(mHandler,
                BiometricScheduler.sensorTypeFromFingerprintProperties(mSensorProperties),
                gestureAvailabilityDispatcher,
                () -> mCurrentSession != null ? mCurrentSession.getUserId() : UserHandle.USER_NULL,
                new UserSwitchProvider<IFingerprint, ISession>() {
                    @NonNull
                    @Override
                    public StopUserClient<ISession> getStopUserClient(int userId) {
                        return new FingerprintStopUserClient(mContext,
                                () -> mLazySession.get().getSession(), mToken,
                                userId, mSensorProperties.sensorId,
                                BiometricLogger.ofUnknown(mContext), mBiometricContext,
                                () -> mCurrentSession = null);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<IFingerprint, ISession> getStartUserClient(
                            int newUserId) {
                        final int sensorId = mSensorProperties.sensorId;
                        final AidlResponseHandler resultController = new AidlResponseHandler(
                                mContext, mScheduler, sensorId, newUserId,
                                mLockoutTracker, lockoutResetDispatcher,
                                mBiometricContext.getAuthSessionCoordinator(), () -> {},
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
                                        Slog.e(TAG,
                                                "Fingerprint sensor hardware unavailable.");
                                        mCurrentSession = null;
                                    }
                                });

                        return Sensor.this.getStartUserClient(resultController, sensorId,
                                newUserId);
                    }
                });
    }

    private UserAwareBiometricScheduler<ISession, AidlSession>
            getUserAwareBiometricSchedulerForInit(
                    GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
                    LockoutResetDispatcher lockoutResetDispatcher) {
        return new UserAwareBiometricScheduler<>(TAG,
                BiometricScheduler.sensorTypeFromFingerprintProperties(mSensorProperties),
                gestureAvailabilityDispatcher,
                () -> mCurrentSession != null ? mCurrentSession.getUserId() : UserHandle.USER_NULL,
                new UserAwareBiometricScheduler.UserSwitchCallback() {
                    @NonNull
                    @Override
                    public StopUserClient<ISession> getStopUserClient(int userId) {
                        return new FingerprintStopUserClient(mContext,
                                () -> mLazySession.get().getSession(), mToken,
                                userId, mSensorProperties.sensorId,
                                BiometricLogger.ofUnknown(mContext), mBiometricContext,
                                () -> mCurrentSession = null);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<IFingerprint, ISession> getStartUserClient(
                            int newUserId) {
                        final int sensorId = mSensorProperties.sensorId;

                        final AidlResponseHandler resultController = new AidlResponseHandler(
                                mContext, mScheduler, sensorId, newUserId,
                                mLockoutTracker, lockoutResetDispatcher,
                                mBiometricContext.getAuthSessionCoordinator(), () -> {
                                    Slog.e(TAG, "Fingerprint hardware unavailable.");
                                    mCurrentSession = null;
                                });

                        return Sensor.this.getStartUserClient(resultController, sensorId,
                                newUserId);
                    }
                });
    }

    private FingerprintStartUserClient getStartUserClient(AidlResponseHandler resultController,
            int sensorId, int newUserId) {
        final StartUserClient.UserStartedCallback<ISession> userStartedCallback =
                (userIdStarted, newSession, halInterfaceVersion) -> {
                    Slog.d(TAG, "New fingerprint session created for user: "
                            + userIdStarted + " with hal version: "
                            + halInterfaceVersion);
                    mCurrentSession = new AidlSession(halInterfaceVersion,
                            newSession, userIdStarted, resultController);
                    if (FingerprintUtils.getInstance(sensorId)
                            .isInvalidationInProgress(mContext, userIdStarted)) {
                        Slog.w(TAG,
                                "Scheduling unfinished invalidation request for "
                                        + "fingerprint sensor: "
                                        + sensorId
                                        + ", user: " + userIdStarted);
                        mProvider.scheduleInvalidationRequest(sensorId,
                                userIdStarted);
                    }
                };

        return new FingerprintStartUserClient(mContext, mProvider::getHalInstance,
                mToken, newUserId, mSensorProperties.sensorId,
                BiometricLogger.ofUnknown(mContext), mBiometricContext,
                resultController, userStartedCallback);
    }

    protected static FingerprintSensorPropertiesInternal getFingerprintSensorPropertiesInternal(
            SensorProps prop, List<SensorLocationInternal> workaroundLocations,
            boolean resetLockoutRequiresHardwareAuthToken) {
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        if (prop.commonProps.componentInfo != null) {
            for (ComponentInfo info : prop.commonProps.componentInfo) {
                componentInfo.add(new ComponentInfoInternal(info.componentId,
                        info.hardwareVersion, info.firmwareVersion, info.serialNumber,
                        info.softwareVersion));
            }
        }
        return new FingerprintSensorPropertiesInternal(prop.commonProps.sensorId,
                prop.commonProps.sensorStrength,
                prop.commonProps.maxEnrollmentsPerUser,
                componentInfo,
                prop.sensorType,
                prop.halControlsIllumination,
                resetLockoutRequiresHardwareAuthToken,
                !workaroundLocations.isEmpty() ? workaroundLocations :
                        Arrays.stream(prop.sensorLocations).map(location ->
                                        new SensorLocationInternal(
                                                location.display,
                                                location.sensorLocationX,
                                                location.sensorLocationY,
                                                location.sensorRadius))
                                .collect(Collectors.toList()));
    }

    @NonNull public Supplier<AidlSession> getLazySession() {
        return mLazySession;
    }

    @NonNull public FingerprintSensorPropertiesInternal getSensorProperties() {
        return mSensorProperties;
    }

    @Nullable protected AidlSession getSessionForUser(int userId) {
        if (mCurrentSession != null && mCurrentSession.getUserId() == userId) {
            return mCurrentSession;
        } else {
            return null;
        }
    }

    @NonNull ITestSession createTestSession(@NonNull ITestSessionCallback callback,
            @NonNull BiometricStateCallback biometricStateCallback) {
        return new BiometricTestSessionImpl(mContext, mSensorProperties.sensorId, callback,
                biometricStateCallback, mProvider, this);
    }

    @NonNull public BiometricScheduler<IFingerprint, ISession> getScheduler() {
        return mScheduler;
    }

    @NonNull protected LockoutTracker getLockoutTracker(boolean forAuth) {
        if (forAuth) {
            return null;
        }
        return mLockoutTracker;
    }

    @NonNull public Map<Integer, Long> getAuthenticatorIds() {
        return mAuthenticatorIds;
    }

    void setTestHalEnabled(boolean enabled) {
        Slog.w(TAG, "Fingerprint setTestHalEnabled: " + enabled);
        if (enabled != mTestHalEnabled) {
            // The framework should retrieve a new session from the HAL.
            try {
                if (mCurrentSession != null) {
                    // TODO(181984005): This should be scheduled instead of directly invoked
                    Slog.d(TAG, "Closing old fingerprint session");
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
            Slog.e(TAG, "Sending fingerprint hardware unavailable error for client: " + client);
            final ErrorConsumer errorConsumer = (ErrorConsumer) client;
            errorConsumer.onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                    BiometricsProtoEnums.MODALITY_FINGERPRINT,
                    BiometricsProtoEnums.ISSUE_HAL_DEATH,
                    -1 /* sensorId */);
        } else if (client != null) {
            client.cancel();
        }

        mScheduler.recordCrashState();
        mScheduler.reset();
        mCurrentSession = null;
    }

    @NonNull protected Handler getHandler() {
        return mHandler;
    }

    @NonNull protected Context getContext() {
        return mContext;
    }

    /**
     * Returns true if the sensor hardware is detected.
     */
    protected boolean isHardwareDetected(String halInstance) {
        if (mTestHalEnabled) {
            return true;
        }
        return (ServiceManager.checkService(IFingerprint.DESCRIPTOR + "/" + halInstance)
                != null);
    }

    @NonNull protected BiometricContext getBiometricContext() {
        return mBiometricContext;
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

    public FingerprintProvider getProvider() {
        return mProvider;
    }
}
