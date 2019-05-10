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
import android.car.CarNotConnectedException;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

/**
 * Helper class for connecting to the {@link CarPowerManager} and listening for power state changes.
 */
public class PowerManagerHelper {
    public static final String TAG = "PowerManagerHelper";

    private final Context mContext;
    private final CarPowerStateListener mCarPowerStateListener;

    private Car mCar;
    private CarPowerManager mCarPowerManager;

    private final ServiceConnection mCarConnectionListener =
            new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.d(TAG, "Car Service connected");
                    try {
                        mCarPowerManager = (CarPowerManager) mCar.getCarManager(Car.POWER_SERVICE);
                        if (mCarPowerManager != null) {
                            mCarPowerManager.setListener(mCarPowerStateListener);
                        } else {
                            Log.e(TAG, "CarPowerManager service not available");
                        }
                    } catch (CarNotConnectedException e) {
                        Log.e(TAG, "Car not connected", e);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    destroyCarPowerManager();
                }
            };

    PowerManagerHelper(Context context, @NonNull CarPowerStateListener listener) {
        mContext = context;
        mCarPowerStateListener = listener;
    }

    /**
     * Connect to Car service.
     */
    void connectToCarService() {
        mCar = Car.createCar(mContext, mCarConnectionListener);
        if (mCar != null) {
            mCar.connect();
        }
    }

    /**
     * Disconnects from Car service.
     */
    void disconnectFromCarService() {
        if (mCar != null) {
            mCar.disconnect();
        }
    }

    private void destroyCarPowerManager() {
        if (mCarPowerManager != null) {
            mCarPowerManager.clearListener();
        }
    }
}
