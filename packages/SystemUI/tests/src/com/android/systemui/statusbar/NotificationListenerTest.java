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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

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

    private NotificationListener mListener;
    private StatusBarNotification mSbn;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationRemoteInputManager.class,
                mRemoteInputManager);

        when(mPresenter.getHandler()).thenReturn(Handler.createAsync(Looper.myLooper()));
        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);

        mListener = new NotificationListener(mContext);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);

        mListener.setUpWithPresenter(mPresenter, mEntryManager);
    }

    @Test
    public void testNotificationAddCallsAddNotification() {
        mListener.onNotificationPosted(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).addNotification(mSbn, mRanking);
    }

    @Test
    public void testPostNotificationRemovesKeyKeptForRemoteInput() {
        mListener.onNotificationPosted(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).removeKeyKeptForRemoteInput(mSbn.getKey());
    }

    @Test
    public void testNotificationUpdateCallsUpdateNotification() {
        when(mNotificationData.get(mSbn.getKey())).thenReturn(new NotificationData.Entry(mSbn));
        mListener.onNotificationPosted(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).updateNotification(mSbn, mRanking);
    }

    @Test
    public void testNotificationRemovalCallsRemoveNotification() {
        mListener.onNotificationRemoved(mSbn, mRanking);
        TestableLooper.get(this).processAllMessages();
        verify(mEntryManager).removeNotification(mSbn.getKey(), mRanking);
    }

    @Test
    public void testRankingUpdateCallsNotificationRankingUpdate() {
        mListener.onNotificationRankingUpdate(mRanking);
        TestableLooper.get(this).processAllMessages();
        // RankingMap may be modified by plugins.
        verify(mEntryManager).updateNotificationRanking(any());
    }
}
