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

package com.android.server.biometrics.sensors;

import static com.android.server.biometrics.sensors.BiometricScheduler.SENSOR_TYPE_FACE;
import static com.android.server.biometrics.sensors.BiometricScheduler.SENSOR_TYPE_UDFPS;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.fingerprint.Udfps;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class CoexCoordinatorTest {

    private static final String TAG = "CoexCoordinatorTest";

    private CoexCoordinator mCoexCoordinator;

    @Mock
    private Context mContext;
    @Mock
    private CoexCoordinator.Callback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCoexCoordinator = CoexCoordinator.getInstance();
        mCoexCoordinator.setAdvancedLogicEnabled(true);
    }

    @Test
    public void testBiometricPrompt_authSuccess() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationSucceeded(client, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
    }

    @Test
    public void testBiometricPrompt_authReject_whenNotLockedOut() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationRejected(client, LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
    }

    @Test
    public void testBiometricPrompt_authReject_whenLockedOut() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationRejected(client, LockoutTracker.LOCKOUT_TIMED, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
    }

    @Test
    public void testKeyguard_faceAuthOnly_success() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isKeyguard()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationSucceeded(client, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
    }

    @Test
    public void testKeyguard_faceAuth_udfpsNotTouching_faceSuccess() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(true);

        AuthenticationClient<?> udfpsClient = mock(AuthenticationClient.class,
                withSettings().extraInterfaces(Udfps.class));
        when(udfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) udfpsClient).isPointerDown()).thenReturn(false);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, udfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(faceClient, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
    }

    @Test
    public void testKeyguard_faceAuth_udfpsTouching_faceSuccess() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(true);

        AuthenticationClient<?> udfpsClient = mock(AuthenticationClient.class,
                withSettings().extraInterfaces(Udfps.class));
        when(udfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) udfpsClient).isPointerDown()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, udfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(faceClient, mCallback);
        verify(mCallback, never()).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
    }
}
