/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.NotificationGroup;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.OnGroupChangeListener;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGroupManagerLegacyTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private NotificationGroupManagerLegacy mGroupManager;
    private final NotificationGroupTestHelper mGroupTestHelper =
            new NotificationGroupTestHelper(mContext);

    @Mock PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock HeadsUpManager mHeadsUpManager;

    @Before
    public void setup() {
        mDependency.injectMockDependency(Bubbles.class);
        initializeGroupManager();
    }

    private void initializeGroupManager() {
        mGroupManager = new NotificationGroupManagerLegacy(
                mock(StatusBarStateController.class),
                () -> mPeopleNotificationIdentifier,
                Optional.of(mock(Bubbles.class)));
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
    }

    @Test
    public void testIsOnlyChildInGroup() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        assertTrue(mGroupManager.isOnlyChildInGroup(childEntry));
    }

    @Test
    public void testIsChildInGroupWithSummary() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        assertTrue(mGroupManager.isChildInGroup(childEntry));
    }

    @Test
    public void testIsSummaryOfGroupWithChildren() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        assertTrue(mGroupManager.isGroupSummary(summaryEntry));
        assertEquals(summaryEntry, mGroupManager.getGroupSummary(childEntry));
    }

    @Test
    public void testRemoveChildFromGroupWithSummary() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        mGroupManager.onEntryRemoved(childEntry);

        assertFalse(mGroupManager.isChildInGroup(childEntry));
    }

    @Test
    public void testRemoveSummaryFromGroupWithSummary() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        mGroupManager.onEntryRemoved(summaryEntry);

        assertNull(mGroupManager.getGroupSummary(childEntry));
        assertFalse(mGroupManager.isGroupSummary(summaryEntry));
    }

    @Test
    public void testHeadsUpEntryIsIsolated() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());
        when(mHeadsUpManager.isAlerting(childEntry.getKey())).thenReturn(true);

        mGroupManager.onHeadsUpStateChanged(childEntry, true);

        // Child entries that are heads upped should be considered separate groups visually even if
        // they are the same group logically
        assertEquals(childEntry, mGroupManager.getGroupSummary(childEntry));
        assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(childEntry));
    }

    @Test
    public void testAlertOverrideWithSiblings_0() {
        helpTestAlertOverrideWithSiblings(0);
    }

    @Test
    public void testAlertOverrideWithSiblings_1() {
        helpTestAlertOverrideWithSiblings(1);
    }

    @Test
    public void testAlertOverrideWithSiblings_2() {
        helpTestAlertOverrideWithSiblings(2);
    }

    /**
     * This tests, for a group with a priority entry and the given number of siblings, that:
     * 1) the priority entry is identified as the alertOverride for the group
     * 2) the onAlertOverrideChanged method is called at that time
     * 3) when the priority entry is removed, these are reversed
     */
    private void helpTestAlertOverrideWithSiblings(int numSiblings) {
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        // Create entries in an order so that the priority entry can be deemed the newest child.
        NotificationEntry[] siblings = new NotificationEntry[numSiblings];
        for (int i = 0; i < numSiblings; i++) {
            siblings[i] = mGroupTestHelper.createChildNotification(groupAlert);
        }
        NotificationEntry priorityEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification(groupAlert);

        // The priority entry is an important conversation.
        when(mPeopleNotificationIdentifier.getPeopleNotificationType(eq(priorityEntry)))
                .thenReturn(PeopleNotificationIdentifier.TYPE_IMPORTANT_PERSON);

        // Register a listener so we can verify that the event is sent.
        OnGroupChangeListener groupChangeListener = mock(OnGroupChangeListener.class);
        mGroupManager.registerGroupChangeListener(groupChangeListener);

        // Add all the entries.  The order here shouldn't matter.
        mGroupManager.onEntryAdded(summaryEntry);
        for (int i = 0; i < numSiblings; i++) {
            mGroupManager.onEntryAdded(siblings[i]);
        }
        mGroupManager.onEntryAdded(priorityEntry);

        // Verify that the summary group has the priority child as its alertOverride
        NotificationGroup summaryGroup = mGroupManager.getGroupForSummary(summaryEntry.getSbn());
        assertEquals(priorityEntry, summaryGroup.alertOverride);
        verify(groupChangeListener).onGroupAlertOverrideChanged(summaryGroup, null, priorityEntry);

        // Verify that only the priority notification is isolated from the group
        assertEquals(priorityEntry, mGroupManager.getGroupSummary(priorityEntry));
        assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(priorityEntry));
        // Verify that the siblings are NOT isolated from the group
        for (int i = 0; i < numSiblings; i++) {
            assertEquals(summaryEntry, mGroupManager.getGroupSummary(siblings[i]));
            assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(siblings[i]));
        }

        // Remove the priority notification to validate that it is removed as the alertOverride
        mGroupManager.onEntryRemoved(priorityEntry);

        // verify that the alertOverride is removed when the priority notification is
        assertNull(summaryGroup.alertOverride);
        verify(groupChangeListener).onGroupAlertOverrideChanged(summaryGroup, null, priorityEntry);
    }
}
