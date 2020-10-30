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

package com.android.systemui.car.notification;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.car.notification.headsup.CarHeadsUpNotificationContainer;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * A controller for SysUI's HUN display.
 *
 * Used to attach HUNs views to window and determine whether to show HUN panel.
 */
@SysUISingleton
public class CarHeadsUpNotificationSystemContainer extends CarHeadsUpNotificationContainer {
    private static final String WINDOW_TITLE = "HeadsUpNotification";
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final OverlayViewGlobalStateController mOverlayViewGlobalStateController;

    @Inject
    CarHeadsUpNotificationSystemContainer(Context context,
            CarDeviceProvisionedController deviceProvisionedController,
            WindowManager windowManager,
            OverlayViewGlobalStateController overlayViewGlobalStateController) {
        super(context, windowManager);
        mCarDeviceProvisionedController = deviceProvisionedController;
        mOverlayViewGlobalStateController = overlayViewGlobalStateController;
    }

    @Override
    protected WindowManager.LayoutParams getWindowManagerLayoutParams() {
        // Use TYPE_STATUS_BAR_SUB_PANEL window type since we need to find a window that is above
        // status bar but below navigation bar.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        lp.gravity = getShowHunOnBottom() ? Gravity.BOTTOM : Gravity.TOP;
        lp.setTitle(WINDOW_TITLE);

        return lp;
    }

    @Override
    public boolean shouldShowHunPanel() {
        return mCarDeviceProvisionedController.isCurrentUserFullySetup()
                && mOverlayViewGlobalStateController.shouldShowHUN();
    }
}
