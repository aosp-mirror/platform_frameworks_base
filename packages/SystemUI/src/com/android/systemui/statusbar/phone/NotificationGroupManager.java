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
        if (notif.isGroupSummary()) {
            group.summary = null;
        } else {
            group.children.remove(removed);
        }
        if (group.children.isEmpty()) {
            if (group.summary == null) {
                mGroupMap.remove(groupKey);
            } else {
                if (group.expanded) {
                    // only the summary is left. Change it to unexpanded in a few ms. We do this to
                    // avoid raceconditions
                    removed.row.post(new Runnable() {
                        @Override
                        public void run() {
                            if (group.children.isEmpty()) {
                                setGroupExpanded(sbn, false);
                            }
                        }
                    });
                } else {
                    group.summary.row.updateExpandButton();
                }
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
        if (notif.isGroupSummary()) {
            group.summary = added;
            group.expanded = added.row.areChildrenExpanded();
            if (!group.children.isEmpty()) {
                mListener.onGroupCreatedFromChildren(group);
            }
        } else {
            group.children.add(added);
            if (group.summary != null && group.children.size() == 1 && !group.expanded) {
                group.summary.row.updateExpandButton();
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
        if (areGroupsProhibited()) {
            return false;
        }
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
        boolean prohibitedBefore = areGroupsProhibited();
        mBarState = newState;
        boolean nowProhibited = areGroupsProhibited();
        if (nowProhibited != prohibitedBefore) {
            if (nowProhibited) {
                for (NotificationGroup group : mGroupMap.values()) {
                    if (group.expanded) {
                        setGroupExpanded(group, false);
                    }
                }
            }
            mListener.onGroupsProhibitedChanged();
        }
    }

    private boolean areGroupsProhibited() {
        return mBarState == StatusBarState.KEYGUARD;
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

    public ExpandableNotificationRow getGroupSummary(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        return group == null ? null
                : group.summary == null ? null
                : group.summary.row;
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
         * Children group policy has changed and children may no be prohibited or allowed.
         */
        void onGroupsProhibitedChanged();

        /**
         * A group of children just received a summary notification and should therefore become
         * children of it.
         *
         * @param group the group created
         */
        void onGroupCreatedFromChildren(NotificationGroup group);
    }
}
