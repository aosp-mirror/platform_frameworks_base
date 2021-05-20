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

import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.Person;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.testing.AndroidTestingRunner;
import android.util.DisplayMetrics;

import androidx.test.filters.SmallTest;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.people.widget.PeopleTileKey;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
@SmallTest
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
    private static final String NAME = "username";
    private static final UserHandle USER = new UserHandle(0);
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
            .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID_1, NAME, ICON, new Intent())
                    .setUserHandle(USER)
                    .setLastInteractionTimestamp(123L)
                    .setNotificationKey(NOTIFICATION_KEY)
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(URI)
                    .setMessagesCount(1)
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
    @Mock
    private PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;

    private Bundle mOptions;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        mOptions = new Bundle();

        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(mOptions);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());

        Configuration configuration = mock(Configuration.class);
        DisplayMetrics displayMetrics = mock(DisplayMetrics.class);
        Resources resources = mock(Resources.class);
        when(mMockContext.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(configuration);
        when(resources.getDisplayMetrics()).thenReturn(displayMetrics);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContentResolver.query(any(Uri.class), any(), any(), any(),
                any())).thenReturn(mMockCursor);
        when(mMockContext.getString(R.string.birthday_status)).thenReturn(
                mContext.getString(R.string.birthday_status));
        when(mMockContext.getString(R.string.basic_status)).thenReturn(
                mContext.getString(R.string.basic_status));
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockContext.getString(R.string.over_two_weeks_timestamp)).thenReturn(
                mContext.getString(R.string.over_two_weeks_timestamp));
        when(mPackageManager.getApplicationIcon(anyString())).thenReturn(null);
        when(mNotificationEntryManager.getVisibleNotifications())
                .thenReturn(List.of(mNotificationEntry1, mNotificationEntry2, mNotificationEntry3));
    }

    @Test
    public void testAugmentTileFromNotification() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_1, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleTileKey key = new PeopleTileKey(tile);
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromNotification(mContext, tile, key, mNotificationEntry1, 0,
                        Optional.empty());

        assertThat(actual.getNotificationContent().toString()).isEqualTo(NOTIFICATION_TEXT_2);
        assertThat(actual.getNotificationSender()).isEqualTo(null);
    }

    @Test
    public void testAugmentTileFromNotificationGroupWithSender() {
        Bundle extras = new Bundle();
        extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true);
        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID_1)
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .setGroupConversation(true)
                        .addMessage(new Notification.MessagingStyle.Message(
                                NOTIFICATION_TEXT_1, 0, PERSON))
                        .addMessage(new Notification.MessagingStyle.Message(
                                NOTIFICATION_TEXT_2, 20, PERSON))
                        .addMessage(new Notification.MessagingStyle.Message(
                                NOTIFICATION_TEXT_3, 10, PERSON))
                )
                .setExtras(extras)
                .build();
        NotificationEntry notificationEntry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_1).build())
                .setUser(UserHandle.of(0))
                .setPkg(PACKAGE_NAME)
                .build();
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_1, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleTileKey key = new PeopleTileKey(tile);
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromNotification(mContext, tile, key, notificationEntry, 0,
                        Optional.empty());

        assertThat(actual.getNotificationContent().toString()).isEqualTo(NOTIFICATION_TEXT_2);
        assertThat(actual.getNotificationSender().toString()).isEqualTo("name");
    }

    @Test
    public void testAugmentTileFromNotificationNoContent() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID_3, "userName", ICON, new Intent())
                        .setPackageName(PACKAGE_NAME)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleTileKey key = new PeopleTileKey(tile);
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromNotification(mContext, tile, key, mNotificationEntry3, 0,
                        Optional.empty());

        assertThat(actual.getNotificationContent()).isEqualTo(null);
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
        PeopleSpaceUtils.getDataFromContacts(mMockContext, mPeopleSpaceWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mPeopleSpaceWidgetManager, never()).updateAppWidgetOptionsAndView(
                eq(WIDGET_ID_WITH_SHORTCUT),
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
        PeopleSpaceUtils.getDataFromContacts(mMockContext, mPeopleSpaceWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mPeopleSpaceWidgetManager, times(1)).updateAppWidgetOptionsAndView(
                eq(WIDGET_ID_WITH_SHORTCUT),
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
        PeopleSpaceUtils.getDataFromContacts(mMockContext, mPeopleSpaceWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mPeopleSpaceWidgetManager, times(1)).updateAppWidgetOptionsAndView(
                eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateSingleConversationAppWidgetWhenBirthday() {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mMockCursor.moveToNext()).thenReturn(true, false, true, false);
        when(mMockCursor.getString(eq(TEST_COLUMN_INDEX))).thenReturn(TEST_LOOKUP_KEY);
        when(mMockCursor.getInt(eq(TEST_COLUMN_INDEX + 1))).thenReturn(1);
        when(mMockCursor.getColumnIndex(eq(ContactsContract.Contacts.STARRED))).thenReturn(
                TEST_COLUMN_INDEX + 1);
        when(mMockCursor.getColumnIndex(eq(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY)
        )).thenReturn(TEST_COLUMN_INDEX);

        // Existing tile has a birthday status.
        Map<Integer, PeopleSpaceTile> widgetIdToTile = Map.of(WIDGET_ID_WITH_SHORTCUT,
                new PeopleSpaceTile.Builder(mShortcutInfo,
                        mContext.getSystemService(LauncherApps.class)).setBirthdayText(
                        mContext.getString(R.string.birthday_status)).build());
        PeopleSpaceUtils.getDataFromContacts(mMockContext, mPeopleSpaceWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mPeopleSpaceWidgetManager, times(1)).updateAppWidgetOptionsAndView(
                eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }
}
