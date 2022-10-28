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
import static com.android.server.biometrics.sensors.BiometricScheduler.SENSOR_TYPE_FP_OTHER;
import static com.android.server.biometrics.sensors.BiometricScheduler.SENSOR_TYPE_UDFPS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricConstants;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.fingerprint.Udfps;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.LinkedList;

@Presubmit
@SmallTest
public class CoexCoordinatorTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private CoexCoordinator.Callback mCallback;
    @Mock
    private CoexCoordinator.ErrorCallback mErrorCallback;
    @Mock
    private AuthenticationClient mFaceClient;
    @Mock
    private AuthenticationClient mFingerprintClient;
    @Mock(extraInterfaces = {Udfps.class})
    private AuthenticationClient mUdfpsClient;

    private CoexCoordinator mCoexCoordinator;

    @Before
    public void setUp() {
        mCoexCoordinator = CoexCoordinator.getInstance();
        mCoexCoordinator.setAdvancedLogicEnabled(true);
        mCoexCoordinator.setFaceHapticDisabledWhenNonBypass(true);
        mCoexCoordinator.reset();
    }

    @Test
    public void testBiometricPrompt_authSuccess() {
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */,
                mFaceClient, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testBiometricPrompt_authReject_whenNotLockedOut() {
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                mFaceClient, LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testBiometricPrompt_authReject_whenLockedOut() {
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                mFaceClient, LockoutTracker.LOCKOUT_TIMED, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback, never()).sendAuthenticationResult(anyBoolean());
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testBiometricPrompt_coex_success() {
        testBiometricPrompt_coex_success(false /* twice */);
    }

    @Test
    public void testBiometricPrompt_coex_successWithoutDouble() {
        testBiometricPrompt_coex_success(true /* twice */);
    }

    private void testBiometricPrompt_coex_success(boolean twice) {
        initFaceAndFingerprintForBiometricPrompt();
        when(mFaceClient.wasAuthSuccessful()).thenReturn(true);
        when(mUdfpsClient.wasAuthSuccessful()).thenReturn(twice, true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */,
                mFaceClient, mCallback);
        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */,
                mUdfpsClient, mCallback);

        if (twice) {
            verify(mCallback, never()).sendHapticFeedback();
        } else {
            verify(mCallback).sendHapticFeedback();
        }
    }

    @Test
    public void testBiometricPrompt_coex_reject() {
        initFaceAndFingerprintForBiometricPrompt();

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                mFaceClient, LockoutTracker.LOCKOUT_NONE, mCallback);

        verify(mCallback, never()).sendHapticFeedback();

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                    mUdfpsClient, LockoutTracker.LOCKOUT_NONE, mCallback);

        verify(mCallback).sendHapticFeedback();
    }

    @Test
    public void testBiometricPrompt_coex_errorNoHaptics() {
        initFaceAndFingerprintForBiometricPrompt();

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationError(mFaceClient,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT, mErrorCallback);
        mCoexCoordinator.onAuthenticationError(mUdfpsClient,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT, mErrorCallback);

        verify(mErrorCallback, never()).sendHapticFeedback();
    }

    private void initFaceAndFingerprintForBiometricPrompt() {
        when(mFaceClient.isKeyguard()).thenReturn(false);
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);
        when(mFaceClient.wasAuthAttempted()).thenReturn(true);
        when(mUdfpsClient.isKeyguard()).thenReturn(false);
        when(mUdfpsClient.isBiometricPrompt()).thenReturn(true);
        when(mUdfpsClient.wasAuthAttempted()).thenReturn(true);
    }

    @Test
    public void testKeyguard_faceAuthOnly_success() {
        when(mFaceClient.isKeyguard()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */,
                mFaceClient, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_faceAuth_udfpsNotTouching_faceSuccess() {
        when(mFaceClient.isKeyguard()).thenReturn(true);

        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(false);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */,
                mFaceClient, mCallback);
        // Haptics tested in #testKeyguard_bypass_haptics. Let's leave this commented out (instead
        // of removed) to keep this context.
        // verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_faceAuthSuccess_nonBypass_udfpsRunning_noHaptics() {
        testKeyguard_bypass_haptics(false /* bypassEnabled */,
                true /* faceAccepted */,
                false /* shouldReceiveHaptics */);
    }

    @Test
    public void testKeyguard_faceAuthReject_nonBypass_udfpsRunning_noHaptics() {
        testKeyguard_bypass_haptics(false /* bypassEnabled */,
                false /* faceAccepted */,
                false /* shouldReceiveHaptics */);
    }

    @Test
    public void testKeyguard_faceAuthSuccess_bypass_udfpsRunning_haptics() {
        testKeyguard_bypass_haptics(true /* bypassEnabled */,
                true /* faceAccepted */,
                true /* shouldReceiveHaptics */);
    }

    @Test
    public void testKeyguard_faceAuthReject_bypass_udfpsRunning_haptics() {
        testKeyguard_bypass_haptics(true /* bypassEnabled */,
                false /* faceAccepted */,
                true /* shouldReceiveHaptics */);
    }

    private void testKeyguard_bypass_haptics(boolean bypassEnabled, boolean faceAccepted,
            boolean shouldReceiveHaptics) {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.isKeyguardBypassEnabled()).thenReturn(bypassEnabled);
        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(false);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        if (faceAccepted) {
            mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, mFaceClient,
                    mCallback);
        } else {
            mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, mFaceClient,
                    LockoutTracker.LOCKOUT_NONE, mCallback);
        }

        if (shouldReceiveHaptics) {
            verify(mCallback).sendHapticFeedback();
        } else {
            verify(mCallback, never()).sendHapticFeedback();
        }

        verify(mCallback).sendAuthenticationResult(eq(faceAccepted) /* addAuthTokenIfStrong */);
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
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(true);
        when(mUdfpsClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        // For easier reading
        final CoexCoordinator.Callback faceCallback = mCallback;

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, mFaceClient,
                faceCallback);
        verify(faceCallback, never()).sendHapticFeedback();
        verify(faceCallback, never()).sendAuthenticationResult(anyBoolean());
        // CoexCoordinator requests the system to hold onto this AuthenticationClient until
        // UDFPS result is known
        verify(faceCallback, never()).handleLifecycleAfterAuth();

        // Reset the mock
        CoexCoordinator.Callback udfpsCallback = mock(CoexCoordinator.Callback.class);
        assertEquals(1, mCoexCoordinator.mSuccessfulAuths.size());
        assertEquals(mFaceClient, mCoexCoordinator.mSuccessfulAuths.get(0).mAuthenticationClient);
        if (thenUdfpsAccepted) {
            mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, mUdfpsClient,
                    udfpsCallback);
            verify(udfpsCallback).sendHapticFeedback();
            verify(udfpsCallback).sendAuthenticationResult(true /* addAuthTokenIfStrong */);
            verify(udfpsCallback).handleLifecycleAfterAuth();

            verify(faceCallback).sendAuthenticationCanceled();

            assertTrue(mCoexCoordinator.mSuccessfulAuths.isEmpty());
        } else {
            mCoexCoordinator.onAuthenticationRejected(udfpsRejectedAfterMs, mUdfpsClient,
                    LockoutTracker.LOCKOUT_NONE, udfpsCallback);
            if (udfpsRejectedAfterMs <= CoexCoordinator.SUCCESSFUL_AUTH_VALID_DURATION_MS) {
                verify(udfpsCallback, never()).sendHapticFeedback();

                verify(faceCallback).sendHapticFeedback();
                verify(faceCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
                verify(faceCallback).handleLifecycleAfterAuth();

                assertTrue(mCoexCoordinator.mSuccessfulAuths.isEmpty());
            } else {
                assertTrue(mCoexCoordinator.mSuccessfulAuths.isEmpty());

                verify(faceCallback, never()).sendHapticFeedback();
                verify(faceCallback, never()).sendAuthenticationResult(anyBoolean());

                verify(udfpsCallback).sendHapticFeedback();
                verify(udfpsCallback)
                        .sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
                verify(udfpsCallback).handleLifecycleAfterAuth();
            }
        }
    }

    @Test
    public void testKeyguard_udfpsAuthSuccess_whileFaceScanning() {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */, mUdfpsClient,
                mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true));
        verify(mFaceClient).cancel();
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_faceRejectedWhenUdfpsTouching_thenUdfpsRejected() {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mUdfpsClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, mFaceClient,
                LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback, never()).sendHapticFeedback();
        verify(mCallback).handleLifecycleAfterAuth();

        // BiometricScheduler removes the face authentication client after rejection
        mCoexCoordinator.removeAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        // Then UDFPS rejected
        CoexCoordinator.Callback udfpsCallback = mock(CoexCoordinator.Callback.class);
        mCoexCoordinator.onAuthenticationRejected(1 /* currentTimeMillis */, mUdfpsClient,
                LockoutTracker.LOCKOUT_NONE, udfpsCallback);
        verify(udfpsCallback).sendHapticFeedback();
        verify(udfpsCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback, never()).sendHapticFeedback();
    }

    @Test
    public void testKeyguard_udfpsRejected_thenFaceRejected_noKeyguardBypass() {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mFaceClient.isKeyguardBypassEnabled()).thenReturn(false); // TODO: also test "true" case
        when(mUdfpsClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                mUdfpsClient, LockoutTracker.LOCKOUT_NONE, mCallback);
        // Auth was attempted
        when(mUdfpsClient.getState())
                .thenReturn(AuthenticationClient.STATE_STARTED_PAUSED_ATTEMPTED);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).handleLifecycleAfterAuth();

        // Then face rejected. Note that scheduler leaves UDFPS in the CoexCoordinator since
        // unlike face, its lifecycle becomes "paused" instead of "finished".
        CoexCoordinator.Callback faceCallback = mock(CoexCoordinator.Callback.class);
        mCoexCoordinator.onAuthenticationRejected(1 /* currentTimeMillis */, mFaceClient,
                LockoutTracker.LOCKOUT_NONE, faceCallback);
        verify(faceCallback).sendHapticFeedback();
        verify(faceCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback).sendHapticFeedback();
    }

    @Test
    public void testKeyguard_capacitiveAccepted_whenFaceScanning() {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mFingerprintClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mFingerprintClient.isKeyguard()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FP_OTHER, mFingerprintClient);

        mCoexCoordinator.onAuthenticationSucceeded(0 /* currentTimeMillis */,
                mFingerprintClient, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(true) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testKeyguard_capacitiveRejected_whenFaceScanning() {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mFingerprintClient.getState()).thenReturn(AuthenticationClient.STATE_STARTED);
        when(mFingerprintClient.isKeyguard()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FP_OTHER, mFingerprintClient);

        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */,
                mFingerprintClient, LockoutTracker.LOCKOUT_NONE, mCallback);
        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false) /* addAuthTokenIfStrong */);
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testNonKeyguard_rejectAndNotLockedOut() {
        when(mFaceClient.isKeyguard()).thenReturn(false);
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, mFaceClient,
                LockoutTracker.LOCKOUT_NONE, mCallback);

        verify(mCallback).sendHapticFeedback();
        verify(mCallback).sendAuthenticationResult(eq(false));
        verify(mCallback).handleLifecycleAfterAuth();
    }

    @Test
    public void testNonKeyguard_rejectLockedOut() {
        when(mFaceClient.isKeyguard()).thenReturn(false);
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.onAuthenticationRejected(0 /* currentTimeMillis */, mFaceClient,
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

    @Test
    public void testBiometricPrompt_FaceError() {
        when(mFaceClient.isBiometricPrompt()).thenReturn(true);
        when(mFaceClient.wasAuthAttempted()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        mCoexCoordinator.onAuthenticationError(mFaceClient,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT, mErrorCallback);
        verify(mErrorCallback).sendHapticFeedback();
    }

    @Test
    public void testKeyguard_faceAuthOnly_errorWhenBypassEnabled() {
        testKeyguard_faceAuthOnly(true /* bypassEnabled */);
    }

    @Test
    public void testKeyguard_faceAuthOnly_errorWhenBypassDisabled() {
        testKeyguard_faceAuthOnly(false /* bypassEnabled */);
    }

    private void testKeyguard_faceAuthOnly(boolean bypassEnabled) {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.isKeyguardBypassEnabled()).thenReturn(bypassEnabled);
        when(mFaceClient.wasAuthAttempted()).thenReturn(true);
        when(mFaceClient.wasUserDetected()).thenReturn(true);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);

        mCoexCoordinator.onAuthenticationError(mFaceClient,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT, mErrorCallback);
        verify(mErrorCallback).sendHapticFeedback();
    }

    @Test
    public void testKeyguard_coex_faceErrorWhenBypassEnabled() {
        testKeyguard_coex_faceError(true /* bypassEnabled */);
    }

    @Test
    public void testKeyguard_coex_faceErrorWhenBypassDisabled() {
        testKeyguard_coex_faceError(false /* bypassEnabled */);
    }

    private void testKeyguard_coex_faceError(boolean bypassEnabled) {
        when(mFaceClient.isKeyguard()).thenReturn(true);
        when(mFaceClient.isKeyguardBypassEnabled()).thenReturn(bypassEnabled);
        when(mFaceClient.wasAuthAttempted()).thenReturn(true);
        when(mFaceClient.wasUserDetected()).thenReturn(true);
        when(mUdfpsClient.isKeyguard()).thenReturn(true);
        when(((Udfps) mUdfpsClient).isPointerDown()).thenReturn(false);

        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_FACE, mFaceClient);
        mCoexCoordinator.addAuthenticationClient(SENSOR_TYPE_UDFPS, mUdfpsClient);

        mCoexCoordinator.onAuthenticationError(mFaceClient,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT, mErrorCallback);

        if (bypassEnabled) {
            verify(mErrorCallback).sendHapticFeedback();
        } else {
            verify(mErrorCallback, never()).sendHapticFeedback();
        }
    }
}
