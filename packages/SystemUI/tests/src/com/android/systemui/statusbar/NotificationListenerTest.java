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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class NotificationListenerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationHandler mNotificationHandler;
    @Mock private NotificationManager mNotificationManager;
    @Mock private PluginManager mPluginManager;

    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);
    private NotificationListener mListener;
    private StatusBarNotification mSbn;
    private RankingMap mRanking = new RankingMap(new Ranking[0]);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mListener = new NotificationListener(
                mContext,
                mNotificationManager,
                mFakeSystemClock,
                mFakeExecutor,
                mPluginManager);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);

        mListener.addNotificationHandler(mNotificationHandler);
    }

    @Test
    public void testNotificationAddCallsAddNotification() {
        mListener.onNotificationPosted(mSbn, mRanking);
        mFakeExecutor.runAllReady();
        verify(mNotificationHandler).onNotificationPosted(mSbn, mRanking);
    }

    @Test
    public void testNotificationRemovalCallsRemoveNotification() {
        mListener.onNotificationRemoved(mSbn, mRanking);
        mFakeExecutor.runAllReady();
        verify(mNotificationHandler).onNotificationRemoved(eq(mSbn), eq(mRanking), anyInt());
    }

    @Test
    public void testRankingUpdateCallsNotificationRankingUpdate() {
        mListener.onNotificationRankingUpdate(mRanking);
        assertThat(mFakeExecutor.runAllReady()).isEqualTo(1);
        verify(mNotificationHandler).onNotificationRankingUpdate(eq(mRanking));
    }

    @Test
    public void testRankingUpdateMultipleTimesCallsNotificationRankingUpdateOnce() {
        // GIVEN multiple notification ranking updates
        RankingMap ranking1 = mock(RankingMap.class);
        RankingMap ranking2 = mock(RankingMap.class);
        RankingMap ranking3 = mock(RankingMap.class);
        mListener.onNotificationRankingUpdate(ranking1);
        mListener.onNotificationRankingUpdate(ranking2);
        mListener.onNotificationRankingUpdate(ranking3);

        // WHEN executor runs with multiple updates in the queue
        assertThat(mFakeExecutor.numPending()).isEqualTo(3);
        assertThat(mFakeExecutor.runAllReady()).isEqualTo(3);

        // VERIFY that only the last ranking actually gets handled
        verify(mNotificationHandler, never()).onNotificationRankingUpdate(eq(ranking1));
        verify(mNotificationHandler, never()).onNotificationRankingUpdate(eq(ranking2));
        verify(mNotificationHandler).onNotificationRankingUpdate(eq(ranking3));
        verifyNoMoreInteractions(mNotificationHandler);
    }

    @Test
    public void testRankingUpdateWillCallAgainIfQueueIsSlow() {
        // GIVEN multiple notification ranking updates
        RankingMap ranking1 = mock(RankingMap.class);
        RankingMap ranking2 = mock(RankingMap.class);
        RankingMap ranking3 = mock(RankingMap.class);
        mListener.onNotificationRankingUpdate(ranking1);
        mListener.onNotificationRankingUpdate(ranking2);
        mListener.onNotificationRankingUpdate(ranking3);

        // WHEN executor runs with a 1-second gap between handling events 1 and 2
        assertThat(mFakeExecutor.numPending()).isEqualTo(3);
        assertThat(mFakeExecutor.runNextReady()).isTrue();
        // delay a second, which empties the executor
        mFakeSystemClock.advanceTime(1000);
        assertThat(mFakeExecutor.numPending()).isEqualTo(0);

        // VERIFY that both event 2 and event 3 are called
        verify(mNotificationHandler, never()).onNotificationRankingUpdate(eq(ranking1));
        verify(mNotificationHandler).onNotificationRankingUpdate(eq(ranking2));
        verify(mNotificationHandler).onNotificationRankingUpdate(eq(ranking3));
        verifyNoMoreInteractions(mNotificationHandler);
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
