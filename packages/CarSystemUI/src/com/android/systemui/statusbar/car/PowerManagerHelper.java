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
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.util.Log;

import com.android.systemui.car.CarServiceProvider;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class for connecting to the {@link CarPowerManager} and listening for power state changes.
 */
@Singleton
public class PowerManagerHelper {
    public static final String TAG = "PowerManagerHelper";

    private final CarServiceProvider mCarServiceProvider;

    private CarPowerManager mCarPowerManager;
    private CarPowerStateListener mCarPowerStateListener;

    private final CarServiceProvider.CarServiceOnConnectedListener mCarServiceLifecycleListener;

    @Inject
    public PowerManagerHelper(CarServiceProvider carServiceProvider) {
        mCarServiceProvider = carServiceProvider;
        mCarServiceLifecycleListener = car -> {
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
     * Sets a {@link CarPowerStateListener}. Should be set before {@link #connectToCarService()}.
     */
    public void setCarPowerStateListener(@NonNull CarPowerStateListener listener) {
        mCarPowerStateListener = listener;
    }

    /**
     * Connect to Car service.
     */
    public void connectToCarService() {
        mCarServiceProvider.addListener(mCarServiceLifecycleListener);
    }
}
