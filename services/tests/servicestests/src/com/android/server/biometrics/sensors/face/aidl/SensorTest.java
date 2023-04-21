/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.SensorProps;
import android.os.Handler;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.UserAwareBiometricScheduler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

@Presubmit
@SmallTest
public class SensorTest {

    private static final String TAG = "SensorTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;
    private static final byte[] HAT = new byte[69];

    @Mock
    private Context mContext;
    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private ISession mSession;
    @Mock
    private UserAwareBiometricScheduler.UserSwitchCallback mUserSwitchCallback;
    @Mock
    private Sensor.HalSessionCallback.Callback mHalSessionCallback;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcher;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private IFace mDaemon;
    @Mock
    private BiometricStateCallback mBiometricStateCallback;

    private final TestLooper mLooper = new TestLooper();
    private final LockoutCache mLockoutCache = new LockoutCache();

    private UserAwareBiometricScheduler mScheduler;
    private Sensor.HalSessionCallback mHalCallback;
    private FaceProvider mFaceProvider;
    private SensorProps[] mSensorProps;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.BIOMETRIC_SERVICE)).thenReturn(mBiometricService);

        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);

        mScheduler = new UserAwareBiometricScheduler(TAG,
                new Handler(mLooper.getLooper()),
                BiometricScheduler.SENSOR_TYPE_FACE,
                null /* gestureAvailabilityDispatcher */,
                mBiometricService,
                () -> USER_ID,
                mUserSwitchCallback);
        mHalCallback = new Sensor.HalSessionCallback(mContext, new Handler(mLooper.getLooper()),
                TAG, mScheduler, SENSOR_ID,
                USER_ID, mLockoutCache, mLockoutResetDispatcher, mAuthSessionCoordinator,
                mHalSessionCallback);

        final SensorProps sensor1 = new SensorProps();
        sensor1.commonProps = new CommonProps();
        sensor1.commonProps.sensorId = 0;
        final SensorProps sensor2 = new SensorProps();
        sensor2.commonProps = new CommonProps();
        sensor2.commonProps.sensorId = 1;
        mSensorProps = new SensorProps[]{sensor1, sensor2};
        mFaceProvider = new FaceProvider(mContext, mBiometricStateCallback,
                mSensorProps, TAG, mLockoutResetDispatcher, mBiometricContext);
    }

    @Test
    public void halSessionCallback_respondsToResetLockout() throws Exception {
        doAnswer((Answer<Void>) invocationOnMock -> {
            mHalCallback.onLockoutCleared();
            return null;
        }).when(mSession).resetLockout(any());
        mLockoutCache.setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_TIMED);

        mScheduler.scheduleClientMonitor(new FaceResetLockoutClient(mContext,
                () -> new AidlSession(1, mSession, USER_ID, mHalCallback),
                USER_ID, TAG, SENSOR_ID, mBiometricLogger, mBiometricContext,
                HAT, mLockoutCache, mLockoutResetDispatcher, 0 /* biometricStrength */));
        mLooper.dispatchAll();

        verifyNotLocked();
    }

    @Test
    public void halSessionCallback_respondsToUnprovokedResetLockout() {
        mLockoutCache.setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_TIMED);

        mHalCallback.onLockoutCleared();
        mLooper.dispatchAll();

        verifyNotLocked();
    }

    @Test
    public void onBinderDied_noErrorOnNullClient() {
        mScheduler.reset();
        assertNull(mScheduler.getCurrentClient());
        mFaceProvider.binderDied();

        for (int i = 0; i < mFaceProvider.mSensors.size(); i++) {
            final Sensor sensor = mFaceProvider.mSensors.valueAt(i);
            assertNull(sensor.getSessionForUser(USER_ID));
        }
    }

    private void verifyNotLocked() {
        assertEquals(LockoutTracker.LOCKOUT_NONE, mLockoutCache.getLockoutModeForUser(USER_ID));
        verify(mLockoutResetDispatcher).notifyLockoutResetCallbacks(eq(SENSOR_ID));
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }
}
