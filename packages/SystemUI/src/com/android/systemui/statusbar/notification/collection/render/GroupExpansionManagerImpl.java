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

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.Coordinator;
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
public class GroupExpansionManagerImpl implements GroupExpansionManager, Coordinator {
    private final GroupMembershipManager mGroupMembershipManager;
    private final Set<OnGroupExpansionChangeListener> mOnGroupChangeListeners = new HashSet<>();

    // Set of summary keys whose groups are expanded
    private final Set<NotificationEntry> mExpandedGroups = new HashSet<>();

    @Inject
    public GroupExpansionManagerImpl(GroupMembershipManager groupMembershipManager) {
        mGroupMembershipManager = groupMembershipManager;
    }

    /**
     * Cleanup entries from mExpandedGroups that no longer exist in the pipeline.
     */
    private final OnBeforeRenderListListener mNotifTracker = (entries) -> {
        final Set<NotificationEntry> renderingSummaries = new HashSet<>();
        for (ListEntry entry : entries) {
            if (entry instanceof GroupEntry) {
                renderingSummaries.add(entry.getRepresentativeEntry());
            }
        }
        mExpandedGroups.removeIf(expandedGroup -> !renderingSummaries.contains(expandedGroup));
    };

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addOnBeforeRenderListListener(mNotifTracker);
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
        final NotificationEntry groupSummary = mGroupMembershipManager.getGroupSummary(entry);
        if (expanded) {
            mExpandedGroups.add(groupSummary);
        } else {
            mExpandedGroups.remove(groupSummary);
        }

        sendOnGroupExpandedChange(entry, expanded);
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
    public void dump(PrintWriter pw, String[] args) {
        pw.println("NotificationEntryExpansion state:");
        pw.println("  # expanded groups: " +  mExpandedGroups.size());
        for (NotificationEntry entry : mExpandedGroups) {
            pw.println("    summary key of expanded group: " + entry.getKey());
        }
    }

    private void sendOnGroupExpandedChange(NotificationEntry entry, boolean expanded) {
        for (OnGroupExpansionChangeListener listener : mOnGroupChangeListeners) {
            listener.onGroupExpansionChange(entry.getRow(), expanded);
        }
    }
}
