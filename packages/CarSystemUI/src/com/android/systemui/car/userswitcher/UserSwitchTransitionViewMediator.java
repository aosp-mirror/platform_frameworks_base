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

package com.android.systemui.car.userswitcher;

import android.car.Car;
import android.car.user.CarUserManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.window.OverlayViewMediator;

import javax.inject.Inject;

/**
 * Registers listeners that subscribe to events that show or hide CarUserSwitchingDialog that is
 * mounted to SystemUiOverlayWindow.
 */
public class UserSwitchTransitionViewMediator implements OverlayViewMediator,
        CarUserManager.UserSwitchUiCallback {
    private static final String TAG = "UserSwitchTransitionViewMediator";

    private final CarServiceProvider mCarServiceProvider;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final UserSwitchTransitionViewController mUserSwitchTransitionViewController;

    @Inject
    public UserSwitchTransitionViewMediator(
            CarServiceProvider carServiceProvider,
            CarDeviceProvisionedController carDeviceProvisionedController,
            UserSwitchTransitionViewController userSwitchTransitionViewController) {
        mCarServiceProvider = carServiceProvider;
        mCarDeviceProvisionedController = carDeviceProvisionedController;
        mUserSwitchTransitionViewController = userSwitchTransitionViewController;
    }

    @Override
    public void registerListeners() {
        mCarServiceProvider.addListener(car -> {
            CarUserManager carUserManager =
                    (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);

            if (carUserManager != null) {
                carUserManager.setUserSwitchUiCallback(this);
                carUserManager.addListener(Runnable::run, this::handleUserLifecycleEvent);
            } else {
                Log.e(TAG, "registerListeners: CarUserManager could not be obtained.");
            }
        });
    }

    @Override
    public void setupOverlayContentViewControllers() {
        // no-op.
    }

    @Override
    public void showUserSwitchDialog(int userId) {
        mUserSwitchTransitionViewController.handleShow(userId);
    }

    @VisibleForTesting
    void handleUserLifecycleEvent(CarUserManager.UserLifecycleEvent event) {
        if (event.getEventType() == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STARTING
                && mCarDeviceProvisionedController.getCurrentUser() == event.getUserId()) {
            mUserSwitchTransitionViewController.handleShow(event.getUserId());
        }

        if (event.getEventType() == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
            mUserSwitchTransitionViewController.handleHide();
        }
    }
}
