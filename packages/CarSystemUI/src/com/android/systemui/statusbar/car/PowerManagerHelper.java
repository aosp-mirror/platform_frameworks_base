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

import android.annotation.NonNull;
import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.Context;
import android.util.Log;

import com.android.systemui.CarSystemUIFactory;
import com.android.systemui.SystemUIFactory;

/**
 * Helper class for connecting to the {@link CarPowerManager} and listening for power state changes.
 */
public class PowerManagerHelper {
    public static final String TAG = "PowerManagerHelper";

    private final Context mContext;
    private final CarPowerStateListener mCarPowerStateListener;

    private CarPowerManager mCarPowerManager;

    private final CarServiceLifecycleListener mCarServiceLifecycleListener;

    PowerManagerHelper(Context context, @NonNull CarPowerStateListener listener) {
        mContext = context;
        mCarPowerStateListener = listener;
        mCarServiceLifecycleListener = (car, ready) -> {
            if (!ready) {
                return;
            }
            Log.d(TAG, "Car Service connected");
            mCarPowerManager = (CarPowerManager) car.getCarManager(Car.POWER_SERVICE);
            if (mCarPowerManager != null) {
                mCarPowerManager.setListener(mCarPowerStateListener);
            } else {
                Log.e(TAG, "CarPowerManager service not available");
            }
        };
    }

    /**
     * Connect to Car service.
     */
    void connectToCarService() {
        ((CarSystemUIFactory) SystemUIFactory.getInstance()).getCarServiceProvider(mContext)
                .addListener(mCarServiceLifecycleListener);
    }
}
