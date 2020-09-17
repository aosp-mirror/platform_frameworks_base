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

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class NotificationChannelExtractorTest extends UiServiceTestCase {

    @Mock RankingConfig mConfig;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testExtractsUpdatedChannel() {
        NotificationChannelExtractor extractor = new NotificationChannelExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);

        NotificationChannel updatedChannel =
                new NotificationChannel("a", "", IMPORTANCE_HIGH);
        when(mConfig.getConversationNotificationChannel(
                any(), anyInt(), eq("a"), eq(null), eq(true), eq(false)))
                .thenReturn(updatedChannel);

        assertNull(extractor.process(r));
        assertEquals(updatedChannel, r.getChannel());
    }

    @Test
    public void testInvalidShortcutFlagEnabled_looksUpCorrectChannel() {

        NotificationChannelExtractor extractor = new NotificationChannelExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setStyle(new Notification.MessagingStyle("name"))
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "tag", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);

        NotificationChannel updatedChannel =
                new NotificationChannel("a", "", IMPORTANCE_HIGH);
        when(mConfig.getConversationNotificationChannel(
                any(), anyInt(), eq("a"), eq(r.getSbn().getShortcutId()),
                eq(true), eq(false)))
                .thenReturn(updatedChannel);

        assertNull(extractor.process(r));
        assertEquals(updatedChannel, r.getChannel());
    }

    @Test
    public void testInvalidShortcutFlagDisabled_looksUpCorrectChannel() {

        NotificationChannelExtractor extractor = new NotificationChannelExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setStyle(new Notification.MessagingStyle("name"))
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "tag", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);

        NotificationChannel updatedChannel =
                new NotificationChannel("a", "", IMPORTANCE_HIGH);
        when(mConfig.getConversationNotificationChannel(
                any(), anyInt(), eq("a"), eq(null), eq(true), eq(false)))
                .thenReturn(updatedChannel);

        assertNull(extractor.process(r));
        assertEquals(updatedChannel, r.getChannel());
    }
}
