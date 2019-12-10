/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.view.View;
import android.view.WindowManager;

import com.android.car.notification.CarNotificationView;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.ShadeControllerImpl;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/** Car specific implementation of {@link com.android.systemui.statusbar.phone.ShadeController}. */
@Singleton
public class CarShadeControllerImpl extends ShadeControllerImpl {

    @Inject
    public CarShadeControllerImpl(CommandQueue commandQueue,
            StatusBarStateController statusBarStateController,
            StatusBarWindowController statusBarWindowController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            WindowManager windowManager,
            Lazy<StatusBar> statusBarLazy,
            Lazy<AssistManager> assistManagerLazy,
            Lazy<BubbleController> bubbleControllerLazy) {
        super(commandQueue, statusBarStateController, statusBarWindowController,
                statusBarKeyguardViewManager, windowManager,
                statusBarLazy, assistManagerLazy, bubbleControllerLazy);
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force, boolean delayed,
            float speedUpFactor) {
        super.animateCollapsePanels(flags, force, delayed, speedUpFactor);
        if (!getCarStatusBar().isPanelExpanded()
                || getCarNotificationView().getVisibility() == View.INVISIBLE) {
            return;
        }

        mStatusBarWindowController.setStatusBarFocusable(false);
        getCarStatusBar().getStatusBarWindowViewController().cancelExpandHelper();
        getStatusBarView().collapsePanel(true /* animate */, delayed, speedUpFactor);

        getCarStatusBar().animateNotificationPanel(getCarStatusBar().getClosingVelocity(), true);

        if (!getCarStatusBar().isTracking()) {
            mStatusBarWindowController.setPanelVisible(false);
            getCarNotificationView().setVisibility(View.INVISIBLE);
        }

        getCarStatusBar().setPanelExpanded(false);
    }

    private CarStatusBar getCarStatusBar() {
        return (CarStatusBar) mStatusBarLazy.get();
    }

    private CarNotificationView getCarNotificationView() {
        return getCarStatusBar().getCarNotificationView();
    }
}
