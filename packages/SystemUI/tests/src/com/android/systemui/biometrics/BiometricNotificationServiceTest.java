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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
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
@RunWith(AndroidJUnit4.class)
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
    BiometricNotificationDialogFactory mNotificationDialogFactory;
    @Mock
    Optional<FingerprintReEnrollNotification> mFingerprintReEnrollNotificationOptional;
    @Mock
    FingerprintReEnrollNotification mFingerprintReEnrollNotification;
    @Mock
    FingerprintManager mFingerprintManager;
    @Mock
    FaceManager mFaceManager;

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
    private BiometricNotificationService mBiometricNotificationService;
    private BiometricStateListener mFaceStateListener;
    private BiometricStateListener mFingerprintStateListener;

    @Before
    public void setUp() {
        when(mFingerprintReEnrollNotificationOptional.orElse(any()))
                .thenReturn(mFingerprintReEnrollNotification);
        when(mFingerprintReEnrollNotification.isFingerprintReEnrollRequested(
                FINGERPRINT_ACQUIRED_RE_ENROLL)).thenReturn(true);

        mLooper = TestableLooper.get(this);
        Handler handler = new Handler(mLooper.getLooper());
        BiometricNotificationBroadcastReceiver broadcastReceiver =
                new BiometricNotificationBroadcastReceiver(mContext, mNotificationDialogFactory);
        mBiometricNotificationService =
                new BiometricNotificationService(mContext,
                        mKeyguardUpdateMonitor, mKeyguardStateController, handler,
                        mNotificationManager,
                        broadcastReceiver,
                        mFingerprintReEnrollNotificationOptional,
                        mFingerprintManager,
                        mFaceManager);
        mBiometricNotificationService.start();

        ArgumentCaptor<KeyguardUpdateMonitorCallback> updateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        ArgumentCaptor<KeyguardStateController.Callback> stateControllerCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
        ArgumentCaptor<BiometricStateListener> faceStateListenerArgumentCaptor =
                ArgumentCaptor.forClass(BiometricStateListener.class);
        ArgumentCaptor<BiometricStateListener> fingerprintStateListenerArgumentCaptor =
                ArgumentCaptor.forClass(BiometricStateListener.class);

        verify(mKeyguardUpdateMonitor).registerCallback(
                updateMonitorCallbackArgumentCaptor.capture());
        verify(mKeyguardStateController).addCallback(
                stateControllerCallbackArgumentCaptor.capture());
        verify(mFaceManager).registerBiometricStateListener(
                faceStateListenerArgumentCaptor.capture());
        verify(mFingerprintManager).registerBiometricStateListener(
                fingerprintStateListenerArgumentCaptor.capture());

        mFaceStateListener = faceStateListenerArgumentCaptor.getValue();
        mFingerprintStateListener = fingerprintStateListenerArgumentCaptor.getValue();
        mKeyguardUpdateMonitorCallback = updateMonitorCallbackArgumentCaptor.getValue();
        mKeyguardStateControllerCallback = stateControllerCallbackArgumentCaptor.getValue();
    }

    @Test
    public void testShowFingerprintReEnrollNotification_onAcquiredReEnroll_Optional() {
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mFingerprintReEnrollNotification.isFingerprintReEnrollForced(
                FINGERPRINT_ACQUIRED_RE_ENROLL)).thenReturn(false);

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
        assertThat(fingerprintNotification.contentIntent.getIntent().getBooleanExtra(
                BiometricNotificationBroadcastReceiver.EXTRA_IS_REENROLL_FORCED, false)).isFalse();
    }

    @Test
    public void testShowFingerprintReEnrollNotification_onAcquiredReEnroll_force() {
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mFingerprintReEnrollNotification.isFingerprintReEnrollForced(
                FINGERPRINT_ACQUIRED_RE_ENROLL)).thenReturn(true);

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
        assertThat(fingerprintNotification.contentIntent.getIntent().getBooleanExtra(
                BiometricNotificationBroadcastReceiver.EXTRA_IS_REENROLL_FORCED, false)).isTrue();
    }
    @Test
    public void testShowFaceReEnrollNotification_onErrorReEnroll() {
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

    @Test
    public void testCancelReEnrollmentNotification_onFaceEnrollmentStateChange() {
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

        mFaceStateListener.onEnrollmentsChanged(0 /* userId */, 0 /* sensorId */,
                false /* hasEnrollments */);

        verify(mNotificationManager).cancelAsUser(eq(TAG), eq(FACE_NOTIFICATION_ID),
                eq(UserHandle.CURRENT));
    }

    @Test
    public void testCancelReEnrollmentNotification_onFingerprintEnrollmentStateChange() {
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

        mFingerprintStateListener.onEnrollmentsChanged(0 /* userId */, 0 /* sensorId */,
                false /* hasEnrollments */);

        verify(mNotificationManager).cancelAsUser(eq(TAG), eq(FINGERPRINT_NOTIFICATION_ID),
                eq(UserHandle.CURRENT));
    }

    @Test
    public void testResetFaceUnlockReEnroll_onStart() {
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        mKeyguardUpdateMonitorCallback.onBiometricError(
                BiometricFaceConstants.BIOMETRIC_ERROR_RE_ENROLL,
                "Testing Face Re-enrollment" /* errString */,
                BiometricSourceType.FACE
        );

        mBiometricNotificationService.start();
        mKeyguardStateControllerCallback.onKeyguardShowingChanged();

        mLooper.moveTimeForward(SHOW_NOTIFICATION_DELAY_MS);
        mLooper.processAllMessages();

        verify(mNotificationManager, never()).notifyAsUser(eq(TAG), eq(FACE_NOTIFICATION_ID),
                mNotificationArgumentCaptor.capture(), any());
    }
}
