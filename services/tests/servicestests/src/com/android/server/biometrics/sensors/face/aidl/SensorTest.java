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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.os.Handler;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.UserSwitchProvider;

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
    private UserSwitchProvider<IFace, ISession> mUserSwitchProvider;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcher;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private FaceProvider mFaceProvider;
    @Mock
    private BaseClientMonitor mClientMonitor;
    @Mock
    private AidlSession mCurrentSession;
    @Mock
    private AidlResponseHandler.AidlResponseHandlerCallback mAidlResponseHandlerCallback;

    private final TestLooper mLooper = new TestLooper();
    private final LockoutCache mLockoutCache = new LockoutCache();

    private BiometricScheduler<IFace, ISession> mScheduler;
    private AidlResponseHandler mHalCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.BIOMETRIC_SERVICE)).thenReturn(mBiometricService);
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);

        mScheduler = new BiometricScheduler<>(
                new Handler(mLooper.getLooper()),
                BiometricScheduler.SENSOR_TYPE_FACE,
                null /* gestureAvailabilityDispatcher */,
                mBiometricService,
                2 /* recentOperationsLimit */,
                () -> USER_ID,
                mUserSwitchProvider);
        mHalCallback = new AidlResponseHandler(mContext, mScheduler, SENSOR_ID, USER_ID,
                mLockoutCache, mLockoutResetDispatcher, mAuthSessionCoordinator,
                mAidlResponseHandlerCallback);
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
        mLooper.dispatchAll();
        final Sensor sensor = getSensor();
        mScheduler = sensor.getScheduler();
        mScheduler.reset();

        assertNull(mScheduler.getCurrentClient());

        sensor.onBinderDied();

        assertNull(sensor.getSessionForUser(USER_ID));
    }

    @Test
    public void onBinderDied_cancelNonInterruptableClient() {
        mLooper.dispatchAll();

        when(mCurrentSession.getUserId()).thenReturn(USER_ID);
        when(mClientMonitor.getTargetUserId()).thenReturn(USER_ID);
        when(mClientMonitor.isInterruptable()).thenReturn(false);

        final Sensor sensor = getSensor();
        mScheduler = sensor.getScheduler();
        sensor.mCurrentSession = new AidlSession(0, mock(ISession.class),
                USER_ID, mHalCallback);

        mScheduler.scheduleClientMonitor(mClientMonitor);

        assertNotNull(mScheduler.getCurrentClient());

        sensor.onBinderDied();

        verify(mClientMonitor).cancel();
        assertNull(sensor.getSessionForUser(USER_ID));
        assertNull(mScheduler.getCurrentClient());
    }

    private void verifyNotLocked() {
        assertEquals(LockoutTracker.LOCKOUT_NONE, mLockoutCache.getLockoutModeForUser(USER_ID));
        verify(mLockoutResetDispatcher).notifyLockoutResetCallbacks(eq(SENSOR_ID));
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    private Sensor getSensor() {
        final SensorProps sensorProps = new SensorProps();
        sensorProps.commonProps = new CommonProps();
        sensorProps.commonProps.sensorId = 1;
        final FaceSensorPropertiesInternal internalProp = new FaceSensorPropertiesInternal(
                sensorProps.commonProps.sensorId, sensorProps.commonProps.sensorStrength,
                sensorProps.commonProps.maxEnrollmentsPerUser, null /* componentInfo */,
                sensorProps.sensorType, sensorProps.supportsDetectInteraction,
                sensorProps.halControlsPreview, false /* resetLockoutRequiresChallenge */);
        final Sensor sensor = new Sensor(mFaceProvider, mContext,
                null /* handler */, internalProp, mBiometricContext);
        sensor.init(mLockoutResetDispatcher, mFaceProvider);

        return sensor;
    }
}
