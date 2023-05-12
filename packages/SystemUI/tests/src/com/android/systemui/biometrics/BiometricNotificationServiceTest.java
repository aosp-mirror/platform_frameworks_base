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

package com.android.systemui.biometrics;

import static com.android.systemui.biometrics.BiometricNotificationBroadcastReceiver.ACTION_SHOW_FACE_REENROLL_DIALOG;
import static com.android.systemui.biometrics.BiometricNotificationBroadcastReceiver.ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BiometricNotificationServiceTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    KeyguardStateController mKeyguardStateController;
    @Mock
    NotificationManager mNotificationManager;
    @Mock
    Optional<FingerprintReEnrollNotification> mFingerprintReEnrollNotificationOptional;
    @Mock
    FingerprintReEnrollNotification mFingerprintReEnrollNotification;

    private static final String TAG = "BiometricNotificationService";
    private static final int FACE_NOTIFICATION_ID = 1;
    private static final int FINGERPRINT_NOTIFICATION_ID = 2;
    private static final long SHOW_NOTIFICATION_DELAY_MS = 5_000L; // 5 seconds
    private static final int FINGERPRINT_ACQUIRED_RE_ENROLL = 0;

    private final ArgumentCaptor<Notification> mNotificationArgumentCaptor =
            ArgumentCaptor.forClass(Notification.class);
    private TestableLooper mLooper;
    private KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback;
    private KeyguardStateController.Callback mKeyguardStateControllerCallback;

    @Before
    public void setUp() {
        when(mFingerprintReEnrollNotificationOptional.orElse(any()))
                .thenReturn(mFingerprintReEnrollNotification);
        when(mFingerprintReEnrollNotification.isFingerprintReEnrollRequired(
                FINGERPRINT_ACQUIRED_RE_ENROLL)).thenReturn(true);

        mLooper = TestableLooper.get(this);
        Handler handler = new Handler(mLooper.getLooper());
        BiometricNotificationDialogFactory dialogFactory = new BiometricNotificationDialogFactory();
        BiometricNotificationBroadcastReceiver broadcastReceiver =
                new BiometricNotificationBroadcastReceiver(mContext, dialogFactory);
        BiometricNotificationService biometricNotificationService =
                new BiometricNotificationService(mContext,
                        mKeyguardUpdateMonitor, mKeyguardStateController, handler,
                        mNotificationManager,
                        broadcastReceiver,
                        mFingerprintReEnrollNotificationOptional);
        biometricNotificationService.start();

        ArgumentCaptor<KeyguardUpdateMonitorCallback> updateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        ArgumentCaptor<KeyguardStateController.Callback> stateControllerCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);

        verify(mKeyguardUpdateMonitor).registerCallback(
                updateMonitorCallbackArgumentCaptor.capture());
        verify(mKeyguardStateController).addCallback(
                stateControllerCallbackArgumentCaptor.capture());

        mKeyguardUpdateMonitorCallback = updateMonitorCallbackArgumentCaptor.getValue();
        mKeyguardStateControllerCallback = stateControllerCallbackArgumentCaptor.getValue();
    }

    @Test
    public void testShowFingerprintReEnrollNotification() {
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        mKeyguardUpdateMonitorCallback.onBiometricHelp(
                FINGERPRINT_ACQUIRED_RE_ENROLL,
                "Testing Fingerprint Re-enrollment" /* errString */,
                BiometricSourceType.FINGERPRINT
        );
        mKeyguardStateControllerCallback.onKeyguardShowingChanged();

        mLooper.moveTimeForward(SHOW_NOTIFICATION_DELAY_MS);
        mLooper.processAllMessages();

        verify(mNotificationManager).notifyAsUser(eq(TAG), eq(FINGERPRINT_NOTIFICATION_ID),
                mNotificationArgumentCaptor.capture(), any());

        Notification fingerprintNotification = mNotificationArgumentCaptor.getValue();

        assertThat(fingerprintNotification.contentIntent.getIntent().getAction())
                .isEqualTo(ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG);
    }
    @Test
    public void testShowFaceReEnrollNotification() {
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        mKeyguardUpdateMonitorCallback.onBiometricError(
                BiometricFaceConstants.BIOMETRIC_ERROR_RE_ENROLL,
                "Testing Face Re-enrollment" /* errString */,
                BiometricSourceType.FACE
        );
        mKeyguardStateControllerCallback.onKeyguardShowingChanged();

        mLooper.moveTimeForward(SHOW_NOTIFICATION_DELAY_MS);
        mLooper.processAllMessages();

        verify(mNotificationManager).notifyAsUser(eq(TAG), eq(FACE_NOTIFICATION_ID),
                mNotificationArgumentCaptor.capture(), any());

        Notification fingerprintNotification = mNotificationArgumentCaptor.getValue();

        assertThat(fingerprintNotification.contentIntent.getIntent().getAction())
                .isEqualTo(ACTION_SHOW_FACE_REENROLL_DIALOG);
    }

}
