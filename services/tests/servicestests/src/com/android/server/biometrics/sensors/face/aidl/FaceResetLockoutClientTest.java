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

package com.android.server.biometrics.sensors.face.aidl;

import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.face.ISession;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class FaceResetLockoutClientTest {
    private static final String TAG = "FaceResetLockoutClientTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AidlSession mAidlSession;
    @Mock
    private ISession mSession;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    Context mContext;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    private final byte[] mHardwareAuthToken = new byte[69];
    @Mock
    private LockoutCache mLockoutTracker;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcher;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;

    private FaceResetLockoutClient mClient;

    @Before
    public void setUp() {
        mClient = new FaceResetLockoutClient(mContext, () -> mAidlSession, USER_ID, TAG, SENSOR_ID,
                mBiometricLogger, mBiometricContext, mHardwareAuthToken, mLockoutTracker,
                mLockoutResetDispatcher, BIOMETRIC_STRONG);

        when(mAidlSession.getSession()).thenReturn(mSession);
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
    }

    @Test
    public void testResetLockout_onLockoutCleared() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onLockoutCleared();
            return null;
        }).when(mSession).resetLockout(any());
        mClient.start(mCallback);

        verify(mSession).resetLockout(any());
        verify(mAuthSessionCoordinator).resetLockoutFor(USER_ID, BIOMETRIC_STRONG, -1);
        verify(mLockoutTracker).setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_NONE);
        verify(mLockoutResetDispatcher).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mCallback).onClientFinished(mClient, true);
    }

    @Test
    public void testResetLockout_onError() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onError(0, 0);
            return null;
        }).when(mSession).resetLockout(any());
        mClient.start(mCallback);

        verify(mSession).resetLockout(any());
        verify(mAuthSessionCoordinator, never()).resetLockoutFor(USER_ID,
                BIOMETRIC_STRONG, -1);
        verify(mLockoutTracker, never()).setLockoutModeForUser(USER_ID,
                LockoutTracker.LOCKOUT_NONE);
        verify(mLockoutResetDispatcher, never()).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mCallback).onClientFinished(mClient, false);
    }
}
