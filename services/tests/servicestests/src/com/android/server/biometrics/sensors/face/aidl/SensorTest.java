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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.face.ISession;
import android.os.Handler;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.BiometricScheduler;
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

    private final TestLooper mLooper = new TestLooper();
    private final LockoutCache mLockoutCache = new LockoutCache();

    private UserAwareBiometricScheduler mScheduler;
    private Sensor.HalSessionCallback mHalCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.BIOMETRIC_SERVICE)).thenReturn(mBiometricService);

        mScheduler = new UserAwareBiometricScheduler(TAG,
                BiometricScheduler.SENSOR_TYPE_FACE,
                null /* gestureAvailabilityDispatcher */,
                () -> USER_ID,
                mUserSwitchCallback);
        mHalCallback = new Sensor.HalSessionCallback(mContext, new Handler(mLooper.getLooper()),
                TAG, mScheduler, SENSOR_ID,
                USER_ID, mLockoutCache, mLockoutResetDispatcher, mHalSessionCallback);
    }

    @Test
    public void halSessionCallback_respondsToResetLockout() throws Exception {
        doAnswer((Answer<Void>) invocationOnMock -> {
            mHalCallback.onLockoutCleared();
            return null;
        }).when(mSession).resetLockout(any());
        mLockoutCache.setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_TIMED);

        mScheduler.scheduleClientMonitor(new FaceResetLockoutClient(mContext,
                () -> mSession, USER_ID, TAG, SENSOR_ID, HAT, mLockoutCache,
                mLockoutResetDispatcher));
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

    private void verifyNotLocked() {
        assertEquals(LockoutTracker.LOCKOUT_NONE, mLockoutCache.getLockoutModeForUser(USER_ID));
        verify(mLockoutResetDispatcher).notifyLockoutResetCallbacks(eq(SENSOR_ID));
    }
}
