/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.os.Handler;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.StopUserClient;
import com.android.server.biometrics.sensors.UserSwitchProvider;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlSession;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.biometrics.sensors.fingerprint.aidl.Sensor;

import java.util.ArrayList;

/**
 * Convert HIDL sensor configurations to an AIDL Sensor.
 */
public class HidlToAidlSensorAdapter extends Sensor implements IHwBinder.DeathRecipient {
    private static final String TAG = "HidlToAidlSensorAdapter";

    private final Runnable mInternalCleanupRunnable;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private LockoutFrameworkImpl mLockoutTracker;
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    private final AidlResponseHandler.AidlResponseHandlerCallback mAidlResponseHandlerCallback;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private IBiometricsFingerprint mDaemon;
    private AidlSession mSession;

    private final StartUserClient.UserStartedCallback<AidlSession> mUserStartedCallback =
            (newUserId, newUser, halInterfaceVersion) -> {
                if (mCurrentUserId != newUserId) {
                    handleUserChanged(newUserId);
                }
            };

    public HidlToAidlSensorAdapter(@NonNull FingerprintProvider provider,
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull SensorProps prop,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext,
            boolean resetLockoutRequiresHardwareAuthToken,
            @NonNull Runnable internalCleanupRunnable) {
        this(provider, context, handler, prop, lockoutResetDispatcher, biometricContext,
                resetLockoutRequiresHardwareAuthToken, internalCleanupRunnable,
                new AuthSessionCoordinator(), null /* daemon */,
                null /* onEnrollSuccessCallback */);
    }

    @VisibleForTesting
    HidlToAidlSensorAdapter(@NonNull FingerprintProvider provider,
            @NonNull Context context, @NonNull Handler handler,
            @NonNull SensorProps prop,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext,
            boolean resetLockoutRequiresHardwareAuthToken,
            @NonNull Runnable internalCleanupRunnable,
            @NonNull AuthSessionCoordinator authSessionCoordinator,
            @Nullable IBiometricsFingerprint daemon,
            @Nullable AidlResponseHandler.AidlResponseHandlerCallback aidlResponseHandlerCallback) {
        super(provider, context, handler, getFingerprintSensorPropertiesInternal(prop,
                        new ArrayList<>(), resetLockoutRequiresHardwareAuthToken),
                biometricContext, null /* session */);
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mInternalCleanupRunnable = internalCleanupRunnable;
        mAuthSessionCoordinator = authSessionCoordinator;
        mDaemon = daemon;
        mAidlResponseHandlerCallback = aidlResponseHandlerCallback == null
                ? new AidlResponseHandler.AidlResponseHandlerCallback() {
                    @Override
                    public void onEnrollSuccess() {
                        getScheduler()
                                .scheduleClientMonitor(getFingerprintUpdateActiveUserClient(
                                        mCurrentUserId, true /* forceUpdateAuthenticatorIds */));
                    }

                    @Override
                    public void onHardwareUnavailable() {
                        mDaemon = null;
                        mSession = null;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }
                } : aidlResponseHandlerCallback;
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.d(TAG, "Fingerprint HAL died.");
        mSession = null;
        mDaemon = null;
    }

