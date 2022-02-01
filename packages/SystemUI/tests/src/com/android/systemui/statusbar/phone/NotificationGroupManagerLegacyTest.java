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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Log;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
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

    @Mock
    PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock
    HeadsUpManager mHeadsUpManager;

    @Before
    public void setup() {
        mDependency.injectMockDependency(Bubbles.class);
        initializeGroupManager();
    }

    private void initializeGroupManager() {
        mGroupManager = new NotificationGroupManagerLegacy(
                mock(StatusBarStateController.class),
                () -> mPeopleNotificationIdentifier,
                Optional.of(mock(Bubbles.class)),
                mock(DumpManager.class));
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

    @Test
    public void testAlertOverrideWithSiblings_3() {
        helpTestAlertOverrideWithSiblings(3);
    }

    @Test
    public void testAlertOverrideWithSiblings_9() {
        helpTestAlertOverrideWithSiblings(9);
    }

    /**
     * Helper for testing various sibling counts
     */
    private void helpTestAlertOverrideWithSiblings(int numSiblings) {
        helpTestAlertOverride(
                /* numSiblings */ numSiblings,
                /* summaryGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* priorityGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* siblingGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* expectAlertOverride */ true);
    }

    @Test
    public void testAlertOverrideWithParentAlertAll() {
        // tests that summary can have GROUP_ALERT_ALL and this still works
        helpTestAlertOverride(
                /* numSiblings */ 1,
                /* summaryGroupAlert */ Notification.GROUP_ALERT_ALL,
                /* priorityGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* siblingGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* expectAlertOverride */ true);
    }

    @Test
    public void testAlertOverrideWithParentAlertChild() {
        // Tests that if the summary alerts CHILDREN, there's no alertOverride
        helpTestAlertOverride(
                /* numSiblings */ 1,
                /* summaryGroupAlert */ Notification.GROUP_ALERT_CHILDREN,
                /* priorityGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* siblingGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* expectAlertOverride */ false);
    }

    @Test
    public void testAlertOverrideWithChildrenAlertAll() {
        // Tests that if the children alert ALL, there's no alertOverride
        helpTestAlertOverride(
                /* numSiblings */ 1,
                /* summaryGroupAlert */ Notification.GROUP_ALERT_SUMMARY,
                /* priorityGroupAlert */ Notification.GROUP_ALERT_ALL,
                /* siblingGroupAlert */ Notification.GROUP_ALERT_ALL,
                /* expectAlertOverride */ false);
    }

    /**
     * This tests, for a group with a priority entry and the given number of siblings, that:
     * 1) the priority entry is identified as the alertOverride for the group
     * 2) the onAlertOverrideChanged method is called at that time
     * 3) when the priority entry is removed, these are reversed
     */
    private void helpTestAlertOverride(int numSiblings,
            @Notification.GroupAlertBehavior int summaryGroupAlert,
            @Notification.GroupAlertBehavior int priorityGroupAlert,
            @Notification.GroupAlertBehavior int siblingGroupAlert,
            boolean expectAlertOverride) {
        long when = 10000;
        // Create entries in an order so that the priority entry can be deemed the newest child.
        NotificationEntry[] siblings = new NotificationEntry[numSiblings];
        for (int i = 0; i < numSiblings; i++) {
            siblings[i] = mGroupTestHelper
                    .createChildNotification(siblingGroupAlert, i, "sibling", ++when);
        }
        NotificationEntry priorityEntry =
                mGroupTestHelper.createChildNotification(priorityGroupAlert, 0, "priority", ++when);
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(summaryGroupAlert, 0, "summary", ++when);

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

        if (!expectAlertOverride) {
            // Test expectation is that there will NOT be an alert, so verify that!
            NotificationGroup summaryGroup =
                    mGroupManager.getGroupForSummary(summaryEntry.getSbn());
            assertNull(summaryGroup.alertOverride);
            return;
        }
        int max2Siblings = Math.min(2, numSiblings);

        // Verify that the summary group has the priority child as its alertOverride
        NotificationGroup summaryGroup = mGroupManager.getGroupForSummary(summaryEntry.getSbn());
        assertEquals(priorityEntry, summaryGroup.alertOverride);
        verify(groupChangeListener).onGroupAlertOverrideChanged(summaryGroup, null, priorityEntry);
        verify(groupChangeListener).onGroupSuppressionChanged(summaryGroup, true);
        if (numSiblings > 1) {
            verify(groupChangeListener).onGroupSuppressionChanged(summaryGroup, false);
        }
        verify(groupChangeListener).onGroupCreated(any(), eq(priorityEntry.getKey()));
        verify(groupChangeListener).onGroupCreated(any(), eq(summaryEntry.getSbn().getGroupKey()));
        verify(groupChangeListener, times(max2Siblings + 1)).onGroupsChanged();
        verifyNoMoreInteractions(groupChangeListener);

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
        verify(groupChangeListener).onGroupAlertOverrideChanged(summaryGroup, priorityEntry, null);
        verify(groupChangeListener).onGroupRemoved(any(), eq(priorityEntry.getKey()));
        verify(groupChangeListener, times(max2Siblings + 2)).onGroupsChanged();
        if (numSiblings == 0) {
            verify(groupChangeListener).onGroupSuppressionChanged(summaryGroup, false);
        }
        verifyNoMoreInteractions(groupChangeListener);
    }

    @Test
    public void testAlertOverrideWhenUpdatingSummaryAtEnd() {
        long when = 10000;
        int numSiblings = 2;
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        // Create entries in an order so that the priority entry can be deemed the newest child.
        NotificationEntry[] siblings = new NotificationEntry[numSiblings];
        for (int i = 0; i < numSiblings; i++) {
            siblings[i] =
                    mGroupTestHelper.createChildNotification(groupAlert, i, "sibling", ++when);
        }
        NotificationEntry priorityEntry =
                mGroupTestHelper.createChildNotification(groupAlert, 0, "priority", ++when);
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(groupAlert, 0, "summary", ++when);

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

        int max2Siblings = Math.min(2, numSiblings);

        // Verify that the summary group has the priority child as its alertOverride
        NotificationGroup summaryGroup = mGroupManager.getGroupForSummary(summaryEntry.getSbn());
        assertEquals(priorityEntry, summaryGroup.alertOverride);
        verify(groupChangeListener).onGroupAlertOverrideChanged(summaryGroup, null, priorityEntry);
        verify(groupChangeListener).onGroupSuppressionChanged(summaryGroup, true);
        if (numSiblings > 1) {
            verify(groupChangeListener).onGroupSuppressionChanged(summaryGroup, false);
        }
        verify(groupChangeListener).onGroupCreated(any(), eq(priorityEntry.getKey()));
        verify(groupChangeListener).onGroupCreated(any(), eq(summaryEntry.getSbn().getGroupKey()));
        verify(groupChangeListener, times(max2Siblings + 1)).onGroupsChanged();
        verifyNoMoreInteractions(groupChangeListener);

        // Verify that only the priority notification is isolated from the group
        assertEquals(priorityEntry, mGroupManager.getGroupSummary(priorityEntry));
        assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(priorityEntry));
        // Verify that the siblings are NOT isolated from the group
        for (int i = 0; i < numSiblings; i++) {
            assertEquals(summaryEntry, mGroupManager.getGroupSummary(siblings[i]));
            assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(siblings[i]));
        }

        Log.d("NotificationGroupManagerLegacyTest",
                "testAlertOverrideWhenUpdatingSummaryAtEnd: About to update summary");

        StatusBarNotification oldSummarySbn = mGroupTestHelper.incrementPost(summaryEntry, 10000);
        mGroupManager.onEntryUpdated(summaryEntry, oldSummarySbn);

        verify(groupChangeListener, times(max2Siblings + 2)).onGroupsChanged();
        verify(groupChangeListener).onGroupAlertOverrideChanged(summaryGroup, priorityEntry, null);
        verifyNoMoreInteractions(groupChangeListener);

        Log.d("NotificationGroupManagerLegacyTest",
                "testAlertOverrideWhenUpdatingSummaryAtEnd: About to update priority child");

        StatusBarNotification oldPrioritySbn = mGroupTestHelper.incrementPost(priorityEntry, 10000);
        mGroupManager.onEntryUpdated(priorityEntry, oldPrioritySbn);

        verify(groupChangeListener).onGroupRemoved(any(), eq(priorityEntry.getKey()));
        verify(groupChangeListener, times(2)).onGroupCreated(any(), eq(priorityEntry.getKey()));
        verify(groupChangeListener, times(2))
                .onGroupAlertOverrideChanged(summaryGroup, null, priorityEntry);
        verify(groupChangeListener, times(max2Siblings + 3)).onGroupsChanged();
        verifyNoMoreInteractions(groupChangeListener);

        Log.d("NotificationGroupManagerLegacyTest",
                "testAlertOverrideWhenUpdatingSummaryAtEnd: Done");
    }
}
