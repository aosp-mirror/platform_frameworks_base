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

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

/**
 * Provides grouping information for notification entries including information about a group's
 * expanded state.
 */
@SysUISingleton
public class GroupExpansionManagerImpl implements GroupExpansionManager, Dumpable {
    private final DumpManager mDumpManager;
    private final GroupMembershipManager mGroupMembershipManager;
    private final Set<OnGroupExpansionChangeListener> mOnGroupChangeListeners = new HashSet<>();

    /**
     * Set of summary keys whose groups are expanded.
     * NOTE: This should not be modified without notifying listeners, so prefer using
     * {@code setGroupExpanded} when making changes.
     */
    private final Set<NotificationEntry> mExpandedGroups = new HashSet<>();

    private final FeatureFlags mFeatureFlags;

    @Inject
    public GroupExpansionManagerImpl(DumpManager dumpManager,
            GroupMembershipManager groupMembershipManager, FeatureFlags featureFlags) {
        mDumpManager = dumpManager;
        mGroupMembershipManager = groupMembershipManager;
        mFeatureFlags = featureFlags;
    }

    /**
     * Cleanup entries from mExpandedGroups that no longer exist in the pipeline.
     */
    private final OnBeforeRenderListListener mNotifTracker = (entries) -> {
        if (mExpandedGroups.isEmpty()) {
            return; // nothing to do
        }

        final Set<NotificationEntry> renderingSummaries = new HashSet<>();
        for (ListEntry entry : entries) {
            if (entry instanceof GroupEntry) {
                renderingSummaries.add(entry.getRepresentativeEntry());
            }
        }

        // If a group is in mExpandedGroups but not in the pipeline entries, collapse it.
        final var groupsToRemove = setDifference(mExpandedGroups, renderingSummaries);
        for (NotificationEntry entry : groupsToRemove) {
            setGroupExpanded(entry, false);
        }
    };

    public void attach(NotifPipeline pipeline) {
        if (mFeatureFlags.isEnabled(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE)) {
            mDumpManager.registerDumpable(this);
            pipeline.addOnBeforeRenderListListener(mNotifTracker);
        }
    }

    @Override
    public void registerGroupExpansionChangeListener(OnGroupExpansionChangeListener listener) {
        mOnGroupChangeListeners.add(listener);
    }

    @Override
    public boolean isGroupExpanded(NotificationEntry entry) {
        return mExpandedGroups.contains(mGroupMembershipManager.getGroupSummary(entry));
    }

    @Override
    public void setGroupExpanded(NotificationEntry entry, boolean expanded) {
        NotificationEntry groupSummary = mGroupMembershipManager.getGroupSummary(entry);
        if (mFeatureFlags.isEnabled(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE)
                && entry.getParent() == null) {
            if (expanded) {
                throw new IllegalArgumentException("Cannot expand group that is not attached");
            } else {
                // The entry is no longer attached, but we still want to make sure we don't have
                // a stale expansion state.
                groupSummary = entry;
            }
        }

        boolean changed;
        if (expanded) {
            changed = mExpandedGroups.add(groupSummary);
        } else {
            changed = mExpandedGroups.remove(groupSummary);
        }

        // Only notify listeners if something changed.
        if (!mFeatureFlags.isEnabled(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE) || changed) {
            sendOnGroupExpandedChange(entry, expanded);
        }
    }

    @Override
    public boolean toggleGroupExpansion(NotificationEntry entry) {
        setGroupExpanded(entry, !isGroupExpanded(entry));
        return isGroupExpanded(entry);
    }

    @Override
    public void collapseGroups() {
        for (NotificationEntry entry : new ArrayList<>(mExpandedGroups)) {
            setGroupExpanded(entry, false);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("NotificationEntryExpansion state:");
        pw.println("  mExpandedGroups: " + mExpandedGroups.size());
        for (NotificationEntry entry : mExpandedGroups) {
            pw.println("  * " + entry.getKey());
        }
    }

    private void sendOnGroupExpandedChange(NotificationEntry entry, boolean expanded) {
        for (OnGroupExpansionChangeListener listener : mOnGroupChangeListeners) {
            listener.onGroupExpansionChange(entry.getRow(), expanded);
        }
    }

    /**
     * Utility method to compute the difference between two sets of NotificationEntry. Unfortunately
     * {@code Sets.difference} from Guava is not available in this codebase.
     */
    @NonNull
    private Set<NotificationEntry> setDifference(Set<NotificationEntry> set1,
            Set<NotificationEntry> set2) {
        if (set1 == null || set1.isEmpty()) {
            return new HashSet<>();
        }
        if (set2 == null || set2.isEmpty()) {
            return new HashSet<>(set1);
        }

        final Set<NotificationEntry> difference = new HashSet<>();
        for (NotificationEntry e : set1) {
            if (!set2.contains(e)) {
                difference.add(e);
            }
        }
        return difference;
    }
}
