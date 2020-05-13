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

import android.annotation.Nullable;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * A class to handle notifications and their corresponding groups.
 */
@Singleton
public class NotificationGroupManager implements OnHeadsUpChangedListener, StateListener {

    private static final String TAG = "NotificationGroupManager";
    private final HashMap<String, NotificationGroup> mGroupMap = new HashMap<>();
    private final ArraySet<OnGroupChangeListener> mListeners = new ArraySet<>();
    private final Lazy<PeopleNotificationIdentifier> mPeopleNotificationIdentifier;
    private int mBarState = -1;
    private HashMap<String, StatusBarNotification> mIsolatedEntries = new HashMap<>();
    private HeadsUpManager mHeadsUpManager;
    private boolean mIsUpdatingUnchangedGroup;
    @Nullable private BubbleController mBubbleController = null;

    @Inject
    public NotificationGroupManager(
            StatusBarStateController statusBarStateController,
            Lazy<PeopleNotificationIdentifier> peopleNotificationIdentifier) {
        statusBarStateController.addCallback(this);
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
    }

    private BubbleController getBubbleController() {
        if (mBubbleController == null) {
            mBubbleController = Dependency.get(BubbleController.class);
        }
        return mBubbleController;
    }

    /**
     * Add a listener for changes to groups.
     *
     * @param listener listener to add
     */
    public void addOnGroupChangeListener(OnGroupChangeListener listener) {
        mListeners.add(listener);
    }

