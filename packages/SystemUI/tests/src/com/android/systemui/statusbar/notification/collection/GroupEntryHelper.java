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

import java.util.List;

/**
 * Helper class to provide methods for test classes that need {@link GroupEntry}'s for their tests.
 */
public class GroupEntryHelper {
    /**
     * Create a group entry for testing purposes.
     * @param groupKey group key for the group and all its entries
     * @param summary summary notification for group
     * @param children group's children notifications
     */
    public static final GroupEntry createGroup(
            String groupKey,
            NotificationEntry summary,
            List<NotificationEntry> children) {
        GroupEntry groupEntry = new GroupEntry(groupKey);
        groupEntry.setSummary(summary);
        for (NotificationEntry child : children) {
            groupEntry.addChild(child);
        }
        return groupEntry;
    }
}
