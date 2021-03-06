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

import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;

import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.OPTIONS_PEOPLE_SPACE_TILE;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.preference.PreferenceManager;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.PeopleSpaceUtils;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceWidgetManagerTest extends SysuiTestCase {
    private static final long MIN_LINGER_DURATION = 5;

    private static final String TEST_PACKAGE_A = "com.android.systemui.tests";
    private static final String TEST_PACKAGE_B = "com.test.package_b";
    private static final String TEST_CHANNEL_ID = "channel_id";
    private static final String TEST_CHANNEL_NAME = "channel_name";
    private static final String TEST_PARENT_CHANNEL_ID = "parent_channel_id";
    private static final String TEST_CONVERSATION_ID = "conversation_id";
    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int SECOND_WIDGET_ID_WITH_SHORTCUT = 3;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final String SHORTCUT_ID = "101";
    private static final String OTHER_SHORTCUT_ID = "102";
    private static final String NOTIFICATION_KEY = "0|com.android.systemui.tests|0|null|0";
    private static final String NOTIFICATION_CONTENT = "message text";
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Icon ICON = Icon.createWithResource("package", R.drawable.ic_android);
    private static final String KEY = PeopleSpaceUtils.getKey(SHORTCUT_ID, TEST_PACKAGE_A, 0);
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
            .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID, "username", ICON, new Intent())
                    .setPackageName(TEST_PACKAGE_A)
                    .setUserHandle(new UserHandle(1))
                    .setNotificationKey(NOTIFICATION_KEY + "1")
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(URI)
                    .build();
    private final ShortcutInfo mShortcutInfo = new ShortcutInfo.Builder(mContext,
            SHORTCUT_ID).setLongLabel("name").build();

    private PeopleSpaceWidgetManager mManager;

    @Mock
    private NotificationListener mListenerService;

    @Mock
    private AppWidgetManager mAppWidgetManager;
    @Mock
    private IPeopleManager mIPeopleManager;
    @Mock
    private PeopleManager mPeopleManager;
    @Mock
    private LauncherApps mLauncherApps;

    @Captor
    private ArgumentCaptor<NotificationHandler> mListenerCaptor;
    @Captor
    private ArgumentCaptor<Bundle> mBundleArgumentCaptor;

    private final NoManSimulator mNoMan = new NoManSimulator();
    private final FakeSystemClock mClock = new FakeSystemClock();

    private PeopleSpaceWidgetProvider mProvider;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLauncherApps = mock(LauncherApps.class);
        mManager =
                new PeopleSpaceWidgetManager(mContext);
        mManager.setAppWidgetManager(mAppWidgetManager, mIPeopleManager, mPeopleManager,
                mLauncherApps);
        mManager.attach(mListenerService);
        mProvider = new PeopleSpaceWidgetProvider();
        mProvider.setPeopleSpaceWidgetManager(mManager);

        verify(mListenerService).addNotificationHandler(mListenerCaptor.capture());
        NotificationHandler serviceListener = requireNonNull(mListenerCaptor.getValue());
        mNoMan.addListener(serviceListener);
        // Default to single People tile widgets.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);

        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, WIDGET_ID_WITH_SHORTCUT);

        Bundle options = new Bundle();
        options.putParcelable(PeopleSpaceUtils.OPTIONS_PEOPLE_SPACE_TILE, PERSON_TILE);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(options);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());
        when(mIPeopleManager.getConversation(TEST_PACKAGE_A, 0, SHORTCUT_ID)).thenReturn(
                getConversationWithShortcutId(SHORTCUT_ID));
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoWidgets() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoShortcutInfo() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        Notification notificationWithoutShortcut = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .addMessage(new Notification.MessagingStyle.Message("text3", 10, PERSON))
                )
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(new SbnBuilder()
                        .setNotification(notificationWithoutShortcut)
                        .setPkg(TEST_PACKAGE_A)
                        .build())
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoPackage() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithoutPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false))
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithoutPackageName)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNonConversationChannelModified() throws Exception {
        int[] widgetIdsArray = {1};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testUpdateAppWidgetIfConversationChannelModified() throws Exception {
        int[] widgetIdsArray = {1};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);
        channel.setConversationId(TEST_PARENT_CHANNEL_ID, TEST_CONVERSATION_ID);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfDifferentPackageName() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithDifferentPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false))
                .setPkg(TEST_PACKAGE_B)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithDifferentPackageName)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfDifferentPackageName() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithDifferentPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setPkg(TEST_PACKAGE_B)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithDifferentPackageName)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateStatusPostedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        ConversationStatus status1 = new ConversationStatus.Builder(OTHER_SHORTCUT_ID,
                ACTIVITY_GAME).setDescription("Playing a game!").build();
        ConversationStatus status2 = new ConversationStatus.Builder(OTHER_SHORTCUT_ID,
                ACTIVITY_BIRTHDAY).build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(OTHER_SHORTCUT_ID,
                Arrays.asList(status1, status2));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testUpdateStatusPostedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        ConversationStatus status = new ConversationStatus.Builder(SHORTCUT_ID,
                ACTIVITY_GAME).setDescription("Playing a game!").build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(SHORTCUT_ID,
                Arrays.asList(status));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        assertThat(tile.getStatuses()).containsExactly(status);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateStatusPostedOnTwoExistingTiles() throws Exception {
        addSecondWidgetForPersonTile();

        ConversationStatus status = new ConversationStatus.Builder(SHORTCUT_ID,
                ACTIVITY_ANNIVERSARY).build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(SHORTCUT_ID,
                Arrays.asList(status));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationPostedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationPostedOnTwoExistingTiles() throws Exception {
        addSecondWidgetForPersonTile();

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationOnExistingTileAfterRemovingTileForSamePerson()
            throws Exception {
        addSecondWidgetForPersonTile();

        PeopleSpaceUtils.removeStorageForTile(mContext, KEY, SECOND_WIDGET_ID_WITH_SHORTCUT);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, never()).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithoutContentPostedIfExistingTile()
            throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, WIDGET_ID_WITH_SHORTCUT);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());

        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent())
                .isEqualTo(mContext.getString(R.string.missed_call));
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfExistingTile()
            throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, WIDGET_ID_WITH_SHORTCUT);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());

        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationRemovedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(2)).updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(null);
        assertThat(tile.getNotificationContent()).isEqualTo(null);
        assertThat(tile.getNotificationDataUri()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDeleteAllWidgetsForConversationsUncachesShortcutAndRemovesListeners() {
        addSecondWidgetForPersonTile();
        mProvider.onUpdate(mContext, mAppWidgetManager,
                new int[]{WIDGET_ID_WITH_SHORTCUT, SECOND_WIDGET_ID_WITH_SHORTCUT});

        // Delete only one widget for the conversation.
        mManager.deleteWidgets(new int[]{WIDGET_ID_WITH_SHORTCUT});

        // Check deleted storage.
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, null)).isNull();
        assertThat(widgetSp.getString(SHORTCUT_ID, null)).isNull();
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getStringSet(KEY, new HashSet<>())).containsExactly(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT));
        // Check listener & shortcut caching remain for other widget.
        verify(mPeopleManager, never()).unregisterConversationListener(any());
        verify(mLauncherApps, never()).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));

        // Delete all widgets for the conversation.
        mProvider.onDeleted(mContext, new int[]{SECOND_WIDGET_ID_WITH_SHORTCUT});

        // Check deleted storage.
        SharedPreferences secondWidgetSp = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(secondWidgetSp.getString(PACKAGE_NAME, null)).isNull();
        assertThat(secondWidgetSp.getString(SHORTCUT_ID, null)).isNull();
        assertThat(secondWidgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        assertThat(sp.getStringSet(KEY, new HashSet<>())).isEmpty();
        // Check listener is removed and shortcut is uncached.
        verify(mPeopleManager, times(1)).unregisterConversationListener(any());
        verify(mLauncherApps, times(1)).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));
    }

    /**
     * Adds another widget for {@code PERSON_TILE} with widget ID: {@code
     * SECOND_WIDGET_ID_WITH_SHORTCUT}.
     */
    private void addSecondWidgetForPersonTile() {
        Bundle options = new Bundle();
        options.putParcelable(PeopleSpaceUtils.OPTIONS_PEOPLE_SPACE_TILE, PERSON_TILE);
        when(mAppWidgetManager.getAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(options);
        // Set the same Person associated on another People Tile widget ID.
        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, WIDGET_ID_WITH_SHORTCUT);
        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, SECOND_WIDGET_ID_WITH_SHORTCUT);
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT,
                SECOND_WIDGET_ID_WITH_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
    }

    /**
     * Returns a single conversation associated with {@code shortcutId}.
     */
    private ConversationChannel getConversationWithShortcutId(String shortcutId) throws Exception {
        return getConversationWithShortcutId(shortcutId, Arrays.asList());
    }

    /**
     * Returns a single conversation associated with {@code shortcutId} and {@code statuses}.
     */
    private ConversationChannel getConversationWithShortcutId(String shortcutId,
            List<ConversationStatus> statuses) throws Exception {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                0L, false, false, statuses);
        return convo;
    }

    private Notification createMessagingStyleNotification(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(shortcutId);
        if (isMessagingStyle) {
            builder.setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(
                            new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT, 10,
                                    PERSON))
            );
        }
        if (isMissedCall) {
            builder.setCategory(CATEGORY_MISSED_CALL);
        }
        return builder.build();
    }

    private StatusBarNotification createNotification(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Notification notification = createMessagingStyleNotification(
                shortcutId, isMessagingStyle, isMissedCall);
        return new SbnBuilder()
                .setNotification(notification)
                .setPkg(TEST_PACKAGE_A)
                .build();
    }

    private void setStorageForTile(String shortcutId, String packageName, int widgetId) {
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetSp.edit();
        widgetEditor.putString(PeopleSpaceUtils.PACKAGE_NAME, packageName);
        widgetEditor.putString(PeopleSpaceUtils.SHORTCUT_ID, shortcutId);
        widgetEditor.putInt(PeopleSpaceUtils.USER_ID, 0);
        widgetEditor.apply();

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(String.valueOf(widgetId), shortcutId);
        String key = PeopleSpaceUtils.getKey(shortcutId, packageName, 0);
        Set<String> storedWidgetIds = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
        storedWidgetIds.add(String.valueOf(widgetId));
        editor.putStringSet(key, storedWidgetIds);
        editor.apply();
    }
}
