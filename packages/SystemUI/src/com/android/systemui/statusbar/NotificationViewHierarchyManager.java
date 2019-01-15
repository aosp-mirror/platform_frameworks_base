/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Resources;
import android.os.Trace;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * NotificationViewHierarchyManager manages updating the view hierarchy of notification views based
 * on their group structure. For example, if a notification becomes bundled with another,
 * NotificationViewHierarchyManager will update the view hierarchy to reflect that. It also will
 * tell NotificationListContainer which notifications to display, and inform it of changes to those
 * notifications that might affect their display.
 */
@Singleton
public class NotificationViewHierarchyManager {
    private static final String TAG = "NotificationViewHierarchyManager";

    //TODO: change this top <Entry, List<Entry>>?
    private final HashMap<ExpandableNotificationRow, List<ExpandableNotificationRow>>
            mTmpChildOrderMap = new HashMap<>();

    // Dependencies:
    protected final NotificationLockscreenUserManager mLockscreenUserManager;
    protected final NotificationGroupManager mGroupManager;
    protected final VisualStabilityManager mVisualStabilityManager;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationEntryManager mEntryManager;

    // Lazy
    private final Lazy<ShadeController> mShadeController;

    /**
     * {@code true} if notifications not part of a group should by default be rendered in their
     * expanded state. If {@code false}, then only the first notification will be expanded if
     * possible.
     */
    private final boolean mAlwaysExpandNonGroupedNotification;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;

    @Inject
    public NotificationViewHierarchyManager(Context context,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationGroupManager groupManager,
            VisualStabilityManager visualStabilityManager,
            StatusBarStateController statusBarStateController,
            NotificationEntryManager notificationEntryManager,
            Lazy<ShadeController> shadeController) {
        mLockscreenUserManager = notificationLockscreenUserManager;
        mGroupManager = groupManager;
        mVisualStabilityManager = visualStabilityManager;
        mStatusBarStateController = statusBarStateController;
        mEntryManager = notificationEntryManager;
        mShadeController = shadeController;
        Resources res = context.getResources();
        mAlwaysExpandNonGroupedNotification =
                res.getBoolean(R.bool.config_alwaysExpandNonGroupedNotifications);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer) {
        mPresenter = presenter;
        mListContainer = listContainer;
    }

    /**
     * Updates the visual representation of the notifications.
     */
    //TODO: Rewrite this to focus on Entries, or some other data object instead of views
    public void updateNotificationViews() {
        ArrayList<NotificationEntry> activeNotifications = mEntryManager.getNotificationData()
                .getActiveNotifications();
        ArrayList<ExpandableNotificationRow> toShow = new ArrayList<>(activeNotifications.size());
        final int N = activeNotifications.size();
        for (int i = 0; i < N; i++) {
            NotificationEntry ent = activeNotifications.get(i);
            if (ent.isRowDismissed() || ent.isRowRemoved()) {
                // we don't want to update removed notifications because they could
                // temporarily become children if they were isolated before.
                continue;
            }

            int userId = ent.notification.getUserId();

            // Display public version of the notification if we need to redact.
            // TODO: This area uses a lot of calls into NotificationLockscreenUserManager.
            // We can probably move some of this code there.
            boolean devicePublic = mLockscreenUserManager.isLockscreenPublicMode(
                    mLockscreenUserManager.getCurrentUserId());
            boolean userPublic = devicePublic
                    || mLockscreenUserManager.isLockscreenPublicMode(userId);
            boolean needsRedaction = mLockscreenUserManager.needsRedaction(ent);
            boolean sensitive = userPublic && needsRedaction;
            boolean deviceSensitive = devicePublic
                    && !mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(
                    mLockscreenUserManager.getCurrentUserId());
            ent.getRow().setSensitive(sensitive, deviceSensitive);
            ent.getRow().setNeedsRedaction(needsRedaction);
            if (mGroupManager.isChildInGroupWithSummary(ent.notification)) {
                NotificationEntry summary = mGroupManager.getGroupSummary(ent.notification);
                List<ExpandableNotificationRow> orderedChildren =
                        mTmpChildOrderMap.get(summary.getRow());
                if (orderedChildren == null) {
                    orderedChildren = new ArrayList<>();
                    mTmpChildOrderMap.put(summary.getRow(), orderedChildren);
                }
                orderedChildren.add(ent.getRow());
            } else {
                toShow.add(ent.getRow());
            }

        }

        ArrayList<ExpandableNotificationRow> viewsToRemove = new ArrayList<>();
        for (int i=0; i< mListContainer.getContainerChildCount(); i++) {
            View child = mListContainer.getContainerChildAt(i);
            if (!toShow.contains(child) && child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;

                // Blocking helper is effectively a detached view. Don't bother removing it from the
                // layout.
                if (!row.isBlockingHelperShowing()) {
                    viewsToRemove.add((ExpandableNotificationRow) child);
                }
            }
        }

        for (ExpandableNotificationRow viewToRemove : viewsToRemove) {
            if (mGroupManager.isChildInGroupWithSummary(viewToRemove.getStatusBarNotification())) {
                // we are only transferring this notification to its parent, don't generate an
                // animation
                mListContainer.setChildTransferInProgress(true);
            }
            if (viewToRemove.isSummaryWithChildren()) {
                viewToRemove.removeAllChildren();
            }
            mListContainer.removeContainerView(viewToRemove);
            mListContainer.setChildTransferInProgress(false);
        }

        removeNotificationChildren();

        for (int i = 0; i < toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mVisualStabilityManager.notifyViewAddition(v);
                mListContainer.addContainerView(v);
            }
        }

