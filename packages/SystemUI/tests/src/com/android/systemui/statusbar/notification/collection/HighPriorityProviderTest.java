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

package com.android.systemui.statusbar.notification.collection;

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.TYPE_NON_PERSON;
import static com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.TYPE_PERSON;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class HighPriorityProviderTest extends SysuiTestCase {
    @Mock private PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock private GroupMembershipManager mGroupMembershipManager;
    private HighPriorityProvider mHighPriorityProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mHighPriorityProvider = new HighPriorityProvider(
                mPeopleNotificationIdentifier,
                mGroupMembershipManager);
    }

    @Test
    public void highImportance() {
        // GIVEN notification has high importance
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_NON_PERSON);

        // THEN it has high priority
        assertTrue(mHighPriorityProvider.isHighPriority(entry));
    }

    @Test
    public void peopleNotification() {
        // GIVEN notification is low importance and is a people notification
        final Notification notification = new Notification.Builder(mContext, "test")
                .build();
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_LOW)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_PERSON);

        // THEN it has high priority BUT it has low explicit priority.
        assertTrue(mHighPriorityProvider.isHighPriority(entry));
        assertFalse(mHighPriorityProvider.isExplicitlyHighPriority(entry));
    }

    @Test
    public void highImportanceConversation() {
        // GIVEN notification is high importance and is a people notification
        final Notification notification = new Notification.Builder(mContext, "test")
                .build();
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_HIGH)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_PERSON);

        // THEN it is high priority conversation
        assertTrue(mHighPriorityProvider.isHighPriorityConversation(entry));
    }

    @Test
    public void lowImportanceConversation() {
        // GIVEN notification is low importance and is a people notification
        final Notification notification = new Notification.Builder(mContext, "test")
                .build();
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_LOW)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_PERSON);

        // THEN it is low priority conversation
        assertFalse(mHighPriorityProvider.isHighPriorityConversation(entry));
    }

    @Test
    public void highImportanceConversationWhenAnyOfChildIsHighPriority() {
        // GIVEN notification is high importance and is a people notification
        final NotificationEntry summary = createNotifEntry(false);
        final NotificationEntry lowPriorityChild = createNotifEntry(false);
        final NotificationEntry highPriorityChild = createNotifEntry(true);
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(summary))
                .thenReturn(TYPE_PERSON);
        final GroupEntry groupEntry = new GroupEntryBuilder()
                .setParent(GroupEntry.ROOT_ENTRY)
                .setSummary(summary)
                .setChildren(List.of(lowPriorityChild, highPriorityChild))
                .build();

        // THEN the groupEntry is high priority conversation since it has a high priority child
        assertTrue(mHighPriorityProvider.isHighPriorityConversation(groupEntry));
    }

    @Test
    public void messagingStyle() {
        // GIVEN notification is low importance but has messaging style
        final Notification notification = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_NON_PERSON);

        // THEN it has high priority but low explicit priority
        assertTrue(mHighPriorityProvider.isHighPriority(entry));
        assertFalse(mHighPriorityProvider.isExplicitlyHighPriority(entry));
    }

    @Test
    public void lowImportanceForeground() {
        // GIVEN notification is low importance and is associated with a foreground service
        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_LOW)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_NON_PERSON);

        // THEN it has low priority
        assertFalse(mHighPriorityProvider.isHighPriority(entry));
    }

    @Test
    public void userChangeTrumpsHighPriorityCharacteristics() {
        // GIVEN notification has high priority characteristics but the user changed the importance
        // to less than IMPORTANCE_DEFAULT (ie: IMPORTANCE_LOW or IMPORTANCE_MIN)
        final Notification notification = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        final NotificationChannel channel = new NotificationChannel("a", "a",
                IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setChannel(channel)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry))
                .thenReturn(TYPE_PERSON);

        // THEN it does NOT have high priority
        assertFalse(mHighPriorityProvider.isHighPriority(entry));
    }

    @Test
    public void testIsHighPriority_checkChildrenToCalculatePriority_legacy() {
        // GIVEN: a summary with low priority has a highPriorityChild and a lowPriorityChild
        final NotificationEntry summary = createNotifEntry(false);
        final NotificationEntry lowPriorityChild = createNotifEntry(false);
        final NotificationEntry highPriorityChild = createNotifEntry(true);
        when(mGroupMembershipManager.isGroupSummary(summary)).thenReturn(true);
        when(mGroupMembershipManager.getChildren(summary)).thenReturn(
                new ArrayList<>(Arrays.asList(lowPriorityChild, highPriorityChild)));

        // THEN the summary is high priority since it has a high priority child
        assertTrue(mHighPriorityProvider.isHighPriority(summary));
    }

    // Tests below here are only relevant to the NEW notification pipeline which uses GroupEntry

    @Test
    public void testIsHighPriority_summaryUpdated() {
        // GIVEN a GroupEntry with a lowPrioritySummary and no children
        final NotificationEntry lowPrioritySummary = createNotifEntry(false);
        final GroupEntry parentEntry = new GroupEntryBuilder()
                .setSummary(lowPrioritySummary)
                .build();
        assertFalse(mHighPriorityProvider.isHighPriority(parentEntry));

        // WHEN the summary changes to high priority
        lowPrioritySummary.setRanking(
                new RankingBuilder()
                        .setKey(lowPrioritySummary.getKey())
                        .setImportance(IMPORTANCE_HIGH)
                        .build());
        assertTrue(mHighPriorityProvider.isHighPriority(lowPrioritySummary));

        // THEN the GroupEntry's priority is updated to high
        assertTrue(mHighPriorityProvider.isHighPriority(parentEntry));
    }

    @Test
    public void testIsHighPriority_checkChildrenToCalculatePriority() {
        // GIVEN:
        // parent with summary = lowPrioritySummary
        //      NotificationEntry = lowPriorityChild
        //      NotificationEntry = highPriorityChild
        final NotificationEntry lowPrioritySummary = createNotifEntry(false);
        final GroupEntry parentEntry = new GroupEntryBuilder()
                .setSummary(lowPrioritySummary)
                .build();
        when(mGroupMembershipManager.getChildren(parentEntry)).thenReturn(
                new ArrayList<>(
                        List.of(
                                createNotifEntry(false),
                                createNotifEntry(true))));

        // THEN the GroupEntry parentEntry is high priority since it has a high priority child
        assertTrue(mHighPriorityProvider.isHighPriority(parentEntry));
    }

    @Test
    public void testIsHighPriority_childEntryRankingUpdated() {
        // GIVEN:
        // parent with summary = lowPrioritySummary
        //      NotificationEntry = lowPriorityChild
        final NotificationEntry lowPrioritySummary = createNotifEntry(false);
        final GroupEntry parentEntry = new GroupEntryBuilder()
                .setSummary(lowPrioritySummary)
                .build();
        final NotificationEntry lowPriorityChild = createNotifEntry(false);
        when(mGroupMembershipManager.getChildren(parentEntry)).thenReturn(
                new ArrayList<>(List.of(lowPriorityChild)));

        // WHEN the child entry ranking changes to high priority
        lowPriorityChild.setRanking(
                new RankingBuilder()
                        .setKey(lowPriorityChild.getKey())
                        .setImportance(IMPORTANCE_HIGH)
                        .build());

        // THEN the parent entry's high priority value is updated
        assertTrue(mHighPriorityProvider.isHighPriority(parentEntry));
    }

    private NotificationEntry createNotifEntry(boolean highPriority) {
        return new NotificationEntryBuilder()
                .setImportance(highPriority ? IMPORTANCE_HIGH : IMPORTANCE_MIN)
                .build();
    }
}
