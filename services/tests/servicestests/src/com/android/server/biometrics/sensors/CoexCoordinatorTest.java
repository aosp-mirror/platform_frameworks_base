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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.fingerprint.Udfps;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.LinkedList;

@Presubmit
@SmallTest
public class CoexCoordinatorTest {

    private static final String TAG = "CoexCoordinatorTest";

    private CoexCoordinator mCoexCoordinator;
    private Handler mHandler;

    @Mock
    private Context mContext;
    @Mock
    private CoexCoordinator.Callback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mHandler = new Handler(Looper.getMainLooper());

        mCoexCoordinator = CoexCoordinator.getInstance();
        mCoexCoordinator.setAdvancedLogicEnabled(true);
    }

    @Test
    public void testBiometricPrompt_authSuccess() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, client, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testBiometricPrompt_authReject_whenNotLockedOut() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                client, LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testBiometricPrompt_authReject_whenLockedOut() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                client, LockoutTracker.LOCKOUT_TIMED, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_faceAuthOnly_success() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        when(client.isKeyguard()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, client);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, client, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
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

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, faceClient,
                mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_faceAuth_udfpsTouching_faceSuccess_thenUdfpsRejectedWithinBounds() {
        testKeyguard_faceAuth_udfpsTouching_faceSuccess(false /* thenUdfpsAccepted */,
                0 /* udfpsRejectedAfterMs */);
    }

    @Test
    public void testKeyguard_faceAuth_udfpsTouching_faceSuccess_thenUdfpsRejectedAfterBounds() {
        testKeyguard_faceAuth_udfpsTouching_faceSuccess(false /* thenUdfpsAccepted */,
                CoexCoordinator.SUCCESSFUL_AUTH_VALID_DURATION_MS + 1 /* udfpsRejectedAfterMs */);
    }

    @Test
    public void testKeyguard_faceAuth_udfpsTouching_faceSuccess_thenUdfpsAccepted() {
        testKeyguard_faceAuth_udfpsTouching_faceSuccess(true /* thenUdfpsAccepted */,
                0 /* udfpsRejectedAfterMs */);
    }

    private void testKeyguard_faceAuth_udfpsTouching_faceSuccess(boolean thenUdfpsAccepted,
            long udfpsRejectedAfterMs) {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(true);

        AuthenticationClient<?> udfpsClient = mock(AuthenticationClient.class,
                withSettings().extraInterfaces(Udfps.class));
        when(udfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) udfpsClient).isPointerDown()).thenReturn(true);
        when (udfpsClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, udfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, faceClient,
                mCallback);
        verify(mCallback, never()).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
        // CoexCoordinator requests the system to hold onto this AuthenticationClient until
        // UDFPS result is known
        verify(mCallback, never()).handleLifecycleAfterAuth();

        // Reset the mock
        CoexCoordinator.Callback udfpsCallback = mock(CoexCoordinator.Callback.class);
        assertEquals(1, mCoexCoordinator.mSuccessfulAuths.size());
        assertEquals(faceClient, mCoexCoordinator.mSuccessfulAuths.get(0).mAuthenticationClient);
        if (thenUdfpsAccepted) {
            mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, udfpsClient,
                    udfpsCallback);
            verify(udfpsCallback).sendHapticFeedback();
            verify(udfpsCallback).sendAuthenticationResult(true /* addAuthTokenIfStrong */);
            verify(udfpsCallback).handleLifecycleAfterAuth();

            assertTrue(mCoexCoordinator.mSuccessfulAuths.isEmpty());
        } else {
            mCoexCoordinator.onAuthenticationRejected(udfpsRejectedAfterMs, udfpsClient,
                    LockoutTracker.LOCKOUT_NONE, udfpsCallback);
            if (udfpsRejectedAfterMs <= CoexCoordinator.SUCCESSFUL_AUTH_VALID_DURATION_MS) {
                verify(udfpsCallback, never()).sendHapticFeedback();

                verify(mCallback).sendHapticFeedback();
                verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
                verify(mCallback).handleLifecycleAfterAuth();

                assertTrue(mCoexCoordinator.mSuccessfulAuths.isEmpty());
            } else {
                assertTrue(mCoexCoordinator.mSuccessfulAuths.isEmpty());

                verify(mCallback, never()).sendHapticFeedback();
                verify(mCallback, never()).sendAuthenticationResult(anyBoolean());

                verify(udfpsCallback).sendHapticFeedback();
                verify(udfpsCallback)
                        .sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
                verify(udfpsCallback).handleLifecycleAfterAuth();
            }
        }
    }

    @Test
    public void testKeyguard_udfpsAuthSuccess_whileFaceScanning() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(true);
        when(faceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);

        AuthenticationClient<?> udfpsClient = mock(AuthenticationClient.class,
                withSettings().extraInterfaces(Udfps.class));
        when(udfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) udfpsClient).isPointerDown()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, udfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, udfpsClient,
                mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true));
        verify(faceClient).cancel();
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_faceRejectedWhenUdfpsTouching_thenUdfpsRejected() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(true);
        when(faceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);

        AuthenticationClient<?> udfpsClient = mock(AuthenticationClient.class,
                withSettings().extraInterfaces(Udfps.class));
        when(udfpsClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(udfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) udfpsClient).isPointerDown()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, udfpsClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, faceClient,
                LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback, never()).sendHapticFeedback();
        verify(mCallback).handleLifecycleAfterAuth();

        // BiometricScheduler removes the face authentication client after rejection
        mCoexCoordinator.removeAuthenticationClient(SENSOR_TYPE_FACE, faceClient);

        // Then UDFPS rejected
        CoexCoordinator.Callback udfpsCallback = mock(CoexCoordinator.Callback.class);
        mCoexCoordinator.onAuthenticationRejected(1 /* currentTimeMillis */, udfpsClient,
                LockoutTracker.LOCKOUT_NONE, udfpsCallback);
        verify(udfpsCallback).sendHapticFeedback();
        verify(udfpsCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback, never()).sendHapticFeedback();
    }

    @Test
    public void testNonKeyguard_rejectAndNotLockedOut() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(false);
        when(faceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, faceClient,
                LockoutTracker.LOCKOUT_NONE, mCallback);

        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false));
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testNonKeyguard_rejectLockedOut() {
        mCoexCoordinator.reset();

        AuthenticationClient<?> faceClient = mock(AuthenticationClient.class);
        when(faceClient.isKeyguard()).thenReturn(false);
        when(faceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, faceClient);
        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, faceClient,
                LockoutTracker.LOCKOUT_TIMED, mCallback);

        verify(mCallback).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testCleanupRunnable() {
        LinkedList<CoexCoordinator.SuccessfulAuth> successfulAuths = mock(LinkedList.class);
        CoexCoordinator.SuccessfulAuth auth = mock(CoexCoordinator.SuccessfulAuth.class);
        CoexCoordinator.Callback callback = mock(CoexCoordinator.Callback.class);
        CoexCoordinator.SuccessfulAuth.CleanupRunnable runnable =
                new CoexCoordinator.SuccessfulAuth.CleanupRunnable(successfulAuths, auth, callback);
        runnable.run();

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        verify(callback).handleLifecycleAfterAuth();
        verify(successfulAuths).remove(eq(auth));
    }
}
