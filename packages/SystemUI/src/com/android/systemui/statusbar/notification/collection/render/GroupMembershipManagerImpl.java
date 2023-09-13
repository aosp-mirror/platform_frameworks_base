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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlagsClassic;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

import javax.inject.Inject;

/**
 * ShadeListBuilder groups notifications from system server. This manager translates
 * ShadeListBuilder's method of grouping to be used within SystemUI.
 */
@SysUISingleton
public class GroupMembershipManagerImpl implements GroupMembershipManager {
    FeatureFlagsClassic mFeatureFlags;

    @Inject
    public GroupMembershipManagerImpl(FeatureFlagsClassic featureFlags) {
        mFeatureFlags = featureFlags;
    }

    @Override
    public boolean isGroupSummary(@NonNull NotificationEntry entry) {
        if (mFeatureFlags.isEnabled(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE)) {
            if (entry.getParent() == null) {
                // The entry is not attached, so it doesn't count.
                return false;
            }
            // If entry is a summary, its parent is a GroupEntry with summary = entry.
            return entry.getParent().getSummary() == entry;
        } else {
            return getGroupSummary(entry) == entry;
        }
    }

    @Nullable
    @Override
    public NotificationEntry getGroupSummary(@NonNull NotificationEntry entry) {
        if (isTopLevelEntry(entry) || entry.getParent() == null) {
            return null;
        }
        return entry.getParent().getSummary();
    }

    @Override
    public boolean isChildInGroup(@NonNull NotificationEntry entry) {
        if (mFeatureFlags.isEnabled(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE)) {
            // An entry is a child if it's not a summary or top level entry, but it is attached.
            return !isGroupSummary(entry) && !isTopLevelEntry(entry) && entry.getParent() != null;
        } else {
            return !isTopLevelEntry(entry);
        }
    }

    @Override
    public boolean isOnlyChildInGroup(@NonNull NotificationEntry entry) {
        if (entry.getParent() == null) {
            return false; // The entry is not attached.
        }

        return !isGroupSummary(entry) && entry.getParent().getChildren().size() == 1;
    }

    @Nullable
    @Override
    public List<NotificationEntry> getChildren(@NonNull ListEntry entry) {
        if (entry instanceof GroupEntry) {
            return ((GroupEntry) entry).getChildren();
        }

        NotificationEntry representativeEntry = entry.getRepresentativeEntry();
        if (representativeEntry != null && isGroupSummary(representativeEntry)) {
            // maybe we were actually passed the summary
            GroupEntry parent = representativeEntry.getParent();
            if (parent != null) {
                return parent.getChildren();
            }
        }

        return null;
    }

    private boolean isTopLevelEntry(@NonNull NotificationEntry entry) {
        return entry.getParent() == ROOT_ENTRY;
    }
}
