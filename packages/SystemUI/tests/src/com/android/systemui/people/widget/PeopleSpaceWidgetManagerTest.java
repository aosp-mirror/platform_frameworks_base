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

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static com.android.systemui.people.PeopleSpaceUtils.OPTIONS_PEOPLE_SPACE_TILE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;
import androidx.test.filters.SmallTest;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.SbnBuilder;
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

import java.util.ArrayList;
import java.util.List;

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
    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final String SHORTCUT_ID = "101";
    private static final String OTHER_SHORTCUT_ID = "102";
    private static final String NOTIFICATION_KEY = "notification_key";
    private static final String NOTIFICATION_CONTENT = "notification_content";
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Icon ICON = Icon.createWithResource("package", R.drawable.ic_android);
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
            .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID, "username", ICON, new Intent())
                    .setNotificationKey(NOTIFICATION_KEY)
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(URI)
                    .build();

    private PeopleSpaceWidgetManager mManager;

    @Mock
    private NotificationListener mListenerService;
    @Mock
    private IAppWidgetService mIAppWidgetService;
    @Mock
    private AppWidgetManager mAppWidgetManager;
    @Mock
    private INotificationManager mINotificationManager;

    @Captor
    private ArgumentCaptor<NotificationHandler> mListenerCaptor;

    private final NoManSimulator mNoMan = new NoManSimulator();
    private final FakeSystemClock mClock = new FakeSystemClock();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mManager =
                new PeopleSpaceWidgetManager(mContext);
        mManager.setAppWidgetManager(mIAppWidgetService, mAppWidgetManager, mINotificationManager);
        mManager.attach(mListenerService);

        verify(mListenerService).addNotificationHandler(mListenerCaptor.capture());
        NotificationHandler serviceListener = requireNonNull(mListenerCaptor.getValue());
        mNoMan.addListener(serviceListener);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 2);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(String.valueOf(WIDGET_ID_WITH_SHORTCUT), SHORTCUT_ID);
        editor.apply();
        Bundle options = new Bundle();
        options.putParcelable(OPTIONS_PEOPLE_SPACE_TILE, PERSON_TILE);

        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(options);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());
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

        verify(mIAppWidgetService, never()).notifyAppWidgetViewDataChanged(any(), any(), anyInt());
    }

    @Test
    public void testDoNotNotifySingleConversationAppWidgetIfNoWidgets() throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        int[] widgetIdsArray = {};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(
                new NotificationEntryBuilder()
                        .setId(0)
                        .setPkg(TEST_PACKAGE_A));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(), any(RemoteViews.class));
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

        verify(mIAppWidgetService, times(1))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
        verify(mIAppWidgetService, never()).updateAppWidgetIds(any(), any(),
                any(RemoteViews.class));
    }

    @Test
    public void testNotifySingleConversationAppWidgetOnceIfNotificationPosted()
            throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1));

        verify(mIAppWidgetService, never())
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any(RemoteViews.class));
        verify(mAppWidgetManager, never()).updateAppWidget(eq(WIDGET_ID_WITHOUT_SHORTCUT),
                any(RemoteViews.class));
    }

    @Test
    public void testNotifySingleConversationAppWidgetTwiceIfTwoNotificationsPosted()
            throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif2 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_B)
                .setId(2));

        verify(mIAppWidgetService, never())
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any(RemoteViews.class));
        verify(mAppWidgetManager, never()).updateAppWidget(eq(WIDGET_ID_WITHOUT_SHORTCUT),
                any(RemoteViews.class));
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

        verify(mIAppWidgetService, times(2))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any(RemoteViews.class));
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

        verify(mIAppWidgetService, times(2))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any(RemoteViews.class));
    }

    @Test
    public void testDoNotNotifyAppWidgetIfNonConversationChannelModified() throws RemoteException {
        int[] widgetIdsArray = {1};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mIAppWidgetService, never()).notifyAppWidgetViewDataChanged(any(), any(), anyInt());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any(RemoteViews.class));
    }

    @Test
    public void testNotifyAppWidgetIfConversationChannelModified() throws RemoteException {
        int[] widgetIdsArray = {1};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);
        channel.setConversationId(TEST_PARENT_CHANNEL_ID, TEST_CONVERSATION_ID);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mIAppWidgetService, times(1))
                .notifyAppWidgetViewDataChanged(any(), eq(widgetIdsArray), anyInt());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any(RemoteViews.class));
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfNoExistingTile() throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createConversationNotification(OTHER_SHORTCUT_ID);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT), any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfNoExistingTile() throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createConversationNotification(OTHER_SHORTCUT_ID);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
    }

    @Test
    public void testUpdateNotificationPostedIfExistingTile() throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createConversationNotification(SHORTCUT_ID);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT), any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedWithoutMessagesIfExistingTile()
            throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        Notification notification = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID)
                .build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notification)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT), any());
    }

    @Test
    public void testUpdateNotificationRemovedIfExistingTile() throws RemoteException {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        when(mINotificationManager.getConversations(true)).thenReturn(
                new ParceledListSlice(getConversationWithShortcutId()));
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createConversationNotification(SHORTCUT_ID);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setPkg(TEST_PACKAGE_A)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(2))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT), any());
    }

    /** Returns a list of a single conversation associated with {@code SHORTCUT_ID}. */
    private List<ConversationChannelWrapper> getConversationWithShortcutId() {
        List<ConversationChannelWrapper> convos = new ArrayList<>();
        ConversationChannelWrapper convo1 = new ConversationChannelWrapper();
        convo1.setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID).setLongLabel(
                "name").build());
        convos.add(convo1);
        return convos;
    }

    private StatusBarNotification createConversationNotification(String shortcutId) {
        Notification notification = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(shortcutId)
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .addMessage(new Notification.MessagingStyle.Message("text3", 10, PERSON))
                )
                .build();
        return new SbnBuilder()
                .setNotification(notification)
                .build();
    }
}