    public boolean isGroupExpanded(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null) {
            return false;
        }
        return group.expanded;
    }

    /**
     * @return if the group that this notification is associated with logically is expanded
     */
    public boolean isLogicalGroupExpanded(StatusBarNotification sbn) {
        NotificationGroup group = mGroupMap.get(sbn.getGroupKey());
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
            for (OnGroupChangeListener listener : mListeners) {
                listener.onGroupExpansionChanged(group.summary.getRow(), expanded);
            }
        }
    }

    public void onEntryRemoved(NotificationEntry removed) {
        onEntryRemovedInternal(removed, removed.getSbn());
        mIsolatedEntries.remove(removed.getKey());
    }

    /**
     * An entry was removed.
     *
     * @param removed the removed entry
     * @param sbn the notification the entry has, which doesn't need to be the same as it's internal
     *            notification
     */
    private void onEntryRemovedInternal(NotificationEntry removed,
            final StatusBarNotification sbn) {
        onEntryRemovedInternal(removed, sbn.getGroupKey(), sbn.isGroup(),
                sbn.getNotification().isGroupSummary());
    }

    private void onEntryRemovedInternal(NotificationEntry removed, String notifGroupKey, boolean
            isGroup, boolean isGroupSummary) {
        String groupKey = getGroupKey(removed.getKey(), notifGroupKey);
        final NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            // When an app posts 2 different notifications as summary of the same group, then a
            // cancellation of the first notification removes this group.
            // This situation is not supported and we will not allow such notifications anymore in
            // the close future. See b/23676310 for reference.
            return;
        }
        if (isGroupChild(removed.getKey(), isGroup, isGroupSummary)) {
            group.children.remove(removed.getKey());
        } else {
            group.summary = null;
        }
        updateSuppression(group);
        if (group.children.isEmpty()) {
            if (group.summary == null) {
                mGroupMap.remove(groupKey);
                for (OnGroupChangeListener listener : mListeners) {
                    listener.onGroupRemoved(group, groupKey);
                }
            }
        }
    }

    /**
     * Notify the group manager that a new entry was added
     */
    public void onEntryAdded(final NotificationEntry added) {
        updateIsolation(added);
        onEntryAddedInternal(added);
    }

    private void onEntryAddedInternal(final NotificationEntry added) {
        if (added.isRowRemoved()) {
            added.setDebugThrowable(new Throwable());
        }
        final StatusBarNotification sbn = added.getSbn();
        boolean isGroupChild = isGroupChild(sbn);
        String groupKey = getGroupKey(sbn);
        NotificationGroup group = mGroupMap.get(groupKey);
        if (group == null) {
            group = new NotificationGroup();
            mGroupMap.put(groupKey, group);
            for (OnGroupChangeListener listener : mListeners) {
                listener.onGroupCreated(group, groupKey);
            }
        }
        if (isGroupChild) {
            NotificationEntry existing = group.children.get(added.getKey());
            if (existing != null && existing != added) {
                Throwable existingThrowable = existing.getDebugThrowable();
                Log.wtf(TAG, "Inconsistent entries found with the same key " + added.getKey()
                        + "existing removed: " + existing.isRowRemoved()
                        + (existingThrowable != null
                                ? Log.getStackTraceString(existingThrowable) + "\n": "")
                        + " added removed" + added.isRowRemoved()
                        , new Throwable());
            }
            group.children.put(added.getKey(), added);
            updateSuppression(group);
        } else {
            group.summary = added;
            group.expanded = added.areChildrenExpanded();
            updateSuppression(group);
            if (!group.children.isEmpty()) {
                ArrayList<NotificationEntry> childrenCopy
                        = new ArrayList<>(group.children.values());
                for (NotificationEntry child : childrenCopy) {
                    onEntryBecomingChild(child);
                }
                for (OnGroupChangeListener listener : mListeners) {
                    listener.onGroupCreatedFromChildren(group);
                }
            }
        }
    }

    private void onEntryBecomingChild(NotificationEntry entry) {
        updateIsolation(entry);
    }

    private void updateSuppression(NotificationGroup group) {
        if (group == null) {
            return;
        }
        int childCount = 0;
        boolean hasBubbles = false;
        for (NotificationEntry entry : group.children.values()) {
            if (!getBubbleController().isBubbleNotificationSuppressedFromShade(entry)) {
                childCount++;
            } else {
                hasBubbles = true;
            }
        }

        boolean prevSuppressed = group.suppressed;
        group.suppressed = group.summary != null && !group.expanded
                && (childCount == 1
                || (childCount == 0
                        && group.summary.getSbn().getNotification().isGroupSummary()
                        && (hasIsolatedChildren(group) || hasBubbles)));
        if (prevSuppressed != group.suppressed) {
            for (OnGroupChangeListener listener : mListeners) {
                if (!mIsUpdatingUnchangedGroup) {
                    listener.onGroupSuppressionChanged(group, group.suppressed);
                    listener.onGroupsChanged();
                }
            }
        }
    }

    private boolean hasIsolatedChildren(NotificationGroup group) {
        return getNumberOfIsolatedChildren(group.summary.getSbn().getGroupKey()) != 0;
    }

    private int getNumberOfIsolatedChildren(String groupKey) {
        int count = 0;
        for (StatusBarNotification sbn : mIsolatedEntries.values()) {
            if (sbn.getGroupKey().equals(groupKey) && isIsolated(sbn.getKey())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Update an entry's group information
     * @param entry notification entry to update
     * @param oldNotification previous notification info before this update
     */
    public void onEntryUpdated(NotificationEntry entry, StatusBarNotification oldNotification) {
        onEntryUpdated(entry, oldNotification.getGroupKey(), oldNotification.isGroup(),
                oldNotification.getNotification().isGroupSummary());
    }

    /**
     * Updates an entry's group information
     * @param entry notification entry to update
     * @param oldGroupKey the notification's previous group key before this update
     * @param oldIsGroup whether this notification was a group before this update
     * @param oldIsGroupSummary whether this notification was a group summary before this update
     */
    public void onEntryUpdated(NotificationEntry entry, String oldGroupKey, boolean oldIsGroup,
            boolean oldIsGroupSummary) {
        String newGroupKey = entry.getSbn().getGroupKey();
        boolean groupKeysChanged = !oldGroupKey.equals(newGroupKey);
        boolean wasGroupChild = isGroupChild(entry.getKey(), oldIsGroup, oldIsGroupSummary);
        boolean isGroupChild = isGroupChild(entry.getSbn());
        mIsUpdatingUnchangedGroup = !groupKeysChanged && wasGroupChild == isGroupChild;
        if (mGroupMap.get(getGroupKey(entry.getKey(), oldGroupKey)) != null) {
            onEntryRemovedInternal(entry, oldGroupKey, oldIsGroup, oldIsGroupSummary);
        }
        onEntryAddedInternal(entry);
        mIsUpdatingUnchangedGroup = false;
        if (isIsolated(entry.getSbn().getKey())) {
            mIsolatedEntries.put(entry.getKey(), entry.getSbn());
            if (groupKeysChanged) {
                updateSuppression(mGroupMap.get(oldGroupKey));
                updateSuppression(mGroupMap.get(newGroupKey));
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
        NotificationEntry logicalGroupSummary = getLogicalGroupSummary(sbn);
        return logicalGroupSummary != null
                && !logicalGroupSummary.getSbn().equals(sbn);
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

    private void setStatusBarState(int newState) {
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
        if (group == null || group.summary == null) {
            return false;
        }
        return !group.children.isEmpty() && Objects.equals(group.summary.getSbn(), sbn);
    }

    /**
     * Get the summary of a specified status bar notification. For isolated notification this return
     * itself.
     */
    public NotificationEntry getGroupSummary(StatusBarNotification sbn) {
        return getGroupSummary(getGroupKey(sbn));
    }

    /**
     * Similar to {@link #getGroupSummary(StatusBarNotification)} but doesn't get the visual summary
     * but the logical summary, i.e when a child is isolated, it still returns the summary as if
     * it wasn't isolated.
     */
    public NotificationEntry getLogicalGroupSummary(StatusBarNotification sbn) {
        return getGroupSummary(sbn.getGroupKey());
    }

    @Nullable
    private NotificationEntry getGroupSummary(String groupKey) {
        NotificationGroup group = mGroupMap.get(groupKey);
        //TODO: see if this can become an Entry
        return group == null ? null
                : group.summary;
    }

    /**
     * Get the children that are logically in the summary's group, whether or not they are isolated.
     *
     * @param summary summary of a group
     * @return list of the children
     */
    public ArrayList<NotificationEntry> getLogicalChildren(StatusBarNotification summary) {
        NotificationGroup group = mGroupMap.get(summary.getGroupKey());
        if (group == null) {
            return null;
        }
        ArrayList<NotificationEntry> children = new ArrayList<>(group.children.values());
        for (StatusBarNotification sbn : mIsolatedEntries.values()) {
            if (sbn.getGroupKey().equals(summary.getGroupKey())) {
                children.add(mGroupMap.get(sbn.getKey()).summary);
            }
        }
        return children;
    }

    /**
     * Get the children that are in the summary's group, not including those isolated.
     *
     * @param summary summary of a group
     * @return list of the children
     */
    public @Nullable ArrayList<NotificationEntry> getChildren(StatusBarNotification summary) {
        NotificationGroup group = mGroupMap.get(summary.getGroupKey());
        if (group == null) {
            return null;
        }
        return new ArrayList<>(group.children.values());
    }

    /**
     * If there is a {@link NotificationGroup} associated with the provided entry, this method
     * will update the suppression of that group.
     */
    public void updateSuppression(NotificationEntry entry) {
        NotificationGroup group = mGroupMap.get(getGroupKey(entry.getSbn()));
        if (group != null) {
            updateSuppression(group);
        }
    }

    /**
     * Get the group key. May differ from the one in the notification due to the notification
     * being temporarily isolated.
     *
     * @param sbn notification to check
     * @return the key of the notification
     */
    public String getGroupKey(StatusBarNotification sbn) {
        return getGroupKey(sbn.getKey(), sbn.getGroupKey());
    }

    private String getGroupKey(String key, String groupKey) {
        if (isIsolated(key)) {
            return key;
        }
        return groupKey;
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

    private boolean isIsolated(String sbnKey) {
        return mIsolatedEntries.containsKey(sbnKey);
    }

    /**
     * Whether a notification is visually a group summary.
     *
     * @param sbn notification to check
     * @return true if it is visually a group summary
     */
    public boolean isGroupSummary(StatusBarNotification sbn) {
        if (isIsolated(sbn.getKey())) {
            return true;
        }
        return sbn.getNotification().isGroupSummary();
    }

    /**
     * Whether a notification is visually a group child.
     *
     * @param sbn notification to check
     * @return true if it is visually a group child
     */
    public boolean isGroupChild(StatusBarNotification sbn) {
        return isGroupChild(sbn.getKey(), sbn.isGroup(), sbn.getNotification().isGroupSummary());
    }

    private boolean isGroupChild(String key, boolean isGroup, boolean isGroupSummary) {
        if (isIsolated(key)) {
            return false;
        }
        return isGroup && !isGroupSummary;
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        updateIsolation(entry);
    }

    /**
     * Whether a notification that is normally part of a group should be temporarily isolated from
     * the group and put in their own group visually.  This generally happens when the notification
     * is alerting.
     *
     * @param entry the notification to check
     * @return true if the entry should be isolated
     */

    private boolean shouldIsolate(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();
        if (!sbn.isGroup() || sbn.getNotification().isGroupSummary()) {
            return false;
        }
        int peopleNotificationType = mPeopleNotificationIdentifier.get().getPeopleNotificationType(
                entry.getSbn(), entry.getRanking());
        if (peopleNotificationType == PeopleNotificationIdentifier.TYPE_IMPORTANT_PERSON) {
            return true;
        }
        if (mHeadsUpManager != null && !mHeadsUpManager.isAlerting(entry.getKey())) {
            return false;
        }
        NotificationGroup notificationGroup = mGroupMap.get(sbn.getGroupKey());
        return (sbn.getNotification().fullScreenIntent != null
                    || notificationGroup == null
                    || !notificationGroup.expanded
                    || isGroupNotFullyVisible(notificationGroup));
    }

    /**
     * Isolate a notification from its group so that it visually shows as its own group.
     *
     * @param entry the notification to isolate
     */
    private void isolateNotification(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();

        // We will be isolated now, so lets update the groups
        onEntryRemovedInternal(entry, entry.getSbn());

        mIsolatedEntries.put(sbn.getKey(), sbn);

        onEntryAddedInternal(entry);
        // We also need to update the suppression of the old group, because this call comes
        // even before the groupManager knows about the notification at all.
        // When the notification gets added afterwards it is already isolated and therefore
        // it doesn't lead to an update.
        updateSuppression(mGroupMap.get(entry.getSbn().getGroupKey()));
        for (OnGroupChangeListener listener : mListeners) {
            listener.onGroupsChanged();
        }
    }

    /**
     * Update the isolation of an entry, splitting it from the group.
     */
    public void updateIsolation(NotificationEntry entry) {
        boolean isIsolated = isIsolated(entry.getSbn().getKey());
        if (shouldIsolate(entry)) {
            if (!isIsolated) {
                isolateNotification(entry);
            }
        } else if (isIsolated) {
            stopIsolatingNotification(entry);
        }
    }

    /**
     * Stop isolating a notification and re-group it with its original logical group.
     *
     * @param entry the notification to un-isolate
     */
    private void stopIsolatingNotification(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();
        if (isIsolated(sbn.getKey())) {
            // not isolated anymore, we need to update the groups
            onEntryRemovedInternal(entry, entry.getSbn());
            mIsolatedEntries.remove(sbn.getKey());
            onEntryAddedInternal(entry);
            for (OnGroupChangeListener listener : mListeners) {
                listener.onGroupsChanged();
            }
        }
    }

    private boolean isGroupNotFullyVisible(NotificationGroup notificationGroup) {
        return notificationGroup.summary == null
                || notificationGroup.summary.isGroupNotFullyVisible();
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

    @Override
    public void onStateChanged(int newState) {
        setStatusBarState(newState);
    }

    public static class NotificationGroup {
        public final HashMap<String, NotificationEntry> children = new HashMap<>();
        public NotificationEntry summary;
        public boolean expanded;
        /**
         * Is this notification group suppressed, i.e its summary is hidden
         */
        public boolean suppressed;

        @Override
        public String toString() {
            String result = "    summary:\n      "
                    + (summary != null ? summary.getSbn() : "null")
                    + (summary != null && summary.getDebugThrowable() != null
                            ? Log.getStackTraceString(summary.getDebugThrowable())
                            : "");
            result += "\n    children size: " + children.size();
            for (NotificationEntry child : children.values()) {
                result += "\n      " + child.getSbn()
                + (child.getDebugThrowable() != null
                        ? Log.getStackTraceString(child.getDebugThrowable())
                        : "");
            }
            result += "\n    summary suppressed: " + suppressed;
            return result;
        }
    }

    public interface OnGroupChangeListener {

        /**
         * A new group has been created.
         *
         * @param group the group that was created
         * @param groupKey the group's key
         */
        default void onGroupCreated(NotificationGroup group, String groupKey) {}

        /**
         * A group has been removed.
         *
         * @param group the group that was removed
         * @param groupKey the group's key
         */
        default void onGroupRemoved(NotificationGroup group, String groupKey) {}

        /**
         * The suppression of a group has changed.
         *
         * @param group the group that has changed
         * @param suppressed true if the group is now suppressed, false o/w
         */
        default void onGroupSuppressionChanged(NotificationGroup group, boolean suppressed) {}

        /**
         * The expansion of a group has changed.
         *
         * @param changedRow the row for which the expansion has changed, which is also the summary
         * @param expanded a boolean indicating the new expanded state
         */
        default void onGroupExpansionChanged(ExpandableNotificationRow changedRow,
                boolean expanded) {}

        /**
         * A group of children just received a summary notification and should therefore become
         * children of it.
         *
         * @param group the group created
         */
        default void onGroupCreatedFromChildren(NotificationGroup group) {}

        /**
         * The groups have changed. This can happen if the isolation of a child has changes or if a
         * group became suppressed / unsuppressed
         */
        default void onGroupsChanged() {}
    }
}
