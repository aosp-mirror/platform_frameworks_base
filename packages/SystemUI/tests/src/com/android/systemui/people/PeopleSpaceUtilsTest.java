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

package com.android.systemui.people;

import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.app.people.ConversationStatus.ACTIVITY_NEW_STORY;
import static android.app.people.ConversationStatus.AVAILABILITY_AVAILABLE;

import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.getPeopleTileFromPersistentStorage;
import static com.android.systemui.people.widget.AppWidgetOptionsHelper.OPTIONS_PEOPLE_TILE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleTileKey;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceUtilsTest extends SysuiTestCase {

    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final String SHORTCUT_ID_1 = "101";
    private static final String SHORTCUT_ID_2 = "202";
    private static final String SHORTCUT_ID_3 = "303";
    private static final String SHORTCUT_ID_4 = "404";
    private static final String NOTIFICATION_KEY = "notification_key";
    private static final String NOTIFICATION_CONTENT = "notification_content";
    private static final String TEST_LOOKUP_KEY = "lookup_key";
    private static final String NOTIFICATION_TEXT_1 = "notification_text_1";
    private static final String NOTIFICATION_TEXT_2 = "notification_text_2";
    private static final String NOTIFICATION_TEXT_3 = "notification_text_3";
    private static final String NOTIFICATION_TEXT_4 = "notification_text_4";
    private static final int TEST_COLUMN_INDEX = 1;
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Icon ICON = Icon.createWithResource("package", R.drawable.ic_android);
    private static final String GAME_DESCRIPTION = "Playing a game!";
    private static final CharSequence MISSED_CALL = "Custom missed call message";
    private static final String NAME = "username";
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
            .build();
    private static final PeopleSpaceTile PERSON_TILE_WITHOUT_NOTIFICATION =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID_1, NAME, ICON, new Intent())
                    .setLastInteractionTimestamp(0L)
                    .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID_1, NAME, ICON, new Intent())
                    .setLastInteractionTimestamp(123L)
                    .setNotificationKey(NOTIFICATION_KEY)
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(URI)
                    .build();
    private static final ConversationStatus GAME_STATUS =
            new ConversationStatus
                    .Builder(PERSON_TILE.getId(), ACTIVITY_GAME)
                    .setDescription(GAME_DESCRIPTION)
                    .build();
    private static final ConversationStatus NEW_STORY_WITH_AVAILABILITY =
            new ConversationStatus
                    .Builder(PERSON_TILE.getId(), ACTIVITY_NEW_STORY)
                    .setAvailability(AVAILABILITY_AVAILABLE)
                    .build();

    private final ShortcutInfo mShortcutInfo = new ShortcutInfo.Builder(mContext,
            SHORTCUT_ID_1).setLongLabel(
            NAME).setPerson(PERSON)
            .build();
    private final ShortcutInfo mShortcutInfoWithoutPerson = new ShortcutInfo.Builder(mContext,
            SHORTCUT_ID_1).setLongLabel(
            NAME)
            .build();
    private final Notification mNotification1 = new Notification.Builder(mContext, "test")
            .setContentTitle("TEST_TITLE")
            .setContentText("TEST_TEXT")
            .setShortcutId(SHORTCUT_ID_1)
            .setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(new Notification.MessagingStyle.Message(
                            NOTIFICATION_TEXT_1, 0, PERSON))
                    .addMessage(new Notification.MessagingStyle.Message(
                            NOTIFICATION_TEXT_2, 20, PERSON))
                    .addMessage(new Notification.MessagingStyle.Message(
                            NOTIFICATION_TEXT_3, 10, PERSON))
            )
            .build();
    private final Notification mNotification2 = new Notification.Builder(mContext, "test2")
            .setContentTitle("TEST_TITLE")
            .setContentText("OTHER_TEXT")
            .setShortcutId(SHORTCUT_ID_2)
            .setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(new Notification.MessagingStyle.Message(
                            NOTIFICATION_TEXT_4, 0, PERSON))
            )
            .build();
    private final Notification mNotification3 = new Notification.Builder(mContext, "test2")
            .setContentTitle("TEST_TITLE")
            .setContentText("OTHER_TEXT")
            .setShortcutId(SHORTCUT_ID_3)
            .setStyle(new Notification.MessagingStyle(PERSON))
            .build();
    private final NotificationEntry mNotificationEntry1 = new NotificationEntryBuilder()
            .setNotification(mNotification1)
            .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_1).build())
            .setUser(UserHandle.of(0))
            .setPkg(PACKAGE_NAME)
            .build();
    private final NotificationEntry mNotificationEntry2 = new NotificationEntryBuilder()
            .setNotification(mNotification2)
            .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_2).build())
            .setUser(UserHandle.of(0))
            .setPkg(PACKAGE_NAME)
            .build();
    private final NotificationEntry mNotificationEntry3 = new NotificationEntryBuilder()
            .setNotification(mNotification3)
            .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_3).build())
            .setUser(UserHandle.of(0))
            .setPkg(PACKAGE_NAME)
            .build();

    @Mock
    private NotificationListener mListenerService;
    @Mock
    private INotificationManager mNotificationManager;
    @Mock
    private IPeopleManager mPeopleManager;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private IAppWidgetService mIAppWidgetService;
    @Mock
    private AppWidgetManager mAppWidgetManager;
    @Mock
    private Cursor mMockCursor;
    @Mock
    private ContentResolver mMockContentResolver;
    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private NotificationEntryManager mNotificationEntryManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);

        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        Bundle options = new Bundle();
        options.putParcelable(OPTIONS_PEOPLE_TILE, PERSON_TILE);

        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(options);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());

        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContentResolver.query(any(Uri.class), any(), anyString(), any(),
                isNull())).thenReturn(mMockCursor);
        when(mMockContext.getString(R.string.birthday_status)).thenReturn(
                mContext.getString(R.string.birthday_status));
        when(mMockContext.getString(R.string.basic_status)).thenReturn(
                mContext.getString(R.string.basic_status));
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockContext.getString(R.string.over_timestamp)).thenReturn(
                mContext.getString(R.string.over_timestamp));
        when(mPackageManager.getApplicationIcon(anyString())).thenReturn(null);
        when(mNotificationEntryManager.getVisibleNotifications())
                .thenReturn(List.of(mNotificationEntry1, mNotificationEntry2, mNotificationEntry3));
    }

    @Test
    public void testGetTilesReturnsSortedListWithMultipleRecentConversations() throws Exception {
        // Ensure the less-recent Important conversation is before more recent conversations.
        ConversationChannelWrapper newerNonImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID_1, false, 3);
        ConversationChannelWrapper olderImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID_1 + 1,
                true, 1);
        when(mNotificationManager.getConversations(anyBoolean())).thenReturn(
                new ParceledListSlice(Arrays.asList(
                        newerNonImportantConversation, olderImportantConversation)));

        // Ensure the non-Important conversation is sorted between these recent conversations.
        ConversationChannel recentConversationBeforeNonImportantConversation =
                getConversationChannel(
                        SHORTCUT_ID_1 + 2, 4);
        ConversationChannel recentConversationAfterNonImportantConversation =
                getConversationChannel(SHORTCUT_ID_1 + 3,
                        2);
        when(mPeopleManager.getRecentConversations()).thenReturn(
                new ParceledListSlice(Arrays.asList(recentConversationAfterNonImportantConversation,
                        recentConversationBeforeNonImportantConversation)));

        List<String> orderedShortcutIds = PeopleSpaceUtils.getTiles(
                mContext, mNotificationManager, mPeopleManager,
                mLauncherApps, mNotificationEntryManager)
                .stream().map(tile -> tile.getId()).collect(Collectors.toList());

        assertThat(orderedShortcutIds).containsExactly(
                // Even though the oldest conversation, should be first since "important"
                olderImportantConversation.getShortcutInfo().getId(),
                // Non-priority conversations should be sorted within recent conversations.
                recentConversationBeforeNonImportantConversation.getShortcutInfo().getId(),
                newerNonImportantConversation.getShortcutInfo().getId(),
                recentConversationAfterNonImportantConversation.getShortcutInfo().getId())
                .inOrder();
    }

    @Test
    public void testGetTilesReturnsSortedListWithMultipleImportantAndRecentConversations()
            throws Exception {
        // Ensure the less-recent Important conversation is before more recent conversations.
        ConversationChannelWrapper newerNonImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID_1, false, 3);
        ConversationChannelWrapper newerImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID_1 + 1, true, 3);
        ConversationChannelWrapper olderImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID_1 + 2,
                true, 1);
        when(mNotificationManager.getConversations(anyBoolean())).thenReturn(
                new ParceledListSlice(Arrays.asList(
                        newerNonImportantConversation, newerImportantConversation,
                        olderImportantConversation)));

        // Ensure the non-Important conversation is sorted between these recent conversations.
        ConversationChannel recentConversationBeforeNonImportantConversation =
                getConversationChannel(
                        SHORTCUT_ID_1 + 3, 4);
        ConversationChannel recentConversationAfterNonImportantConversation =
                getConversationChannel(SHORTCUT_ID_1 + 4,
                        2);
        when(mPeopleManager.getRecentConversations()).thenReturn(
                new ParceledListSlice(Arrays.asList(recentConversationAfterNonImportantConversation,
                        recentConversationBeforeNonImportantConversation)));

        List<String> orderedShortcutIds = PeopleSpaceUtils.getTiles(
                mContext, mNotificationManager, mPeopleManager,
                mLauncherApps, mNotificationEntryManager)
                .stream().map(tile -> tile.getId()).collect(Collectors.toList());

        assertThat(orderedShortcutIds).containsExactly(
                // Important conversations should be sorted at the beginning.
                newerImportantConversation.getShortcutInfo().getId(),
                olderImportantConversation.getShortcutInfo().getId(),
                // Non-priority conversations should be sorted within recent conversations.
                recentConversationBeforeNonImportantConversation.getShortcutInfo().getId(),
                newerNonImportantConversation.getShortcutInfo().getId(),
                recentConversationAfterNonImportantConversation.getShortcutInfo().getId())
                .inOrder();
    }

    @Test
    public void testGetLastMessagingStyleMessageNoMessage() {
        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID_1)
                .build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notification)
                .build();

        Notification.MessagingStyle.Message lastMessage =
                PeopleSpaceUtils.getLastMessagingStyleMessage(sbn.getNotification());

        assertThat(lastMessage).isNull();
    }

    @Test
    public void testGetBackgroundTextFromMessageNoPunctuation() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("test");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetBackgroundTextFromMessageSingleExclamation() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("test!");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetBackgroundTextFromMessageSingleQuestion() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("?test");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetBackgroundTextFromMessageSeparatedMarks() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("test! right!");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetBackgroundTextFromMessageDoubleExclamation() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("!!test");

        assertThat(backgroundText).isEqualTo("!");
    }

    @Test
    public void testGetBackgroundTextFromMessageDoubleQuestion() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("test??");

        assertThat(backgroundText).isEqualTo("?");
    }

    @Test
    public void testGetBackgroundTextFromMessageMixed() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage("test?!");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetBackgroundTextFromMessageMixedInTheMiddle() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage(
                "test!? in the middle");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetBackgroundTextFromMessageMixedDifferentOrder() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage(
                "test!? in the middle");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetBackgroundTextFromMessageMultiple() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage(
                "test!?!!? in the middle");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetBackgroundTextFromMessageQuestionFirst() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage(
                "test?? in the middle!!");

        assertThat(backgroundText).isEqualTo("?");
    }

    @Test
    public void testGetBackgroundTextFromMessageExclamationFirst() {
        String backgroundText = PeopleSpaceUtils.getBackgroundTextFromMessage(
                "test!! in the middle??");

        assertThat(backgroundText).isEqualTo("!");
    }

    @Test
    public void testGetLastMessagingStyleMessage() {
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(mNotification1)
                .build();

        Notification.MessagingStyle.Message lastMessage =
                PeopleSpaceUtils.getLastMessagingStyleMessage(sbn.getNotification());

        assertThat(lastMessage.getText().toString()).isEqualTo(NOTIFICATION_TEXT_2);
    }

    @Test
    public void testAugmentTileFromNotification() {
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(mNotification1)
                .build();

        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_1, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromNotification(mContext, tile, sbn);

        assertThat(actual.getNotificationContent().toString()).isEqualTo(NOTIFICATION_TEXT_2);
    }

    @Test
    public void testAugmentTileFromNotificationNoContent() {
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(mNotification3)
                .build();

        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_3, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromNotification(mContext, tile, sbn);

        assertThat(actual.getNotificationContent()).isEqualTo(null);
    }

    @Test
    public void testAugmentTileFromVisibleNotifications() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_1, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromVisibleNotifications(mContext, tile,
                        Map.of(new PeopleTileKey(mNotificationEntry1), mNotificationEntry1));

        assertThat(actual.getNotificationContent().toString()).isEqualTo(NOTIFICATION_TEXT_2);
    }

    @Test
    public void testAugmentTileFromVisibleNotificationsDifferentShortcutId() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_4, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromVisibleNotifications(mContext, tile,
                        Map.of(new PeopleTileKey(mNotificationEntry1), mNotificationEntry1));

        assertThat(actual.getNotificationContent()).isEqualTo(null);
    }

    @Test
    public void testAugmentTilesFromVisibleNotificationsSingleTile() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_1, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        List<PeopleSpaceTile> actualList = PeopleSpaceUtils
                .augmentTilesFromVisibleNotifications(
                        mContext, List.of(tile), mNotificationEntryManager);

        assertThat(actualList.size()).isEqualTo(1);
        assertThat(actualList.get(0).getNotificationContent().toString())
                .isEqualTo(NOTIFICATION_TEXT_2);

        verify(mNotificationEntryManager, times(1)).getVisibleNotifications();
    }

    @Test
    public void testAugmentTilesFromVisibleNotificationsMultipleTiles() {
        PeopleSpaceTile tile1 =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_1, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleSpaceTile tile2 =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_2, "userName2", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        List<PeopleSpaceTile> actualList = PeopleSpaceUtils
                .augmentTilesFromVisibleNotifications(mContext, List.of(tile1, tile2),
                        mNotificationEntryManager);

        assertThat(actualList.size()).isEqualTo(2);
        assertThat(actualList.get(0).getNotificationContent().toString())
                .isEqualTo(NOTIFICATION_TEXT_2);
        assertThat(actualList.get(1).getNotificationContent().toString())
                .isEqualTo(NOTIFICATION_TEXT_4);

        verify(mNotificationEntryManager, times(1)).getVisibleNotifications();
    }

    @Test
    public void testDoNotUpdateSingleConversationAppWidgetWhenNotBirthday() {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mMockCursor.moveToNext()).thenReturn(true, false);
        when(mMockCursor.getString(eq(TEST_COLUMN_INDEX))).thenReturn(TEST_LOOKUP_KEY);
        when(mMockCursor.getColumnIndex(eq(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY)
        )).thenReturn(TEST_COLUMN_INDEX);

        // Existing tile does not have birthday status.
        Map<Integer, PeopleSpaceTile> widgetIdToTile = Map.of(WIDGET_ID_WITH_SHORTCUT,
                new PeopleSpaceTile.Builder(mShortcutInfoWithoutPerson,
                        mContext.getSystemService(LauncherApps.class)).build());
        PeopleSpaceUtils.getBirthdays(mMockContext, mAppWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mAppWidgetManager, never()).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateSingleConversationAppWidgetWithoutPersonContactUriToRemoveBirthday() {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mMockCursor.moveToNext()).thenReturn(true, false);
        when(mMockCursor.getString(eq(TEST_COLUMN_INDEX))).thenReturn(TEST_LOOKUP_KEY);
        when(mMockCursor.getColumnIndex(eq(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY)
        )).thenReturn(TEST_COLUMN_INDEX);

        // Existing tile has a birthday status.
        Map<Integer, PeopleSpaceTile> widgetIdToTile = Map.of(WIDGET_ID_WITH_SHORTCUT,
                new PeopleSpaceTile.Builder(mShortcutInfoWithoutPerson,
                        mContext.getSystemService(LauncherApps.class)).setBirthdayText(
                        mContext.getString(R.string.birthday_status)).build());
        PeopleSpaceUtils.getBirthdays(mMockContext, mAppWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateSingleConversationAppWidgetToRemoveBirthdayWhenNoLongerBirthday() {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        Cursor mockPersonUriCursor = mock(Cursor.class);
        Cursor mockBirthdaysUriCursor = mock(Cursor.class);
        when(mockPersonUriCursor.moveToNext()).thenReturn(true, false);
        when(mockBirthdaysUriCursor.moveToNext()).thenReturn(true, false);
        when(mockBirthdaysUriCursor.getColumnIndex(
                eq(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY)
        )).thenReturn(TEST_COLUMN_INDEX);
        when(mockPersonUriCursor.getColumnIndex(
                eq(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY)
        )).thenReturn(TEST_COLUMN_INDEX);
        // Return different cursors based on the Uri queried.
        when(mMockContentResolver.query(eq(URI), any(), any(), any(),
                any())).thenReturn(mockPersonUriCursor);
        when(mMockContentResolver.query(eq(ContactsContract.Data.CONTENT_URI), any(), any(), any(),
                any())).thenReturn(mockBirthdaysUriCursor);
        // Each cursor returns a different lookup key.
        when(mockBirthdaysUriCursor.getString(eq(TEST_COLUMN_INDEX))).thenReturn(TEST_LOOKUP_KEY);
        when(mockPersonUriCursor.getString(eq(TEST_COLUMN_INDEX))).thenReturn(
                TEST_LOOKUP_KEY + "differentlookup");

        // Existing tile has a birthday status.
        Map<Integer, PeopleSpaceTile> widgetIdToTile = Map.of(WIDGET_ID_WITH_SHORTCUT,
                new PeopleSpaceTile.Builder(mShortcutInfo,
                        mContext.getSystemService(LauncherApps.class)).setBirthdayText(
                        mContext.getString(R.string.birthday_status)).build());
        PeopleSpaceUtils.getBirthdays(mMockContext, mAppWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateSingleConversationAppWidgetWhenBirthday() {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mMockCursor.moveToNext()).thenReturn(true, false, true, false);
        when(mMockCursor.getString(eq(TEST_COLUMN_INDEX))).thenReturn(TEST_LOOKUP_KEY);
        when(mMockCursor.getColumnIndex(eq(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY)
        )).thenReturn(TEST_COLUMN_INDEX);

        // Existing tile has a birthday status.
        Map<Integer, PeopleSpaceTile> widgetIdToTile = Map.of(WIDGET_ID_WITH_SHORTCUT,
                new PeopleSpaceTile.Builder(mShortcutInfo,
                        mContext.getSystemService(LauncherApps.class)).setBirthdayText(
                        mContext.getString(R.string.birthday_status)).build());
        PeopleSpaceUtils.getBirthdays(mMockContext, mAppWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testCreateRemoteViewsWithLastInteractionTime() {
        RemoteViews views = PeopleSpaceUtils.createRemoteViews(mMockContext,
                PERSON_TILE_WITHOUT_NOTIFICATION, 0);
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has last interaction.
        TextView lastInteraction = (TextView) result.findViewById(R.id.last_interaction);
        assertEquals(lastInteraction.getText(), mContext.getString(R.string.basic_status));
        // No availability.
        View availability = result.findViewById(R.id.availability);
        assertEquals(View.GONE, availability.getVisibility());
        // No new story.
        View personIcon = result.findViewById(R.id.person_icon_only);
        View personIconWithStory = result.findViewById(R.id.person_icon_with_story);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        assertEquals(View.GONE, personIconWithStory.getVisibility());
        // No status.
        assertThat((View) result.findViewById(R.id.status)).isNull();
    }

    @Test
    public void testCreateRemoteViewsWithGameTypeOnlyIsIgnored() {
        PeopleSpaceTile tileWithAvailabilityAndNewStory =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(NEW_STORY_WITH_AVAILABILITY,
                                new ConversationStatus.Builder(
                                        PERSON_TILE_WITHOUT_NOTIFICATION.getId(),
                                        ACTIVITY_GAME).build())).build();
        RemoteViews views = PeopleSpaceUtils.createRemoteViews(mMockContext,
                tileWithAvailabilityAndNewStory, 0);
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has last interaction over status.
        TextView lastInteraction = (TextView) result.findViewById(R.id.last_interaction);
        assertEquals(lastInteraction.getText(), mContext.getString(R.string.basic_status));
        // Has availability.
        View availability = result.findViewById(R.id.availability);
        assertEquals(View.VISIBLE, availability.getVisibility());
        // Has new story.
        View personIcon = result.findViewById(R.id.person_icon_only);
        View personIconWithStory = result.findViewById(R.id.person_icon_with_story);
        assertEquals(View.GONE, personIcon.getVisibility());
        assertEquals(View.VISIBLE, personIconWithStory.getVisibility());
        // No status.
        assertThat((View) result.findViewById(R.id.status)).isNull();
    }

    @Test
    public void testCreateRemoteViewsWithBirthdayTypeOnlyIsNotIgnored() {
        PeopleSpaceTile tileWithStatusTemplate =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(
                                NEW_STORY_WITH_AVAILABILITY, new ConversationStatus.Builder(
                                        PERSON_TILE_WITHOUT_NOTIFICATION.getId(),
                                        ACTIVITY_BIRTHDAY).build())).build();
        RemoteViews views = PeopleSpaceUtils.createRemoteViews(mContext,
                tileWithStatusTemplate, 0);
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has availability.
        View availability = result.findViewById(R.id.availability);
        assertEquals(View.VISIBLE, availability.getVisibility());
        // Has new story.
        View personIcon = result.findViewById(R.id.person_icon_only);
        View personIconWithStory = result.findViewById(R.id.person_icon_with_story);
        assertEquals(View.GONE, personIcon.getVisibility());
        assertEquals(View.VISIBLE, personIconWithStory.getVisibility());
        // Has status text from backup text.
        TextView statusContent = (TextView) result.findViewById(R.id.status);
        assertEquals(statusContent.getText(), mContext.getString(R.string.birthday_status));
    }

    @Test
    public void testCreateRemoteViewsWithStatusTemplate() {
        PeopleSpaceTile tileWithStatusTemplate =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(GAME_STATUS,
                                NEW_STORY_WITH_AVAILABILITY)).build();
        RemoteViews views = PeopleSpaceUtils.createRemoteViews(mContext,
                tileWithStatusTemplate, 0);
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has availability.
        View availability = result.findViewById(R.id.availability);
        assertEquals(View.VISIBLE, availability.getVisibility());
        // Has new story.
        View personIcon = result.findViewById(R.id.person_icon_only);
        View personIconWithStory = result.findViewById(R.id.person_icon_with_story);
        assertEquals(View.GONE, personIcon.getVisibility());
        assertEquals(View.VISIBLE, personIconWithStory.getVisibility());
        // Has status.
        TextView statusContent = (TextView) result.findViewById(R.id.status);
        assertEquals(statusContent.getText(), GAME_DESCRIPTION);
    }

    @Test
    public void testCreateRemoteViewsWithMissedCallNotification() {
        PeopleSpaceTile tileWithMissedCallNotification = PERSON_TILE.toBuilder()
                .setNotificationDataUri(null)
                .setNotificationCategory(CATEGORY_MISSED_CALL)
                .setNotificationContent(MISSED_CALL)
                .build();
        RemoteViews views = PeopleSpaceUtils.createRemoteViews(mContext,
                tileWithMissedCallNotification, 0);
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has availability.
        View availability = result.findViewById(R.id.availability);
        assertEquals(View.GONE, availability.getVisibility());
        // Has new story.
        View personIcon = result.findViewById(R.id.person_icon_only);
        View personIconWithStory = result.findViewById(R.id.person_icon_with_story);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        assertEquals(View.GONE, personIconWithStory.getVisibility());
        // Has status.
        TextView statusContent = (TextView) result.findViewById(R.id.status);
        assertEquals(statusContent.getText(), MISSED_CALL);
    }


    @Test
    public void testCreateRemoteViewsWithNotificationTemplate() {
        PeopleSpaceTile tileWithStatusAndNotification = PERSON_TILE.toBuilder()
                .setNotificationDataUri(null)
                .setStatuses(Arrays.asList(GAME_STATUS,
                        NEW_STORY_WITH_AVAILABILITY)).build();
        RemoteViews views = PeopleSpaceUtils.createRemoteViews(mContext,
                tileWithStatusAndNotification, 0);
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        TextView subtext = (TextView) result.findViewById(R.id.subtext);
        assertTrue(subtext.getText().toString().contains("weeks ago"));
        // Has availability.
        View availability = result.findViewById(R.id.availability);
        assertEquals(View.VISIBLE, availability.getVisibility());
        // Has new story.
        View personIcon = result.findViewById(R.id.person_icon_only);
        View personIconWithStory = result.findViewById(R.id.person_icon_with_story);
        assertEquals(View.GONE, personIcon.getVisibility());
        assertEquals(View.VISIBLE, personIconWithStory.getVisibility());
        // Has notification content.
        TextView statusContent = (TextView) result.findViewById(R.id.content);
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
    }

    @Test
    public void testGetPeopleTileFromPersistentStorageExistingConversation()
            throws Exception {
        when(mPeopleManager.getConversation(PACKAGE_NAME, 0, SHORTCUT_ID_1)).thenReturn(
                getConversationChannelWithoutTimestamp(SHORTCUT_ID_1));
        PeopleTileKey key = new PeopleTileKey(SHORTCUT_ID_1, 0, PACKAGE_NAME);
        PeopleSpaceTile tile = getPeopleTileFromPersistentStorage(mContext, key, mPeopleManager);
        assertThat(tile.getId()).isEqualTo(key.getShortcutId());
    }

    @Test
    public void testGetPeopleTileFromPersistentStorageNoConversation() {
        PeopleTileKey key = new PeopleTileKey(SHORTCUT_ID_2, 0, PACKAGE_NAME);
        PeopleSpaceTile tile = getPeopleTileFromPersistentStorage(mContext, key, mPeopleManager);
        assertThat(tile).isNull();
    }

    private ConversationChannelWrapper getConversationChannelWrapper(String shortcutId,
            boolean importantConversation, long lastInteractionTimestamp) throws Exception {
        ConversationChannelWrapper convo = new ConversationChannelWrapper();
        NotificationChannel notificationChannel = new NotificationChannel(shortcutId,
                "channel" + shortcutId,
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setImportantConversation(importantConversation);
        convo.setNotificationChannel(notificationChannel);
        convo.setShortcutInfo(new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").build());
        when(mPeopleManager.getLastInteraction(anyString(), anyInt(),
                eq(shortcutId))).thenReturn(lastInteractionTimestamp);
        return convo;
    }

    private ConversationChannel getConversationChannel(String shortcutId,
            long lastInteractionTimestamp) throws Exception {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                lastInteractionTimestamp, false);
        when(mPeopleManager.getLastInteraction(anyString(), anyInt(),
                eq(shortcutId))).thenReturn(lastInteractionTimestamp);
        return convo;
    }

    private ConversationChannel getConversationChannelWithoutTimestamp(String shortcutId)
            throws Exception {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                0L, false);
        return convo;
    }
}