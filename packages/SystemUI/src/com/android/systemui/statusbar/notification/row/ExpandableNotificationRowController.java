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
import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;

import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.NotifViewController;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.dagger.AppName;
import com.android.systemui.statusbar.notification.row.dagger.NotificationKey;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowScope;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerLogger;
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

    static final Uri BUBBLES_SETTING_URI =
            Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_BUBBLES);
    private static final String BUBBLES_SETTING_ENABLED_VALUE = "1";
    private final ExpandableNotificationRow mView;
    private final NotificationListContainer mListContainer;
    private final RemoteInputViewSubcomponent.Factory mRemoteInputViewSubcomponentFactory;
    private final ActivatableNotificationViewController mActivatableNotificationViewController;
    private final PluginManager mPluginManager;
    private final SystemClock mClock;
    private final String mAppName;
    private final String mNotificationKey;
    private final KeyguardBypassController mKeyguardBypassController;
    private final GroupMembershipManager mGroupMembershipManager;
    private final GroupExpansionManager mGroupExpansionManager;
    private final RowContentBindStage mRowContentBindStage;
    private final NotificationLogger mNotificationLogger;
    private final NotificationRowLogger mLogBufferLogger;
    private final HeadsUpManager mHeadsUpManager;
    private final ExpandableNotificationRow.OnExpandClickListener mOnExpandClickListener;
    private final StatusBarStateController mStatusBarStateController;
    private final MetricsLogger mMetricsLogger;
    private final NotificationChildrenContainerLogger mChildrenContainerLogger;
    private final ExpandableNotificationRow.CoordinateOnClickListener mOnFeedbackClickListener;
    private final NotificationGutsManager mNotificationGutsManager;
    private final OnUserInteractionCallback mOnUserInteractionCallback;
    private final FalsingManager mFalsingManager;
    private final FeatureFlags mFeatureFlags;
    private final boolean mAllowLongPress;
    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final SmartReplyConstants mSmartReplyConstants;
    private final SmartReplyController mSmartReplyController;
    private final ExpandableNotificationRowDragController mDragController;
    private final NotificationDismissibilityProvider mDismissibilityProvider;
    private final IStatusBarService mStatusBarService;

    private final NotificationSettingsController mSettingsController;

    @VisibleForTesting
    final NotificationSettingsController.Listener mSettingsListener =
            new NotificationSettingsController.Listener() {
                @Override
                public void onSettingChanged(Uri setting, int userId, String value) {
                    if (BUBBLES_SETTING_URI.equals(setting)) {
                        final int viewUserId = mView.getEntry().getSbn().getUserId();
                        if (viewUserId == UserHandle.USER_ALL || viewUserId == userId) {
                            mView.getPrivateLayout().setBubblesEnabledForUser(
                                    BUBBLES_SETTING_ENABLED_VALUE.equals(value));
                        }
                    }
                }
            };
    private final ExpandableNotificationRow.ExpandableNotificationRowLogger mLoggerCallback =
            new ExpandableNotificationRow.ExpandableNotificationRowLogger() {
                @Override
                public void logNotificationExpansion(String key, boolean userAction,
                        boolean expanded) {
                    mNotificationLogger.onExpansionChanged(key, userAction, expanded);
                }

                @Override
                public void logKeepInParentChildDetached(
                        NotificationEntry child,
                        NotificationEntry oldParent
                ) {
                    mLogBufferLogger.logKeepInParentChildDetached(child, oldParent);
                }

                @Override
                public void logSkipAttachingKeepInParentChild(
                        NotificationEntry child,
                        NotificationEntry newParent
                ) {
                    mLogBufferLogger.logSkipAttachingKeepInParentChild(child, newParent);
                }

                @Override
                public void logRemoveTransientFromContainer(
                        NotificationEntry childEntry,
                        NotificationEntry containerEntry
                ) {
                    mLogBufferLogger.logRemoveTransientFromContainer(childEntry, containerEntry);
                }

                @Override
                public void logRemoveTransientFromNssl(
                        NotificationEntry childEntry
                ) {
                    mLogBufferLogger.logRemoveTransientFromNssl(childEntry);
                }

                @Override
                public void logRemoveTransientFromViewGroup(
                        NotificationEntry childEntry,
                        ViewGroup containerView
                ) {
                    mLogBufferLogger.logRemoveTransientFromViewGroup(childEntry, containerView);
                }

                @Override
                public void logAddTransientRow(
                        NotificationEntry childEntry,
                        NotificationEntry containerEntry,
                        int index
                ) {
                    mLogBufferLogger.logAddTransientRow(childEntry, containerEntry, index);
                }

                @Override
                public void logRemoveTransientRow(
                        NotificationEntry childEntry,
                        NotificationEntry containerEntry
                ) {
                    mLogBufferLogger.logRemoveTransientRow(childEntry, containerEntry);
                }
            };


    @Inject
    public ExpandableNotificationRowController(
            ExpandableNotificationRow view,
            ActivatableNotificationViewController activatableNotificationViewController,
            RemoteInputViewSubcomponent.Factory rivSubcomponentFactory,
            MetricsLogger metricsLogger,
            NotificationRowLogger logBufferLogger,
            NotificationChildrenContainerLogger childrenContainerLogger,
            NotificationListContainer listContainer,
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
            FeatureFlags featureFlags,
            PeopleNotificationIdentifier peopleNotificationIdentifier,
            Optional<BubblesManager> bubblesManagerOptional,
            NotificationSettingsController settingsController,
            ExpandableNotificationRowDragController dragController,
            NotificationDismissibilityProvider dismissibilityProvider,
            IStatusBarService statusBarService) {
        mView = view;
        mListContainer = listContainer;
        mRemoteInputViewSubcomponentFactory = rivSubcomponentFactory;
        mActivatableNotificationViewController = activatableNotificationViewController;
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
        mFeatureFlags = featureFlags;
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
        mBubblesManagerOptional = bubblesManagerOptional;
        mSettingsController = settingsController;
        mDragController = dragController;
        mMetricsLogger = metricsLogger;
        mChildrenContainerLogger = childrenContainerLogger;
        mLogBufferLogger = logBufferLogger;
        mSmartReplyConstants = smartReplyConstants;
        mSmartReplyController = smartReplyController;
        mDismissibilityProvider = dismissibilityProvider;
        mStatusBarService = statusBarService;
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
                mLoggerCallback,
                mKeyguardBypassController,
                mGroupMembershipManager,
                mGroupExpansionManager,
                mHeadsUpManager,
                mRowContentBindStage,
                mOnExpandClickListener,
                mOnFeedbackClickListener,
                mFalsingManager,
                mStatusBarStateController,
                mPeopleNotificationIdentifier,
                mOnUserInteractionCallback,
                mBubblesManagerOptional,
                mNotificationGutsManager,
                mDismissibilityProvider,
                mMetricsLogger,
                mChildrenContainerLogger,
                mSmartReplyConstants,
                mSmartReplyController,
                mFeatureFlags,
                mStatusBarService
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
                mSettingsController.addCallback(BUBBLES_SETTING_URI, mSettingsListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mPluginManager.removePluginListener(mView);
                mStatusBarStateController.removeCallback(mStatusBarStateListener);
                mSettingsController.removeCallback(BUBBLES_SETTING_URI, mSettingsListener);
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

    @Override
    @NonNull
    public String getNodeLabel() {
        return logKey(mView.getEntry());
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
            mListContainer.notifyGroupChildRemoved(childView, mView.getChildrenContainer());
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
    public void setNotificationGroupWhen(long whenMillis) {
        if (mView.isSummaryWithChildren()) {
            mView.setNotificationGroupWhen(whenMillis);
        } else {
            Log.w(TAG, "Called setNotificationTime(" + whenMillis + ") on a leaf row");
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

    @Override
    public boolean offerToKeepInParentForAnimation() {
        //If the User dismissed the notification's parent, we want to keep it attached until the
        //dismiss animation is ongoing. Therefore we don't want to remove it in the ShadeViewDiffer.
        if (mView.isParentDismissed()) {
            mView.setKeepInParentForDismissAnimation(true);
            return true;
        }

        //Otherwise the view system doesn't do the removal, so we rely on the ShadeViewDiffer
        return false;
    }

    @Override
    public boolean removeFromParentIfKeptForAnimation() {
        ExpandableNotificationRow parent = mView.getNotificationParent();
        if (mView.keepInParentForDismissAnimation() && parent != null) {
            parent.removeChildNotification(mView);
            return true;
        }

        return false;
    }

    @Override
    public void resetKeepInParentForAnimation() {
        mView.setKeepInParentForDismissAnimation(false);
    }
}
