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
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class HighPriorityProviderTest extends SysuiTestCase {
    @Mock private PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock private NotificationGroupManager mGroupManager;
    private HighPriorityProvider mHighPriorityProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mHighPriorityProvider = new HighPriorityProvider(
                mPeopleNotificationIdentifier,
                mGroupManager);
    }

    @Test
    public void highImportance() {
        // GIVEN notification has high importance
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry.getSbn(), entry.getRanking()))
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
                .getPeopleNotificationType(entry.getSbn(), entry.getRanking()))
                .thenReturn(TYPE_PERSON);

        // THEN it has high priority
        assertTrue(mHighPriorityProvider.isHighPriority(entry));
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
                .getPeopleNotificationType(entry.getSbn(), entry.getRanking()))
                .thenReturn(TYPE_NON_PERSON);

        // THEN it has high priority
        assertTrue(mHighPriorityProvider.isHighPriority(entry));
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
                .getPeopleNotificationType(entry.getSbn(), entry.getRanking()))
                .thenReturn(TYPE_NON_PERSON);

        // THEN it has high priority
        assertTrue(mHighPriorityProvider.isHighPriority(entry));
    }

    @Test
    public void minImportanceForeground() {
        // GIVEN notification is low importance and is associated with a foreground service
        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_MIN)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry.getSbn(), entry.getRanking()))
                .thenReturn(TYPE_NON_PERSON);

        // THEN it does NOT have high priority
        assertFalse(mHighPriorityProvider.isHighPriority(entry));
    }

    @Test
    public void userChangeTrumpsHighPriorityCharacteristics() {
        // GIVEN notification has high priority characteristics but the user changed the importance
        // to less than IMPORTANCE_DEFAULT (ie: IMPORTANCE_LOW or IMPORTANCE_MIN)
        final Notification notification = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        final NotificationChannel channel = new NotificationChannel("a", "a",
                IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setChannel(channel)
                .build();
        when(mPeopleNotificationIdentifier
                .getPeopleNotificationType(entry.getSbn(), entry.getRanking()))
                .thenReturn(TYPE_PERSON);

        // THEN it does NOT have high priority
        assertFalse(mHighPriorityProvider.isHighPriority(entry));
    }

    @Test
    public void testIsHighPriority_checkChildrenToCalculatePriority() {
        // GIVEN: a summary with low priority has a highPriorityChild and a lowPriorityChild
        final NotificationEntry summary = createNotifEntry(false);
        final NotificationEntry lowPriorityChild = createNotifEntry(false);
        final NotificationEntry highPriorityChild = createNotifEntry(true);
        when(mGroupManager.isGroupSummary(summary.getSbn())).thenReturn(true);
        when(mGroupManager.getChildren(summary.getSbn())).thenReturn(
                new ArrayList<>(Arrays.asList(lowPriorityChild, highPriorityChild)));

        // THEN the summary is high priority since it has a high priority child
        assertTrue(mHighPriorityProvider.isHighPriority(summary));
    }

    // Tests below here are only relevant to the NEW notification pipeline which uses GroupEntry

    @Test
    public void testIsHighPriority_summaryUpdated() {
        // GIVEN a GroupEntry with a lowPrioritySummary and no children
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        final NotificationEntry lowPrioritySummary = createNotifEntry(false);
        setSummary(parentEntry, lowPrioritySummary);
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
    public void testIsHighPriority_checkChildrenToCalculatePriorityOf() {
        // GIVEN:
        // GroupEntry = parentEntry, summary = lowPrioritySummary
        //      NotificationEntry = lowPriorityChild
        //      NotificationEntry = highPriorityChild
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        setSummary(parentEntry, createNotifEntry(false));
        addChild(parentEntry, createNotifEntry(false));
        addChild(parentEntry, createNotifEntry(true));

        // THEN the GroupEntry parentEntry is high priority since it has a high priority child
        assertTrue(mHighPriorityProvider.isHighPriority(parentEntry));
    }

    @Test
    public void testIsHighPriority_childEntryRankingUpdated() {
        // GIVEN:
        // GroupEntry = parentEntry, summary = lowPrioritySummary
        //      NotificationEntry = lowPriorityChild
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        final NotificationEntry lowPriorityChild = createNotifEntry(false);
        setSummary(parentEntry, createNotifEntry(false));
        addChild(parentEntry, lowPriorityChild);

        // WHEN the child entry ranking changes to high priority
        lowPriorityChild.setRanking(
                new RankingBuilder()
                        .setKey(lowPriorityChild.getKey())
                        .setImportance(IMPORTANCE_HIGH)
                        .build());

        // THEN the parent entry's high priority value is updated - but not the parent's summary
        assertTrue(mHighPriorityProvider.isHighPriority(parentEntry));
        assertFalse(mHighPriorityProvider.isHighPriority(parentEntry.getSummary()));
    }

    private NotificationEntry createNotifEntry(boolean highPriority) {
        return new NotificationEntryBuilder()
                .setImportance(highPriority ? IMPORTANCE_HIGH : IMPORTANCE_MIN)
                .build();
    }

    private void setSummary(GroupEntry parent, NotificationEntry summary) {
        parent.setSummary(summary);
        summary.setParent(parent);
    }

    private void addChild(GroupEntry parent, NotificationEntry child) {
        parent.addChild(child);
        child.setParent(parent);
    }
}
