/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.notifications;

import android.app.ActivityManager;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationViewController;
import com.android.car.notification.PreprocessingManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.SystemUI;

/**
 * Standalone SystemUI for displaying Notifications that have been designed to be used in the car
 */
public class NotificationsUI extends SystemUI {

    private static final String TAG = "NotificationsUI";
    private CarNotificationListener mCarNotificationListener;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;
    private NotificationClickHandlerFactory mClickHandlerFactory;
    private Car mCar;
    private ViewGroup mCarNotificationWindow;
    private NotificationViewController mNotificationViewController;
    private boolean mIsShowing;
    private CarUxRestrictionManagerWrapper mCarUxRestrictionManagerWrapper =
            new CarUxRestrictionManagerWrapper();

    /**
     * Inits the window that hosts the notifications and establishes the connections
     * to the car related services.
     */
    @Override
    public void start() {
        WindowManager windowManager =
                (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mCarNotificationListener = new CarNotificationListener();
        mClickHandlerFactory = new NotificationClickHandlerFactory(
                IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE)),
                launchResult -> {
                    if (launchResult == ActivityManager.START_TASK_TO_FRONT
                            || launchResult == ActivityManager.START_SUCCESS) {
                        closeCarNotifications();
                    }
                });
        mCarNotificationListener.registerAsSystemService(mContext, mCarUxRestrictionManagerWrapper,
                mClickHandlerFactory);
        mCar = Car.createCar(mContext, mCarConnectionListener);
        mCar.connect();


        mCarNotificationWindow = (ViewGroup) View.inflate(mContext,
                R.layout.navigation_bar_window, null);
        View.inflate(mContext,
                com.android.car.notification.R.layout.notification_center_activity,
                mCarNotificationWindow);
        mCarNotificationWindow.findViewById(
                com.android.car.notification.R.id.exit_button_container)
                .setOnClickListener(v -> toggleShowingCarNotifications());

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        layoutParams.setTitle("Car Notification Window");
        // start in the hidden state
        mCarNotificationWindow.setVisibility(View.GONE);
        windowManager.addView(mCarNotificationWindow, layoutParams);
        mNotificationViewController = new NotificationViewController(
                mCarNotificationWindow
                        .findViewById(com.android.car.notification.R.id.notification_view),
                PreprocessingManager.getInstance(mContext),
                mCarNotificationListener,
                mCarUxRestrictionManagerWrapper
        );
        // Add to the SystemUI component registry
        putComponent(NotificationsUI.class, this);
    }

    /**
     * Connection callback to establish UX Restrictions
     */
    private ServiceConnection mCarConnectionListener = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mCarUxRestrictionsManager = (CarUxRestrictionsManager) mCar.getCarManager(
                        Car.CAR_UX_RESTRICTION_SERVICE);
                mCarUxRestrictionManagerWrapper
                        .setCarUxRestrictionsManager(mCarUxRestrictionsManager);
                PreprocessingManager preprocessingManager = PreprocessingManager.getInstance(
                        mContext);
                preprocessingManager
                        .setCarUxRestrictionManagerWrapper(mCarUxRestrictionManagerWrapper);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car not connected in CarConnectionListener", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "Car service disconnected unexpectedly");
        }
    };

    /**
     * Toggles the visiblity of the notifications
     */
    public void toggleShowingCarNotifications() {
        if (mCarNotificationWindow.getVisibility() == View.VISIBLE) {
            closeCarNotifications();
            return;
        }
        openCarNotifications();
    }

    /**
     * Hides the notifications
     */
    public void closeCarNotifications() {
        mCarNotificationWindow.setVisibility(View.GONE);
        mNotificationViewController.disable();
        mIsShowing = false;
    }

    /**
     * Sets the notifications to visible
     */
    public void openCarNotifications() {
        mCarNotificationWindow.setVisibility(View.VISIBLE);
        mNotificationViewController.enable();
        mIsShowing = true;
    }

    /**
     * Returns {@code true} if notifications are currently on the screen
     */
    public boolean isShowing() {
        return mIsShowing;
    }
}
