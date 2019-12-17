/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationEntryBuilder;
import com.android.systemui.statusbar.RankingBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class GroupEntryTest extends SysuiTestCase {
    @Test
    public void testIsHighPriority_addChild() {
        // GIVEN a GroupEntry with a lowPrioritySummary and no children
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        final NotificationEntry lowPrioritySummary = createNotifEntry(false);
        setSummary(parentEntry, lowPrioritySummary);
        assertFalse(parentEntry.isHighPriority());

        // WHEN we add a high priority child and invalidate derived members
        addChild(parentEntry, createNotifEntry(true));
        parentEntry.invalidateDerivedMembers();

        // THEN the GroupEntry's priority is updated to high even though the summary is still low
        // priority
        assertTrue(parentEntry.isHighPriority());
        assertFalse(lowPrioritySummary.isHighPriority());
    }

    @Test
    public void testIsHighPriority_clearChildren() {
        // GIVEN a GroupEntry with a lowPrioritySummary and high priority children
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        setSummary(parentEntry, createNotifEntry(false));
        addChild(parentEntry, createNotifEntry(true));
        addChild(parentEntry, createNotifEntry(true));
        addChild(parentEntry, createNotifEntry(true));
        assertTrue(parentEntry.isHighPriority());

        // WHEN we clear the children and invalidate derived members
        parentEntry.clearChildren();
        parentEntry.invalidateDerivedMembers();

        // THEN the parentEntry isn't high priority anymore
        assertFalse(parentEntry.isHighPriority());
    }

    @Test
    public void testIsHighPriority_summaryUpdated() {
        // GIVEN a GroupEntry with a lowPrioritySummary and no children
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        final NotificationEntry lowPrioritySummary = createNotifEntry(false);
        setSummary(parentEntry, lowPrioritySummary);
        assertFalse(parentEntry.isHighPriority());

        // WHEN the summary changes to high priority and invalidates its derived members
        lowPrioritySummary.setRanking(
                new RankingBuilder()
                        .setKey(lowPrioritySummary.getKey())
                        .setImportance(IMPORTANCE_HIGH)
                        .build());
        lowPrioritySummary.invalidateDerivedMembers();
        assertTrue(lowPrioritySummary.isHighPriority());

        // THEN the GroupEntry's priority is updated to high
        assertTrue(parentEntry.isHighPriority());
    }

    @Test
    public void testIsHighPriority_checkChildrenToCalculatePriority() {
        // GIVEN:
        // GroupEntry = parentEntry, summary = lowPrioritySummary
        //      NotificationEntry = lowPriorityChild
        //      NotificationEntry = highPriorityChild
        final GroupEntry parentEntry = new GroupEntry("test_group_key");
        setSummary(parentEntry, createNotifEntry(false));
        addChild(parentEntry, createNotifEntry(false));
        addChild(parentEntry, createNotifEntry(true));

        // THEN the GroupEntry parentEntry is high priority since it has a high priority child
        assertTrue(parentEntry.isHighPriority());
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

        // WHEN the child entry ranking changes to high priority and invalidates its derived members
        lowPriorityChild.setRanking(
                new RankingBuilder()
                        .setKey(lowPriorityChild.getKey())
                        .setImportance(IMPORTANCE_HIGH)
                        .build());
        lowPriorityChild.invalidateDerivedMembers();

        // THEN the parent entry's high priority value is updated - but not the parent's summary
        assertTrue(parentEntry.isHighPriority());
        assertFalse(parentEntry.getSummary().isHighPriority());
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
