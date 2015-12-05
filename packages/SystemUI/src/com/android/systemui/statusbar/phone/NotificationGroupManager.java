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

import android.app.Notification;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarState;

import java.util.HashMap;
import java.util.HashSet;

/**
 * A class to handle notifications and their corresponding groups.
 */
public class NotificationGroupManager {

    private final HashMap<String, NotificationGroup> mGroupMap = new HashMap<>();
    private OnGroupChangeListener mListener;
    private int mBarState = -1;

    public void setOnGroupChangeListener(OnGroupChangeListener listener) {
        mListener = listener;
    }

    public boolean isGroupExpanded(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        if (group == null) {
            return false;
        }
        return group.expanded;
    }

    public void setGroupExpanded(StatusBarNotification sbn, boolean expanded) {
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
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
        Notification notif = sbn.getNotification();
        String groupKey = sbn.getGroupKey();
        final NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            // When an app posts 2 different notifications as summary of the same group, then a
            // cancellation of the first notification removes this group.
            // This situation is not supported and we will not allow such notifications anymore in
            // the close future. See b/23676310 for reference.
            return;
        }
        if (notif.isGroupChild()) {
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

    public void onEntryAdded(NotificationData.Entry added) {
        StatusBarNotification sbn = added.notification;
        Notification notif = sbn.getNotification();
        String groupKey = sbn.getGroupKey();
        NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            group = new NotificationGroup();
            mGroupMap.put(groupKey, group);
        }
        if (notif.isGroupChild()) {
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
        if (mGroupMap.get(oldNotification.getGroupKey()) != null) {
            onEntryRemovedInternal(entry, oldNotification);
        }
        onEntryAdded(entry);
    }

    public boolean isVisible(StatusBarNotification sbn) {
        if (!sbn.getNotification().isGroupChild()) {
            return true;
        }
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        if (group != null && (group.expanded || group.summary == null)) {
            return true;
        }
        return false;
    }

    public boolean hasGroupChildren(StatusBarNotification sbn) {
        if (!sbn.getNotification().isGroupSummary()) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
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
        if (!sbn.getNotification().isGroupChild()) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        if (group == null || group.summary == null) {
            return false;
        }
        return true;
    }

    /**
     * @return whether a given notification is a summary in a group which has children
     */
    public boolean isSummaryOfGroup(StatusBarNotification sbn) {
        if (!sbn.getNotification().isGroupSummary()) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        if (group == null) {
            return false;
        }
        return !group.children.isEmpty();
    }

    public ExpandableNotificationRow getGroupSummary(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        return group == null ? null
                : group.summary == null ? null
                : group.summary.row;
    }

    public void onEntryHeadsUped(NotificationData.Entry headsUp) {
        // TODO: handle this nicely
    }

    public void toggleGroupExpansion(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        if (group == null) {
            return;
        }
        setGroupExpanded(group, !group.expanded);
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
    }
}