        addNotificationChildrenAndSort();

        // So after all this work notifications still aren't sorted correctly.
        // Let's do that now by advancing through toShow and mListContainer in
        // lock-step, making sure mListContainer matches what we see in toShow.
        int j = 0;
        for (int i = 0; i < mListContainer.getContainerChildCount(); i++) {
            View child = mListContainer.getContainerChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                // We don't care about non-notification views.
                continue;
            }
            if (((ExpandableNotificationRow) child).isBlockingHelperShowing()) {
                // Don't count/reorder notifications that are showing the blocking helper!
                continue;
            }

            ExpandableNotificationRow targetChild = toShow.get(j);
            if (child != targetChild) {
                // Oops, wrong notification at this position. Put the right one
                // here and advance both lists.
                if (mVisualStabilityManager.canReorderNotification(targetChild)) {
                    mListContainer.changeViewPosition(targetChild, i);
                } else {
                    mVisualStabilityManager.addReorderingAllowedCallback(mEntryManager);
                }
            }
            j++;

        }

        mVisualStabilityManager.onReorderingFinished();
        // clear the map again for the next usage
        mTmpChildOrderMap.clear();

        updateRowStates();

        mListContainer.onNotificationViewUpdateFinished();
    }

    private void addNotificationChildrenAndSort() {
        // Let's now add all notification children which are missing
        boolean orderChanged = false;
        for (int i = 0; i < mListContainer.getContainerChildCount(); i++) {
            View view = mListContainer.getContainerChildAt(i);
            if (!(view instanceof ExpandableNotificationRow)) {
                // We don't care about non-notification views.
                continue;
            }

            ExpandableNotificationRow parent = (ExpandableNotificationRow) view;
            List<ExpandableNotificationRow> children = parent.getNotificationChildren();
            List<ExpandableNotificationRow> orderedChildren = mTmpChildOrderMap.get(parent);

            for (int childIndex = 0; orderedChildren != null && childIndex < orderedChildren.size();
                    childIndex++) {
                ExpandableNotificationRow childView = orderedChildren.get(childIndex);
                if (children == null || !children.contains(childView)) {
                    if (childView.getParent() != null) {
                        Log.wtf(TAG, "trying to add a notification child that already has " +
                                "a parent. class:" + childView.getParent().getClass() +
                                "\n child: " + childView);
                        // This shouldn't happen. We can recover by removing it though.
                        ((ViewGroup) childView.getParent()).removeView(childView);
                    }
                    mVisualStabilityManager.notifyViewAddition(childView);
                    parent.addChildNotification(childView, childIndex);
                    mListContainer.notifyGroupChildAdded(childView);
                }
            }

            // Finally after removing and adding has been performed we can apply the order.
            orderChanged |= parent.applyChildOrder(orderedChildren, mVisualStabilityManager,
                    mEntryManager);
        }
        if (orderChanged) {
            mListContainer.generateChildOrderChangedEvent();
        }
    }

    private void removeNotificationChildren() {
        // First let's remove all children which don't belong in the parents
        ArrayList<ExpandableNotificationRow> toRemove = new ArrayList<>();
        for (int i = 0; i < mListContainer.getContainerChildCount(); i++) {
            View view = mListContainer.getContainerChildAt(i);
            if (!(view instanceof ExpandableNotificationRow)) {
                // We don't care about non-notification views.
                continue;
            }

            ExpandableNotificationRow parent = (ExpandableNotificationRow) view;
            List<ExpandableNotificationRow> children = parent.getNotificationChildren();
            List<ExpandableNotificationRow> orderedChildren = mTmpChildOrderMap.get(parent);

            if (children != null) {
                toRemove.clear();
                for (ExpandableNotificationRow childRow : children) {
                    if ((orderedChildren == null
                            || !orderedChildren.contains(childRow))
                            && !childRow.keepInParent()) {
                        toRemove.add(childRow);
                    }
                }
                for (ExpandableNotificationRow remove : toRemove) {
                    parent.removeChildNotification(remove);
                    if (mEntryManager.getNotificationData().get(
                            remove.getStatusBarNotification().getKey()) == null) {
                        // We only want to add an animation if the view is completely removed
                        // otherwise it's just a transfer
                        mListContainer.notifyGroupChildRemoved(remove,
                                parent.getChildrenContainer());
                    }
                }
            }
        }
    }

    /**
     * Updates expanded, dimmed and locked states of notification rows.
     */
    public void updateRowStates() {
        Trace.beginSection("NotificationViewHierarchyManager#updateRowStates");
        final int N = mListContainer.getContainerChildCount();

        int visibleNotifications = 0;
        boolean onKeyguard = mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        int maxNotifications = -1;
        if (onKeyguard) {
            maxNotifications = mPresenter.getMaxNotificationsWhileLocked(true /* recompute */);
        }
        mListContainer.setMaxDisplayedNotifications(maxNotifications);
        Stack<ExpandableNotificationRow> stack = new Stack<>();
        for (int i = N - 1; i >= 0; i--) {
            View child = mListContainer.getContainerChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            stack.push((ExpandableNotificationRow) child);
        }
        while(!stack.isEmpty()) {
            ExpandableNotificationRow row = stack.pop();
            NotificationEntry entry = row.getEntry();
            boolean isChildNotification =
                    mGroupManager.isChildInGroupWithSummary(entry.notification);

            row.setOnKeyguard(onKeyguard);

            if (!onKeyguard) {
                // If mAlwaysExpandNonGroupedNotification is false, then only expand the
                // very first notification and if it's not a child of grouped notifications.
                row.setSystemExpanded(mAlwaysExpandNonGroupedNotification
                        || (visibleNotifications == 0 && !isChildNotification
                        && !row.isLowPriority()));
            }

            entry.getRow().setOnAmbient(mShadeController.get().isDozing());
            int userId = entry.notification.getUserId();
            boolean suppressedSummary = mGroupManager.isSummaryOfSuppressedGroup(
                    entry.notification) && !entry.isRowRemoved();
            boolean showOnKeyguard = mLockscreenUserManager.shouldShowOnKeyguard(entry
                    .notification);
            if (!showOnKeyguard) {
                // min priority notifications should show if their summary is showing
                if (mGroupManager.isChildInGroupWithSummary(entry.notification)) {
                    NotificationEntry summary = mGroupManager.getLogicalGroupSummary(
                            entry.notification);
                    if (summary != null && mLockscreenUserManager.shouldShowOnKeyguard(
                            summary.notification))         {
                        showOnKeyguard = true;
                    }
                }
            }
            if (suppressedSummary
                    || mLockscreenUserManager.shouldHideNotifications(userId)
                    || (onKeyguard && !showOnKeyguard)) {
                entry.getRow().setVisibility(View.GONE);
            } else {
                boolean wasGone = entry.getRow().getVisibility() == View.GONE;
                if (wasGone) {
                    entry.getRow().setVisibility(View.VISIBLE);
                }
                if (!isChildNotification && !entry.getRow().isRemoved()) {
                    if (wasGone) {
                        // notify the scroller of a child addition
                        mListContainer.generateAddAnimation(entry.getRow(),
                                !showOnKeyguard /* fromMoreCard */);
                    }
                    visibleNotifications++;
                }
            }
            if (row.isSummaryWithChildren()) {
                List<ExpandableNotificationRow> notificationChildren =
                        row.getNotificationChildren();
                int size = notificationChildren.size();
                for (int i = size - 1; i >= 0; i--) {
                    stack.push(notificationChildren.get(i));
                }
            }

            row.showAppOpsIcons(entry.mActiveAppOps);
            row.setLastAudiblyAlertedMs(entry.lastAudiblyAlertedMs);
        }

        Trace.beginSection("NotificationPresenter#onUpdateRowStates");
        mPresenter.onUpdateRowStates();
        Trace.endSection();
        Trace.endSection();
    }
}
