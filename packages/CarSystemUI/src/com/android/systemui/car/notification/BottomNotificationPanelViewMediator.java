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

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.navigationbar.CarNavigationBarController;
import com.android.systemui.car.window.OverlayPanelViewController;
import com.android.systemui.statusbar.policy.ConfigurationController;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Implementation of NotificationPanelViewMediator that sets the notification panel to be opened
 * from the top navigation bar.
 */
@Singleton
public class BottomNotificationPanelViewMediator extends NotificationPanelViewMediator {

    @Inject
    public BottomNotificationPanelViewMediator(
            CarNavigationBarController carNavigationBarController,
            NotificationPanelViewController notificationPanelViewController,

            PowerManagerHelper powerManagerHelper,
            BroadcastDispatcher broadcastDispatcher,

            CarDeviceProvisionedController carDeviceProvisionedController,
            ConfigurationController configurationController
    ) {
        super(carNavigationBarController,
                notificationPanelViewController,
                powerManagerHelper,
                broadcastDispatcher,
                carDeviceProvisionedController,
                configurationController);
        notificationPanelViewController.setOverlayDirection(
                OverlayPanelViewController.OVERLAY_FROM_BOTTOM_BAR);
    }

    @Override
    public void registerListeners() {
        super.registerListeners();
        getCarNavigationBarController().registerBottomBarTouchListener(
                getNotificationPanelViewController().getDragOpenTouchListener());
    }
}
