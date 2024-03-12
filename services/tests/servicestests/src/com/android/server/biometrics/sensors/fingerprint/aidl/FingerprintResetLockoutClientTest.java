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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.fingerprint.ISession;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
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
public class FingerprintResetLockoutClientTest {
    private static final String TAG = "FingerprintResetLockoutClientTest";
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
    private Context mContext;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private LockoutTracker mLockoutTracker;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcher;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;

    private FingerprintResetLockoutClient mClient;

    @Before
    public void setUp() {
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        when(mAidlSession.getSession()).thenReturn(mSession);

        mClient = new FingerprintResetLockoutClient(mContext, () -> mAidlSession, USER_ID, TAG,
                SENSOR_ID, mBiometricLogger, mBiometricContext, new byte[69],
                mLockoutTracker, mLockoutResetDispatcher,
                BiometricManager.Authenticators.BIOMETRIC_STRONG);
    }

    @Test
    public void resetLockout_onLockoutCleared() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onLockoutCleared();
            return null;
        }).when(mSession).resetLockout(any());
        mClient.start(mCallback);

        verify(mSession).resetLockout(any());
        verify(mLockoutTracker).setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_NONE);
        verify(mLockoutTracker).resetFailedAttemptsForUser(true, USER_ID);
        verify(mLockoutResetDispatcher).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID),
                eq(BiometricManager.Authenticators.BIOMETRIC_STRONG), anyLong());
    }
}
