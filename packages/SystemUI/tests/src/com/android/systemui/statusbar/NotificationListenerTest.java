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

import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationListenerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    private NotificationPresenter mPresenter;
    private Handler mHandler;
    private NotificationListener mListener;
    private StatusBarNotification mSbn;
    private NotificationListenerService.RankingMap mRanking;
    private Set<String> mKeysKeptForRemoteInput;
    private NotificationData mNotificationData;
    private NotificationRemoteInputManager mRemoteInputManager;
    private NotificationEntryManager mEntryManager;

    @Before
    public void setUp() {
        mHandler = new Handler(Looper.getMainLooper());
        mPresenter = mock(NotificationPresenter.class);
        mNotificationData = mock(NotificationData.class);
        mRanking = mock(NotificationListenerService.RankingMap.class);
        mRemoteInputManager = mock(NotificationRemoteInputManager.class);
        mEntryManager = mock(NotificationEntryManager.class);
        mKeysKeptForRemoteInput = new HashSet<>();

        when(mPresenter.getHandler()).thenReturn(mHandler);
        when(mPresenter.getEntryManager()).thenReturn(mEntryManager);
        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);
        when(mRemoteInputManager.getKeysKeptForRemoteInput()).thenReturn(mKeysKeptForRemoteInput);

        mListener = new NotificationListener(mRemoteInputManager, mContext);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);

        mListener.setUpWithPresenter(mPresenter);
    }

    @Test
    public void testNotificationAddCallsAddNotification() {
        mListener.onNotificationPosted(mSbn, mRanking);
        waitForIdleSync(mHandler);
        verify(mEntryManager).addNotification(mSbn, mRanking);
    }

    @Test
    public void testPostNotificationRemovesKeyKeptForRemoteInput() {
        mKeysKeptForRemoteInput.add(mSbn.getKey());
        mListener.onNotificationPosted(mSbn, mRanking);
        waitForIdleSync(mHandler);
        assertTrue(mKeysKeptForRemoteInput.isEmpty());
    }

    @Test
    public void testNotificationUpdateCallsUpdateNotification() {
        when(mNotificationData.get(mSbn.getKey())).thenReturn(new NotificationData.Entry(mSbn));
        mListener.onNotificationPosted(mSbn, mRanking);
        waitForIdleSync(mHandler);
        verify(mEntryManager).updateNotification(mSbn, mRanking);
    }

    @Test
    public void testNotificationRemovalCallsRemoveNotification() {
        mListener.onNotificationRemoved(mSbn, mRanking);
        waitForIdleSync(mHandler);
        verify(mEntryManager).removeNotification(mSbn.getKey(), mRanking);
    }

    @Test
    public void testRankingUpdateCallsNotificationRankingUpdate() {
        mListener.onNotificationRankingUpdate(mRanking);
        waitForIdleSync(mHandler);
        // RankingMap may be modified by plugins.
        verify(mEntryManager).updateNotificationRanking(any());
    }
}
