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
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BadgeExtractorTest extends UiServiceTestCase {

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

    private NotificationRecord getNotificationRecord(boolean showBadge, int importanceHigh) {
        NotificationChannel channel = new NotificationChannel("a", "a", importanceHigh);
        channel.setShowBadge(showBadge);
        when(mConfig.getNotificationChannel(mPkg, mUid, "a", false)).thenReturn(channel);

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

    //
    // Tests
    //

    @Test
    public void testAppYesChannelNo() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(false, IMPORTANCE_UNSPECIFIED);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testAppNoChannelYes() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(false);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_HIGH);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testAppYesChannelYes() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);

        extractor.process(r);

        assertTrue(r.canShowBadge());
    }

    @Test
    public void testAppNoChannelNo() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(false);
        NotificationRecord r = getNotificationRecord(false, IMPORTANCE_UNSPECIFIED);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testAppYesChannelYesUserNo() throws Exception {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(false);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_HIGH);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testDndOverridesYes() {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);
        r.setIntercepted(true);
        r.setSuppressedVisualEffects(SUPPRESSED_EFFECT_BADGE);

        extractor.process(r);

        assertFalse(r.canShowBadge());
    }

    @Test
    public void testDndOConsidersInterception() {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);
        r.setIntercepted(false);
        r.setSuppressedVisualEffects(SUPPRESSED_EFFECT_BADGE);

        extractor.process(r);

        assertTrue(r.canShowBadge());
    }

    @Test
    public void testDndConsidersSuppressedVisualEffects() {
        BadgeExtractor extractor = new BadgeExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.badgingEnabled(mUser)).thenReturn(true);
        when(mConfig.canShowBadge(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);
        r.setIntercepted(true);
        r.setSuppressedVisualEffects(SUPPRESSED_EFFECT_LIGHTS);

        extractor.process(r);

        assertTrue(r.canShowBadge());
    }
}
