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

package com.android.systemui.car.statusbar;

import android.car.Car;
import android.car.user.CarUserManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Controls a TextView with the current driver's username
 */
@SysUISingleton
public class UserNameViewController {
    private static final String TAG = "UserNameViewController";

    private Context mContext;
    private UserManager mUserManager;
    private CarUserManager mCarUserManager;
    private CarServiceProvider mCarServiceProvider;
    private CarDeviceProvisionedController mCarDeviceProvisionedController;
    private BroadcastDispatcher mBroadcastDispatcher;
    private TextView mUserNameView;

    private final BroadcastReceiver mUserUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUser(mCarDeviceProvisionedController.getCurrentUser());
        }
    };

    private final CarUserManager.UserLifecycleListener mUserLifecycleListener =
            new CarUserManager.UserLifecycleListener() {
                @Override
                public void onEvent(CarUserManager.UserLifecycleEvent event) {
                    if (event.getEventType()
                            == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
                        updateUser(event.getUserId());
                    }
                }
            };

    @Inject
    public UserNameViewController(Context context, CarServiceProvider carServiceProvider,
            UserManager userManager, BroadcastDispatcher broadcastDispatcher,
            CarDeviceProvisionedController carDeviceProvisionedController) {
        mContext = context;
        mCarServiceProvider = carServiceProvider;
        mUserManager = userManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mCarDeviceProvisionedController = carDeviceProvisionedController;
    }

    /**
     * Find the {@link TextView} for the driver's user name from a view and if found set it with the
     * current driver's user name.
     */
    public void addUserNameView(View v) {
        TextView userNameView = v.findViewById(R.id.user_name_text);
        if (userNameView != null) {
            if (mUserNameView == null) {
                registerForUserChangeEvents();
            }
            mUserNameView = userNameView;
            updateUser(mCarDeviceProvisionedController.getCurrentUser());
        }
    }

    /**
     * Clean up the controller and unregister receiver.
     */
    public void removeAll() {
        mBroadcastDispatcher.unregisterReceiver(mUserUpdateReceiver);
        if (mCarUserManager != null) {
            mCarUserManager.removeListener(mUserLifecycleListener);
        }
    }

    private void registerForUserChangeEvents() {
        // Register for user switching
        mCarServiceProvider.addListener(car -> {
            mCarUserManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
            if (mCarUserManager != null) {
                mCarUserManager.addListener(Runnable::run, mUserLifecycleListener);
            } else {
                Log.e(TAG, "CarUserManager could not be obtained.");
            }
        });
        // Also register for user info changing
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mBroadcastDispatcher.registerReceiver(mUserUpdateReceiver, filter, /* executor= */ null,
                UserHandle.ALL);
    }

    private void updateUser(int userId) {
        if (mUserNameView != null) {
            UserInfo currentUserInfo = mUserManager.getUserInfo(userId);
            mUserNameView.setText(currentUserInfo.name);
        }
    }
}
