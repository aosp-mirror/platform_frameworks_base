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

import android.hardware.biometrics.BiometricsProtoEnums;
import android.platform.test.annotations.Presubmit;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.server.biometrics.log.BiometricFrameworkStatsLogger;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class BiometricNotificationLoggerTest {
    @Rule
    public MockitoRule mockitorule = MockitoJUnit.rule();

    @Mock
    private BiometricFrameworkStatsLogger mLogger;
    private BiometricNotificationLogger mNotificationLogger;

    @Before
    public void setUp() {
        mNotificationLogger = new BiometricNotificationLogger(
                mLogger);
    }

    @Test
    public void testNotification_nullNotification_doesNothing() {
        mNotificationLogger.onNotificationPosted(null, null);

        verify(mLogger, never()).logFrameworkNotification(anyInt(), anyInt());
    }

    @Test
    public void testNotification_emptyStringTag_doesNothing() {
        final StatusBarNotification noti = createNotificationWithNullTag();
        mNotificationLogger.onNotificationPosted(noti, null);

        verify(mLogger, never()).logFrameworkNotification(anyInt(), anyInt());
    }

    @Test
    public void testFaceNotification_posted() {
        final StatusBarNotification noti = createFaceNotification();
        mNotificationLogger.onNotificationPosted(noti, null);

        verify(mLogger).logFrameworkNotification(
                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_SHOWN,
                BiometricsProtoEnums.MODALITY_FACE);
    }

    @Test
    public void testFingerprintNotification_posted() {
        final StatusBarNotification noti = createFingerprintNotification();
        mNotificationLogger.onNotificationPosted(noti, null);

        verify(mLogger).logFrameworkNotification(
                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_SHOWN,
                BiometricsProtoEnums.MODALITY_FINGERPRINT);
    }

    @Test
    public void testFaceNotification_clicked() {
        final StatusBarNotification noti = createFaceNotification();
        mNotificationLogger.onNotificationRemoved(noti, null,
                NotificationListenerService.REASON_CLICK);

        verify(mLogger).logFrameworkNotification(
                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_CLICKED,
                BiometricsProtoEnums.MODALITY_FACE);
    }

    @Test
    public void testFingerprintNotification_clicked() {
        final StatusBarNotification noti = createFingerprintNotification();
        mNotificationLogger.onNotificationRemoved(noti, null,
                NotificationListenerService.REASON_CLICK);

        verify(mLogger).logFrameworkNotification(
                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_CLICKED,
                BiometricsProtoEnums.MODALITY_FINGERPRINT);
    }

    @Test
    public void testFaceNotification_dismissed() {
        final StatusBarNotification noti = createFaceNotification();
        mNotificationLogger.onNotificationRemoved(noti, null,
                NotificationListenerService.REASON_CANCEL);

        verify(mLogger).logFrameworkNotification(
                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_DISMISSED,
                BiometricsProtoEnums.MODALITY_FACE);
    }

    @Test
    public void testFingerprintNotification_dismissed() {
        final StatusBarNotification noti = createFingerprintNotification();
        mNotificationLogger.onNotificationRemoved(noti, null,
                NotificationListenerService.REASON_CANCEL);

        verify(mLogger).logFrameworkNotification(
                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_DISMISSED,
                BiometricsProtoEnums.MODALITY_FINGERPRINT);
    }

    private StatusBarNotification createNotificationWithNullTag() {
        final StatusBarNotification notification = mock(StatusBarNotification.class);
        return notification;
    }

    private StatusBarNotification createFaceNotification() {
        final StatusBarNotification notification = mock(StatusBarNotification.class);
        when(notification.getTag())
                .thenReturn(BiometricNotificationUtils.FACE_ENROLL_NOTIFICATION_TAG);
        return notification;
    }

    private StatusBarNotification createFingerprintNotification() {
        final StatusBarNotification notification = mock(StatusBarNotification.class);
        when(notification.getTag())
                .thenReturn(BiometricNotificationUtils.FINGERPRINT_ENROLL_NOTIFICATION_TAG);
        return notification;
    }

}
