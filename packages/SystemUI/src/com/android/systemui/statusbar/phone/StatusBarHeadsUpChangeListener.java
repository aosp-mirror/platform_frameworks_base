/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.window.StatusBarWindowController;

import javax.inject.Inject;

/**
 * Ties the status bar to {@link com.android.systemui.statusbar.policy.HeadsUpManager}.
 */
@SysUISingleton
public class StatusBarHeadsUpChangeListener implements OnHeadsUpChangedListener, CoreStartable {
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarWindowController mStatusBarWindowController;
    private final ShadeViewController mShadeViewController;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
    private final NotificationStackScrollLayoutController mNsslController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final HeadsUpManager mHeadsUpManager;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;

    @Inject
    StatusBarHeadsUpChangeListener(
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarWindowController statusBarWindowController,
            ShadeViewController shadeViewController,
            PanelExpansionInteractor panelExpansionInteractor,
            NotificationStackScrollLayoutController nsslController,
            KeyguardBypassController keyguardBypassController,
            HeadsUpManager headsUpManager,
            StatusBarStateController statusBarStateController,
            NotificationRemoteInputManager notificationRemoteInputManager) {
        mNotificationShadeWindowController = notificationShadeWindowController;
        mStatusBarWindowController = statusBarWindowController;
        mShadeViewController = shadeViewController;
        mPanelExpansionInteractor = panelExpansionInteractor;
        mNsslController = nsslController;
        mKeyguardBypassController = keyguardBypassController;
        mHeadsUpManager = headsUpManager;
        mStatusBarStateController = statusBarStateController;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
    }

    @Override
    public void start() {
        mHeadsUpManager.addListener(this);
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        if (inPinnedMode) {
            mNotificationShadeWindowController.setHeadsUpShowing(true);
            mStatusBarWindowController.setForceStatusBarVisible(true);
            if (mPanelExpansionInteractor.isFullyCollapsed()) {
                mShadeViewController.updateTouchableRegion();
            }
        } else {
            boolean bypassKeyguard = mKeyguardBypassController.getBypassEnabled()
                    && mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
            if (!mPanelExpansionInteractor.isFullyCollapsed()
                    || mShadeViewController.isTracking()
                    || bypassKeyguard) {
                // We are currently tracking or is open and the shade doesn't need to
                //be kept
                // open artificially.
                mNotificationShadeWindowController.setHeadsUpShowing(false);
                if (bypassKeyguard) {
                    mStatusBarWindowController.setForceStatusBarVisible(false);
                }
            } else {
                // we need to keep the panel open artificially, let's wait until the
                //animation
                // is finished.
                mHeadsUpManager.setHeadsUpGoingAway(true);
                mNsslController.runAfterAnimationFinished(() -> {
                    if (!mHeadsUpManager.hasPinnedHeadsUp()) {
                        mNotificationShadeWindowController.setHeadsUpShowing(false);
                        mHeadsUpManager.setHeadsUpGoingAway(false);
                    }
                    mNotificationRemoteInputManager.onPanelCollapsed();
                });
            }
        }
    }
}
