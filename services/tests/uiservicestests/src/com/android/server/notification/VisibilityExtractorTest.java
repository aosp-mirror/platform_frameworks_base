/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.Notification.VISIBILITY_SECRET;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.VISIBILITY_NO_OVERRIDE;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.admin.DevicePolicyManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VisibilityExtractorTest extends UiServiceTestCase {

    @Mock RankingConfig mConfig;
    @Mock
    DevicePolicyManager mDpm;

    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private int mUser = ActivityManager.getCurrentUser();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(DevicePolicyManager.class, mDpm);
    }

    private NotificationRecord getNotificationRecord(int visibility) {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(visibility);
        when(mConfig.getNotificationChannel(mPkg, mUid, "a", false)).thenReturn(channel);

        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, mId, mTag, mUid,
                mPid, n, UserHandle.of(mUser), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        return r;
    }

    //
    // Tests
    //

    @Test
    public void testGlobalAllDpmAllChannelAll() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(0);

        NotificationRecord r = getNotificationRecord(VISIBILITY_NO_OVERRIDE);

        extractor.process(r);

        assertEquals(VISIBILITY_NO_OVERRIDE, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalNoneDpmAllChannelAll() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(false);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(0);

        NotificationRecord r = getNotificationRecord(VISIBILITY_NO_OVERRIDE);

        extractor.process(r);

        assertEquals(VISIBILITY_SECRET, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalSomeDpmAllChannelAll() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(false);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(0);

        NotificationRecord r = getNotificationRecord(VISIBILITY_NO_OVERRIDE);

        extractor.process(r);

        assertEquals(VISIBILITY_PRIVATE, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalAllDpmNoneChannelAll() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(
                KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        NotificationRecord r = getNotificationRecord(VISIBILITY_NO_OVERRIDE);

        extractor.process(r);

        assertEquals(VISIBILITY_SECRET, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalAllDpmSomeChannelAll() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(
                KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        NotificationRecord r = getNotificationRecord(VISIBILITY_NO_OVERRIDE);

        extractor.process(r);

        assertEquals(VISIBILITY_PRIVATE, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalAllDpmAllChannelNone() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(0);

        NotificationRecord r = getNotificationRecord(VISIBILITY_SECRET);

        extractor.process(r);

        assertEquals(VISIBILITY_SECRET, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalAllDpmAllChannelSome() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(0);

        NotificationRecord r = getNotificationRecord(VISIBILITY_PRIVATE);

        extractor.process(r);

        assertEquals(VISIBILITY_PRIVATE, r.getPackageVisibilityOverride());
    }

    @Test
    public void testGlobalAllDpmSomeChannelNone() {
        VisibilityExtractor extractor = new VisibilityExtractor();
        extractor.setConfig(mConfig);
        extractor.initialize(mContext, null);

        when(mConfig.canShowNotificationsOnLockscreen(mUser)).thenReturn(true);
        when(mConfig.canShowPrivateNotificationsOnLockScreen(mUser)).thenReturn(true);

        when(mDpm.getKeyguardDisabledFeatures(null, mUser)).thenReturn(
                KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        NotificationRecord r = getNotificationRecord(VISIBILITY_SECRET);

        extractor.process(r);

        assertEquals(VISIBILITY_SECRET, r.getPackageVisibilityOverride());
    }

}
