/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.NotificationGroup;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneModule;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A helper class dealing with the alert interactions between {@link NotificationGroupManagerLegacy}
 * and {@link HeadsUpManager}. In particular, this class deals with keeping
 * the correct notification in a group alerting based off the group suppression and alertOverride.
 */
public class NotificationGroupAlertTransferHelper implements OnHeadsUpChangedListener,
        StateListener {

    private static final long ALERT_TRANSFER_TIMEOUT = 300;
    private static final String TAG = "NotifGroupAlertTransfer";
    private static final boolean DEBUG = StatusBar.DEBUG;
    private static final boolean SPEW = StatusBar.SPEW;

    /**
     * The list of entries containing group alert metadata for each group. Keyed by group key.
     */
    private final ArrayMap<String, GroupAlertEntry> mGroupAlertEntries = new ArrayMap<>();

    /**
     * The list of entries currently inflating that should alert after inflation. Keyed by
     * notification key.
     */
    private final ArrayMap<String, PendingAlertInfo> mPendingAlerts = new ArrayMap<>();

    private HeadsUpManager mHeadsUpManager;
    private final RowContentBindStage mRowContentBindStage;
    private final NotificationGroupManagerLegacy mGroupManager =
            Dependency.get(NotificationGroupManagerLegacy.class);

    private NotificationEntryManager mEntryManager;

    private boolean mIsDozing;

    /**
     * Injected constructor. See {@link StatusBarPhoneModule}.
     */
    public NotificationGroupAlertTransferHelper(RowContentBindStage bindStage) {
        Dependency.get(StatusBarStateController.class).addCallback(this);
        mRowContentBindStage = bindStage;
    }

    /** Causes the TransferHelper to register itself as a listener to the appropriate classes. */
    public void bind(NotificationEntryManager entryManager,
            NotificationGroupManagerLegacy groupManager) {
        if (mEntryManager != null) {
            throw new IllegalStateException("Already bound.");
        }

        // TODO(b/119637830): It would be good if GroupManager already had all pending notifications
        // as normal children (i.e. add notifications to GroupManager before inflation) so that we
        // don't have to have this dependency. We'd also have to worry less about the suppression
        // not being up to date.
        mEntryManager = entryManager;

        mEntryManager.addNotificationEntryListener(mNotificationEntryListener);
        groupManager.registerGroupChangeListener(mOnGroupChangeListener);
    }

    /**
     * Whether or not a notification has transferred its alert state to the notification and
     * the notification should alert after inflating.
     *
     * @param entry notification to check
     * @return true if the entry was transferred to and should inflate + alert
     */
    public boolean isAlertTransferPending(@NonNull NotificationEntry entry) {
        PendingAlertInfo alertInfo = mPendingAlerts.get(entry.getKey());
        return alertInfo != null && alertInfo.isStillValid();
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    @Override
    public void onStateChanged(int newState) {}

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (mIsDozing != isDozing) {
            for (GroupAlertEntry groupAlertEntry : mGroupAlertEntries.values()) {
                groupAlertEntry.mLastAlertTransferTime = 0;
                groupAlertEntry.mAlertSummaryOnNextAddition = false;
            }
        }
        mIsDozing = isDozing;
    }

    private final NotificationGroupManagerLegacy.OnGroupChangeListener mOnGroupChangeListener =
            new NotificationGroupManagerLegacy.OnGroupChangeListener() {
        @Override
        public void onGroupCreated(NotificationGroup group, String groupKey) {
            mGroupAlertEntries.put(groupKey, new GroupAlertEntry(group));
        }

        @Override
        public void onGroupRemoved(NotificationGroup group, String groupKey) {
            mGroupAlertEntries.remove(groupKey);
        }

        @Override
        public void onGroupSuppressionChanged(NotificationGroup group, boolean suppressed) {
            if (DEBUG) {
                Log.d(TAG, "!! onGroupSuppressionChanged: group.summary=" + group.summary
                        + " suppressed=" + suppressed);
            }
            NotificationEntry oldAlertOverride = group.alertOverride;
            onGroupChanged(group, oldAlertOverride);
        }

        @Override
        public void onGroupAlertOverrideChanged(NotificationGroup group,
                @Nullable NotificationEntry oldAlertOverride,
                @Nullable NotificationEntry newAlertOverride) {
            if (DEBUG) {
                Log.d(TAG, "!! onGroupAlertOverrideChanged: group.summary=" + group.summary
                        + " oldAlertOverride=" + oldAlertOverride
                        + " newAlertOverride=" + newAlertOverride);
            }
            onGroupChanged(group, oldAlertOverride);
        }
    };

    /**
     * Called when either the suppressed or alertOverride fields of the group changed
     *
     * @param group the group which changed
     * @param oldAlertOverride the previous value of group.alertOverride
     */
    private void onGroupChanged(NotificationGroup group,
            NotificationEntry oldAlertOverride) {
        // Group summary can be null if we are no longer suppressed because the summary was
        // removed. In that case, we don't need to alert the summary.
        if (group.summary == null) {
            if (DEBUG) {
                Log.d(TAG, "onGroupChanged: summary is null");
            }
            return;
        }
        if (group.suppressed || group.alertOverride != null) {
            checkForForwardAlertTransfer(group.summary, oldAlertOverride);
        } else {
            if (DEBUG) {
                Log.d(TAG, "onGroupChanged: maybe transfer back");
            }
            GroupAlertEntry groupAlertEntry = mGroupAlertEntries.get(mGroupManager.getGroupKey(
                    group.summary.getSbn()));
            // Group is no longer suppressed or overridden.
            // We should check if we need to transfer the alert back to the summary.
            if (groupAlertEntry.mAlertSummaryOnNextAddition) {
                if (!mHeadsUpManager.isAlerting(group.summary.getKey())) {
                    alertNotificationWhenPossible(group.summary);
                }
                groupAlertEntry.mAlertSummaryOnNextAddition = false;
            } else {
                checkShouldTransferBack(groupAlertEntry);
            }
        }
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        if (DEBUG) {
            Log.d(TAG, "!! onHeadsUpStateChanged: entry=" + entry + " isHeadsUp=" + isHeadsUp);
        }
        if (isHeadsUp && entry.getSbn().getNotification().isGroupSummary()) {
            // a group summary is alerting; trigger the forward transfer checks
            checkForForwardAlertTransfer(entry, /* oldAlertOverride */ null);
        }
    }

    /**
     * Handles changes in a group's suppression or alertOverride, but where at least one of those
     * conditions is still true (either the group is suppressed, the group has an alertOverride,
     * or both).  The method determined which kind of child needs to receive the alert, finds the
     * entry currently alerting, and makes the transfer.
     *
     * Internally, this is handled with two main cases: the override needs the alert, or there is
     * no override but the summary is suppressed (so an isolated child needs the alert).
     *
     * @param summary the notification entry of the summary of the logical group.
     * @param oldAlertOverride the former value of group.alertOverride, before whatever event
     *                         required us to check for for a transfer condition.
     */
    private void checkForForwardAlertTransfer(NotificationEntry summary,
            NotificationEntry oldAlertOverride) {
        if (DEBUG) {
            Log.d(TAG, "checkForForwardAlertTransfer: enter");
        }
        NotificationGroup group = mGroupManager.getGroupForSummary(summary.getSbn());
        if (group != null && group.alertOverride != null) {
            handleOverriddenSummaryAlerted(summary);
        } else if (mGroupManager.isSummaryOfSuppressedGroup(summary.getSbn())) {
            handleSuppressedSummaryAlerted(summary, oldAlertOverride);
        }
    }

    private final NotificationEntryListener mNotificationEntryListener =
            new NotificationEntryListener() {
        // Called when a new notification has been posted but is not inflated yet. We use this to
        // see as early as we can if we need to abort a transfer.
        @Override
        public void onPendingEntryAdded(NotificationEntry entry) {
            if (DEBUG) {
                Log.d(TAG, "!! onPendingEntryAdded: entry=" + entry);
            }
            String groupKey = mGroupManager.getGroupKey(entry.getSbn());
            GroupAlertEntry groupAlertEntry = mGroupAlertEntries.get(groupKey);
            if (groupAlertEntry != null && groupAlertEntry.mGroup.alertOverride == null) {
                // new pending group entries require us to transfer back from the child to the
                // group, but alertOverrides are only present in very limited circumstances, so
                // while it's possible the group should ALSO alert, the previous detection which set
                // this alertOverride won't be invalidated by this notification added to this group.
                checkShouldTransferBack(groupAlertEntry);
            }
        }

        @Override
        public void onEntryRemoved(
                @Nullable NotificationEntry entry,
                NotificationVisibility visibility,
                boolean removedByUser,
                int reason) {
            // Removes any alerts pending on this entry. Note that this will not stop any inflation
            // tasks started by a transfer, so this should only be used as clean-up for when
            // inflation is stopped and the pending alert no longer needs to happen.
            mPendingAlerts.remove(entry.getKey());
        }
    };

    /**
     * Gets the number of new notifications pending inflation that will be added to the group
     * but currently aren't and should not alert.
     *
     * @param group group to check
     * @return the number of new notifications that will be added to the group
     */
    private int getPendingChildrenNotAlerting(@NonNull NotificationGroup group) {
        if (mEntryManager == null) {
            return 0;
        }
        int number = 0;
        Iterable<NotificationEntry> values = mEntryManager.getPendingNotificationsIterator();
        for (NotificationEntry entry : values) {
            if (isPendingNotificationInGroup(entry, group) && onlySummaryAlerts(entry)) {
                number++;
            }
        }
        return number;
    }

    /**
     * Checks if the pending inflations will add children to this group.
     *
     * @param group group to check
     * @return true if a pending notification will add to this group
     */
    private boolean pendingInflationsWillAddChildren(@NonNull NotificationGroup group) {
        if (mEntryManager == null) {
            return false;
        }
        Iterable<NotificationEntry> values = mEntryManager.getPendingNotificationsIterator();
        for (NotificationEntry entry : values) {
            if (isPendingNotificationInGroup(entry, group)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a new pending notification will be added to the group.
     *
     * @param entry pending notification
     * @param group group to check
     * @return true if the notification will add to the group, false o/w
     */
    private boolean isPendingNotificationInGroup(@NonNull NotificationEntry entry,
            @NonNull NotificationGroup group) {
        String groupKey = mGroupManager.getGroupKey(group.summary.getSbn());
        return mGroupManager.isGroupChild(entry.getSbn())
                && Objects.equals(mGroupManager.getGroupKey(entry.getSbn()), groupKey)
                && !group.children.containsKey(entry.getKey());
    }

    /**
     * Handles the scenario where a summary that has been suppressed is itself, or has a former
     * alertOverride (in the form of an isolated logical child) which was alerted.  A suppressed
     * summary should for all intents and purposes be invisible to the user and as a result should
     * not alert.  When this is the case, it is our responsibility to pass the alert to the
     * appropriate child which will be the representative notification alerting for the group.
     *
     * @param summary the summary that is suppressed and (potentially) alerting
     * @param oldAlertOverride the alertOverride before whatever event triggered this method.  If
     *                         the alert override was removed, this will be the entry that should
     *                         be transferred back from.
     */
    private void handleSuppressedSummaryAlerted(@NonNull NotificationEntry summary,
            NotificationEntry oldAlertOverride) {
        if (DEBUG) {
            Log.d(TAG, "handleSuppressedSummaryAlerted: summary=" + summary);
        }
        GroupAlertEntry groupAlertEntry =
                mGroupAlertEntries.get(mGroupManager.getGroupKey(summary.getSbn()));

        if (!mGroupManager.isSummaryOfSuppressedGroup(summary.getSbn())
                || groupAlertEntry == null) {
            if (DEBUG) {
                Log.d(TAG, "handleSuppressedSummaryAlerted: invalid state");
            }
            return;
        }
        boolean summaryIsAlerting = mHeadsUpManager.isAlerting(summary.getKey());
        boolean priorityIsAlerting = oldAlertOverride != null
                && mHeadsUpManager.isAlerting(oldAlertOverride.getKey());
        if (!summaryIsAlerting && !priorityIsAlerting) {
            if (DEBUG) {
                Log.d(TAG, "handleSuppressedSummaryAlerted: no summary or override alerting");
            }
            return;
        }

        if (pendingInflationsWillAddChildren(groupAlertEntry.mGroup)) {
            // New children will actually be added to this group, let's not transfer the alert.
            if (DEBUG) {
                Log.d(TAG, "handleSuppressedSummaryAlerted: pending inflations");
            }
            return;
        }

        NotificationEntry child =
                mGroupManager.getLogicalChildren(summary.getSbn()).iterator().next();
        if (summaryIsAlerting) {
            if (DEBUG) {
                Log.d(TAG, "handleSuppressedSummaryAlerted: transfer summary -> child");
            }
            tryTransferAlertState(summary, /*from*/ summary, /*to*/ child, groupAlertEntry);
            return;
        }
        // Summary didn't have the alert, so we're in "transfer back" territory.  First, make sure
        // it's not too late to transfer back, then transfer the alert from the oldAlertOverride to
        // the isolated child which should receive the alert.
        if (!canStillTransferBack(groupAlertEntry)) {
            if (DEBUG) {
                Log.d(TAG, "handleSuppressedSummaryAlerted: transfer from override: too late");
            }
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "handleSuppressedSummaryAlerted: transfer override -> child");
        }
        tryTransferAlertState(summary, /*from*/ oldAlertOverride, /*to*/ child, groupAlertEntry);
    }

    /**
     * Checks for and handles the scenario where the given entry is the summary of a group which
     * has an alertOverride, and either the summary itself or one of its logical isolated children
     * is currently alerting (which happens if the summary is suppressed).
     */
    private void handleOverriddenSummaryAlerted(NotificationEntry summary) {
        if (DEBUG) {
            Log.d(TAG, "handleOverriddenSummaryAlerted: summary=" + summary);
        }
        GroupAlertEntry groupAlertEntry =
                mGroupAlertEntries.get(mGroupManager.getGroupKey(summary.getSbn()));
        NotificationGroup group = mGroupManager.getGroupForSummary(summary.getSbn());
        if (group == null || group.alertOverride == null || groupAlertEntry == null) {
            if (DEBUG) {
                Log.d(TAG, "handleOverriddenSummaryAlerted: invalid state");
            }
            return;
        }
        boolean summaryIsAlerting = mHeadsUpManager.isAlerting(summary.getKey());
        if (summaryIsAlerting) {
            if (DEBUG) {
                Log.d(TAG, "handleOverriddenSummaryAlerted: transfer summary -> override");
            }
            tryTransferAlertState(summary, /*from*/ summary, group.alertOverride, groupAlertEntry);
            return;
        }
        // Summary didn't have the alert, so we're in "transfer back" territory.  First, make sure
        // it's not too late to transfer back, then remove the alert from any of the logical
        // children, and if one of them was alerting, we can alert the override.
        if (!canStillTransferBack(groupAlertEntry)) {
            if (DEBUG) {
                Log.d(TAG, "handleOverriddenSummaryAlerted: transfer from child: too late");
            }
            return;
        }
        List<NotificationEntry> children = mGroupManager.getLogicalChildren(summary.getSbn());
        if (children == null) {
            if (DEBUG) {
                Log.d(TAG, "handleOverriddenSummaryAlerted: no children");
            }
            return;
        }
        children.remove(group.alertOverride); // do not release the alert on our desired destination
        boolean releasedChild = releaseChildAlerts(children);
        if (releasedChild) {
            if (DEBUG) {
                Log.d(TAG, "handleOverriddenSummaryAlerted: transfer child -> override");
            }
            tryTransferAlertState(summary, /*from*/ null, group.alertOverride, groupAlertEntry);
        } else {
            if (DEBUG) {
                Log.d(TAG, "handleOverriddenSummaryAlerted: no child alert released");
            }
        }
    }

    /**
     * Transfers the alert state one entry to another. We remove the alert from the first entry
     * immediately to have the incorrect one up as short as possible. The second should alert
     * when possible.
     *
     * @param summary entry of the summary
     * @param fromEntry entry to transfer alert from
     * @param toEntry entry to transfer to
     */
    private void tryTransferAlertState(
            NotificationEntry summary,
            NotificationEntry fromEntry,
            NotificationEntry toEntry,
            GroupAlertEntry groupAlertEntry) {
        if (toEntry != null) {
            if (toEntry.getRow().keepInParent()
                    || toEntry.isRowRemoved()
                    || toEntry.isRowDismissed()) {
                // The notification is actually already removed. No need to alert it.
                return;
            }
            if (!mHeadsUpManager.isAlerting(toEntry.getKey()) && onlySummaryAlerts(summary)) {
                groupAlertEntry.mLastAlertTransferTime = SystemClock.elapsedRealtime();
            }
            if (DEBUG) {
                Log.d(TAG, "transferAlertState: fromEntry=" + fromEntry + " toEntry=" + toEntry);
            }
            transferAlertState(fromEntry, toEntry);
        }
    }
    private void transferAlertState(@Nullable NotificationEntry fromEntry,
            @NonNull NotificationEntry toEntry) {
        if (fromEntry != null) {
            mHeadsUpManager.removeNotification(fromEntry.getKey(), true /* releaseImmediately */);
        }
        alertNotificationWhenPossible(toEntry);
    }

    /**
     * Determines if we need to transfer the alert back to the summary from the child and does
     * so if needed.
     *
     * This can happen since notification groups are not delivered as a whole unit and it is
     * possible we erroneously transfer the alert from the summary to the child even though
     * more children are coming. Thus, if a child is added within a certain timeframe after we
     * transfer, we back out and alert the summary again.
     *
     * An alert can only transfer back within a small window of time after a transfer away from the
     * summary to a child happened.
     *
     * @param groupAlertEntry group alert entry to check
     */
    private void checkShouldTransferBack(@NonNull GroupAlertEntry groupAlertEntry) {
        if (canStillTransferBack(groupAlertEntry)) {
            NotificationEntry summary = groupAlertEntry.mGroup.summary;

            if (!onlySummaryAlerts(summary)) {
                return;
            }
            ArrayList<NotificationEntry> children = mGroupManager.getLogicalChildren(
                    summary.getSbn());
            int numActiveChildren = children.size();
            int numPendingChildren = getPendingChildrenNotAlerting(groupAlertEntry.mGroup);
            int numChildren = numActiveChildren + numPendingChildren;
            if (numChildren <= 1) {
                return;
            }
            boolean releasedChild = releaseChildAlerts(children);
            if (releasedChild && !mHeadsUpManager.isAlerting(summary.getKey())) {
                boolean notifyImmediately = numActiveChildren > 1;
                if (notifyImmediately) {
                    alertNotificationWhenPossible(summary);
                } else {
                    // Should wait until the pending child inflates before alerting.
                    groupAlertEntry.mAlertSummaryOnNextAddition = true;
                }
                groupAlertEntry.mLastAlertTransferTime = 0;
            }
        }
    }

    private boolean canStillTransferBack(@NonNull GroupAlertEntry groupAlertEntry) {
        return SystemClock.elapsedRealtime() - groupAlertEntry.mLastAlertTransferTime
                < ALERT_TRANSFER_TIMEOUT;
    }

    private boolean releaseChildAlerts(List<NotificationEntry> children) {
        boolean releasedChild = false;
        if (SPEW) {
            Log.d(TAG, "releaseChildAlerts: numChildren=" + children.size());
        }
        for (int i = 0; i < children.size(); i++) {
            NotificationEntry entry = children.get(i);
            if (SPEW) {
                Log.d(TAG, "releaseChildAlerts: checking i=" + i + " entry=" + entry
                        + " onlySummaryAlerts=" + onlySummaryAlerts(entry)
                        + " isAlerting=" + mHeadsUpManager.isAlerting(entry.getKey())
                        + " isPendingAlert=" + mPendingAlerts.containsKey(entry.getKey()));
            }
            if (onlySummaryAlerts(entry) && mHeadsUpManager.isAlerting(entry.getKey())) {
                releasedChild = true;
                mHeadsUpManager.removeNotification(
                        entry.getKey(), true /* releaseImmediately */);
            }
            if (mPendingAlerts.containsKey(entry.getKey())) {
                // This is the child that would've been removed if it was inflated.
                releasedChild = true;
                mPendingAlerts.get(entry.getKey()).mAbortOnInflation = true;
            }
        }
        if (SPEW) {
            Log.d(TAG, "releaseChildAlerts: didRelease=" + releasedChild);
        }
        return releasedChild;
    }

    /**
     * Tries to alert the notification. If its content view is not inflated, we inflate and continue
     * when the entry finishes inflating the view.
     *
     * @param entry entry to show
     */
    private void alertNotificationWhenPossible(@NonNull NotificationEntry entry) {
        @InflationFlag int contentFlag = mHeadsUpManager.getContentFlag();
        final RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        if ((params.getContentViews() & contentFlag) == 0) {
            if (DEBUG) {
                Log.d(TAG, "alertNotificationWhenPossible: async requestRebind entry=" + entry);
            }
            mPendingAlerts.put(entry.getKey(), new PendingAlertInfo(entry));
            params.requireContentViews(contentFlag);
            mRowContentBindStage.requestRebind(entry, en -> {
                PendingAlertInfo alertInfo = mPendingAlerts.remove(entry.getKey());
                if (alertInfo != null) {
                    if (alertInfo.isStillValid()) {
                        alertNotificationWhenPossible(entry);
                    } else {
                        // The transfer is no longer valid. Free the content.
                        mRowContentBindStage.getStageParams(entry).markContentViewsFreeable(
                                contentFlag);
                        mRowContentBindStage.requestRebind(entry, null);
                    }
                }
            });
            return;
        }
        if (mHeadsUpManager.isAlerting(entry.getKey())) {
            if (DEBUG) {
                Log.d(TAG, "alertNotificationWhenPossible: continue alerting entry=" + entry);
            }
            mHeadsUpManager.updateNotification(entry.getKey(), true /* alert */);
        } else {
            if (DEBUG) {
                Log.d(TAG, "alertNotificationWhenPossible: start alerting entry=" + entry);
            }
            mHeadsUpManager.showNotification(entry);
        }
    }

    private boolean onlySummaryAlerts(NotificationEntry entry) {
        return entry.getSbn().getNotification().getGroupAlertBehavior()
                == Notification.GROUP_ALERT_SUMMARY;
    }

    /**
     * Information about a pending alert used to determine if the alert is still needed when
     * inflation completes.
     */
    private class PendingAlertInfo {

        /**
         * The original notification when the transfer is initiated. This is used to determine if
         * the transfer is still valid if the notification is updated.
         */
        final StatusBarNotification mOriginalNotification;
        final NotificationEntry mEntry;

        /**
         * The notification is still pending inflation but we've decided that we no longer need
         * the content view (e.g. suppression might have changed and we decided we need to transfer
         * back).
         *
         * TODO: Replace this entire structure with {@link RowContentBindStage#requestRebind)}.
         */
        boolean mAbortOnInflation;

        PendingAlertInfo(NotificationEntry entry) {
            mOriginalNotification = entry.getSbn();
            mEntry = entry;
        }

        /**
         * Whether or not the pending alert is still valid and should still alert after inflation.
         *
         * @return true if the pending alert should still occur, false o/w
         */
        private boolean isStillValid() {
            if (mAbortOnInflation) {
                // Notification is aborted due to the transfer being explicitly cancelled
                return false;
            }
            if (mEntry.getSbn().getGroupKey() != mOriginalNotification.getGroupKey()) {
                // Groups have changed
                return false;
            }
            if (mEntry.getSbn().getNotification().isGroupSummary()
                    != mOriginalNotification.getNotification().isGroupSummary()) {
                // Notification has changed from group summary to not or vice versa
                return false;
            }
            return true;
        }
    }

    /**
     * Contains alert metadata for the notification group used to determine when/how the alert
     * should be transferred.
     */
    private static class GroupAlertEntry {
        /**
         * The time when the last alert transfer from summary to child happened.
         */
        long mLastAlertTransferTime;
        boolean mAlertSummaryOnNextAddition;
        final NotificationGroup mGroup;

        GroupAlertEntry(NotificationGroup group) {
            this.mGroup = group;
        }
    }
}
