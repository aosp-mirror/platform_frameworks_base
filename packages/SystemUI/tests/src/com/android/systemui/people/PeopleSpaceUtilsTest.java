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

import static com.android.systemui.people.PeopleSpaceUtils.OPTIONS_PEOPLE_SPACE_TILE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.Person;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.SbnBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceUtilsTest extends SysuiTestCase {

    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final String SHORTCUT_ID = "101";
    private static final String NOTIFICATION_KEY = "notification_key";
    private static final String NOTIFICATION_CONTENT = "notification_content";
    private static final String TEST_LOOKUP_KEY = "lookup_key";
    private static final int TEST_COLUMN_INDEX = 1;
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

    private final ShortcutInfo mShortcutInfo = new ShortcutInfo.Builder(mContext,
            SHORTCUT_ID).setLongLabel(
            "name").setPerson(PERSON)
            .build();
    private final ShortcutInfo mShortcutInfoWithoutPerson = new ShortcutInfo.Builder(mContext,
            SHORTCUT_ID).setLongLabel(
            "name")
            .build();

    @Mock
    private NotificationListener mListenerService;
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

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);

        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        Bundle options = new Bundle();
        options.putParcelable(OPTIONS_PEOPLE_SPACE_TILE, PERSON_TILE);

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
    }

    @Test
    public void testGetLastMessagingStyleMessageNoMessage() {
        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID)
                .build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notification)
                .build();

        Notification.MessagingStyle.Message lastMessage =
                PeopleSpaceUtils.getLastMessagingStyleMessage(sbn);

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
        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID)
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .addMessage(new Notification.MessagingStyle.Message("text1", 0, PERSON))
                        .addMessage(new Notification.MessagingStyle.Message("text2", 20, PERSON))
                        .addMessage(new Notification.MessagingStyle.Message("text3", 10, PERSON))
                )
                .build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notification)
                .build();

        Notification.MessagingStyle.Message lastMessage =
                PeopleSpaceUtils.getLastMessagingStyleMessage(sbn);

        assertThat(lastMessage.getText()).isEqualTo("text2");
    }

    @Test
    public void testAugmentTileFromStorageWithNotification() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder("id", "userName", ICON, new Intent())
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromStorage(tile, mAppWidgetManager, WIDGET_ID_WITH_SHORTCUT);

        assertThat(actual.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(actual.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        assertThat(actual.getNotificationDataUri()).isEqualTo(URI);
    }

    @Test
    public void testAugmentTileFromStorageWithoutNotification() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder("id", "userName", ICON, new Intent())
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromStorage(tile, mAppWidgetManager, WIDGET_ID_WITHOUT_SHORTCUT);

        assertThat(actual.getNotificationKey()).isEqualTo(null);
        assertThat(actual.getNotificationKey()).isEqualTo(null);
        assertThat(actual.getNotificationDataUri()).isEqualTo(null);
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
                        mContext.getSystemService(LauncherApps.class)).setStatusText(
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
                        mContext.getSystemService(LauncherApps.class)).setStatusText(
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
                        mContext.getSystemService(LauncherApps.class)).setStatusText(
                        mContext.getString(R.string.birthday_status)).build());
        PeopleSpaceUtils.getBirthdays(mMockContext, mAppWidgetManager,
                widgetIdToTile, widgetIdsArray);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }
}
