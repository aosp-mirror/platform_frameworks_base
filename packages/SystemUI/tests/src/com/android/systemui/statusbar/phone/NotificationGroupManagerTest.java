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
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
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

    private NotificationGroupManager mGroupManager;
    private final NotificationGroupTestHelper mGroupTestHelper =
            new NotificationGroupTestHelper(mContext);

    @Mock HeadsUpManager mHeadsUpManager;

    @Before
    public void setup() {
        initializeGroupManager();
    }

    private void initializeGroupManager() {
        mGroupManager = new NotificationGroupManager(mock(StatusBarStateController.class));
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
    }

    @Test
    public void testIsOnlyChildInGroup() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        assertTrue(mGroupManager.isOnlyChildInGroup(childEntry.notification));
    }

    @Test
    public void testIsChildInGroupWithSummary() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        assertTrue(mGroupManager.isChildInGroupWithSummary(childEntry.notification));
    }

    @Test
    public void testIsSummaryOfGroupWithChildren() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        assertTrue(mGroupManager.isSummaryOfGroup(summaryEntry.notification));
        assertEquals(summaryEntry, mGroupManager.getGroupSummary(childEntry.notification));
    }

    @Test
    public void testRemoveChildFromGroupWithSummary() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        mGroupManager.onEntryRemoved(childEntry);

        assertFalse(mGroupManager.isChildInGroupWithSummary(childEntry.notification));
    }

    @Test
    public void testRemoveSummaryFromGroupWithSummary() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        mGroupManager.onEntryRemoved(summaryEntry);

        assertNull(mGroupManager.getGroupSummary(childEntry.notification));
        assertFalse(mGroupManager.isSummaryOfGroup(summaryEntry.notification));
    }

    @Test
    public void testHeadsUpEntryIsIsolated() {
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());
        when(mHeadsUpManager.isAlerting(childEntry.key)).thenReturn(true);

        mGroupManager.onHeadsUpStateChanged(childEntry, true);

        // Child entries that are heads upped should be considered separate groups visually even if
        // they are the same group logically
        assertEquals(childEntry, mGroupManager.getGroupSummary(childEntry.notification));
        assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(childEntry.notification));
    }
}
