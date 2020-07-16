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
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.car.notification.R;
import com.android.car.notification.headsup.CarHeadsUpNotificationContainer;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A controller for SysUI's HUN display.
 */
@Singleton
public class CarHeadsUpNotificationSystemContainer implements CarHeadsUpNotificationContainer {
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final OverlayViewGlobalStateController mOverlayViewGlobalStateController;

    private final ViewGroup mWindow;
    private final ViewGroup mHeadsUpContentFrame;

    @Inject
    CarHeadsUpNotificationSystemContainer(Context context,
            @Main Resources resources,
            CarDeviceProvisionedController deviceProvisionedController,
            WindowManager windowManager,
            OverlayViewGlobalStateController overlayViewGlobalStateController) {
        mCarDeviceProvisionedController = deviceProvisionedController;
        mOverlayViewGlobalStateController = overlayViewGlobalStateController;

        boolean showOnBottom = resources.getBoolean(R.bool.config_showHeadsUpNotificationOnBottom);

        // Use TYPE_STATUS_BAR_SUB_PANEL window type since we need to find a window that is above
        // status bar but below navigation bar.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        lp.gravity = showOnBottom ? Gravity.BOTTOM : Gravity.TOP;
        lp.setTitle("HeadsUpNotification");

        int layoutId = showOnBottom
                ? R.layout.headsup_container_bottom
                : R.layout.headsup_container;
        mWindow = (ViewGroup) LayoutInflater.from(context).inflate(layoutId, null, false);
        windowManager.addView(mWindow, lp);
        mWindow.setVisibility(View.INVISIBLE);
        mHeadsUpContentFrame = mWindow.findViewById(R.id.headsup_content);
    }

    private void animateShow() {
        if (mCarDeviceProvisionedController.isCurrentUserFullySetup()
                && mOverlayViewGlobalStateController.shouldShowHUN()) {
            mWindow.setVisibility(View.VISIBLE);
        }
    }

    private void animateHide() {
        mWindow.setVisibility(View.INVISIBLE);
    }

    @Override
    public void displayNotification(View notificationView) {
        mHeadsUpContentFrame.addView(notificationView);
        animateShow();
    }

    @Override
    public void removeNotification(View notificationView) {
        mHeadsUpContentFrame.removeView(notificationView);
        if (mHeadsUpContentFrame.getChildCount() == 0) {
            animateHide();
        }
    }

    @Override
    public boolean isVisible() {
        return mWindow.getVisibility() == View.VISIBLE;
    }
}
