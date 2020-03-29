/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
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
public class BubbleExtractorTest extends UiServiceTestCase {

    @Mock RankingConfig mConfig;
    @Mock BubbleExtractor.BubbleChecker mBubbleChecker;
    BubbleExtractor mBubbleExtractor;

    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mBubbleExtractor = new BubbleExtractor();
        mBubbleExtractor.initialize(mContext, mock(NotificationUsageStats.class));
        mBubbleExtractor.setConfig(mConfig);
        mBubbleExtractor.setShortcutHelper(mock(ShortcutHelper.class));
    }

    private NotificationRecord getNotificationRecord(boolean allow, int importanceHigh) {
        NotificationChannel channel = new NotificationChannel("a", "a", importanceHigh);
        channel.setAllowBubbles(allow);
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
    public void testAppYesChannelNo() {
        when(mConfig.bubblesEnabled()).thenReturn(true);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(false, IMPORTANCE_UNSPECIFIED);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
    }

    @Test
    public void testAppNoChannelYes() throws Exception {
        when(mConfig.bubblesEnabled()).thenReturn(true);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(false);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_HIGH);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
    }

    @Test
    public void testAppYesChannelYes() {
        when(mConfig.bubblesEnabled()).thenReturn(true);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);

        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
    }

    @Test
    public void testAppNoChannelNo() {
        when(mConfig.bubblesEnabled()).thenReturn(true);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(false);
        NotificationRecord r = getNotificationRecord(false, IMPORTANCE_UNSPECIFIED);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
    }

    @Test
    public void testAppYesChannelYesUserNo() {
        when(mConfig.bubblesEnabled()).thenReturn(false);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_HIGH);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
    }

    @Test
    public void testFlagBubble_true() {
        when(mConfig.bubblesEnabled()).thenReturn(true);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);

        mBubbleExtractor.setBubbleChecker(mBubbleChecker);
        when(mBubbleChecker.isNotificationAppropriateToBubble(r)).thenReturn(true);
        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
        assertTrue(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_noFlag_previouslyRemoved() {
        when(mConfig.bubblesEnabled()).thenReturn(true);
        when(mConfig.areBubblesAllowed(mPkg, mUid)).thenReturn(true);
        NotificationRecord r = getNotificationRecord(true, IMPORTANCE_UNSPECIFIED);
        r.setFlagBubbleRemoved(true);

        mBubbleExtractor.setBubbleChecker(mBubbleChecker);
        when(mBubbleChecker.isNotificationAppropriateToBubble(r)).thenReturn(true);
        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }
}
