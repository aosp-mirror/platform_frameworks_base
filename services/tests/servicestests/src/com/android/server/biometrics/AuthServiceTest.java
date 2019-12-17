/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_SUCCESS;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.os.Binder;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class AuthServiceTest {

    private static final String TAG = "AuthServiceTest";
    private static final String TEST_OP_PACKAGE_NAME = "test_package";

    private AuthService mAuthService;

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    IBiometricServiceReceiver mReceiver;
    @Mock
    AuthService.Injector mInjector;
    @Mock
    IBiometricService mBiometricService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Dummy test config
        final String[] config = {
                "0:2:15", // ID0:Fingerprint:Strong
                "1:4:15", // ID1:Iris:Strong
                "2:8:15", // ID2:Face:Strong
        };

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mInjector.getBiometricService()).thenReturn(mBiometricService);
        when(mInjector.getConfiguration(any())).thenReturn(config);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_IRIS)).thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
    }


    // TODO(b/141025588): Check that an exception is thrown when the userId != callingUserId
    @Test
    public void testAuthenticate_callsBiometricServiceAuthenticate() throws Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final Binder token = new Binder();
        final Bundle bundle = new Bundle();
        final long sessionId = 0;
        final int userId = 0;

        mAuthService.mImpl.authenticate(
                token,
                sessionId,
                userId,
                mReceiver,
                TEST_OP_PACKAGE_NAME,
                bundle);
        waitForIdle();
        verify(mBiometricService).authenticate(
                eq(token),
                eq(sessionId),
                eq(userId),
                eq(mReceiver),
                eq(TEST_OP_PACKAGE_NAME),
                eq(bundle));
    }

    @Test
    public void testCanAuthenticate_callsBiometricServiceCanAuthenticate() throws Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final int userId = 0;
        final int expectedResult = BIOMETRIC_SUCCESS;
        final int authenticators = 0;
        when(mBiometricService.canAuthenticate(anyString(), anyInt(), anyInt()))
                .thenReturn(expectedResult);

        final int result = mAuthService.mImpl
                .canAuthenticate(TEST_OP_PACKAGE_NAME, userId, authenticators);

        assertEquals(expectedResult, result);
        waitForIdle();
        verify(mBiometricService).canAuthenticate(
                eq(TEST_OP_PACKAGE_NAME),
                eq(userId),
                eq(authenticators));
    }


    @Test
    public void testHasEnrolledBiometrics_callsBiometricServiceHasEnrolledBiometrics() throws
            Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final int userId = 0;
        final boolean expectedResult = true;
        when(mBiometricService.hasEnrolledBiometrics(anyInt(), anyString())).thenReturn(
                expectedResult);

        final boolean result = mAuthService.mImpl.hasEnrolledBiometrics(userId,
                TEST_OP_PACKAGE_NAME);

        assertEquals(expectedResult, result);
        waitForIdle();
        verify(mBiometricService).hasEnrolledBiometrics(
                eq(userId),
                eq(TEST_OP_PACKAGE_NAME));
    }


    @Test
    public void testRegisterKeyguardCallback_callsBiometricServiceRegisterKeyguardCallback()
            throws Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final IBiometricEnabledOnKeyguardCallback callback =
                new IBiometricEnabledOnKeyguardCallback.Default();

        mAuthService.mImpl.registerEnabledOnKeyguardCallback(callback);

        waitForIdle();
        verify(mBiometricService).registerEnabledOnKeyguardCallback(eq(callback));
    }

    @Test
    public void testSetActiveUser_callsBiometricServiceSetActiveUser() throws
            Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final int userId = 0;

        mAuthService.mImpl.setActiveUser(userId);

        waitForIdle();
        verify(mBiometricService).setActiveUser(eq(userId));
    }

    @Test
    public void testResetLockout_callsBiometricServiceResetLockout() throws
            Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final byte[] token = new byte[0];

        mAuthService.mImpl.resetLockout(token);

        waitForIdle();
        verify(mBiometricService).resetLockout(token);
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
