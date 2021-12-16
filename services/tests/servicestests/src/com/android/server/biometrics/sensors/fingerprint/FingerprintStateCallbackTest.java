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

package com.android.server.biometrics.sensors.fingerprint;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.fingerprint.FingerprintStateListener;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.EnrollClient;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class FingerprintStateCallbackTest {

    private FingerprintStateCallback mCallback;

    @Mock
    FingerprintStateListener mFingerprintStateListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mCallback = new FingerprintStateCallback();
        mCallback.registerFingerprintStateListener(mFingerprintStateListener);
    }

    @Test
    public void testNoEnrollmentsToEnrollments_callbackNotified() {
        testEnrollmentCallback(true /* changed */, true /* isNowEnrolled */,
                true /* expectCallback */, true /* expectedCallbackValue */);
    }

    @Test
    public void testEnrollmentsToNoEnrollments_callbackNotified() {
        testEnrollmentCallback(true /* changed */, false /* isNowEnrolled */,
                true /* expectCallback */, false /* expectedCallbackValue */);
    }

    @Test
    public void testEnrollmentsToEnrollments_callbackNotNotified() {
        testEnrollmentCallback(false /* changed */, true /* isNowEnrolled */,
                false /* expectCallback */, false /* expectedCallbackValue */);
    }

    private void testEnrollmentCallback(boolean changed, boolean isNowEnrolled,
            boolean expectCallback, boolean expectedCallbackValue) {
        EnrollClient<?> client = mock(EnrollClient.class);

        final int userId = 10;
        final int sensorId = 100;

        when(client.hasEnrollmentStateChanged()).thenReturn(changed);
        when(client.hasEnrollments()).thenReturn(isNowEnrolled);
        when(client.getTargetUserId()).thenReturn(userId);
        when(client.getSensorId()).thenReturn(sensorId);

        mCallback.onClientFinished(client, true /* success */);
        if (expectCallback) {
            verify(mFingerprintStateListener).onEnrollmentsChanged(eq(userId), eq(sensorId),
                    eq(expectedCallbackValue));
        } else {
            verify(mFingerprintStateListener, never()).onEnrollmentsChanged(anyInt(), anyInt(),
                    anyBoolean());
        }
    }

    @Test
    public void testAuthentication_enrollmentCallbackNeverNotified() {
        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        mCallback.onClientFinished(client, true /* success */);
        verify(mFingerprintStateListener, never()).onEnrollmentsChanged(anyInt(), anyInt(),
                anyBoolean());
    }
}
