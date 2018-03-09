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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ZenModeExtractorTest extends UiServiceTestCase {

    @Mock
    ZenModeHelper mZenModeHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testExtractIntercepted() {
        ZenModeExtractor extractor = new ZenModeExtractor();
        extractor.setZenHelper(mZenModeHelper);
        NotificationRecord r = generateRecord();

        assertFalse(r.isIntercepted());

        when(mZenModeHelper.shouldIntercept(any())).thenReturn(true);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(
                new NotificationManager.Policy(0,0,0));

        extractor.process(r);

        assertTrue(r.isIntercepted());
    }

    @Test
    public void testExtractVisualDisturbancesNotIntercepted() {
        ZenModeExtractor extractor = new ZenModeExtractor();
        extractor.setZenHelper(mZenModeHelper);
        NotificationRecord r = generateRecord();

        when(mZenModeHelper.shouldIntercept(any())).thenReturn(false);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(
                new NotificationManager.Policy(0,0,0));

        extractor.process(r);

        assertEquals(0, r.getSuppressedVisualEffects());
    }

    @Test
    public void testExtractVisualDisturbancesIntercepted() {
        ZenModeExtractor extractor = new ZenModeExtractor();
        extractor.setZenHelper(mZenModeHelper);
        NotificationRecord r = generateRecord();

        when(mZenModeHelper.shouldIntercept(any())).thenReturn(true);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(
                new NotificationManager.Policy(0,0,0, SUPPRESSED_EFFECT_PEEK
                        | SUPPRESSED_EFFECT_NOTIFICATION_LIST));

        extractor.process(r);

        assertEquals(NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
                | NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST,
                r.getSuppressedVisualEffects());
    }

    private NotificationRecord generateRecord() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        return new NotificationRecord(getContext(), sbn, channel);
    }
}
