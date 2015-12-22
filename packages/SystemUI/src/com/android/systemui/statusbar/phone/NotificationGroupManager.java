/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.service.notification.StatusBarNotification;
import android.util.ArraySet;

import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.util.HashMap;
import java.util.HashSet;

/**
 * A class to handle notifications and their corresponding groups.
 */
public class NotificationGroupManager implements HeadsUpManager.OnHeadsUpChangedListener {

    private final HashMap<String, NotificationGroup> mGroupMap = new HashMap<>();
    private OnGroupChangeListener mListener;
    private int mBarState = -1;
    private ArraySet<String> mHeadsUpedEntries = new ArraySet<>();

    public void setOnGroupChangeListener(OnGroupChangeListener listener) {
        mListener = listener;
    }

    public boolean isGroupExpanded(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return false;
        }
        return group.expanded;
    }

    public void setGroupExpanded(StatusBarNotification sbn, boolean expanded) {
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return;
        }
        setGroupExpanded(group, expanded);
    }

    private void setGroupExpanded(NotificationGroup group, boolean expanded) {
        group.expanded = expanded;
        if (group.summary != null) {
            mListener.onGroupExpansionChanged(group.summary.row, expanded);
        }
    }

    public void onEntryRemoved(NotificationData.Entry removed) {
        onEntryRemovedInternal(removed, removed.notification);
    }

    /**
     * An entry was removed.
     *
     * @param removed the removed entry
     * @param sbn the notification the entry has, which doesn't need to be the same as it's internal
     *            notification
     */
    private void onEntryRemovedInternal(NotificationData.Entry removed,
            final StatusBarNotification sbn) {
        String groupKey = getGroupKey(sbn);
        final NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            // When an app posts 2 different notifications as summary of the same group, then a
            // cancellation of the first notification removes this group.
            // This situation is not supported and we will not allow such notifications anymore in
            // the close future. See b/23676310 for reference.
            return;
        }
        if (isGroupChild(sbn)) {
            group.children.remove(removed);
        } else {
            group.summary = null;
        }
        if (group.children.isEmpty()) {
            if (group.summary == null) {
                mGroupMap.remove(groupKey);
            }
        }
    }

    public void onEntryAdded(final NotificationData.Entry added) {
        final StatusBarNotification sbn = added.notification;
        boolean isGroupChild = isGroupChild(sbn);
        String groupKey = getGroupKey(sbn);
        NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            group = new NotificationGroup();
            mGroupMap.put(groupKey, group);
        }
        if (isGroupChild) {
            group.children.add(added);
        } else {
            group.summary = added;
            group.expanded = added.row.areChildrenExpanded();
            if (!group.children.isEmpty()) {
                mListener.onGroupCreatedFromChildren(group);
            }
        }
    }

    public void onEntryUpdated(NotificationData.Entry entry,
            StatusBarNotification oldNotification) {
        if (mGroupMap.get(getGroupKey(oldNotification)) != null) {
            onEntryRemovedInternal(entry, oldNotification);
        }
        onEntryAdded(entry);
    }

    public boolean isVisible(StatusBarNotification sbn) {
        if (!isGroupChild(sbn)) {
            return true;
        }
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group != null && (group.expanded || group.summary == null)) {
            return true;
        }
        return false;
    }

    public boolean hasGroupChildren(StatusBarNotification sbn) {
        if (!isGroupSummary(sbn)) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return false;
        }
        return !group.children.isEmpty();
    }

    public void setStatusBarState(int newState) {
        if (mBarState == newState) {
            return;
        }
        mBarState = newState;
        if (mBarState == StatusBarState.KEYGUARD) {
            for (NotificationGroup group : mGroupMap.values()) {
                if (group.expanded) {
                    setGroupExpanded(group, false);
                }
            }
        }
    }

    /**
     * @return whether a given notification is a child in a group which has a summary
     */
    public boolean isChildInGroupWithSummary(StatusBarNotification sbn) {
        if (!isGroupChild(sbn)) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null || group.summary == null) {
            return false;
        }
        return true;
    }

    /**
     * @return whether a given notification is a summary in a group which has children
     */
    public boolean isSummaryOfGroup(StatusBarNotification sbn) {
        if (!isGroupSummary(sbn)) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return false;
        }
        return !group.children.isEmpty();
    }

    public ExpandableNotificationRow getGroupSummary(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        return group == null ? null
                : group.summary == null ? null
                : group.summary.row;
    }

    public void toggleGroupExpansion(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return;
        }
        setGroupExpanded(group, !group.expanded);
    }

    private boolean isIsolated(StatusBarNotification sbn) {
        return mHeadsUpedEntries.contains(sbn.getKey()) && sbn.getNotification().isGroupChild();
    }

    private boolean isGroupSummary(StatusBarNotification sbn) {
        if (isIsolated(sbn)) {
            return true;
        }
        return sbn.getNotification().isGroupSummary();
    }
    private boolean isGroupChild(StatusBarNotification sbn) {
        if (isIsolated(sbn)) {
            return false;
        }
        return sbn.getNotification().isGroupChild();
    }

    private String getGroupKey(StatusBarNotification sbn) {
        if (isIsolated(sbn)) {
            return sbn.getKey();
        }
        return sbn.getGroupKey();
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
    }

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {
    }

    @Override
    public void onHeadsUpStateChanged(NotificationData.Entry entry, boolean isHeadsUp) {
        final StatusBarNotification sbn = entry.notification;
        if (entry.row.isHeadsUp()) {
            if (!mHeadsUpedEntries.contains(sbn.getKey())) {
                final boolean groupChild = sbn.getNotification().isGroupChild();
                if (groupChild) {
                    // We will be isolated now, so lets update the groups
                    onEntryRemovedInternal(entry, entry.notification);
                }
                mHeadsUpedEntries.add(sbn.getKey());
                if (groupChild) {
                    onEntryAdded(entry);
                    mListener.onChildIsolationChanged();
                }
            }
        } else {
            if (mHeadsUpedEntries.contains(sbn.getKey())) {
                boolean isolatedBefore = isIsolated(sbn);
                if (isolatedBefore) {
                    // not isolated anymore, we need to update the groups
                    onEntryRemovedInternal(entry, entry.notification);
                }
                mHeadsUpedEntries.remove(sbn.getKey());
                if (isolatedBefore) {
                    onEntryAdded(entry);
                    mListener.onChildIsolationChanged();
                }
            }
        }
    }

    public static class NotificationGroup {
        public final HashSet<NotificationData.Entry> children = new HashSet<>();
        public NotificationData.Entry summary;
        public boolean expanded;
    }

    public interface OnGroupChangeListener {
        /**
         * The expansion of a group has changed.
         *
         * @param changedRow the row for which the expansion has changed, which is also the summary
         * @param expanded a boolean indicating the new expanded state
         */
        void onGroupExpansionChanged(ExpandableNotificationRow changedRow, boolean expanded);

        /**
         * A group of children just received a summary notification and should therefore become
         * children of it.
         *
         * @param group the group created
         */
        void onGroupCreatedFromChildren(NotificationGroup group);

        /**
         * The isolation of a child has changed i.e it's group changes.
         */
        void onChildIsolationChanged();
    }
}
