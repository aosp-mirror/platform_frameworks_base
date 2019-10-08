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
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

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

    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationListenerService.RankingMap mRanking;
    @Mock private NotificationData mNotificationData;

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private NotificationManager mNotificationManager;

    private NotificationListener mListener;
    private StatusBarNotification mSbn;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationRemoteInputManager.class,
                mRemoteInputManager);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                new Handler(TestableLooper.get(this).getLooper()));
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);

        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);

        mListener = new NotificationListener(mContext);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);
    }

    @Test
    public void testNotificationAddCallsAddNotification() {
        mListener.onNotificationPosted(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).addNotification(mSbn, mRanking);
    }

    @Test
    public void testNotificationUpdateCallsUpdateNotification() {
        when(mNotificationData.get(mSbn.getKey())).thenReturn(new NotificationEntry(mSbn));
        mListener.onNotificationPosted(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).updateNotification(mSbn, mRanking);
    }

    @Test
    public void testNotificationRemovalCallsRemoveNotification() {
        mListener.onNotificationRemoved(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).removeNotification(eq(mSbn.getKey()), eq(mRanking), anyInt());
    }

    @Test
    public void testRankingUpdateCallsNotificationRankingUpdate() {
        mListener.onNotificationRankingUpdate(mRanking);
        TestableLooper.get(this).processAllMessages();
        // RankingMap may be modified by plugins.
        verify(mEntryManager).updateNotificationRanking(any());
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
