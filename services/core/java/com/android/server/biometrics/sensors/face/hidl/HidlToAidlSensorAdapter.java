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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
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
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.face.FaceUtils;
import com.android.server.biometrics.sensors.face.LockoutHalImpl;
import com.android.server.biometrics.sensors.face.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.face.aidl.AidlSession;
import com.android.server.biometrics.sensors.face.aidl.FaceProvider;
import com.android.server.biometrics.sensors.face.aidl.Sensor;

/**
 * Convert HIDL sensor configurations to an AIDL Sensor.
 */
public class HidlToAidlSensorAdapter extends Sensor implements IHwBinder.DeathRecipient{

    private static final String TAG = "HidlToAidlSensorAdapter";

    private IBiometricsFace mDaemon;
    private AidlSession mSession;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private final Runnable mInternalCleanupAndGetFeatureRunnable;
    private final FaceProvider mFaceProvider;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    private final AidlResponseHandler.AidlResponseHandlerCallback mAidlResponseHandlerCallback;
    private final StartUserClient.UserStartedCallback<AidlSession> mUserStartedCallback =
            (newUserId, newUser, halInterfaceVersion) -> {
                if (newUserId != mCurrentUserId) {
                    handleUserChanged(newUserId);
                }
            };
    private LockoutHalImpl mLockoutTracker;

    public HidlToAidlSensorAdapter(@NonNull FaceProvider provider,
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull SensorProps prop,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext,
            boolean resetLockoutRequiresChallenge,
            @NonNull Runnable internalCleanupAndGetFeatureRunnable) {
        this(provider, context, handler, prop, lockoutResetDispatcher, biometricContext,
                resetLockoutRequiresChallenge, internalCleanupAndGetFeatureRunnable,
                new AuthSessionCoordinator(), null /* daemon */,
                null /* onEnrollSuccessCallback */);
    }

    @VisibleForTesting
    HidlToAidlSensorAdapter(@NonNull FaceProvider provider,
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull SensorProps prop,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull BiometricContext biometricContext,
            boolean resetLockoutRequiresChallenge,
            @NonNull Runnable internalCleanupAndGetFeatureRunnable,
            @NonNull AuthSessionCoordinator authSessionCoordinator,
            @Nullable IBiometricsFace daemon,
            @Nullable AidlResponseHandler.AidlResponseHandlerCallback aidlResponseHandlerCallback) {
        super(provider, context, handler, prop, biometricContext,
                resetLockoutRequiresChallenge);
        mInternalCleanupAndGetFeatureRunnable = internalCleanupAndGetFeatureRunnable;
        mFaceProvider = provider;
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mAuthSessionCoordinator = authSessionCoordinator;
        mDaemon = daemon;
        mAidlResponseHandlerCallback = aidlResponseHandlerCallback == null
                ? new AidlResponseHandler.AidlResponseHandlerCallback() {
                    @Override
                    public void onEnrollSuccess() {
                        scheduleFaceUpdateActiveUserClient(mCurrentUserId);
                    }

                    @Override
                    public void onHardwareUnavailable() {
                        mDaemon = null;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }
                } : aidlResponseHandlerCallback;
    }

    @Override
    public void scheduleFaceUpdateActiveUserClient(int userId) {
        getScheduler().scheduleClientMonitor(getFaceUpdateActiveUserClient(userId));
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.d(TAG, "Face HAL died.");
        mDaemon = null;
    }

    @Override
    public boolean isHardwareDetected(String halInstanceName) {
        return getIBiometricsFace() != null;
    }

    @Override
    @LockoutTracker.LockoutMode
    public int getLockoutModeForUser(int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    @Override
    public void init(@NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull FaceProvider provider) {
        setScheduler(new BiometricScheduler<ISession, AidlSession>(getHandler(),
                BiometricScheduler.SENSOR_TYPE_FACE,
                null /* gestureAvailabilityTracker */, () -> mCurrentUserId,
                null /* userSwitchProvider */));
        setLazySession(this::getSession);
        mLockoutTracker = new LockoutHalImpl();
    }

    @Override
    @VisibleForTesting @Nullable protected AidlSession getSessionForUser(int userId) {
        if (mSession != null && mSession.getUserId() == userId) {
            return mSession;
        } else {
            return null;
        }
    }

    @Override
    public FaceUtils getFaceUtilsInstance() {
        return FaceUtils.getLegacyInstance(getSensorProperties().sensorId);
    }

    @Override
    protected LockoutTracker getLockoutTracker(boolean forAuth) {
        return mLockoutTracker;
    }

    @NonNull AidlSession getSession() {
        if (mDaemon != null && mSession != null) {
            return mSession;
        } else {
            return mSession = new AidlSession(getContext(), this::getIBiometricsFace,
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
                getFaceUtilsInstance());
    }

    private IBiometricsFace getIBiometricsFace() {
        if (mFaceProvider.getTestHalEnabled()) {
            final TestHal testHal = new TestHal(getContext(), getSensorProperties().sensorId);
            testHal.setCallback(new HidlToAidlCallbackConverter(getAidlResponseHandler()));
            return testHal;
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(TAG, "Face daemon was null, reconnecting, current operation: "
                + getScheduler().getCurrentClient());

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

        scheduleLoadAuthenticatorIds();
        mInternalCleanupAndGetFeatureRunnable.run();
        return mDaemon;
    }

    @VisibleForTesting void handleUserChanged(int newUserId) {
        Slog.d(TAG, "User changed. Current user for face sensor is " + newUserId);
        mSession = null;
        mCurrentUserId = newUserId;
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
        getHandler().post(() -> {
            for (UserInfo user : UserManager.get(getContext()).getAliveUsers()) {
                final int targetUserId = user.id;
                if (!getAuthenticatorIds().containsKey(targetUserId)) {
                    scheduleFaceUpdateActiveUserClient(targetUserId);
                }
            }
        });
    }

    private FaceUpdateActiveUserClient getFaceUpdateActiveUserClient(int userId) {
        return new FaceUpdateActiveUserClient(getContext(), this::getIBiometricsFace,
                mUserStartedCallback, userId, TAG, getSensorProperties().sensorId,
                BiometricLogger.ofUnknown(getContext()), getBiometricContext(),
                !getFaceUtilsInstance().getBiometricsForUser(getContext(), userId).isEmpty(),
                getAuthenticatorIds());
    }
}
