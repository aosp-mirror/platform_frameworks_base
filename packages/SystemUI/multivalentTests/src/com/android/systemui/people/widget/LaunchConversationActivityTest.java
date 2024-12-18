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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.NotificationListenerService;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubble;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
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
    private NotificationVisibilityProvider mVisibilityProvider;
    @Mock
    private CommonNotifCollection mNotifCollection;
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
    @Mock
    private UserManager mUserManager;
    @Mock
    private CommandQueue mCommandQueue;

    @Captor
    private ArgumentCaptor<NotificationVisibility> mNotificationVisibilityCaptor;
    @Captor
    private ArgumentCaptor<CommandQueue.Callbacks> mCallbacksCaptor;

    private Intent mIntent;

    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mBgExecutor = new FakeExecutor(mFakeSystemClock);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mActivity = new LaunchConversationActivity(
                mVisibilityProvider,
                mNotifCollection,
                Optional.of(mBubblesManager),
                mUserManager,
                mCommandQueue,
                mBgExecutor
        );
        verify(mCommandQueue, times(1)).addCallback(mCallbacksCaptor.capture());

        mActivity.setIsForTesting(true, mIStatusBarService);
        mIntent = new Intent();
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, "tile ID");
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, PACKAGE_NAME);
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_USER_HANDLE, USER_HANDLE);

        when(mNotifCollection.getEntry(NOTIF_KEY)).thenReturn(mNotifEntry);
        when(mNotifCollection.getEntry(NOTIF_KEY_NO_ENTRY)).thenReturn(null);
        when(mNotifCollection.getEntry(NOTIF_KEY_NO_RANKING)).thenReturn(mNotifEntryNoRanking);
        when(mNotifCollection.getEntry(NOTIF_KEY_CAN_BUBBLE)).thenReturn(mNotifEntryCanBubble);
        when(mVisibilityProvider.obtain(anyString(), anyBoolean())).thenAnswer(
                invocation-> {
                    String key = invocation.getArgument(0);
                    boolean visible = invocation.getArgument(1);
                    return NotificationVisibility.obtain(key, NOTIF_RANK, NOTIF_COUNT, visible);
                });
        when(mVisibilityProvider.obtain(any(NotificationEntry.class), anyBoolean())).thenAnswer(
                invocation-> {
                    String key = invocation.<NotificationEntry>getArgument(0).getKey();
                    boolean visible = invocation.getArgument(1);
                    return NotificationVisibility.obtain(key, NOTIF_RANK, NOTIF_COUNT, visible);
                });
        when(mNotifEntry.getRanking()).thenReturn(mRanking);
        when(mNotifEntryCanBubble.getRanking()).thenReturn(mRanking);
        when(mNotifEntryCanBubble.canBubble()).thenReturn(true);
        when(mNotifEntryNoRanking.getRanking()).thenReturn(null);
        when(mUserManager.isQuietModeEnabled(any(UserHandle.class))).thenReturn(false);
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

        assertThat(mActivity.isFinishing()).isTrue();
        mCallbacksCaptor.getValue().appTransitionFinished(DEFAULT_DISPLAY);

        // Ensure callback removed
        verify(mCommandQueue).removeCallback(any());
        // Clear the notification for bubbles.
        FakeExecutor.exhaustExecutors(mBgExecutor);
        verify(mIStatusBarService, times(1)).onNotificationClear(any(),
                anyInt(), any(), anyInt(), anyInt(), mNotificationVisibilityCaptor.capture());
        // Do not select the bubble.
        verify(mBubblesManager, never()).expandStackAndSelectBubble(any(Bubble.class));
        verify(mBubblesManager, never()).expandStackAndSelectBubble(any(NotificationEntry.class));

        NotificationVisibility nv = mNotificationVisibilityCaptor.getValue();
        assertThat(nv.count).isEqualTo(NOTIF_COUNT);
        assertThat(nv.rank).isEqualTo(NOTIF_RANK);
    }

    @Test
    public void testBubbleEntryOpensBubbleAndDoesNotClearNotification() throws Exception {
        when(mBubblesManager.getBubbleWithShortcutId(any())).thenReturn(null);
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                NOTIF_KEY_CAN_BUBBLE);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        assertThat(mActivity.isFinishing()).isTrue();
        mCallbacksCaptor.getValue().appTransitionFinished(DEFAULT_DISPLAY);

        // Ensure callback removed
        verify(mCommandQueue).removeCallback(any());
        // Don't clear the notification for bubbles.
        verify(mIStatusBarService, never()).onNotificationClear(any(),
                anyInt(), any(), anyInt(), anyInt(), any());
        // Select the bubble.
        verify(mBubblesManager, times(1)).getBubbleWithShortcutId(any());
        verify(mBubblesManager, times(1)).expandStackAndSelectBubble(eq(mNotifEntryCanBubble));
    }

    @Test
    public void testQuietModeOpensQuietModeDialog() throws Exception {
        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                NOTIF_KEY);
        when(mUserManager.isQuietModeEnabled(eq(USER_HANDLE))).thenReturn(true);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        assertThat(mActivity.isFinishing()).isTrue();
        mCallbacksCaptor.getValue().appTransitionFinished(DEFAULT_DISPLAY);

        // Ensure callback removed
        verify(mCommandQueue).removeCallback(any());
        // Don't clear the notification for bubbles.
        verify(mIStatusBarService, never()).onNotificationClear(any(),
                anyInt(), any(), anyInt(), anyInt(), any());
        // Do not select the bubble.
        verify(mBubblesManager, never()).expandStackAndSelectBubble(any(Bubble.class));
        verify(mBubblesManager, never()).expandStackAndSelectBubble(any(NotificationEntry.class));
    }


    @Test
    public void testBubbleWithNoNotifOpensBubble() throws Exception {
        Bubble bubble = mock(Bubble.class);
        when(mBubblesManager.getBubbleWithShortcutId(any())).thenReturn(bubble);

        mIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                EMPTY_STRING);
        mActivity.setIntent(mIntent);
        mActivity.onCreate(new Bundle());

        assertThat(mActivity.isFinishing()).isTrue();
        mCallbacksCaptor.getValue().appTransitionFinished(DEFAULT_DISPLAY);

        // Ensure callback removed
        verify(mCommandQueue).removeCallback(any());
        // Select the bubble.
        verify(mBubblesManager, times(1)).expandStackAndSelectBubble(eq(bubble));
    }
}
