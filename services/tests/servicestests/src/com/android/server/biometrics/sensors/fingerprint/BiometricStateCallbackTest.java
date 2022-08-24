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

import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricServiceProvider;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.EnrollClient;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

@Presubmit
@SmallTest
public class BiometricStateCallbackTest {

    private static final int USER_ID = 10;
    private static final int SENSOR_ID = 2;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private BiometricStateCallback<FakeProvider, SensorPropertiesInternal> mCallback;

    @Mock
    private UserManager mUserManager;
    @Mock
    private BiometricStateListener mBiometricStateListener;
    @Mock
    private FakeProvider mFakeProvider;

    private SensorPropertiesInternal mFakeProviderProps;

    @Before
    public void setup() {
        mFakeProviderProps = new SensorPropertiesInternal(SENSOR_ID, STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */, List.of(),
                false /* resetLockoutRequiresHardwareAuthToken */,
                false /* resetLockoutRequiresChallenge */);
        when(mFakeProvider.getSensorProperties()).thenReturn(List.of(mFakeProviderProps));
        when(mFakeProvider.getSensorProperties(eq(SENSOR_ID))).thenReturn(mFakeProviderProps);
        when(mFakeProvider.hasEnrollments(eq(SENSOR_ID), eq(USER_ID))).thenReturn(true);
        when(mUserManager.getAliveUsers()).thenReturn(
                List.of(new UserInfo(USER_ID, "name", 0)));
        when(mBiometricStateListener.asBinder()).thenReturn(mBiometricStateListener);


        mCallback = new BiometricStateCallback<>(mUserManager);
        mCallback.registerBiometricStateListener(mBiometricStateListener);
    }

    @Test
    public void startNotifiesEnrollments() {
        mCallback.start(List.of(mFakeProvider));

        verify(mBiometricStateListener).onEnrollmentsChanged(eq(USER_ID), eq(SENSOR_ID), eq(true));
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

    @Test
    public void testBinderDeath() {
        mCallback.binderDied(mBiometricStateListener.asBinder());

        testEnrollmentCallback(true /* changed */, false /* isNowEnrolled */,
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
            verify(mBiometricStateListener).onEnrollmentsChanged(eq(userId), eq(sensorId),
                    eq(expectedCallbackValue));
        } else {
            verify(mBiometricStateListener, never()).onEnrollmentsChanged(anyInt(), anyInt(),
                    anyBoolean());
        }
    }

    @Test
    public void testAuthentication_enrollmentCallbackNeverNotified() {
        AuthenticationClient<?> client = mock(AuthenticationClient.class);
        mCallback.onClientFinished(client, true /* success */);
        verify(mBiometricStateListener, never()).onEnrollmentsChanged(anyInt(), anyInt(),
                anyBoolean());
    }

    private interface FakeProvider extends BiometricServiceProvider<SensorPropertiesInternal> {}
}
