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
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A class to handle notifications and their corresponding groups.
 */
public class NotificationGroupManager implements OnHeadsUpChangedListener {

    private static final String TAG = "NotificationGroupManager";
    private static final long HEADS_UP_TRANSFER_TIMEOUT = 300;
    private final HashMap<String, NotificationGroup> mGroupMap = new HashMap<>();
    private OnGroupChangeListener mListener;
    private int mBarState = -1;
    private HashMap<String, StatusBarNotification> mIsolatedEntries = new HashMap<>();
    private HeadsUpManager mHeadsUpManager;
    private boolean mIsUpdatingUnchangedGroup;
    private HashMap<String, NotificationData.Entry> mPendingNotifications;

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
        mIsolatedEntries.remove(removed.key);
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
            group.children.remove(removed.key);
        } else {
            group.summary = null;
        }
        updateSuppression(group);
        if (group.children.isEmpty()) {
            if (group.summary == null) {
                mGroupMap.remove(groupKey);
            }
        }
    }

    public void onEntryAdded(final NotificationData.Entry added) {
        if (added.row.isRemoved()) {
            added.setDebugThrowable(new Throwable());
        }
        final StatusBarNotification sbn = added.notification;
        boolean isGroupChild = isGroupChild(sbn);
        String groupKey = getGroupKey(sbn);
        NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            group = new NotificationGroup();
            mGroupMap.put(groupKey, group);
        }
        if (isGroupChild) {
            NotificationData.Entry existing = group.children.get(added.key);
            if (existing != null && existing != added) {
                Throwable existingThrowable = existing.getDebugThrowable();
                Log.wtf(TAG, "Inconsistent entries found with the same key " + added.key
                        + "existing removed: " + existing.row.isRemoved()
                        + (existingThrowable != null
                                ? Log.getStackTraceString(existingThrowable) + "\n": "")
                        + " added removed" + added.row.isRemoved()
                        , new Throwable());
            }
            group.children.put(added.key, added);
            updateSuppression(group);
        } else {
            group.summary = added;
            group.expanded = added.row.areChildrenExpanded();
            updateSuppression(group);
            if (!group.children.isEmpty()) {
                ArrayList<NotificationData.Entry> childrenCopy
                        = new ArrayList<>(group.children.values());
                for (NotificationData.Entry child : childrenCopy) {
                    onEntryBecomingChild(child);
                }
                mListener.onGroupCreatedFromChildren(group);
            }
        }
        cleanUpHeadsUpStatesOnAdd(group, false /* addIsPending */);
    }

    public void onPendingEntryAdded(NotificationData.Entry shadeEntry) {
        String groupKey = getGroupKey(shadeEntry.notification);
        NotificationGroup group = mGroupMap.get(groupKey);
        if (group != null) {
            cleanUpHeadsUpStatesOnAdd(group, true /* addIsPending */);
        }
    }

    /**
     * Clean up the heads up states when a new child was added.
     * @param group The group where a view was added or will be added.
     * @param addIsPending True if is the addition still pending or false has it already been added.
     */
    private void cleanUpHeadsUpStatesOnAdd(NotificationGroup group, boolean addIsPending) {
        if (!addIsPending && group.hunSummaryOnNextAddition) {
            if (!mHeadsUpManager.isHeadsUp(group.summary.key)) {
                mHeadsUpManager.showNotification(group.summary);
            }
            group.hunSummaryOnNextAddition = false;
        }
        // Because notification groups are not delivered as a whole unit, it may happen that a
        // group child gets added quite a bit after the summary got posted. Our guidance is, that
        // apps should always post the group summary as well and we'll hide it for them if the child
        // is the only child in a group. Because of this, we also have to transfer heads up to the
        // child, otherwise the invisible summary would be heads-upped.
        // This transfer to the child is not always correct in case the app has just posted another
        // child in addition to the existing one, but it hasn't arrived in systemUI yet. In such
        // a scenario we would transfer the heads up to the old child and the wrong notification
        // would be heads-upped. In oder to avoid this, we'll recover from this issue and hun the
        // summary again instead of the old child if it's within a certain timeout.
        if (SystemClock.elapsedRealtime() - group.lastHeadsUpTransfer < HEADS_UP_TRANSFER_TIMEOUT) {
            if (!onlySummaryAlerts(group.summary)) {
                return;
            }
            int numChildren = group.children.size();
            NotificationData.Entry isolatedChild = getIsolatedChild(getGroupKey(
                    group.summary.notification));
            int numPendingChildren = getPendingChildrenNotAlerting(group);
            numChildren += numPendingChildren;
            if (isolatedChild != null) {
                numChildren++;
            }
            if (numChildren <= 1) {
                return;
            }
            boolean releasedChild = false;
            ArrayList<NotificationData.Entry> children = new ArrayList<>(group.children.values());
            int size = children.size();
            for (int i = 0; i < size; i++) {
                NotificationData.Entry entry = children.get(i);
                if (onlySummaryAlerts(entry) && entry.row.isHeadsUp()) {
                    releasedChild = true;
                    mHeadsUpManager.releaseImmediately(entry.key);
                }
            }
            if (isolatedChild != null && onlySummaryAlerts(isolatedChild)
                    && isolatedChild.row.isHeadsUp()) {
                releasedChild = true;
                mHeadsUpManager.releaseImmediately(isolatedChild.key);
            }
            if (releasedChild && !mHeadsUpManager.isHeadsUp(group.summary.key)) {
                boolean notifyImmediately = (numChildren - numPendingChildren) > 1;
                if (notifyImmediately) {
                    mHeadsUpManager.showNotification(group.summary);
                } else {
                    group.hunSummaryOnNextAddition = true;
                }
                group.lastHeadsUpTransfer = 0;
            }
        }
    }

    private int getPendingChildrenNotAlerting(NotificationGroup group) {
        if (mPendingNotifications == null) {
            return 0;
        }
        int number = 0;
        String groupKey = getGroupKey(group.summary.notification);
        Collection<NotificationData.Entry> values = mPendingNotifications.values();
        for (NotificationData.Entry entry : values) {
            if (!isGroupChild(entry.notification)) {
                continue;
            }
            if (!Objects.equals(getGroupKey(entry.notification), groupKey)) {
                continue;
            }
            if (group.children.containsKey(entry.key)) {
                continue;
            }
            if (onlySummaryAlerts(entry)) {
                number++;
            }
        }
        return number;
    }

    private void onEntryBecomingChild(NotificationData.Entry entry) {
        if (entry.row.isHeadsUp()) {
            onHeadsUpStateChanged(entry, true);
        }
    }

    private void updateSuppression(NotificationGroup group) {
        if (group == null) {
            return;
        }
        boolean prevSuppressed = group.suppressed;
        group.suppressed = group.summary != null && !group.expanded
                && (group.children.size() == 1
                || (group.children.size() == 0
                        && group.summary.notification.getNotification().isGroupSummary()
                        && hasIsolatedChildren(group)));
        if (prevSuppressed != group.suppressed) {
            if (group.suppressed) {
                handleSuppressedSummaryHeadsUpped(group.summary);
            }
            if (!mIsUpdatingUnchangedGroup && mListener != null) {
                mListener.onGroupsChanged();
            }
        }
    }

    private boolean hasIsolatedChildren(NotificationGroup group) {
        return getNumberOfIsolatedChildren(group.summary.notification.getGroupKey()) != 0;
    }

    private int getNumberOfIsolatedChildren(String groupKey) {
        int count = 0;
        for (StatusBarNotification sbn : mIsolatedEntries.values()) {
            if (sbn.getGroupKey().equals(groupKey) && isIsolated(sbn)) {
                count++;
            }
        }
        return count;
    }

    private NotificationData.Entry getIsolatedChild(String groupKey) {
        for (StatusBarNotification sbn : mIsolatedEntries.values()) {
            if (sbn.getGroupKey().equals(groupKey) && isIsolated(sbn)) {
                return mGroupMap.get(sbn.getKey()).summary;
            }
        }
        return null;
    }

    public void onEntryUpdated(NotificationData.Entry entry,
            StatusBarNotification oldNotification) {
        String oldKey = oldNotification.getGroupKey();
        String newKey = entry.notification.getGroupKey();
        boolean groupKeysChanged = !oldKey.equals(newKey);
        boolean wasGroupChild = isGroupChild(oldNotification);
        boolean isGroupChild = isGroupChild(entry.notification);
        mIsUpdatingUnchangedGroup = !groupKeysChanged && wasGroupChild == isGroupChild;
        if (mGroupMap.get(getGroupKey(oldNotification)) != null) {
            onEntryRemovedInternal(entry, oldNotification);
        }
        onEntryAdded(entry);
        mIsUpdatingUnchangedGroup = false;
        if (isIsolated(entry.notification)) {
            mIsolatedEntries.put(entry.key, entry.notification);
            if (groupKeysChanged) {
                updateSuppression(mGroupMap.get(oldKey));
                updateSuppression(mGroupMap.get(newKey));
            }
        } else if (!wasGroupChild && isGroupChild) {
            onEntryBecomingChild(entry);
        }
    }

    public boolean isSummaryOfSuppressedGroup(StatusBarNotification sbn) {
        return isGroupSuppressed(getGroupKey(sbn)) && sbn.getNotification().isGroupSummary();
    }

    private boolean isOnlyChild(StatusBarNotification sbn) {
        return !sbn.getNotification().isGroupSummary()
                && getTotalNumberOfChildren(sbn) == 1;
    }

    public boolean isOnlyChildInGroup(StatusBarNotification sbn) {
        if (!isOnlyChild(sbn)) {
            return false;
        }
        ExpandableNotificationRow logicalGroupSummary = getLogicalGroupSummary(sbn);
        return logicalGroupSummary != null
                && !logicalGroupSummary.getStatusBarNotification().equals(sbn);
    }

    private int getTotalNumberOfChildren(StatusBarNotification sbn) {
        int isolatedChildren = getNumberOfIsolatedChildren(sbn.getGroupKey());
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
        int realChildren = group != null ? group.children.size() : 0;
        return isolatedChildren + realChildren;
    }

    private boolean isGroupSuppressed(String groupKey) {
        NotificationGroup group = mGroupMap.get(groupKey);
        return group != null && group.suppressed;
    }

    public void setStatusBarState(int newState) {
        if (mBarState == newState) {
            return;
        }
        mBarState = newState;
        if (mBarState == StatusBarState.KEYGUARD) {
            collapseAllGroups();
        }
    }

    public void collapseAllGroups() {
        // Because notifications can become isolated when the group becomes suppressed it can
        // lead to concurrent modifications while looping. We need to make a copy.
        ArrayList<NotificationGroup> groupCopy = new ArrayList<>(mGroupMap.values());
        int size = groupCopy.size();
        for (int i = 0; i < size; i++) {
            NotificationGroup group =  groupCopy.get(i);
            if (group.expanded) {
                setGroupExpanded(group, false);
            }
            updateSuppression(group);
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
        if (group == null || group.summary == null || group.suppressed) {
            return false;
        }
        if (group.children.isEmpty()) {
            // If the suppression of a group changes because the last child was removed, this can
            // still be called temporarily because the child hasn't been fully removed yet. Let's
            // make sure we still return false in that case.
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

    /**
     * Get the summary of a specified status bar notification. For isolated notification this return
     * itself.
     */
    public ExpandableNotificationRow getGroupSummary(StatusBarNotification sbn) {
        return getGroupSummary(getGroupKey(sbn));
    }

    /**
     * Similar to {@link #getGroupSummary(StatusBarNotification)} but doesn't get the visual summary
     * but the logical summary, i.e when a child is isolated, it still returns the summary as if
     * it wasn't isolated.
     */
    public ExpandableNotificationRow getLogicalGroupSummary(
            StatusBarNotification sbn) {
        return getGroupSummary(sbn.getGroupKey());
    }

    @Nullable
    private ExpandableNotificationRow getGroupSummary(String groupKey) {
        NotificationGroup group = mGroupMap.get(groupKey);
        return group == null ? null
                : group.summary == null ? null
                        : group.summary.row;
    }

    /** @return group expansion state after toggling. */
    public boolean toggleGroupExpansion(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return false;
        }
        setGroupExpanded(group, !group.expanded);
        return group.expanded;
    }

    private boolean isIsolated(StatusBarNotification sbn) {
        return mIsolatedEntries.containsKey(sbn.getKey());
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
        return sbn.isGroup() && !sbn.getNotification().isGroupSummary();
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
            if (shouldIsolate(sbn)) {
                // We will be isolated now, so lets update the groups
                onEntryRemovedInternal(entry, entry.notification);

                mIsolatedEntries.put(sbn.getKey(), sbn);

                onEntryAdded(entry);
                // We also need to update the suppression of the old group, because this call comes
                // even before the groupManager knows about the notification at all.
                // When the notification gets added afterwards it is already isolated and therefore
                // it doesn't lead to an update.
                updateSuppression(mGroupMap.get(entry.notification.getGroupKey()));
                mListener.onGroupsChanged();
            } else {
                handleSuppressedSummaryHeadsUpped(entry);
            }
        } else {
            if (mIsolatedEntries.containsKey(sbn.getKey())) {
                // not isolated anymore, we need to update the groups
                onEntryRemovedInternal(entry, entry.notification);
                mIsolatedEntries.remove(sbn.getKey());
                onEntryAdded(entry);
                mListener.onGroupsChanged();
            }
        }
    }

    private void handleSuppressedSummaryHeadsUpped(NotificationData.Entry entry) {
        StatusBarNotification sbn = entry.notification;
        if (!isGroupSuppressed(sbn.getGroupKey())
                || !sbn.getNotification().isGroupSummary()
                || !entry.row.isHeadsUp()) {
            return;
        }

        // The parent of a suppressed group got huned, lets hun the child!
        NotificationGroup notificationGroup = mGroupMap.get(sbn.getGroupKey());

        if (pendingInflationsWillAddChildren(notificationGroup)) {
            // New children will actually be added to this group, let's not transfer the heads
            // up
            return;
        }

        if (notificationGroup != null) {
            Iterator<NotificationData.Entry> iterator
                    = notificationGroup.children.values().iterator();
            NotificationData.Entry child = iterator.hasNext() ? iterator.next() : null;
            if (child == null) {
                child = getIsolatedChild(sbn.getGroupKey());
            }
            if (child != null) {
                if (child.row.keepInParent() || child.row.isRemoved() || child.row.isDismissed()) {
                    // the notification is actually already removed, no need to do heads-up on it.
                    return;
                }
                if (mHeadsUpManager.isHeadsUp(child.key)) {
                    mHeadsUpManager.updateNotification(child, true);
                } else {
                    if (onlySummaryAlerts(entry)) {
                        notificationGroup.lastHeadsUpTransfer = SystemClock.elapsedRealtime();
                    }
                    mHeadsUpManager.showNotification(child);
                }
            }
        }
        mHeadsUpManager.releaseImmediately(entry.key);
    }

    private boolean onlySummaryAlerts(NotificationData.Entry entry) {
        return entry.notification.getNotification().getGroupAlertBehavior()
                == Notification.GROUP_ALERT_SUMMARY;
    }

    /**
     * Check if the pending inflations will add children to this group.
     * @param group The group to check.
     */
    private boolean pendingInflationsWillAddChildren(NotificationGroup group) {
        if (mPendingNotifications == null) {
            return false;
        }
        Collection<NotificationData.Entry> values = mPendingNotifications.values();
        String groupKey = getGroupKey(group.summary.notification);
        for (NotificationData.Entry entry : values) {
            if (!isGroupChild(entry.notification)) {
                continue;
            }
            if (!Objects.equals(getGroupKey(entry.notification), groupKey)) {
                continue;
            }
            if (!group.children.containsKey(entry.key)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldIsolate(StatusBarNotification sbn) {
        NotificationGroup notificationGroup = mGroupMap.get(sbn.getGroupKey());
        return (sbn.isGroup() && !sbn.getNotification().isGroupSummary())
                && (sbn.getNotification().fullScreenIntent != null
                        || notificationGroup == null
                        || !notificationGroup.expanded
                        || isGroupNotFullyVisible(notificationGroup));
    }

    private boolean isGroupNotFullyVisible(NotificationGroup notificationGroup) {
        return notificationGroup.summary == null
                || notificationGroup.summary.row.getClipTopAmount() > 0
                || notificationGroup.summary.row.getTranslationY() < 0;
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GroupManager state:");
        pw.println("  number of groups: " +  mGroupMap.size());
        for (Map.Entry<String, NotificationGroup>  entry : mGroupMap.entrySet()) {
            pw.println("\n    key: " + entry.getKey()); pw.println(entry.getValue());
        }
        pw.println("\n    isolated entries: " +  mIsolatedEntries.size());
        for (Map.Entry<String, StatusBarNotification> entry : mIsolatedEntries.entrySet()) {
            pw.print("      "); pw.print(entry.getKey());
            pw.print(", "); pw.println(entry.getValue());
        }
    }

    public void setPendingEntries(HashMap<String, NotificationData.Entry> pendingNotifications) {
        mPendingNotifications = pendingNotifications;
    }

    public static class NotificationGroup {
        public final HashMap<String, NotificationData.Entry> children = new HashMap<>();
        public NotificationData.Entry summary;
        public boolean expanded;
        /**
         * Is this notification group suppressed, i.e its summary is hidden
         */
        public boolean suppressed;
        /**
         * The time when the last heads transfer from group to child happened, while the summary
         * has the flags to heads up on its own.
         */
        public long lastHeadsUpTransfer;
        public boolean hunSummaryOnNextAddition;

        @Override
        public String toString() {
            String result = "    summary:\n      "
                    + (summary != null ? summary.notification : "null")
                    + (summary != null && summary.getDebugThrowable() != null
                            ? Log.getStackTraceString(summary.getDebugThrowable())
                            : "");
            result += "\n    children size: " + children.size();
            for (NotificationData.Entry child : children.values()) {
                result += "\n      " + child.notification
                + (child.getDebugThrowable() != null
                        ? Log.getStackTraceString(child.getDebugThrowable())
                        : "");
            }
            return result;
        }
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
         * The groups have changed. This can happen if the isolation of a child has changes or if a
         * group became suppressed / unsuppressed
         */
        void onGroupsChanged();
    }
}
