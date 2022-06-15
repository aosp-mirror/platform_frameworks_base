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

package com.android.systemui.statusbar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationListenerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationHandler mNotificationHandler;
    @Mock private NotificationManager mNotificationManager;

    private NotificationListener mListener;
    private StatusBarNotification mSbn;
    private RankingMap mRanking = new RankingMap(new Ranking[0]);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mListener = new NotificationListener(
                mContext,
                mNotificationManager,
                new Handler(TestableLooper.get(this).getLooper()));
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);

        mListener.addNotificationHandler(mNotificationHandler);
    }

    @Test
    public void testNotificationAddCallsAddNotification() {
        mListener.onNotificationPosted(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mNotificationHandler).onNotificationPosted(mSbn, mRanking);
    }

    @Test
    public void testNotificationRemovalCallsRemoveNotification() {
        mListener.onNotificationRemoved(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mNotificationHandler).onNotificationRemoved(eq(mSbn), eq(mRanking), anyInt());
    }

    @Test
    public void testRankingUpdateCallsNotificationRankingUpdate() {
        mListener.onNotificationRankingUpdate(mRanking);
        TestableLooper.get(this).processAllMessages();
        // RankingMap may be modified by plugins.
        verify(mNotificationHandler).onNotificationRankingUpdate(any());
    }

    @Test
    public void testOnConnectReadStatusBarSetting() {
        NotificationListener.NotificationSettingsListener settingsListener =
                mock(NotificationListener.NotificationSettingsListener.class);
        mListener.addNotificationSettingsListener(settingsListener);

        when(mNotificationManager.shouldHideSilentStatusBarIcons()).thenReturn(true);

        mListener.onListenerConnected();

        verify(settingsListener).onStatusBarIconsBehaviorChanged(true);
    }

    @Test
    public void testOnStatusBarIconsBehaviorChanged() {
        NotificationListener.NotificationSettingsListener settingsListener =
                mock(NotificationListener.NotificationSettingsListener.class);
        mListener.addNotificationSettingsListener(settingsListener);

        mListener.onSilentStatusBarIconsVisibilityChanged(true);

        verify(settingsListener).onStatusBarIconsBehaviorChanged(true);
    }
}
