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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.NotifViewController;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.dagger.AppName;
import com.android.systemui.statusbar.notification.row.dagger.NotificationKey;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowScope;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.wmshell.BubblesManager;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link ExpandableNotificationRow}.
 */
@NotificationRowScope
public class ExpandableNotificationRowController implements NotifViewController {
    private static final String TAG = "NotifRowController";
    private final ExpandableNotificationRow mView;
    private final NotificationListContainer mListContainer;
    private final RemoteInputViewSubcomponent.Factory mRemoteInputViewSubcomponentFactory;
    private final ActivatableNotificationViewController mActivatableNotificationViewController;
    private final NotificationMediaManager mMediaManager;
    private final PluginManager mPluginManager;
    private final SystemClock mClock;
    private final String mAppName;
    private final String mNotificationKey;
    private final KeyguardBypassController mKeyguardBypassController;
    private final GroupMembershipManager mGroupMembershipManager;
    private final GroupExpansionManager mGroupExpansionManager;
    private final RowContentBindStage mRowContentBindStage;
    private final NotificationLogger mNotificationLogger;
    private final HeadsUpManager mHeadsUpManager;
    private final ExpandableNotificationRow.OnExpandClickListener mOnExpandClickListener;
    private final StatusBarStateController mStatusBarStateController;
    private final MetricsLogger mMetricsLogger;

    private final ExpandableNotificationRow.ExpansionLogger mExpansionLogger =
            this::logNotificationExpansion;
    private final ExpandableNotificationRow.CoordinateOnClickListener mOnFeedbackClickListener;
    private final NotificationGutsManager mNotificationGutsManager;
    private final OnUserInteractionCallback mOnUserInteractionCallback;
    private final FalsingManager mFalsingManager;
    private final FalsingCollector mFalsingCollector;
    private final FeatureFlags mFeatureFlags;
    private final boolean mAllowLongPress;
    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final SmartReplyConstants mSmartReplyConstants;
    private final SmartReplyController mSmartReplyController;

    private final ExpandableNotificationRowDragController mDragController;

    @Inject
    public ExpandableNotificationRowController(
            ExpandableNotificationRow view,
            ActivatableNotificationViewController activatableNotificationViewController,
            RemoteInputViewSubcomponent.Factory rivSubcomponentFactory,
            MetricsLogger metricsLogger,
            NotificationListContainer listContainer,
            NotificationMediaManager mediaManager,
            SmartReplyConstants smartReplyConstants,
            SmartReplyController smartReplyController,
            PluginManager pluginManager,
            SystemClock clock,
            @AppName String appName,
            @NotificationKey String notificationKey,
            KeyguardBypassController keyguardBypassController,
            GroupMembershipManager groupMembershipManager,
            GroupExpansionManager groupExpansionManager,
            RowContentBindStage rowContentBindStage,
            NotificationLogger notificationLogger,
            HeadsUpManager headsUpManager,
            ExpandableNotificationRow.OnExpandClickListener onExpandClickListener,
            StatusBarStateController statusBarStateController,
            NotificationGutsManager notificationGutsManager,
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            OnUserInteractionCallback onUserInteractionCallback,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            FeatureFlags featureFlags,
            PeopleNotificationIdentifier peopleNotificationIdentifier,
            Optional<BubblesManager> bubblesManagerOptional,
            ExpandableNotificationRowDragController dragController) {
        mView = view;
        mListContainer = listContainer;
        mRemoteInputViewSubcomponentFactory = rivSubcomponentFactory;
        mActivatableNotificationViewController = activatableNotificationViewController;
        mMediaManager = mediaManager;
        mPluginManager = pluginManager;
        mClock = clock;
        mAppName = appName;
        mNotificationKey = notificationKey;
        mKeyguardBypassController = keyguardBypassController;
        mGroupMembershipManager = groupMembershipManager;
        mGroupExpansionManager = groupExpansionManager;
        mRowContentBindStage = rowContentBindStage;
        mNotificationLogger = notificationLogger;
        mHeadsUpManager = headsUpManager;
        mOnExpandClickListener = onExpandClickListener;
        mStatusBarStateController = statusBarStateController;
        mNotificationGutsManager = notificationGutsManager;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mFalsingManager = falsingManager;
        mOnFeedbackClickListener = mNotificationGutsManager::openGuts;
        mAllowLongPress = allowLongPress;
        mFalsingCollector = falsingCollector;
        mFeatureFlags = featureFlags;
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
        mBubblesManagerOptional = bubblesManagerOptional;
        mDragController = dragController;
        mMetricsLogger = metricsLogger;
        mSmartReplyConstants = smartReplyConstants;
        mSmartReplyController = smartReplyController;
    }

