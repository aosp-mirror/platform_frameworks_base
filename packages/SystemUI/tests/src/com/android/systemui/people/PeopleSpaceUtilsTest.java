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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.Person;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
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

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceUtilsTest extends SysuiTestCase {

    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final String SHORTCUT_ID = "101";
    private static final String NOTIFICATION_KEY = "notification_key";
    private static final String NOTIFICATION_CONTENT = "notification_content";

    @Mock
    private NotificationListener mListenerService;
    @Mock
    private IAppWidgetService mIAppWidgetService;
    @Mock
    private AppWidgetManager mAppWidgetManager;

    private static Icon sIcon = Icon.createWithResource("package", R.drawable.ic_android);
    private static Uri sUri = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority("something")
            .path("test")
            .build();
    private static Person sPerson = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri("uri")
            .setBot(false)
            .build();
    private static PeopleSpaceTile sPeopleSpaceTile =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID, "username", sIcon, new Intent())
                    .setNotificationKey(NOTIFICATION_KEY)
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(sUri)
                    .build();

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0);

        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        Bundle options = new Bundle();
        options.putParcelable(OPTIONS_PEOPLE_SPACE_TILE, sPeopleSpaceTile);

        when(mIAppWidgetService.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(options);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());
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
                .setStyle(new Notification.MessagingStyle(sPerson)
                        .addMessage(new Notification.MessagingStyle.Message("text1", 0, sPerson))
                        .addMessage(new Notification.MessagingStyle.Message("text2", 20, sPerson))
                        .addMessage(new Notification.MessagingStyle.Message("text3", 10, sPerson))
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
                        .Builder("id", "userName", sIcon, new Intent())
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromStorage(tile, mAppWidgetManager, WIDGET_ID_WITH_SHORTCUT);

        assertThat(actual.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(actual.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        assertThat(actual.getNotificationDataUri()).isEqualTo(sUri);
    }

    @Test
    public void testAugmentTileFromStorageWithoutNotification() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder("id", "userName", sIcon, new Intent())
                        .build();
        PeopleSpaceTile actual = PeopleSpaceUtils
                .augmentTileFromStorage(tile, mAppWidgetManager, WIDGET_ID_WITHOUT_SHORTCUT);

        assertThat(actual.getNotificationKey()).isEqualTo(null);
        assertThat(actual.getNotificationKey()).isEqualTo(null);
        assertThat(actual.getNotificationDataUri()).isEqualTo(null);
    }
}
