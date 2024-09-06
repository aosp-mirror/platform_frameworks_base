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

import static com.android.server.biometrics.sensors.face.hidl.HidlToAidlSessionAdapter.ENROLL_TIMEOUT_SEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.OptionalUint64;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.hardware.face.FaceEnrollOptions;
import android.hardware.face.HidlFaceSensorConfig;
import android.os.Handler;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.face.aidl.FaceEnrollClient;
import com.android.server.biometrics.sensors.face.aidl.FaceProvider;
import com.android.server.biometrics.sensors.face.aidl.FaceResetLockoutClient;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class HidlToAidlSensorAdapterTest {
    private static final String TAG = "HidlToAidlSensorAdapterTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;
    private static final byte[] HAT = new byte[69];
    @Rule
    public final MockitoRule mMockito = MockitoJUnit.rule();

    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcherForSensor;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcherForClient;
    @Mock
    private BiometricLogger mLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private FaceProvider mFaceProvider;
    @Mock
    private Runnable mInternalCleanupAndGetFeatureRunnable;
    @Mock
    private IBiometricsFace mDaemon;
    @Mock
    private AidlResponseHandler.AidlResponseHandlerCallback mAidlResponseHandlerCallback;
    @Mock
    private BiometricUtils<Face> mBiometricUtils;
    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;

    private final TestLooper mLooper = new TestLooper();
    private HidlToAidlSensorAdapter mHidlToAidlSensorAdapter;
    private final TestableContext mContext = new TestableContext(
            ApplicationProvider.getApplicationContext());

    @Before
    public void setUp() throws RemoteException {
        final OptionalUint64 result = new OptionalUint64();
        result.status = Status.OK;

        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        when(mDaemon.setCallback(any())).thenReturn(result);
        doAnswer((answer) -> {
            mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback()
                    .onLockoutChanged(0);
            return null;
        }).when(mDaemon).resetLockout(any());
        doAnswer((answer) -> {
            mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback()
                    .onEnrollmentProgress(1, 0);
            return null;
        }).when(mDaemon).enroll(any(), anyInt(), any());

        mContext.getOrCreateTestableResources();

        final String config = String.format("%d:8:15", SENSOR_ID);
        final HidlFaceSensorConfig faceSensorConfig = new HidlFaceSensorConfig();
        faceSensorConfig.parse(config, mContext);
        mHidlToAidlSensorAdapter = new HidlToAidlSensorAdapter(mFaceProvider,
                mContext, new Handler(mLooper.getLooper()), faceSensorConfig,
                mLockoutResetDispatcherForSensor, mBiometricContext,
                false /* resetLockoutRequiresChallenge */, mInternalCleanupAndGetFeatureRunnable,
                mAuthSessionCoordinator, mDaemon, mAidlResponseHandlerCallback);
        mHidlToAidlSensorAdapter.init(mLockoutResetDispatcherForSensor, mFaceProvider);
        mHidlToAidlSensorAdapter.handleUserChanged(USER_ID);
    }

    @Test
    public void lockoutTimedResetViaClient() {
        setLockoutTimed();

        mHidlToAidlSensorAdapter.getScheduler().scheduleClientMonitor(
                new FaceResetLockoutClient(mContext, mHidlToAidlSensorAdapter.getLazySession(),
                        USER_ID, TAG, SENSOR_ID, mLogger, mBiometricContext, HAT,
                        mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */),
                        mLockoutResetDispatcherForClient, 0 /* biometricStrength */));
        mLooper.dispatchAll();

        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false/* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mLockoutResetDispatcherForClient, never()).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mLockoutResetDispatcherForSensor).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mAuthSessionCoordinator, never()).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void lockoutTimedResetViaCallback() {
        setLockoutTimed();

        mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback().onLockoutChanged(0);
        mLooper.dispatchAll();

        verify(mLockoutResetDispatcherForSensor).notifyLockoutResetCallbacks(eq(
                SENSOR_ID));
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID))
                .isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator, never()).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void lockoutPermanentResetViaCallback() {
        setLockoutPermanent();

        mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback().onLockoutChanged(0);
        mLooper.dispatchAll();

        verify(mLockoutResetDispatcherForSensor).notifyLockoutResetCallbacks(eq(
                SENSOR_ID));
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator, never()).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void lockoutPermanentResetViaClient() {
        setLockoutPermanent();

        mHidlToAidlSensorAdapter.getScheduler().scheduleClientMonitor(
                new FaceResetLockoutClient(mContext,
                        mHidlToAidlSensorAdapter.getLazySession(),
                        USER_ID, TAG, SENSOR_ID, mLogger, mBiometricContext, HAT,
                        mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */),
                        mLockoutResetDispatcherForClient, 0 /* biometricStrength */));
        mLooper.dispatchAll();

        verify(mLockoutResetDispatcherForClient, never()).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mLockoutResetDispatcherForSensor).notifyLockoutResetCallbacks(SENSOR_ID);
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator, never()).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void verifyOnEnrollSuccessCallback() {
        mHidlToAidlSensorAdapter.getScheduler().scheduleClientMonitor(new FaceEnrollClient(mContext,
                mHidlToAidlSensorAdapter.getLazySession(), null /* token */, null /* listener */,
                USER_ID, HAT, TAG, 1 /* requestId */, mBiometricUtils,
                new int[]{} /* disabledFeatures */, ENROLL_TIMEOUT_SEC, null /* previewSurface */,
                SENSOR_ID, mLogger, mBiometricContext, 1 /* maxTemplatesPerUser */,
                false /* debugConsent */, (new FaceEnrollOptions.Builder()).build(),
                mAuthenticationStateListeners, mBiometricUtils));
        mLooper.dispatchAll();

        verify(mAidlResponseHandlerCallback).onEnrollSuccess();
    }

    private void setLockoutTimed() {
        mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback()
                .onLockoutChanged(1);
        mLooper.dispatchAll();

        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID))
                .isEqualTo(LockoutTracker.LOCKOUT_TIMED);
    }

    private void setLockoutPermanent() {
        mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback()
                .onLockoutChanged(-1);
        mLooper.dispatchAll();

        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_PERMANENT);
    }
}