    @Override
    @LockoutTracker.LockoutMode
    public int getLockoutModeForUser(int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    @Override
    public void init(@NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        setLazySession(this::getSession);
        setScheduler(new BiometricScheduler<ISession, AidlSession>(getHandler(),
                BiometricScheduler.sensorTypeFromFingerprintProperties(getSensorProperties()),
                gestureAvailabilityDispatcher, () -> mCurrentUserId, getUserSwitchProvider()));
        mLockoutTracker = new LockoutFrameworkImpl(getContext(),
                userId -> mLockoutResetDispatcher.notifyLockoutResetCallbacks(
                        getSensorProperties().sensorId), getHandler());
    }

    @Override
    public FingerprintUtils getFingerprintUtilsInstance() {
        return FingerprintUtils.getLegacyInstance(getSensorProperties().sensorId);
    }

    @Override
    @Nullable
    @VisibleForTesting
    protected AidlSession getSessionForUser(int userId) {
        if (mSession != null && mSession.getUserId() == userId) {
            return mSession;
        } else {
            return null;
        }
    }

    @Override
    protected boolean isHardwareDetected(String halInstance) {
        return getIBiometricsFingerprint() != null;
    }

    @NonNull
    @Override
    protected LockoutTracker getLockoutTracker(boolean forAuth) {
        return mLockoutTracker;
    }

    private synchronized AidlSession getSession() {
        if (mSession != null && mDaemon != null) {
            return mSession;
        } else {
            return mSession = new AidlSession(this::getIBiometricsFingerprint,
                    mCurrentUserId, getAidlResponseHandler());
        }
    }

    private AidlResponseHandler getAidlResponseHandler() {
        return new AidlResponseHandler(getContext(),
                getScheduler(),
                getSensorProperties().sensorId,
                mCurrentUserId,
                mLockoutTracker,
                mLockoutResetDispatcher,
                mAuthSessionCoordinator,
                mAidlResponseHandlerCallback,
                getFingerprintUtilsInstance());
    }

    @VisibleForTesting IBiometricsFingerprint getIBiometricsFingerprint() {
        if (getProvider().getTestHalEnabled()) {
            final TestHal testHal = new TestHal(getContext(), getSensorProperties().sensorId);
            testHal.setNotify(new HidlToAidlCallbackConverter(getAidlResponseHandler()));
            return testHal;
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        try {
            mDaemon = IBiometricsFingerprint.getService();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get fingerprint HAL", e);
        } catch (java.util.NoSuchElementException e) {
            // Service doesn't exist or cannot be opened.
            Slog.w(TAG, "NoSuchElementException", e);
        }

        if (mDaemon == null) {
            Slog.w(TAG, "Fingerprint HAL not available");
            return null;
        }

        mDaemon.asBinder().linkToDeath(this, 0 /* flags */);
        scheduleLoadAuthenticatorIds();
        mInternalCleanupRunnable.run();
        return mDaemon;
    }

    private UserSwitchProvider<ISession, AidlSession> getUserSwitchProvider() {
        return new UserSwitchProvider<>() {
            @NonNull
            @Override
            public StopUserClient<AidlSession> getStopUserClient(int userId) {
                return new StopUserClient<>(getContext(),
                        HidlToAidlSensorAdapter.this::getSession,
                        null /* token */, userId, getSensorProperties().sensorId,
                        BiometricLogger.ofUnknown(getContext()), getBiometricContext(),
                        () -> {
                            mCurrentUserId = UserHandle.USER_NULL;
                            mSession = null;
                        }) {
                    @Override
                    public void start(@NonNull ClientMonitorCallback callback) {
                        super.start(callback);
                        startHalOperation();
                    }

                    @Override
                    protected void startHalOperation() {
                        onUserStopped();
                    }

                    @Override
                    public void unableToStart() {
                        getCallback().onClientFinished(this, false /* success */);
                    }
                };
            }

            @NonNull
            @Override
            public StartUserClient<ISession, AidlSession> getStartUserClient(int newUserId) {
                return getFingerprintUpdateActiveUserClient(newUserId,
                        false /* forceUpdateAuthenticatorId */);
            }
        };
    }

    private FingerprintUpdateActiveUserClient getFingerprintUpdateActiveUserClient(int newUserId,
            boolean forceUpdateAuthenticatorIds) {
        return new FingerprintUpdateActiveUserClient(getContext(),
                () -> getSession().getSession(), newUserId, TAG,
                getSensorProperties().sensorId, BiometricLogger.ofUnknown(getContext()),
                getBiometricContext(), () -> mCurrentUserId,
                !getFingerprintUtilsInstance().getBiometricsForUser(getContext(),
                newUserId).isEmpty(), getAuthenticatorIds(), forceUpdateAuthenticatorIds,
                mUserStartedCallback);
    }

    private void scheduleLoadAuthenticatorIds() {
        getHandler().post(() -> {
            for (UserInfo user : UserManager.get(getContext()).getAliveUsers()) {
                final int targetUserId = user.id;
                if (!getAuthenticatorIds().containsKey(targetUserId)) {
                    getScheduler().scheduleClientMonitor(getFingerprintUpdateActiveUserClient(
                            targetUserId, true /* forceUpdateAuthenticatorIds */));
                }
            }
        });
    }

    @VisibleForTesting void handleUserChanged(int newUserId) {
        Slog.d(TAG, "User changed. Current user for fingerprint sensor is " + newUserId);
        mSession = null;
        mCurrentUserId = newUserId;
    }
}
