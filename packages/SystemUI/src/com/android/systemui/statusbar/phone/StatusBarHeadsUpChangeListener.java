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

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.window.StatusBarWindowController;

import javax.inject.Inject;

/**
 * Ties the {@link CentralSurfaces} to {@link com.android.systemui.statusbar.policy.HeadsUpManager}.
 */
@CentralSurfacesComponent.CentralSurfacesScope
public class StatusBarHeadsUpChangeListener implements OnHeadsUpChangedListener {
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarWindowController mStatusBarWindowController;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final NotificationsController mNotificationsController;
    private final DozeServiceHost mDozeServiceHost;
    private final DozeScrimController mDozeScrimController;

    @Inject
    StatusBarHeadsUpChangeListener(
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarWindowController statusBarWindowController,
            NotificationPanelViewController notificationPanelViewController,
            KeyguardBypassController keyguardBypassController,
            HeadsUpManagerPhone headsUpManager,
            StatusBarStateController statusBarStateController,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationsController notificationsController,
            DozeServiceHost dozeServiceHost,
            DozeScrimController dozeScrimController) {

        mNotificationShadeWindowController = notificationShadeWindowController;
        mStatusBarWindowController = statusBarWindowController;
        mNotificationPanelViewController = notificationPanelViewController;
        mKeyguardBypassController = keyguardBypassController;
        mHeadsUpManager = headsUpManager;
        mStatusBarStateController = statusBarStateController;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mNotificationsController = notificationsController;
        mDozeServiceHost = dozeServiceHost;
        mDozeScrimController = dozeScrimController;
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        if (inPinnedMode) {
            mNotificationShadeWindowController.setHeadsUpShowing(true);
            mStatusBarWindowController.setForceStatusBarVisible(true);
            if (mNotificationPanelViewController.isFullyCollapsed()) {
                // We need to ensure that the touchable region is updated before the
                //window will be
                // resized, in order to not catch any touches. A layout will ensure that
                // onComputeInternalInsets will be called and after that we can
                //resize the layout. Let's
                // make sure that the window stays small for one frame until the
                //touchableRegion is set.
                mNotificationPanelViewController.getView().requestLayout();
                mNotificationShadeWindowController.setForceWindowCollapsed(true);
                mNotificationPanelViewController.getView().post(() -> {
                    mNotificationShadeWindowController.setForceWindowCollapsed(false);
                });
            }
        } else {
            boolean bypassKeyguard = mKeyguardBypassController.getBypassEnabled()
                    && mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
            if (!mNotificationPanelViewController.isFullyCollapsed()
                    || mNotificationPanelViewController.isTracking()
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
                mNotificationPanelViewController.runAfterAnimationFinished(() -> {
                    if (!mHeadsUpManager.hasPinnedHeadsUp()) {
                        mNotificationShadeWindowController.setHeadsUpShowing(false);
                        mHeadsUpManager.setHeadsUpGoingAway(false);
                    }
                    mNotificationRemoteInputManager.onPanelCollapsed();
                });
            }
        }
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        mNotificationsController.requestNotificationUpdate("onHeadsUpStateChanged");
        if (mStatusBarStateController.isDozing() && isHeadsUp) {
            entry.setPulseSuppressed(false);
            mDozeServiceHost.fireNotificationPulse(entry);
            if (mDozeServiceHost.isPulsing()) {
                mDozeScrimController.cancelPendingPulseTimeout();
            }
        }
        if (!isHeadsUp && !mHeadsUpManager.hasNotifications()) {
            // There are no longer any notifications to show.  We should end the
            //pulse now.
            mDozeScrimController.pulseOutNow();
        }
    }
}
