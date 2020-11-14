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

import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.app.NotificationChannel;
import android.content.Context;
import android.os.RemoteException;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.notification.collection.NoManSimulator;
import com.android.systemui.statusbar.notification.collection.NoManSimulator.NotifEvent;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceWidgetManagerTest extends SysuiTestCase {
    private static final long MIN_LINGER_DURATION = 5;

    private static final String TEST_PACKAGE_A = "com.test.package_a";
    private static final String TEST_PACKAGE_B = "com.test.package_b";
    private static final String TEST_CHANNEL_ID = "channel_id";
    private static final String TEST_CHANNEL_NAME = "channel_name";
    private static final String TEST_PARENT_CHANNEL_ID = "parent_channel_id";
    private static final String TEST_CONVERSATION_ID = "conversation_id";

    private PeopleSpaceWidgetManager mManager;

    @Mock private NotificationListener mListenerService;
    @Mock private IAppWidgetService mIAppWidgetService;
    @Mock private Context mContext;

    @Captor private ArgumentCaptor<NotificationHandler> mListenerCaptor;

    private final NoManSimulator mNoMan = new NoManSimulator();
    private final FakeSystemClock mClock = new FakeSystemClock();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mManager =
                new PeopleSpaceWidgetManager(mContext);
        mManager.setAppWidgetManager(mIAppWidgetService);
        mManager.attach(mListenerService);

        verify(mListenerService).addNotificationHandler(mListenerCaptor.capture());
        NotificationHandler serviceListener = requireNonNull(mListenerCaptor.getValue());
        mNoMan.addListener(serviceListener);
    }


    @Test
    public void testDoNotNotifyAppWidgetIfNoWidgets() throws RemoteException {
        int[] widgetIdsArray = {};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(
                new NotificationEntryBuilder()
                        .setId(0)
                        .setPkg(TEST_PACKAGE_A));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mIAppWidgetService, times(1)).getAppWidgetIds(any());
        verify(mIAppWidgetService, never()).notifyAppWidgetViewDataChanged(any(), any(), anyInt());

    }

    @Test
    public void testNotifyAppWidgetIfNotificationPosted() throws RemoteException {
        int[] widgetIdsArray = {1};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(
                new NotificationEntryBuilder()
                        .setId(0)
                        .setPkg(TEST_PACKAGE_A));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mIAppWidgetService, times(1)).getAppWidgetIds(any());
        verify(mIAppWidgetService, times(1))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());

    }

    @Test
    public void testNotifyAppWidgetTwiceIfTwoNotificationsPosted() throws RemoteException {
        int[] widgetIdsArray = {1, 2};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif2 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_B)
                .setId(2));

        verify(mIAppWidgetService, times(2)).getAppWidgetIds(any());
        verify(mIAppWidgetService, times(2))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
    }

    @Test
    public void testNotifyAppWidgetTwiceIfNotificationPostedAndRemoved() throws RemoteException {
        int[] widgetIdsArray = {1, 2};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);

        verify(mIAppWidgetService, times(2)).getAppWidgetIds(any());
        verify(mIAppWidgetService, times(2))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
    }

    @Test
    public void testDoNotNotifyAppWidgetIfNonConversationChannelModified() throws RemoteException {
        int[] widgetIdsArray = {1};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                mNoMan.createNotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mIAppWidgetService, never()).getAppWidgetIds(any());
        verify(mIAppWidgetService, never()).notifyAppWidgetViewDataChanged(any(), any(), anyInt());

    }

    @Test
    public void testNotifyAppWidgetIfConversationChannelModified() throws RemoteException {
        int[] widgetIdsArray = {1};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                mNoMan.createNotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME);
        channel.setConversationId(TEST_PARENT_CHANNEL_ID, TEST_CONVERSATION_ID);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mIAppWidgetService, times(1)).getAppWidgetIds(any());
        verify(mIAppWidgetService, times(1))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());

    }
}
