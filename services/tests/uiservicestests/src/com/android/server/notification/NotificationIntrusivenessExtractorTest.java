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

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;

import static com.android.server.notification.NotificationIntrusivenessExtractor.HANG_TIME_MS;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.server.UiServiceTestCase;

import org.junit.Test;

public class NotificationIntrusivenessExtractorTest extends UiServiceTestCase {

    @Test
    public void testNonIntrusive() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);

        assertNull(new NotificationIntrusivenessExtractor().process(r));
    }

    @Test
    public void testIntrusive_fillScreen() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setFullScreenIntent(PendingIntent.getActivity(
                        getContext(), 0, new Intent(""), PendingIntent.FLAG_IMMUTABLE),
                        true)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null,
                System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);

        assertNotNull(new NotificationIntrusivenessExtractor().process(r));
    }

    @Test
    public void testOldNotificationsNotIntrusive() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setFullScreenIntent(PendingIntent.getActivity(
                        getContext(), 0, new Intent(""), PendingIntent.FLAG_IMMUTABLE),
                        true)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null,
                System.currentTimeMillis() - HANG_TIME_MS);

        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        assertNull(new NotificationIntrusivenessExtractor().process(r));
        assertFalse(r.isRecentlyIntrusive());
    }
}
