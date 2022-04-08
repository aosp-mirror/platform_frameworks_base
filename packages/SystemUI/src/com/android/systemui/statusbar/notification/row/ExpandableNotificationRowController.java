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

import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.dagger.AppName;
import com.android.systemui.statusbar.notification.row.dagger.DismissRunnable;
import com.android.systemui.statusbar.notification.row.dagger.NotificationKey;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowScope;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.time.SystemClock;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link ExpandableNotificationRow}.
 */
@NotificationRowScope
public class ExpandableNotificationRowController {
    private final ExpandableNotificationRow mView;
    private final ActivatableNotificationViewController mActivatableNotificationViewController;
    private final NotificationMediaManager mMediaManager;
    private final PluginManager mPluginManager;
    private final SystemClock mClock;
    private final String mAppName;
    private final String mNotificationKey;
    private final KeyguardBypassController mKeyguardBypassController;
    private final NotificationGroupManager mNotificationGroupManager;
    private final RowContentBindStage mRowContentBindStage;
    private final NotificationLogger mNotificationLogger;
    private final HeadsUpManager mHeadsUpManager;
    private final ExpandableNotificationRow.OnExpandClickListener mOnExpandClickListener;
    private final StatusBarStateController mStatusBarStateController;

    private final ExpandableNotificationRow.ExpansionLogger mExpansionLogger =
            this::logNotificationExpansion;
    private final ExpandableNotificationRow.OnAppOpsClickListener mOnAppOpsClickListener;
    private final NotificationGutsManager mNotificationGutsManager;
    private Runnable mOnDismissRunnable;
    private final FalsingManager mFalsingManager;
    private final boolean mAllowLongPress;
    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;

    @Inject
    public ExpandableNotificationRowController(ExpandableNotificationRow view,
            ActivatableNotificationViewController activatableNotificationViewController,
            NotificationMediaManager mediaManager, PluginManager pluginManager,
            SystemClock clock, @AppName String appName, @NotificationKey String notificationKey,
            KeyguardBypassController keyguardBypassController,
            NotificationGroupManager notificationGroupManager,
            RowContentBindStage rowContentBindStage,
            NotificationLogger notificationLogger, HeadsUpManager headsUpManager,
            ExpandableNotificationRow.OnExpandClickListener onExpandClickListener,
            StatusBarStateController statusBarStateController,
            NotificationGutsManager notificationGutsManager,
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            @DismissRunnable Runnable onDismissRunnable, FalsingManager falsingManager,
            PeopleNotificationIdentifier peopleNotificationIdentifier) {
        mView = view;
        mActivatableNotificationViewController = activatableNotificationViewController;
        mMediaManager = mediaManager;
        mPluginManager = pluginManager;
        mClock = clock;
        mAppName = appName;
        mNotificationKey = notificationKey;
        mKeyguardBypassController = keyguardBypassController;
        mNotificationGroupManager = notificationGroupManager;
        mRowContentBindStage = rowContentBindStage;
        mNotificationLogger = notificationLogger;
        mHeadsUpManager = headsUpManager;
        mOnExpandClickListener = onExpandClickListener;
        mStatusBarStateController = statusBarStateController;
        mNotificationGutsManager = notificationGutsManager;
        mOnDismissRunnable = onDismissRunnable;
        mOnAppOpsClickListener = mNotificationGutsManager::openGuts;
        mAllowLongPress = allowLongPress;
        mFalsingManager = falsingManager;
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
    }

    /**
     * Initialize the controller.
     */
    public void init() {
        mActivatableNotificationViewController.init();
        mView.initialize(
                mAppName,
                mNotificationKey,
                mExpansionLogger,
                mKeyguardBypassController,
                mNotificationGroupManager,
                mHeadsUpManager,
                mRowContentBindStage,
                mOnExpandClickListener,
                mMediaManager,
                mOnAppOpsClickListener,
                mFalsingManager,
                mStatusBarStateController,
                mPeopleNotificationIdentifier
        );
        mView.setOnDismissRunnable(mOnDismissRunnable);
        mView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (mAllowLongPress) {
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
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mPluginManager.removePluginListener(mView);
            }
        });
    }

    private void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        mNotificationLogger.onExpansionChanged(key, userAction, expanded);
    }

    /** */
    public void setOnDismissRunnable(Runnable onDismissRunnable) {
        mOnDismissRunnable = onDismissRunnable;
        mView.setOnDismissRunnable(onDismissRunnable);
    }
}
