/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.systemui.people.NotificationHelper.getHighestPriorityNotification;
import static com.android.systemui.people.NotificationHelper.getMessagingStyleMessages;
import static com.android.systemui.people.NotificationHelper.getSenderIfGroupConversation;
import static com.android.systemui.people.NotificationHelper.isMissedCall;
import static com.android.systemui.people.NotificationHelper.isMissedCallOrHasContent;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.Person;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationHelperTest extends SysuiTestCase {
    private static final String SHORTCUT_ID_1 = "101";
    private static final String SHORTCUT_ID_2 = "102";

    private static final String NOTIFICATION_TEXT_1 = "notification_text_1";
    private static final String NOTIFICATION_TEXT_2 = "notification_text_2";
    private static final String NOTIFICATION_TEXT_3 = "notification_text_3";
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
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

    private final Notification mNotification2 = new Notification.Builder(mContext, "test")
            .setContentTitle("TEST_TITLE")
            .setContentText("TEST_TEXT")
            .setShortcutId(SHORTCUT_ID_1)
            .setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(new Notification.MessagingStyle.Message(
                            NOTIFICATION_TEXT_1, 0, PERSON))
            )
            .build();

    private final Notification mNoContentNotification = new Notification.Builder(mContext, "test")
            .setContentTitle("TEST_TITLE")
            .setContentText("TEST_TEXT")
            .setShortcutId(SHORTCUT_ID_1)
            .setStyle(new Notification.MessagingStyle(PERSON))
            .build();

    private final Notification mMissedCallNotification = new Notification.Builder(mContext, "test")
            .setContentTitle("TEST_TITLE")
            .setContentText("TEST_TEXT")
            .setShortcutId(SHORTCUT_ID_2)
            .setCategory(CATEGORY_MISSED_CALL)
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
            .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_1).build())
            .setUser(UserHandle.of(0))
            .setPkg(PACKAGE_NAME)
            .build();


    private final NotificationEntry mMissedCallNotificationEntry = new NotificationEntryBuilder()
            .setNotification(mMissedCallNotification)
            .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_1).build())
            .setUser(UserHandle.of(0))
            .setPkg(PACKAGE_NAME)
            .build();

    private final NotificationEntry mNoContentNotificationEntry = new NotificationEntryBuilder()
            .setNotification(mNoContentNotification)
            .setShortcutInfo(new ShortcutInfo.Builder(mContext, SHORTCUT_ID_1).build())
            .setUser(UserHandle.of(0))
            .setPkg(PACKAGE_NAME)
            .build();

    @Test
    public void testGetMessagingStyleMessagesNoMessage() {
        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID_1)
                .build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notification)
                .build();

        List<Notification.MessagingStyle.Message> messages =
                getMessagingStyleMessages(sbn.getNotification());

        assertThat(ArrayUtils.isEmpty(messages)).isTrue();
    }

    @Test
    public void testGetMessagingStyleMessages() {
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(mNotification1)
                .build();

        List<Notification.MessagingStyle.Message> messages =
                getMessagingStyleMessages(sbn.getNotification());

        assertThat(messages.size()).isEqualTo(3);
        assertThat(messages.get(0).getText().toString()).isEqualTo(NOTIFICATION_TEXT_2);
    }

    @Test
    public void testIsMissedCall_notMissedCall() {
        assertFalse(isMissedCall(mNotificationEntry1));
    }

    @Test
    public void testIsMissedCall_missedCall() {
        assertTrue(isMissedCall(mMissedCallNotificationEntry));
    }

    @Test
    public void testisMissedCallOrHasContent_NoContent() {
        assertFalse(isMissedCallOrHasContent(mNoContentNotificationEntry));
    }

    @Test
    public void testisMissedCallOrHasContent_Hasontent() {
        assertTrue(isMissedCallOrHasContent(mNotificationEntry1));
    }

    @Test
    public void testGetHighestPriorityNotification_missedCallHigherPriority() {
        Set<NotificationEntry> notifications = Set.of(
                mNotificationEntry1, mMissedCallNotificationEntry);

        assertThat(getHighestPriorityNotification(notifications))
                .isEqualTo(mMissedCallNotificationEntry);
    }

    @Test
    public void testGetHighestPriorityNotification_moreRecentLastMessage() {
        Set<NotificationEntry> notifications = Set.of(
                mNotificationEntry1, mNotificationEntry2);

        assertThat(getHighestPriorityNotification(notifications))
                .isEqualTo(mNotificationEntry1);
    }

    @Test
    public void testGetSenderIfGroupConversation_notGroup() {
        Notification.MessagingStyle.Message message = new Notification.MessagingStyle.Message(
                NOTIFICATION_TEXT_3, 10, PERSON);
        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID_1)
                .setStyle(new Notification.MessagingStyle(PERSON).addMessage(message))
                .build();
        assertThat(getSenderIfGroupConversation(notification, message)).isNull();
    }

    @Test
    public void testGetSenderIfGroupConversation_group() {
        Bundle extras = new Bundle();
        extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true);
        Notification.MessagingStyle.Message message = new Notification.MessagingStyle.Message(
                NOTIFICATION_TEXT_3, 10, PERSON);

        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID_1)
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .setGroupConversation(true)
                        .addMessage(message))
                .addExtras(extras)
                .build();
        assertThat(getSenderIfGroupConversation(notification, message)).isEqualTo("name");
    }

    @Test
    public void testGetSenderIfGroupConversation_groupNoName() {
        Bundle extras = new Bundle();
        extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true);
        Notification.MessagingStyle.Message message = new Notification.MessagingStyle.Message(
                NOTIFICATION_TEXT_3, 10, new Person.Builder().build());

        Notification notification = new Notification.Builder(mContext, "test")
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(SHORTCUT_ID_1)
                .setStyle(new Notification.MessagingStyle(PERSON).addMessage(message))
                .setExtras(extras)
                .build();
        assertThat(getSenderIfGroupConversation(notification, message)).isNull();
    }
}