    /**
     * Initialize the controller.
     */
    public void init(NotificationEntry entry) {
        mActivatableNotificationViewController.init();
        mView.initialize(
                entry,
                mRemoteInputViewSubcomponentFactory,
                mAppName,
                mNotificationKey,
                mExpansionLogger,
                mKeyguardBypassController,
                mGroupMembershipManager,
                mGroupExpansionManager,
                mHeadsUpManager,
                mRowContentBindStage,
                mOnExpandClickListener,
                mMediaManager,
                mOnFeedbackClickListener,
                mFalsingManager,
                mFalsingCollector,
                mStatusBarStateController,
                mPeopleNotificationIdentifier,
                mOnUserInteractionCallback,
                mBubblesManagerOptional,
                mNotificationGutsManager,
                mMetricsLogger,
                mSmartReplyConstants,
                mSmartReplyController
        );
        mView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (mAllowLongPress) {
            if (mFeatureFlags.isEnabled(Flags.NOTIFICATION_DRAG_TO_CONTENTS)) {
                mView.setDragController(mDragController);
            }

            mView.setLongPressListener((v, x, y, item) -> {
                if (mView.isSummaryWithChildren()) {
                    mView.expandNotification();
                    return true;
                }
                return mNotificationGutsManager.openGuts(v, x, y, item);
            });
        }
        if (ENABLE_REMOTE_INPUT) {
            mView.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mView.getEntry().setInitializationTime(mClock.elapsedRealtime());
                mPluginManager.addPluginListener(mView,
                        NotificationMenuRowPlugin.class, false /* Allow multiple */);
                mView.setOnKeyguard(mStatusBarStateController.getState() == KEYGUARD);
                mStatusBarStateController.addCallback(mStatusBarStateListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mPluginManager.removePluginListener(mView);
                mStatusBarStateController.removeCallback(mStatusBarStateListener);
            }
        });
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mView.setOnKeyguard(newState == KEYGUARD);
                }
            };

    private void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        mNotificationLogger.onExpansionChanged(key, userAction, expanded);
    }

    @Override
    @NonNull
    public String getNodeLabel() {
        return mView.getEntry().getKey();
    }

    @Override
    @NonNull
    public View getView() {
        return mView;
    }

    @Override
    public View getChildAt(int index) {
        return mView.getChildNotificationAt(index);
    }

    @Override
    public void addChildAt(NodeController child, int index) {
        ExpandableNotificationRow childView = (ExpandableNotificationRow) child.getView();

        mView.addChildNotification((ExpandableNotificationRow) child.getView(), index);
        mListContainer.notifyGroupChildAdded(childView);
        childView.setChangingPosition(false);
    }

    @Override
    public void moveChildTo(NodeController child, int index) {
        ExpandableNotificationRow childView = (ExpandableNotificationRow) child.getView();
        childView.setChangingPosition(true);
        mView.removeChildNotification(childView);
        mView.addChildNotification(childView, index);
        childView.setChangingPosition(false);
    }

    @Override
    public void removeChild(NodeController child, boolean isTransfer) {
        ExpandableNotificationRow childView = (ExpandableNotificationRow) child.getView();

        if (isTransfer) {
            childView.setChangingPosition(true);
        }
        mView.removeChildNotification(childView);
        if (!isTransfer) {
            mListContainer.notifyGroupChildRemoved(childView, mView);
        }
    }

    @Override
    public void onViewAdded() {
    }

    @Override
    public void onViewMoved() {
    }

    @Override
    public void onViewRemoved() {
    }

    @Override
    public int getChildCount() {
        final List<ExpandableNotificationRow> mChildren = mView.getAttachedChildren();
        return mChildren != null ? mChildren.size() : 0;
    }

    @Override
    public void setUntruncatedChildCount(int childCount) {
        if (mView.isSummaryWithChildren()) {
            mView.setUntruncatedChildCount(childCount);
        } else {
            Log.w(TAG, "Called setUntruncatedChildCount(" + childCount + ") on a leaf row");
        }
    }

    @Override
    public void setSystemExpanded(boolean systemExpanded) {
        mView.setSystemExpanded(systemExpanded);
    }

    @Override
    public void setLastAudiblyAlertedMs(long lastAudiblyAlertedMs) {
        mView.setLastAudiblyAlertedMs(lastAudiblyAlertedMs);
    }

    @Override
    public void setFeedbackIcon(@Nullable FeedbackIcon icon) {
        mView.setFeedbackIcon(icon);
    }
}
