/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.Notification.CATEGORY_ALARM;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;
import static com.android.server.notification.NotificationChannelExtractor.RESTRICT_AUDIO_ATTRIBUTES;
import static com.google.common.truth.Truth.assertThat;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Flags;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import com.android.internal.compat.IPlatformCompat;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class NotificationChannelExtractorTest extends UiServiceTestCase {

    @Mock RankingConfig mConfig;
    @Mock
    IPlatformCompat mPlatformCompat;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    NotificationChannelExtractor mExtractor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mExtractor = new NotificationChannelExtractor();
        mExtractor.setConfig(mConfig);
        mExtractor.initialize(mContext, null);
        mExtractor.setCompatChangeLogger(mPlatformCompat);
    }

    private NotificationRecord getRecord(NotificationChannel channel, Notification n) {
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        return new NotificationRecord(getContext(), sbn, channel);
    }

    @Test
    public void testExtractsUpdatedConversationChannel() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        NotificationChannel updatedChannel =
                new NotificationChannel("a", "", IMPORTANCE_HIGH);
        when(mConfig.getConversationNotificationChannel(
                any(), anyInt(), eq("a"), eq(null), eq(true), eq(false)))
                .thenReturn(updatedChannel);

        assertNull(mExtractor.process(r));
        assertEquals(updatedChannel, r.getChannel());
    }

    @Test
    public void testInvalidShortcutFlagEnabled_looksUpCorrectNonChannel() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setStyle(new Notification.MessagingStyle("name"))
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        NotificationChannel updatedChannel =
                new NotificationChannel("a", "", IMPORTANCE_HIGH);
        when(mConfig.getConversationNotificationChannel(
                any(), anyInt(), eq("a"), eq(r.getSbn().getShortcutId()),
                eq(true), eq(false)))
                .thenReturn(updatedChannel);

        assertNull(mExtractor.process(r));
        assertEquals(updatedChannel, r.getChannel());
    }

    @Test
    public void testInvalidShortcutFlagDisabled_looksUpCorrectChannel() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setStyle(new Notification.MessagingStyle("name"))
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        NotificationChannel updatedChannel =
                new NotificationChannel("a", "", IMPORTANCE_HIGH);
        when(mConfig.getConversationNotificationChannel(
                any(), anyInt(), eq("a"), eq(null), eq(true), eq(false)))
                .thenReturn(updatedChannel);

        assertNull(mExtractor.process(r));
        assertEquals(updatedChannel, r.getChannel());
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_CALL)
    public void testAudioAttributes_callStyleCanUseCallUsage() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION_RINGTONE)
                .build());
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setStyle(Notification.CallStyle.forIncomingCall(
                        new Person.Builder().setName("A Caller").build(),
                        mock(PendingIntent.class),
                        mock(PendingIntent.class)
                ))
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        assertThat(mExtractor.process(r)).isNull();
        assertThat(r.getAudioAttributes().getUsage()).isEqualTo(USAGE_NOTIFICATION_RINGTONE);
        assertThat(r.getChannel()).isEqualTo(channel);
        verify(mPlatformCompat, never()).reportChangeByUid(anyLong(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_CALL)
    public void testAudioAttributes_nonCallStyleCannotUseCallUsage() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION_RINGTONE)
                .build());
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        assertThat(mExtractor.process(r)).isNull();
        // instance updated
        assertThat(r.getAudioAttributes().getUsage()).isEqualTo(USAGE_NOTIFICATION);
        verify(mPlatformCompat).reportChangeByUid(RESTRICT_AUDIO_ATTRIBUTES, r.getUid());
        // in-memory channel unchanged
        assertThat(channel.getAudioAttributes().getUsage()).isEqualTo(USAGE_NOTIFICATION_RINGTONE);
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_ALARM)
    public void testAudioAttributes_alarmCategoryCanUseAlarmUsage() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, new AudioAttributes.Builder()
                .setUsage(USAGE_ALARM)
                .build());
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setCategory(CATEGORY_ALARM)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        assertThat(mExtractor.process(r)).isNull();
        assertThat(r.getAudioAttributes().getUsage()).isEqualTo(USAGE_ALARM);
        assertThat(r.getChannel()).isEqualTo(channel);
        verify(mPlatformCompat, never()).reportChangeByUid(anyLong(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_ALARM)
    public void testAudioAttributes_nonAlarmCategoryCannotUseAlarmUsage() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, new AudioAttributes.Builder()
                .setUsage(USAGE_ALARM)
                .build());
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        assertThat(mExtractor.process(r)).isNull();
        // instance updated
        assertThat(r.getAudioAttributes().getUsage()).isEqualTo(USAGE_NOTIFICATION);
        verify(mPlatformCompat).reportChangeByUid(RESTRICT_AUDIO_ATTRIBUTES, r.getUid());
        // in-memory channel unchanged
        assertThat(channel.getAudioAttributes().getUsage()).isEqualTo(USAGE_ALARM);
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_MEDIA)
    public void testAudioAttributes_noMediaUsage() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, new AudioAttributes.Builder()
                .setUsage(USAGE_MEDIA)
                .build());
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        assertThat(mExtractor.process(r)).isNull();
        // instance updated
        assertThat(r.getAudioAttributes().getUsage()).isEqualTo(USAGE_NOTIFICATION);
        verify(mPlatformCompat).reportChangeByUid(RESTRICT_AUDIO_ATTRIBUTES, r.getUid());
        // in-memory channel unchanged
        assertThat(channel.getAudioAttributes().getUsage()).isEqualTo(USAGE_MEDIA);
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_MEDIA)
    public void testAudioAttributes_noUnknownUsage() throws RemoteException {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, new AudioAttributes.Builder()
                .setUsage(USAGE_UNKNOWN)
                .build());
        final Notification n = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
        NotificationRecord r = getRecord(channel, n);

        assertThat(mExtractor.process(r)).isNull();
        // instance updated
        assertThat(r.getAudioAttributes().getUsage()).isEqualTo(USAGE_NOTIFICATION);
        verify(mPlatformCompat).reportChangeByUid(RESTRICT_AUDIO_ATTRIBUTES, r.getUid());
        // in-memory channel unchanged
        assertThat(channel.getAudioAttributes().getUsage()).isEqualTo(USAGE_UNKNOWN);
    }
}
