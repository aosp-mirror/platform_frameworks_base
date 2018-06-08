/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.provider.Settings.Global.ZEN_MODE_ALARMS;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZenModeFilteringTest extends UiServiceTestCase {

    @Mock
    private NotificationMessagingUtil mMessagingUtil;
    private ZenModeFiltering mZenModeFiltering;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mZenModeFiltering = new ZenModeFiltering(mContext, mMessagingUtil);
    }

    private NotificationRecord getNotificationRecord() {
        return getNotificationRecord(mock(NotificationChannel.class));
    }

    private NotificationRecord getNotificationRecord(NotificationChannel c) {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getNotification()).thenReturn(mock(Notification.class));
        return new NotificationRecord(mContext, sbn, c);
    }

    @Test
    public void testIsMessage() {
        NotificationRecord r = getNotificationRecord();

        when(mMessagingUtil.isMessaging(any())).thenReturn(true);
        assertTrue(mZenModeFiltering.isMessage(r));

        when(mMessagingUtil.isMessaging(any())).thenReturn(false);
        assertFalse(mZenModeFiltering.isMessage(r));
    }

    @Test
    public void testIsAlarm() {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.getAudioAttributes()).thenReturn(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build());
        NotificationRecord r = getNotificationRecord(c);
        assertTrue(mZenModeFiltering.isAlarm(r));

        r = getNotificationRecord();
        r.sbn.getNotification().category = Notification.CATEGORY_ALARM;
        assertTrue(mZenModeFiltering.isAlarm(r));
    }

    @Test
    public void testIsAlarm_wrongCategory() {
        NotificationRecord r = getNotificationRecord();
        r.sbn.getNotification().category = Notification.CATEGORY_CALL;
        assertFalse(mZenModeFiltering.isAlarm(r));
    }

    @Test
    public void testIsAlarm_wrongUsage() {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.getAudioAttributes()).thenReturn(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build());
        NotificationRecord r = getNotificationRecord(c);
        assertFalse(mZenModeFiltering.isAlarm(r));
    }

    @Test
    public void testSuppressDNDInfo_yes_VisEffectsAllowed() {
        NotificationRecord r = getNotificationRecord();
        when(r.sbn.getPackageName()).thenReturn("android");
        when(r.sbn.getId()).thenReturn(SystemMessage.NOTE_ZEN_UPGRADE);
        ZenModeConfig config = mock(ZenModeConfig.class);
        config.suppressedVisualEffects = NotificationManager.Policy.getAllSuppressedVisualEffects()
                - SUPPRESSED_EFFECT_STATUS_BAR;

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, config, r));
    }

    @Test
    public void testSuppressDNDInfo_yes_WrongId() {
        NotificationRecord r = getNotificationRecord();
        when(r.sbn.getPackageName()).thenReturn("android");
        when(r.sbn.getId()).thenReturn(SystemMessage.NOTE_ACCOUNT_CREDENTIAL_PERMISSION);
        ZenModeConfig config = mock(ZenModeConfig.class);
        config.suppressedVisualEffects = NotificationManager.Policy.getAllSuppressedVisualEffects();

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, config, r));
    }

    @Test
    public void testSuppressDNDInfo_yes_WrongPackage() {
        NotificationRecord r = getNotificationRecord();
        when(r.sbn.getPackageName()).thenReturn("android2");
        when(r.sbn.getId()).thenReturn(SystemMessage.NOTE_ZEN_UPGRADE);
        ZenModeConfig config = mock(ZenModeConfig.class);
        config.suppressedVisualEffects = NotificationManager.Policy.getAllSuppressedVisualEffects();

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, config, r));
    }

    @Test
    public void testSuppressDNDInfo_no() {
        NotificationRecord r = getNotificationRecord();
        when(r.sbn.getPackageName()).thenReturn("android");
        when(r.sbn.getId()).thenReturn(SystemMessage.NOTE_ZEN_UPGRADE);
        ZenModeConfig config = mock(ZenModeConfig.class);
        config.suppressedVisualEffects = NotificationManager.Policy.getAllSuppressedVisualEffects();

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, config, r));
        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_ALARMS, config, r));
        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_NO_INTERRUPTIONS, config, r));
    }

    @Test
    public void testSuppressAnything_yes_ZenModeOff() {
        NotificationRecord r = getNotificationRecord();
        when(r.sbn.getPackageName()).thenReturn("bananas");
        ZenModeConfig config = mock(ZenModeConfig.class);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_OFF, config, r));
    }
}
