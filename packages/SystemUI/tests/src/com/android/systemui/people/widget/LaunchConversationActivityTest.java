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

package com.android.systemui.people.widget;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.wmshell.BubblesManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class LaunchConversationActivityTest extends SysuiTestCase {
    private static final String EMPTY_STRING = "";
    private static final String PACKAGE_NAME = "com.android.systemui.tests";
    private static final String NOTIF_KEY = "notifKey";
    private static final String NOTIF_KEY_NO_ENTRY = "notifKeyNoEntry";
    private static final String NOTIF_KEY_NO_RANKING = "notifKeyNoRanking";
    private static final String NOTIF_KEY_CAN_BUBBLE = "notifKeyCanBubble";

    private static final UserHandle USER_HANDLE = UserHandle.of(0);
    private static final int NOTIF_COUNT = 10;
    private static final int NOTIF_RANK = 2;

    private LaunchConversationActivity mActivity;

    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private IStatusBarService mIStatusBarService;
    @Mock
    private NotificationEntry mNotifEntry;
    @Mock
    private NotificationEntry mNotifEntryNoRanking;
    @Mock
    private NotificationEntry mNotifEntryCanBubble;
    @Mock
    private BubblesManager mBubblesManager;
    @Mock
    private NotificationListenerService.Ranking mRanking;

    @Captor
    private ArgumentCaptor<NotificationVisibility> mNotificationVisibilityCaptor;

    private Intent mIntent;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mActivity = new LaunchConversationActivity(mNotificationEntryManager,
                Optional.of(mBubblesManager));
        mActivity.setIsForTesting(true, mIStatusBarService);
        mIntent = new Intent();
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, "tile ID");
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, PACKAGE_NAME);
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_USER_HANDLE, USER_HANDLE);

        when(mNotificationEntryManager.getActiveNotificationsCount()).thenReturn(NOTIF_COUNT);
        when(mNotificationEntryManager.getPendingOrActiveNotif(NOTIF_KEY)).thenReturn(mNotifEntry);
        when(mNotificationEntryManager.getPendingOrActiveNotif(NOTIF_KEY_NO_ENTRY))
                .thenReturn(null);
        when(mNotificationEntryManager.getPendingOrActiveNotif(NOTIF_KEY_NO_RANKING))
                .thenReturn(mNotifEntryNoRanking);
        when(mNotificationEntryManager.getPendingOrActiveNotif(NOTIF_KEY_CAN_BUBBLE))
                .thenReturn(mNotifEntryCanBubble);
        when(mNotifEntry.getRanking()).thenReturn(mRanking);
        when(mNotifEntryCanBubble.getRanking()).thenReturn(mRanking);
        when(mNotifEntryCanBubble.canBubble()).thenReturn(true);
        when(mNotifEntryNoRanking.getRanking()).thenReturn(null);
        when(mRanking.getRank()).thenReturn(NOTIF_RANK);
    }

    @Test
    public void testDoNotClearNotificationIfNoKey() throws Exception {
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                EMPTY_STRING);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        verify(mIStatusBarService, never()).onNotificationClear(
                any(), anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testDoNotClearNotificationIfNoNotificationEntry() throws Exception {
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                NOTIF_KEY_NO_ENTRY);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        verify(mIStatusBarService, never()).onNotificationClear(
                any(), anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testDoNotClearNotificationIfNoRanking() throws Exception {
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                NOTIF_KEY_NO_RANKING);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        verify(mIStatusBarService, never()).onNotificationClear(
                any(), anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testEntryClearsNotificationAndDoesNotOpenBubble() throws Exception {
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                NOTIF_KEY);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        verify(mIStatusBarService, times(1)).onNotificationClear(any(),
                anyInt(), any(), anyInt(), anyInt(), mNotificationVisibilityCaptor.capture());
        verify(mBubblesManager, never()).expandStackAndSelectBubble(any());

        NotificationVisibility nv = mNotificationVisibilityCaptor.getValue();
        assertThat(nv.count).isEqualTo(NOTIF_COUNT);
        assertThat(nv.rank).isEqualTo(NOTIF_RANK);
    }

    @Test
    public void testBubbleEntryOpensBubbleAndDoesNotClearNotification() throws Exception {
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                NOTIF_KEY_CAN_BUBBLE);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        // Don't clear the notification for bubbles.
        verify(mIStatusBarService, never()).onNotificationClear(any(),
                anyInt(), any(), anyInt(), anyInt(), any());
        verify(mBubblesManager, times(1)).expandStackAndSelectBubble(eq(mNotifEntryCanBubble));
    }
}
