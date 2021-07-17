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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class CoexCoordinatorTest {

    private CoexCoordinator mCoexCoordinator;

    @Mock
    private CoexCoordinator.Callback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCoexCoordinator = CoexCoordinator.getInstance();
    }

    @Test
    public void testBiometricPrompt_authSuccess() {
        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.onAuthenticationSucceeded(client, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
    }

    @Test
    public void testBiometricPrompt_authReject_whenNotLockedOut() {
        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.onAuthenticationRejected(client, LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
    }

    @Test
    public void testBiometricPrompt_authReject_whenLockedOut() {
        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.onAuthenticationRejected(client, LockoutTracker.LOCKOUT_TIMED, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
    }
}
