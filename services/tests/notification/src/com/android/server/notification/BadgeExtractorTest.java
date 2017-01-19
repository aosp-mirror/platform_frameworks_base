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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BadgeExtractorTest {

    @Mock RankingConfig mConfig;

    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private NotificationRecord getNotificationRecord(NotificationChannel channel) {
        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND);

        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, mId, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        return r;
    }

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    //
    // Tests
    //

    @Test
    public void testAppYesChannelNo() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationChannel channel =
                new NotificationChannel("a", "a", NotificationManager.IMPORTANCE_UNSPECIFIED);
        channel.setShowBadge(false);

        NotificationRecord r = getNotificationRecord(channel);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testAppNoChannelYes() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(false);
        NotificationChannel channel =
                new NotificationChannel("a", "a", NotificationManager.IMPORTANCE_HIGH);
        channel.setShowBadge(true);

        NotificationRecord r = getNotificationRecord(channel);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testAppYesChannelYes() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationChannel channel =
                new NotificationChannel("a", "a", NotificationManager.IMPORTANCE_UNSPECIFIED);
        channel.setShowBadge(true);

        NotificationRecord r = getNotificationRecord(channel);

        extractor.process(r);

        assertTrue(r.canShowBadge());
    }

    @Test
    public void testAppNoChannelNo() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(false);
        NotificationChannel channel =
                new NotificationChannel("a", "a", NotificationManager.IMPORTANCE_UNSPECIFIED);
        channel.setShowBadge(false);

        NotificationRecord r = getNotificationRecord(channel);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }
}
