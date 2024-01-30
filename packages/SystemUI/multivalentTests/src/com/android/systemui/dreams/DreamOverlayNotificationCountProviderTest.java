/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamOverlayNotificationCountProviderTest extends SysuiTestCase {
    @Mock
    NotificationListener mNotificationListener;
    @Mock
    DreamOverlayNotificationCountProvider.Callback mCallback;
    @Mock
    StatusBarNotification mNotification1;
    @Mock
    StatusBarNotification mNotification2;
    @Mock
    StatusBarNotification mNotification3;
    @Mock
    NotificationListenerService.RankingMap mRankingMap;

    private DreamOverlayNotificationCountProvider mProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mNotification1.getKey()).thenReturn("key1");
        when(mNotification2.getKey()).thenReturn("key2");
        when(mNotification3.getKey()).thenReturn("key3");
        when(mNotification3.isOngoing()).thenReturn(true);

        final StatusBarNotification[] notifications = {mNotification1};
        when(mNotificationListener.getActiveNotifications()).thenReturn(notifications);
        mProvider = new DreamOverlayNotificationCountProvider(
                mNotificationListener, Runnable::run);
        mProvider.addCallback(mCallback);
    }

    @Test
    public void testPostingNotificationCallsCallbackWithNotificationCount() {
        final ArgumentCaptor<NotificationHandler> handlerArgumentCaptor =
                ArgumentCaptor.forClass(NotificationHandler.class);
        verify(mNotificationListener).addNotificationHandler(handlerArgumentCaptor.capture());
        handlerArgumentCaptor.getValue().onNotificationPosted(mNotification2, mRankingMap);
        verify(mCallback).onNotificationCountChanged(2);
    }

    @Test
    public void testRemovingNotificationCallsCallbackWithZeroNotificationCount() {
        final ArgumentCaptor<NotificationHandler> handlerArgumentCaptor =
                ArgumentCaptor.forClass(NotificationHandler.class);
        verify(mNotificationListener).addNotificationHandler(handlerArgumentCaptor.capture());
        handlerArgumentCaptor.getValue().onNotificationRemoved(mNotification1, mRankingMap);
        verify(mCallback).onNotificationCountChanged(0);
    }

    @Test
    public void testPostingOngoingNotificationDoesNotCallCallbackWithNotificationCount() {
        final ArgumentCaptor<NotificationHandler> handlerArgumentCaptor =
                ArgumentCaptor.forClass(NotificationHandler.class);
        verify(mNotificationListener).addNotificationHandler(handlerArgumentCaptor.capture());
        handlerArgumentCaptor.getValue().onNotificationPosted(mNotification3, mRankingMap);
        verify(mCallback, never()).onNotificationCountChanged(2);
    }
}
