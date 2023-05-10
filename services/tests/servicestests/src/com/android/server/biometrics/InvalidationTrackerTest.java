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

package com.android.server.biometrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IInvalidationCallback;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.BiometricService.InvalidationTracker;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@Presubmit
@SmallTest
public class InvalidationTrackerTest {

    @Mock
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCallbackReceived_whenAllStrongSensorsInvalidated() throws Exception {
        final IBiometricAuthenticator authenticator1 = mock(IBiometricAuthenticator.class);
        when(authenticator1.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        final TestSensor sensor1 = new TestSensor(mContext, 0 /* id */,
                BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                authenticator1);

        final IBiometricAuthenticator authenticator2 = mock(IBiometricAuthenticator.class);
        when(authenticator2.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        final TestSensor sensor2 = new TestSensor(mContext, 1 /* id */,
                BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                authenticator2);

        final IBiometricAuthenticator authenticator3 = mock(IBiometricAuthenticator.class);
        when(authenticator3.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        final TestSensor sensor3 = new TestSensor(mContext, 2 /* id */,
                BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG,
                authenticator3);

        final IBiometricAuthenticator authenticator4 = mock(IBiometricAuthenticator.class);
        when(authenticator4.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        final TestSensor sensor4 = new TestSensor(mContext, 3 /* id */,
                BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_WEAK,
                authenticator4);

        final ArrayList<BiometricSensor> sensors = new ArrayList<>();
        sensors.add(sensor1);
        sensors.add(sensor2);
        sensors.add(sensor3);
        sensors.add(sensor4);

        final IInvalidationCallback callback = mock(IInvalidationCallback.class);
        final InvalidationTracker tracker =
                InvalidationTracker.start(mock(Context.class), sensors, 0 /* userId */,
                        0 /* fromSensorId */, callback);

        // The sensor which the request originated from should not be requested to invalidate
        // its authenticatorId.
        verify(authenticator1, never()).invalidateAuthenticatorId(anyInt(), any());

        // All other strong sensors should be requested to invalidate authenticatorId
        verify(authenticator2).invalidateAuthenticatorId(eq(0) /* userId */, any());
        verify(authenticator3).invalidateAuthenticatorId(eq(0) /* userId */, any());

        // Weak sensors are not requested to invalidate authenticatorId
        verify(authenticator4, never()).invalidateAuthenticatorId(anyInt(), any());

        // Client is not notified until invalidation for all required sensors have completed
        verify(callback, never()).onCompleted();
        tracker.onInvalidated(1);
        verify(callback, never()).onCompleted();
        tracker.onInvalidated(2);
        verify(callback).onCompleted();
    }

    private static class TestSensor extends BiometricSensor {

        TestSensor(@NonNull Context context, int id, int modality, int strength,
                @NonNull IBiometricAuthenticator impl) {
            super(context, id, modality, strength, impl);
        }

        @Override
        boolean confirmationAlwaysRequired(int userId) {
            return false;
        }

        @Override
        boolean confirmationSupported() {
            return false;
        }
    }
}
