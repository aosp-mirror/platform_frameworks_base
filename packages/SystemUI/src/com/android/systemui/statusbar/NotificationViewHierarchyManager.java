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

import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import javax.inject.Inject;
import javax.inject.Named;
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
public class NotificationViewHierarchyManager implements DynamicPrivacyController.Listener {
    private static final String TAG = "NotificationViewHierarchyManager";

    private final Handler mHandler;

    //TODO: change this top <Entry, List<Entry>>?
    private final HashMap<ExpandableNotificationRow, List<ExpandableNotificationRow>>
            mTmpChildOrderMap = new HashMap<>();

    // Dependencies:
    protected final NotificationLockscreenUserManager mLockscreenUserManager;
    protected final NotificationGroupManager mGroupManager;
    protected final VisualStabilityManager mVisualStabilityManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationEntryManager mEntryManager;

    // Lazy
    private final Lazy<ShadeController> mShadeController;

    /**
     * {@code true} if notifications not part of a group should by default be rendered in their
     * expanded state. If {@code false}, then only the first notification will be expanded if
     * possible.
     */
    private final boolean mAlwaysExpandNonGroupedNotification;
    private final BubbleController mBubbleController;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final KeyguardBypassController mBypassController;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;

    // Used to help track down re-entrant calls to our update methods, which will cause bugs.
    private boolean mPerformingUpdate;
    // Hack to get around re-entrant call in onDynamicPrivacyChanged() until we can track down
    // the problem.
    private boolean mIsHandleDynamicPrivacyChangeScheduled;

    @Inject
    public NotificationViewHierarchyManager(Context context,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationGroupManager groupManager,
            VisualStabilityManager visualStabilityManager,
            StatusBarStateController statusBarStateController,
            NotificationEntryManager notificationEntryManager,
            Lazy<ShadeController> shadeController,
            KeyguardBypassController bypassController,
            BubbleController bubbleController,
            DynamicPrivacyController privacyController) {
        mHandler = mainHandler;
        mLockscreenUserManager = notificationLockscreenUserManager;
        mBypassController = bypassController;
        mGroupManager = groupManager;
        mVisualStabilityManager = visualStabilityManager;
        mStatusBarStateController = (SysuiStatusBarStateController) statusBarStateController;
        mEntryManager = notificationEntryManager;
        mShadeController = shadeController;
        Resources res = context.getResources();
        mAlwaysExpandNonGroupedNotification =
                res.getBoolean(R.bool.config_alwaysExpandNonGroupedNotifications);
        mBubbleController = bubbleController;
        mDynamicPrivacyController = privacyController;
        privacyController.addListener(this);
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
        Assert.isMainThread();
        beginUpdate();

        ArrayList<NotificationEntry> activeNotifications = mEntryManager.getNotificationData()
                .getActiveNotifications();
        ArrayList<ExpandableNotificationRow> toShow = new ArrayList<>(activeNotifications.size());
        final int N = activeNotifications.size();
        for (int i = 0; i < N; i++) {
            NotificationEntry ent = activeNotifications.get(i);
            if (ent.isRowDismissed() || ent.isRowRemoved()
                    || mBubbleController.isBubbleNotificationSuppressedFromShade(ent.key)) {
                // we don't want to update removed notifications because they could
                // temporarily become children if they were isolated before.
                continue;
            }

            int userId = ent.notification.getUserId();

            // Display public version of the notification if we need to redact.
            // TODO: This area uses a lot of calls into NotificationLockscreenUserManager.
            // We can probably move some of this code there.
            int currentUserId = mLockscreenUserManager.getCurrentUserId();
            boolean devicePublic = mLockscreenUserManager.isLockscreenPublicMode(currentUserId);
            boolean userPublic = devicePublic
                    || mLockscreenUserManager.isLockscreenPublicMode(userId);
            if (userPublic && mDynamicPrivacyController.isDynamicallyUnlocked()
                    && (userId == currentUserId || userId == UserHandle.USER_ALL
                    || !mLockscreenUserManager.needsSeparateWorkChallenge(userId))) {
                userPublic = false;
            }
            boolean needsRedaction = mLockscreenUserManager.needsRedaction(ent);
            boolean sensitive = userPublic && needsRedaction;
            boolean deviceSensitive = devicePublic
                    && !mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(
                    currentUserId);
            ent.setSensitive(sensitive, deviceSensitive);
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
            } else if (!mListContainer.containsView(v)) {
                // the view is added somewhere else. Let's make sure
                // the ordering works properly below, by excluding these
                toShow.remove(v);
                i--;
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

        updateRowStatesInternal();

        mListContainer.onNotificationViewUpdateFinished();

        endUpdate();
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
        Assert.isMainThread();
        beginUpdate();
        updateRowStatesInternal();
        endUpdate();
    }

    private void updateRowStatesInternal() {
        Trace.beginSection("NotificationViewHierarchyManager#updateRowStates");
        final int N = mListContainer.getContainerChildCount();

        int visibleNotifications = 0;
        boolean onKeyguard = mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        int maxNotifications = -1;
        if (onKeyguard && !mBypassController.getBypassEnabled()) {
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

            int userId = entry.notification.getUserId();
            boolean suppressedSummary = mGroupManager.isSummaryOfSuppressedGroup(
                    entry.notification) && !entry.isRowRemoved();
            boolean showOnKeyguard = mLockscreenUserManager.shouldShowOnKeyguard(entry);
            if (!showOnKeyguard) {
                // min priority notifications should show if their summary is showing
                if (mGroupManager.isChildInGroupWithSummary(entry.notification)) {
                    NotificationEntry summary = mGroupManager.getLogicalGroupSummary(
                            entry.notification);
                    if (summary != null && mLockscreenUserManager.shouldShowOnKeyguard(summary)) {
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
            row.setLastAudiblyAlertedMs(entry.getLastAudiblyAlertedMs());
        }

        Trace.beginSection("NotificationPresenter#onUpdateRowStates");
        mPresenter.onUpdateRowStates();
        Trace.endSection();
        Trace.endSection();
    }

    @Override
    public void onDynamicPrivacyChanged() {
        if (mPerformingUpdate) {
            Log.w(TAG, "onDynamicPrivacyChanged made a re-entrant call");
        }
        // This listener can be called from updateNotificationViews() via a convoluted listener
        // chain, so we post here to prevent a re-entrant call. See b/136186188
        // TODO: Refactor away the need for this
        if (!mIsHandleDynamicPrivacyChangeScheduled) {
            mIsHandleDynamicPrivacyChangeScheduled = true;
            mHandler.post(this::onHandleDynamicPrivacyChanged);
        }
    }

    private void onHandleDynamicPrivacyChanged() {
        mIsHandleDynamicPrivacyChangeScheduled = false;
        updateNotificationViews();
    }

    private void beginUpdate() {
        if (mPerformingUpdate) {
            Log.wtf(TAG, "Re-entrant code during update", new Exception());
        }
        mPerformingUpdate = true;
    }

    private void endUpdate() {
        if (!mPerformingUpdate) {
            Log.wtf(TAG, "Manager state has become desynced", new Exception());
        }
        mPerformingUpdate = false;
    }
}
