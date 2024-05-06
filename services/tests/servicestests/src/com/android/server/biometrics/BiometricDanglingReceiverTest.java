/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.server.biometrics.sensors.BiometricNotificationUtils.NOTIFICATION_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BiometricDanglingReceiverTest {
    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    private BiometricDanglingReceiver mBiometricDanglingReceiver;

    @Rule
    public final TestableContext mContext = spy(new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null));

    @Mock
    NotificationManager mNotificationManager;

    @Mock
    Intent mIntent;

    @Captor
    private ArgumentCaptor<Intent> mArgumentCaptor;

    @Before
    public void setUp() {
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);
    }

    @Test
    public void testFingerprintRegisterReceiver() {
        initBroadcastReceiver(BiometricsProtoEnums.MODALITY_FINGERPRINT);
        verify(mContext).registerReceiver(eq(mBiometricDanglingReceiver), any());
    }

    @Test
    public void testFaceRegisterReceiver() {
        initBroadcastReceiver(BiometricsProtoEnums.MODALITY_FACE);
        verify(mContext).registerReceiver(eq(mBiometricDanglingReceiver), any());
    }

    @Test
    public void testOnReceive_fingerprintReEnrollLaunch() {
        initBroadcastReceiver(BiometricsProtoEnums.MODALITY_FINGERPRINT);
        when(mIntent.getAction()).thenReturn(
                BiometricDanglingReceiver.ACTION_FINGERPRINT_RE_ENROLL_LAUNCH);

        mBiometricDanglingReceiver.onReceive(mContext, mIntent);

        // Verify fingerprint enroll process is launched.
        verify(mContext).startActivity(mArgumentCaptor.capture());
        assertThat(mArgumentCaptor.getValue().getAction())
                .isEqualTo(Settings.ACTION_FINGERPRINT_ENROLL);

        // Verify notification is canceled
        verify(mNotificationManager).cancelAsUser("FingerprintReEnroll", NOTIFICATION_ID,
                UserHandle.CURRENT);

        // Verify receiver is unregistered after receiving the broadcast
        verify(mContext).unregisterReceiver(mBiometricDanglingReceiver);
    }

    @Test
    public void testOnReceive_faceReEnrollLaunch() {
        initBroadcastReceiver(BiometricsProtoEnums.MODALITY_FACE);
        when(mIntent.getAction()).thenReturn(
                BiometricDanglingReceiver.ACTION_FACE_RE_ENROLL_LAUNCH);

        mBiometricDanglingReceiver.onReceive(mContext, mIntent);

        // Verify face enroll process is launched.
        verify(mContext).startActivity(mArgumentCaptor.capture());
        assertThat(mArgumentCaptor.getValue().getAction())
                .isEqualTo(BiometricDanglingReceiver.FACE_SETTINGS_ACTION);

        // Verify notification is canceled
        verify(mNotificationManager).cancelAsUser("FaceReEnroll", NOTIFICATION_ID,
                UserHandle.CURRENT);

        // Verify receiver is unregistered after receiving the broadcast.
        verify(mContext).unregisterReceiver(mBiometricDanglingReceiver);
    }

    private void initBroadcastReceiver(int modality) {
        mBiometricDanglingReceiver = new BiometricDanglingReceiver(mContext, modality);
    }
}
