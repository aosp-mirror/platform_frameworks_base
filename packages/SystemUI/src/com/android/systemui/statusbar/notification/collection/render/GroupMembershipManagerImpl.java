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

package com.android.systemui.statusbar.notification.collection.render;

import static com.android.systemui.statusbar.notification.collection.GroupEntry.ROOT_ENTRY;

import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

/**
 * ShadeListBuilder groups notifications from system server. This manager translates
 * ShadeListBuilder's method of grouping to be used within SystemUI.
 */
public class GroupMembershipManagerImpl implements GroupMembershipManager {
    @Override
    public boolean isGroupSummary(NotificationEntry entry) {
        return getGroupSummary(entry) == entry;
    }

    @Override
    public NotificationEntry getGroupSummary(NotificationEntry entry) {
        if (isEntryTopLevel(entry) || entry.getParent() == null) {
            return null;
        }

        return entry.getParent().getRepresentativeEntry();
    }

    @Override
    public boolean isChildInGroup(NotificationEntry entry) {
        return !isEntryTopLevel(entry);
    }

    @Override
    public boolean isOnlyChildInGroup(NotificationEntry entry) {
        if (entry.getParent() == null) {
            return false;
        }

        return !isGroupSummary(entry) && entry.getParent().getChildren().size() == 1;
    }

    @Nullable
    @Override
    public List<NotificationEntry> getChildren(ListEntry entry) {
        if (entry instanceof GroupEntry) {
            return ((GroupEntry) entry).getChildren();
        }

        if (isGroupSummary(entry.getRepresentativeEntry())) {
            // maybe we were actually passed the summary
            return entry.getRepresentativeEntry().getParent().getChildren();
        }

        return null;
    }

    private boolean isEntryTopLevel(NotificationEntry entry) {
        return entry.getParent() == ROOT_ENTRY;
    }
}
