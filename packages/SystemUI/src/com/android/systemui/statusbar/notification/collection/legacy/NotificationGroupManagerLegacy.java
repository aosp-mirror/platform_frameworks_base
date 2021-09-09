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
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.collection.legacy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.wm.shell.bubbles.Bubbles;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * A class to handle notifications and their corresponding groups.
 * This includes:
 * 1. Determining whether an entry is a member of a group and whether it is a summary or a child
 * 2. Tracking group expansion states
 */
@SysUISingleton
public class NotificationGroupManagerLegacy implements
        OnHeadsUpChangedListener,
        StateListener,
        GroupMembershipManager,
        GroupExpansionManager,
        Dumpable {

    private static final String TAG = "NotifGroupManager";
    private static final boolean DEBUG = StatusBar.DEBUG;
    private static final boolean SPEW = StatusBar.SPEW;
    /**
     * The maximum amount of time (in ms) between the posting of notifications that can be
     * considered part of the same update batch.
     */
    private static final long POST_BATCH_MAX_AGE = 5000;
    private final HashMap<String, NotificationGroup> mGroupMap = new HashMap<>();
    private final ArraySet<OnGroupExpansionChangeListener> mExpansionChangeListeners =
            new ArraySet<>();
    private final ArraySet<OnGroupChangeListener> mGroupChangeListeners = new ArraySet<>();
    private final Lazy<PeopleNotificationIdentifier> mPeopleNotificationIdentifier;
    private final Optional<Bubbles> mBubblesOptional;
    private final EventBuffer mEventBuffer = new EventBuffer();
    private int mBarState = -1;
    private HashMap<String, StatusBarNotification> mIsolatedEntries = new HashMap<>();
    private HeadsUpManager mHeadsUpManager;
    private boolean mIsUpdatingUnchangedGroup;

    @Inject
    public NotificationGroupManagerLegacy(
            StatusBarStateController statusBarStateController,
            Lazy<PeopleNotificationIdentifier> peopleNotificationIdentifier,
            Optional<Bubbles> bubblesOptional,
            DumpManager dumpManager) {
        statusBarStateController.addCallback(this);
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
        mBubblesOptional = bubblesOptional;

        dumpManager.registerDumpable(this);
    }

    /**
     * Add a listener for changes to groups.
     */
    public void registerGroupChangeListener(OnGroupChangeListener listener) {
        mGroupChangeListeners.add(listener);
    }

    @Override
    public void registerGroupExpansionChangeListener(OnGroupExpansionChangeListener listener) {
        mExpansionChangeListeners.add(listener);
    }

    @Override
    public boolean isGroupExpanded(NotificationEntry entry) {
        NotificationGroup group = mGroupMap.get(getGroupKey(entry.getSbn()));
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

    @Override
    public void setGroupExpanded(NotificationEntry entry, boolean expanded) {
        NotificationGroup group = mGroupMap.get(getGroupKey(entry.getSbn()));
        if (group == null) {
            return;
        }
        setGroupExpanded(group, expanded);
    }

    private void setGroupExpanded(NotificationGroup group, boolean expanded) {
        group.expanded = expanded;
        if (group.summary != null) {
            for (OnGroupExpansionChangeListener listener : mExpansionChangeListeners) {
                listener.onGroupExpansionChange(group.summary.getRow(), expanded);
            }
        }
    }

    /**
     * When we want to remove an entry from being tracked for grouping
     */
    public void onEntryRemoved(NotificationEntry removed) {
        if (SPEW) {
            Log.d(TAG, "onEntryRemoved: entry=" + removed);
        }
        onEntryRemovedInternal(removed, removed.getSbn());
        StatusBarNotification oldSbn = mIsolatedEntries.remove(removed.getKey());
        if (oldSbn != null) {
            updateSuppression(mGroupMap.get(oldSbn.getGroupKey()));
        }
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
        if (SPEW) {
            Log.d(TAG, "onEntryRemovedInternal: entry=" + removed + " group=" + group.groupKey);
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
                for (OnGroupChangeListener listener : mGroupChangeListeners) {
                    listener.onGroupRemoved(group, groupKey);
                }
            }
        }
    }

    /**
     * Notify the group manager that a new entry was added
     */
    public void onEntryAdded(final NotificationEntry added) {
        if (SPEW) {
            Log.d(TAG, "onEntryAdded: entry=" + added);
        }
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
            group = new NotificationGroup(groupKey);
            mGroupMap.put(groupKey, group);

            for (OnGroupChangeListener listener : mGroupChangeListeners) {
                listener.onGroupCreated(group, groupKey);
            }
        }
        if (SPEW) {
            Log.d(TAG, "onEntryAddedInternal: entry=" + added + " group=" + group.groupKey);
        }
        if (isGroupChild) {
            NotificationEntry existing = group.children.get(added.getKey());
            if (existing != null && existing != added) {
                Throwable existingThrowable = existing.getDebugThrowable();
                Log.wtf(TAG, "Inconsistent entries found with the same key " + added.getKey()
                        + "existing removed: " + existing.isRowRemoved()
                        + (existingThrowable != null
                                ? Log.getStackTraceString(existingThrowable) + "\n" : "")
                        + " added removed" + added.isRowRemoved(), new Throwable());
            }
            group.children.put(added.getKey(), added);
            addToPostBatchHistory(group, added);
            updateSuppression(group);
        } else {
            group.summary = added;
            addToPostBatchHistory(group, added);
            group.expanded = added.areChildrenExpanded();
            updateSuppression(group);
            if (!group.children.isEmpty()) {
                ArrayList<NotificationEntry> childrenCopy =
                        new ArrayList<>(group.children.values());
                for (NotificationEntry child : childrenCopy) {
                    onEntryBecomingChild(child);
                }
                for (OnGroupChangeListener listener : mGroupChangeListeners) {
                    listener.onGroupCreatedFromChildren(group);
                }
            }
        }
    }

    private void addToPostBatchHistory(NotificationGroup group, @Nullable NotificationEntry entry) {
        if (entry == null) {
            return;
        }
        boolean didAdd = group.postBatchHistory.add(new PostRecord(entry));
        if (didAdd) {
            trimPostBatchHistory(group.postBatchHistory);
        }
    }

    /** remove all history that's too old to be in the batch. */
    private void trimPostBatchHistory(@NonNull TreeSet<PostRecord> postBatchHistory) {
        if (postBatchHistory.size() <= 1) {
            return;
        }
        long batchStartTime = postBatchHistory.last().postTime - POST_BATCH_MAX_AGE;
        while (!postBatchHistory.isEmpty() && postBatchHistory.first().postTime < batchStartTime) {
            postBatchHistory.pollFirst();
        }
    }

    private void onEntryBecomingChild(NotificationEntry entry) {
        updateIsolation(entry);
    }

    private void updateSuppression(NotificationGroup group) {
        if (group == null) {
            return;
        }
        NotificationEntry prevAlertOverride = group.alertOverride;
        group.alertOverride = getPriorityConversationAlertOverride(group);

        int childCount = 0;
        boolean hasBubbles = false;
        for (NotificationEntry entry : group.children.values()) {
            if (mBubblesOptional.isPresent() && mBubblesOptional.get()
                    .isBubbleNotificationSuppressedFromShade(
                            entry.getKey(), entry.getSbn().getGroupKey())) {
                hasBubbles = true;
            } else {
                childCount++;
            }
        }

        boolean prevSuppressed = group.suppressed;
        group.suppressed = group.summary != null && !group.expanded
                && (childCount == 1
                || (childCount == 0
                && group.summary.getSbn().getNotification().isGroupSummary()
                && (hasIsolatedChildren(group) || hasBubbles)));

        boolean alertOverrideChanged = prevAlertOverride != group.alertOverride;
        boolean suppressionChanged = prevSuppressed != group.suppressed;
        if (alertOverrideChanged || suppressionChanged) {
            if (DEBUG && alertOverrideChanged) {
                Log.d(TAG, "updateSuppression: alertOverride was=" + prevAlertOverride
                        + " now=" + group.alertOverride + " group:\n" + group);
            }
            if (DEBUG && suppressionChanged) {
                Log.d(TAG,
                        "updateSuppression: suppressed changed to " + group.suppressed
                                + " group:\n" + group);
            }
            if (!mIsUpdatingUnchangedGroup) {
                if (alertOverrideChanged) {
                    mEventBuffer.notifyAlertOverrideChanged(group, prevAlertOverride);
                }
                if (suppressionChanged) {
                    for (OnGroupChangeListener listener : mGroupChangeListeners) {
                        listener.onGroupSuppressionChanged(group, group.suppressed);
                    }
                }
                mEventBuffer.notifyGroupsChanged();
            } else {
                if (DEBUG) {
                    Log.d(TAG, group + " did not notify listeners of above change(s)");
                }
            }
        }
    }

    /**
     * Finds the isolated logical child of this group which is should be alerted instead.
     *
     * Notifications from priority conversations are isolated from their groups to make them more
     * prominent, however apps may post these with a GroupAlertBehavior that has the group receiving
     * the alert.  This would lead to the group alerting even though the conversation that was
     * updated was not actually a part of that group.  This method finds the best priority
     * conversation in this situation, if there is one, so they can be set as the alertOverride of
     * the group.
     *
     * @param group the group to check
     * @return the entry which should receive the alert instead of the group, if any.
     */
    @Nullable
    private NotificationEntry getPriorityConversationAlertOverride(NotificationGroup group) {
        // GOAL: if there is a priority child which wouldn't alert based on its groupAlertBehavior,
        // but which should be alerting (because priority conversations are isolated), find it.
        if (group == null || group.summary == null) {
            if (SPEW) {
                Log.d(TAG, "getPriorityConversationAlertOverride: null group or summary");
            }
            return null;
        }
        if (isIsolated(group.summary.getKey())) {
            if (SPEW) {
                Log.d(TAG, "getPriorityConversationAlertOverride: isolated group");
            }
            return null;
        }

        // Precondiions:
        // * Only necessary when all notifications in the group use GROUP_ALERT_SUMMARY
        // * Only necessary when at least one notification in the group is on a priority channel
        if (group.summary.getSbn().getNotification().getGroupAlertBehavior()
                != Notification.GROUP_ALERT_SUMMARY) {
            if (SPEW) {
                Log.d(TAG, "getPriorityConversationAlertOverride: summary != GROUP_ALERT_SUMMARY");
            }
            return null;
        }

        // Get the important children first, copy the keys for the final importance check,
        // then add the non-isolated children to the map for unified lookup.
        HashMap<String, NotificationEntry> children = getImportantConversations(group);
        if (children == null || children.isEmpty()) {
            if (SPEW) {
                Log.d(TAG, "getPriorityConversationAlertOverride: no important conversations");
            }
            return null;
        }
        HashSet<String> importantChildKeys = new HashSet<>(children.keySet());
        children.putAll(group.children);

        // Ensure all children have GROUP_ALERT_SUMMARY
        for (NotificationEntry child : children.values()) {
            if (child.getSbn().getNotification().getGroupAlertBehavior()
                    != Notification.GROUP_ALERT_SUMMARY) {
                if (SPEW) {
                    Log.d(TAG, "getPriorityConversationAlertOverride: "
                            + "child != GROUP_ALERT_SUMMARY");
                }
                return null;
            }
        }

        // Create a merged post history from all the children
        TreeSet<PostRecord> combinedHistory = new TreeSet<>(group.postBatchHistory);
        for (String importantChildKey : importantChildKeys) {
            NotificationGroup importantChildGroup = mGroupMap.get(importantChildKey);
            combinedHistory.addAll(importantChildGroup.postBatchHistory);
        }
        trimPostBatchHistory(combinedHistory);

        // This is a streamlined implementation of the following idea:
        // * From the subset of notifications in the latest 'batch' of updates.  A batch is:
        //   * Notifs posted less than POST_BATCH_MAX_AGE before the most recently posted.
        //   * Only including notifs newer than the second-to-last post of any notification.
        // * Find the newest child in the batch -- the with the largest 'when' value.
        // * If the newest child is a priority conversation, set that as the override.
        HashSet<String> batchKeys = new HashSet<>();
        long newestChildWhen = -1;
        NotificationEntry newestChild = null;
        // Iterate backwards through the post history, tracking the child with the smallest sort key
        for (PostRecord record : combinedHistory.descendingSet()) {
            if (batchKeys.contains(record.key)) {
                // Once you see a notification again, the batch has ended
                break;
            }
            batchKeys.add(record.key);
            NotificationEntry child = children.get(record.key);
            if (child != null) {
                long childWhen = child.getSbn().getNotification().when;
                if (newestChild == null || childWhen > newestChildWhen) {
                    newestChildWhen = childWhen;
                    newestChild = child;
                }
            }
        }
        if (newestChild != null && importantChildKeys.contains(newestChild.getKey())) {
            if (SPEW) {
                Log.d(TAG, "getPriorityConversationAlertOverride: result=" + newestChild);
            }
            return newestChild;
        }
        if (SPEW) {
            Log.d(TAG, "getPriorityConversationAlertOverride: result=null, newestChild="
                    + newestChild);
        }
        return null;
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

    @Nullable
    private HashMap<String, NotificationEntry> getImportantConversations(NotificationGroup group) {
        String groupKey = group.summary.getSbn().getGroupKey();
        HashMap<String, NotificationEntry> result = null;
        for (StatusBarNotification sbn : mIsolatedEntries.values()) {
            if (sbn.getGroupKey().equals(groupKey)) {
                NotificationEntry entry = mGroupMap.get(sbn.getKey()).summary;
                if (isImportantConversation(entry)) {
                    if (result == null) {
                        result = new HashMap<>();
                    }
                    result.put(sbn.getKey(), entry);
                }
            }
        }
        return result;
    }

    /**
     * Update an entry's group information
     * @param entry notification entry to update
     * @param oldNotification previous notification info before this update
     */
    public void onEntryUpdated(NotificationEntry entry, StatusBarNotification oldNotification) {
        if (SPEW) {
            Log.d(TAG, "onEntryUpdated: entry=" + entry);
        }
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

    /**
     * Whether the given notification is the summary of a group that is being suppressed
     */
    public boolean isSummaryOfSuppressedGroup(StatusBarNotification sbn) {
        return sbn.getNotification().isGroupSummary() && isGroupSuppressed(getGroupKey(sbn));
    }

    /**
     * If the given notification is a summary, get the group for it.
     */
    public NotificationGroup getGroupForSummary(StatusBarNotification sbn) {
        if (sbn.getNotification().isGroupSummary()) {
            return mGroupMap.get(getGroupKey(sbn));
        }
        return null;
    }

    private boolean isOnlyChild(StatusBarNotification sbn) {
        return !sbn.getNotification().isGroupSummary()
                && getTotalNumberOfChildren(sbn) == 1;
    }

    @Override
    public boolean isOnlyChildInGroup(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.getSbn();
        if (!isOnlyChild(sbn)) {
            return false;
        }
        NotificationEntry logicalGroupSummary = getLogicalGroupSummary(entry);
        return logicalGroupSummary != null && !logicalGroupSummary.getSbn().equals(sbn);
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
            collapseGroups();
        }
    }

    @Override
    public void collapseGroups() {
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

    @Override
    public boolean isChildInGroup(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.getSbn();
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

    @Override
    public boolean isGroupSummary(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.getSbn();
        if (!isGroupSummary(sbn)) {
            return false;
        }
        NotificationGroup group = mGroupMap.get(getGroupKey(sbn));
        if (group == null || group.summary == null) {
            return false;
        }
        return !group.children.isEmpty() && Objects.equals(group.summary.getSbn(), sbn);
    }

    @Override
    public NotificationEntry getGroupSummary(NotificationEntry entry) {
        return getGroupSummary(getGroupKey(entry.getSbn()));
    }

    @Override
    public NotificationEntry getLogicalGroupSummary(NotificationEntry entry) {
        return getGroupSummary(entry.getSbn().getGroupKey());
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

    @Override
    public @Nullable List<NotificationEntry> getChildren(ListEntry listEntrySummary) {
        NotificationEntry summary = listEntrySummary.getRepresentativeEntry();
        NotificationGroup group = mGroupMap.get(summary.getSbn().getGroupKey());
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

    @Override
    public boolean toggleGroupExpansion(NotificationEntry entry) {
        NotificationGroup group = mGroupMap.get(getGroupKey(entry.getSbn()));
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
     * Is this notification the summary of a group?
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
        if (isImportantConversation(entry)) {
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

    private boolean isImportantConversation(NotificationEntry entry) {
        int peopleNotificationType =
                mPeopleNotificationIdentifier.get().getPeopleNotificationType(entry);
        return peopleNotificationType == PeopleNotificationIdentifier.TYPE_IMPORTANT_PERSON;
    }

    /**
     * Isolate a notification from its group so that it visually shows as its own group.
     *
     * @param entry the notification to isolate
     */
    private void isolateNotification(NotificationEntry entry) {
        if (SPEW) {
            Log.d(TAG, "isolateNotification: entry=" + entry);
        }
        // We will be isolated now, so lets update the groups
        onEntryRemovedInternal(entry, entry.getSbn());

        mIsolatedEntries.put(entry.getKey(), entry.getSbn());

        onEntryAddedInternal(entry);
        // We also need to update the suppression of the old group, because this call comes
        // even before the groupManager knows about the notification at all.
        // When the notification gets added afterwards it is already isolated and therefore
        // it doesn't lead to an update.
        updateSuppression(mGroupMap.get(entry.getSbn().getGroupKey()));
        for (OnGroupChangeListener listener : mGroupChangeListeners) {
            listener.onGroupsChanged();
        }
    }

    /**
     * Update the isolation of an entry, splitting it from the group.
     */
    public void updateIsolation(NotificationEntry entry) {
        // We need to buffer a few events because we do isolation changes in 3 steps:
        // removeInternal, update mIsolatedEntries, addInternal.  This means that often the
        // alertOverride will update on the removal, however processing the event in that case can
        // cause problems because the mIsolatedEntries map is not in its final state, so the event
        // listener may be unable to correctly determine the true state of the group.  By delaying
        // the alertOverride change until after the add phase, we can ensure that listeners only
        // have to handle a consistent state.
        mEventBuffer.startBuffering();
        boolean isIsolated = isIsolated(entry.getSbn().getKey());
        if (shouldIsolate(entry)) {
            if (!isIsolated) {
                isolateNotification(entry);
            }
        } else if (isIsolated) {
            stopIsolatingNotification(entry);
        }
        mEventBuffer.flushAndStopBuffering();
    }

    /**
     * Stop isolating a notification and re-group it with its original logical group.
     *
     * @param entry the notification to un-isolate
     */
    private void stopIsolatingNotification(NotificationEntry entry) {
        if (SPEW) {
            Log.d(TAG, "stopIsolatingNotification: entry=" + entry);
        }
        // not isolated anymore, we need to update the groups
        onEntryRemovedInternal(entry, entry.getSbn());
        mIsolatedEntries.remove(entry.getKey());
        onEntryAddedInternal(entry);
        for (OnGroupChangeListener listener : mGroupChangeListeners) {
            listener.onGroupsChanged();
        }
    }

    private boolean isGroupNotFullyVisible(NotificationGroup notificationGroup) {
        return notificationGroup.summary == null
                || notificationGroup.summary.isGroupNotFullyVisible();
    }

    /**
     * Directly set the heads up manager to avoid circular dependencies
     */
    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GroupManagerLegacy state:");
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

    /**
     * A record of a notification being posted, containing the time of the post and the key of the
     * notification entry.  These are stored in a TreeSet by the NotificationGroup and used to
     * calculate a batch of notifications.
     */
    public static class PostRecord implements Comparable<PostRecord> {
        public final long postTime;
        public final String key;

        /** constructs a record containing the post time and key from the notification entry */
        public PostRecord(@NonNull NotificationEntry entry) {
            this.postTime = entry.getSbn().getPostTime();
            this.key = entry.getKey();
        }

        @Override
        public int compareTo(PostRecord o) {
            int postTimeComparison = Long.compare(this.postTime, o.postTime);
            return postTimeComparison == 0
                    ? String.CASE_INSENSITIVE_ORDER.compare(this.key, o.key)
                    : postTimeComparison;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PostRecord that = (PostRecord) o;
            return postTime == that.postTime && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(postTime, key);
        }
    }

    /**
     * Represents a notification group in the notification shade.
     */
    public static class NotificationGroup {
        public final String groupKey;
        public final HashMap<String, NotificationEntry> children = new HashMap<>();
        public final TreeSet<PostRecord> postBatchHistory = new TreeSet<>();
        public NotificationEntry summary;
        public boolean expanded;
        /**
         * Is this notification group suppressed, i.e its summary is hidden
         */
        public boolean suppressed;
        /**
         * The child (which is isolated from this group) to which the alert should be transferred,
         * due to priority conversations.
         */
        public NotificationEntry alertOverride;

        NotificationGroup(String groupKey) {
            this.groupKey = groupKey;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("    groupKey: ").append(groupKey);
            sb.append("\n    summary:");
            appendEntry(sb, summary);
            sb.append("\n    children size: ").append(children.size());
            for (NotificationEntry child : children.values()) {
                appendEntry(sb, child);
            }
            sb.append("\n    alertOverride:");
            appendEntry(sb, alertOverride);
            sb.append("\n    summary suppressed: ").append(suppressed);
            return sb.toString();
        }

        private void appendEntry(StringBuilder sb, NotificationEntry entry) {
            sb.append("\n      ").append(entry != null ? entry.getSbn() : "null");
            if (entry != null && entry.getDebugThrowable() != null) {
                sb.append(Log.getStackTraceString(entry.getDebugThrowable()));
            }
        }
    }

    /**
     * This class is a toggleable buffer for a subset of events of {@link OnGroupChangeListener}.
     * When buffering, instead of notifying the listeners it will set internal state that will allow
     * it to notify listeners of those events later
     */
    private class EventBuffer {
        private final HashMap<String, NotificationEntry> mOldAlertOverrideByGroup = new HashMap<>();
        private boolean mIsBuffering = false;
        private boolean mDidGroupsChange = false;

        void notifyAlertOverrideChanged(NotificationGroup group,
                NotificationEntry oldAlertOverride) {
            if (mIsBuffering) {
                // The value in this map is the override before the event.  If there is an entry
                // already in the map, then we are effectively coalescing two events, which means
                // we need to preserve the original initial value.
                mOldAlertOverrideByGroup.putIfAbsent(group.groupKey, oldAlertOverride);
            } else {
                for (OnGroupChangeListener listener : mGroupChangeListeners) {
                    listener.onGroupAlertOverrideChanged(group, oldAlertOverride,
                            group.alertOverride);
                }
            }
        }

        void notifyGroupsChanged() {
            if (mIsBuffering) {
                mDidGroupsChange = true;
            } else {
                for (OnGroupChangeListener listener : mGroupChangeListeners) {
                    listener.onGroupsChanged();
                }
            }
        }

        void startBuffering() {
            mIsBuffering = true;
        }

        void flushAndStopBuffering() {
            // stop buffering so that we can call our own helpers
            mIsBuffering = false;
            // alert all group alert override changes for groups that were not removed
            for (Map.Entry<String, NotificationEntry> entry : mOldAlertOverrideByGroup.entrySet()) {
                NotificationGroup group = mGroupMap.get(entry.getKey());
                if (group == null) {
                    // The group can be null if this alertOverride changed before the group was
                    // permanently removed, meaning that there's no guarantee that listeners will
                    // that field clear.
                    continue;
                }
                NotificationEntry oldAlertOverride = entry.getValue();
                if (group.alertOverride == oldAlertOverride) {
                    // If the final alertOverride equals the initial, it means we coalesced two
                    // events which undid the change, so we can drop it entirely.
                    continue;
                }
                notifyAlertOverrideChanged(group, oldAlertOverride);
            }
            mOldAlertOverrideByGroup.clear();
            // alert that groups changed
            if (mDidGroupsChange) {
                notifyGroupsChanged();
                mDidGroupsChange = false;
            }
        }
    }

    /**
     * Listener for group changes not including group expansion changes which are handled by
     * {@link OnGroupExpansionChangeListener}.
     */
    public interface OnGroupChangeListener {
        /**
         * A new group has been created.
         *
         * @param group the group that was created
         * @param groupKey the group's key
         */
        default void onGroupCreated(
                NotificationGroup group,
                String groupKey) {}

        /**
         * A group has been removed.
         *
         * @param group the group that was removed
         * @param groupKey the group's key
         */
        default void onGroupRemoved(
                NotificationGroup group,
                String groupKey) {}

        /**
         * The suppression of a group has changed.
         *
         * @param group the group that has changed
         * @param suppressed true if the group is now suppressed, false o/w
         */
        default void onGroupSuppressionChanged(
                NotificationGroup group,
                boolean suppressed) {}

        /**
         * The alert override of a group has changed.
         *
         * @param group the group that has changed
         * @param oldAlertOverride the previous notification to which the group's alerts were sent
         * @param newAlertOverride the notification to which the group's alerts should now be sent
         */
        default void onGroupAlertOverrideChanged(
                NotificationGroup group,
                @Nullable NotificationEntry oldAlertOverride,
                @Nullable NotificationEntry newAlertOverride) {}

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
