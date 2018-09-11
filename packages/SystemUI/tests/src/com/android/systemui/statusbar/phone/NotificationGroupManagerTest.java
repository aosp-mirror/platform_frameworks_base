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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.NotificationGroupManager.OnGroupChangeListener;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGroupManagerTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final String TEST_CHANNEL_ID = "test_channel";
    private static final String TEST_GROUP_ID = "test_group";
    private static final String TEST_PACKAGE_NAME = "test_pkg";
    private NotificationGroupManager mGroupManager;
    private int mId = 0;

    @Mock HeadsUpManager mHeadsUpManager;
    @Mock AmbientPulseManager mAmbientPulseManager;

    @Before
    public void setup() {
        mDependency.injectTestDependency(AmbientPulseManager.class, mAmbientPulseManager);

        initializeGroupManager();
    }

    private void initializeGroupManager() {
        mGroupManager = new NotificationGroupManager();
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
        mGroupManager.setOnGroupChangeListener(mock(OnGroupChangeListener.class));
    }

    @Test
    public void testIsOnlyChildInGroup() {
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        assertTrue(mGroupManager.isOnlyChildInGroup(childEntry.notification));
    }

    @Test
    public void testIsChildInGroupWithSummary() {
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(createChildNotification());

        assertTrue(mGroupManager.isChildInGroupWithSummary(childEntry.notification));
    }

    @Test
    public void testIsSummaryOfGroupWithChildren() {
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(createChildNotification());

        assertTrue(mGroupManager.isSummaryOfGroup(summaryEntry.notification));
        assertEquals(summaryEntry.row, mGroupManager.getGroupSummary(childEntry.notification));
    }

    @Test
    public void testRemoveChildFromGroupWithSummary() {
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(createChildNotification());

        mGroupManager.onEntryRemoved(childEntry);

        assertFalse(mGroupManager.isChildInGroupWithSummary(childEntry.notification));
    }

    @Test
    public void testRemoveSummaryFromGroupWithSummary() {
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(createChildNotification());

        mGroupManager.onEntryRemoved(summaryEntry);

        assertNull(mGroupManager.getGroupSummary(childEntry.notification));
        assertFalse(mGroupManager.isSummaryOfGroup(summaryEntry.notification));
    }

    @Test
    public void testHeadsUpEntryIsIsolated() {
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(createChildNotification());
        when(mHeadsUpManager.isAlerting(childEntry.key)).thenReturn(true);

        mGroupManager.onHeadsUpStateChanged(childEntry, true);

        // Child entries that are heads upped should be considered separate groups visually even if
        // they are the same group logically
        assertEquals(childEntry.row, mGroupManager.getGroupSummary(childEntry.notification));
        assertEquals(summaryEntry.row,
                mGroupManager.getLogicalGroupSummary(childEntry.notification));
    }

    @Test
    public void testAmbientPulseEntryIsIsolated() {
        mGroupManager.setDozing(true);
        NotificationData.Entry childEntry = createChildNotification();
        NotificationData.Entry summaryEntry = createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(createChildNotification());
        when(mAmbientPulseManager.isAlerting(childEntry.key)).thenReturn(true);

        mGroupManager.onAmbientStateChanged(childEntry, true);

        // Child entries that are heads upped should be considered separate groups visually even if
        // they are the same group logically
        assertEquals(childEntry.row, mGroupManager.getGroupSummary(childEntry.notification));
        assertEquals(summaryEntry.row,
                mGroupManager.getLogicalGroupSummary(childEntry.notification));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransfersToChild() {
        NotificationData.Entry summaryEntry = createSummaryNotification();
        when(mHeadsUpManager.isAlerting(summaryEntry.key)).thenReturn(true);
        NotificationData.Entry childEntry = createChildNotification();

        // Summary will be suppressed because there is only one child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // A suppressed summary should transfer its heads up state to the child.
        verify(mHeadsUpManager, never()).showNotification(summaryEntry);
        verify(mHeadsUpManager).showNotification(childEntry);
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransfersToChildButBackAgain() {
        mHeadsUpManager = new HeadsUpManager(mContext) {};
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
        NotificationData.Entry summaryEntry =
                createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationData.Entry childEntry =
                createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationData.Entry childEntry2 =
                createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of heads up state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Add second child notification so that summary is no longer suppressed.
        mGroupManager.onEntryAdded(childEntry2);

        // The heads up state should transfer back to the summary as there is now more than one
        // child and the summary should no longer be suppressed.
        assertTrue(mHeadsUpManager.isAlerting(summaryEntry.key));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.key));
    }

    @Test
    public void testSuppressedSummaryAmbientPulseTransfersToChild() {
        mGroupManager.setDozing(true);
        NotificationData.Entry summaryEntry = createSummaryNotification();
        when(mAmbientPulseManager.isAlerting(summaryEntry.key)).thenReturn(true);
        NotificationData.Entry childEntry = createChildNotification();

        // Summary will be suppressed because there is only one child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // A suppressed summary should transfer its ambient state to the child.
        verify(mAmbientPulseManager, never()).showNotification(summaryEntry);
        verify(mAmbientPulseManager).showNotification(childEntry);
    }

    @Test
    public void testSuppressedSummaryAmbientPulseTransfersToChildButBackAgain() {
        mGroupManager.setDozing(true);
        mAmbientPulseManager = new AmbientPulseManager(mContext);
        mDependency.injectTestDependency(AmbientPulseManager.class, mAmbientPulseManager);
        initializeGroupManager();
        NotificationData.Entry summaryEntry =
                createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationData.Entry childEntry =
                createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationData.Entry childEntry2 =
                createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mAmbientPulseManager.showNotification(summaryEntry);
        // Trigger a transfer of ambient state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Add second child notification so that summary is no longer suppressed.
        mGroupManager.onEntryAdded(childEntry2);

        // The ambient state should transfer back to the summary as there is now more than one
        // child and the summary should no longer be suppressed.
        assertTrue(mAmbientPulseManager.isAlerting(summaryEntry.key));
        assertFalse(mAmbientPulseManager.isAlerting(childEntry.key));
    }

    private NotificationData.Entry createSummaryNotification() {
        return createSummaryNotification(Notification.GROUP_ALERT_ALL);
    }

    private NotificationData.Entry createSummaryNotification(int groupAlertBehavior) {
        return createEntry(true, groupAlertBehavior);
    }

    private NotificationData.Entry createChildNotification() {
        return createChildNotification(Notification.GROUP_ALERT_ALL);
    }

    private NotificationData.Entry createChildNotification(int groupAlertBehavior) {
        return createEntry(false, groupAlertBehavior);
    }

    private NotificationData.Entry createEntry(boolean isSummary, int groupAlertBehavior) {
        Notification notif = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("Title")
                .setSmallIcon(R.drawable.ic_person)
                .setGroupAlertBehavior(groupAlertBehavior)
                .setGroupSummary(isSummary)
                .setGroup(TEST_GROUP_ID)
                .build();
        StatusBarNotification sbn = new StatusBarNotification(
                TEST_PACKAGE_NAME /* pkg */,
                TEST_PACKAGE_NAME,
                mId++,
                null /* tag */,
                0, /* uid */
                0 /* initialPid */,
                notif,
                new UserHandle(ActivityManager.getCurrentUser()),
                null /* overrideGroupKey */,
                0 /* postTime */);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        entry.row = row;
        when(row.getEntry()).thenReturn(entry);
        when(row.getStatusBarNotification()).thenReturn(sbn);
        return entry;
    }
}
