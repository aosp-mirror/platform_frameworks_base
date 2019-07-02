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

package com.android.systemui.statusbar.car;

import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarDrivingStateManager;
import android.car.drivingstate.CarDrivingStateManager.CarDrivingStateEventListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Helper class for connecting to the {@link CarDrivingStateManager} and listening for driving state
 * changes.
 */
public class DrivingStateHelper {
    public static final String TAG = "DrivingStateHelper";

    private final Context mContext;
    private CarDrivingStateManager mDrivingStateManager;
    private Car mCar;
    private CarDrivingStateEventListener mDrivingStateHandler;

    public DrivingStateHelper(Context context,
            @NonNull CarDrivingStateEventListener drivingStateHandler) {
        mContext = context;
        mDrivingStateHandler = drivingStateHandler;
    }

    /**
     * Queries {@link CarDrivingStateManager} for current driving state. Returns {@code true} if car
     * is idling or moving, {@code false} otherwise.
     */
    public boolean isCurrentlyDriving() {
        if (mDrivingStateManager == null) {
            return false;
        }
        try {
            CarDrivingStateEvent currentState = mDrivingStateManager.getCurrentCarDrivingState();
            if (currentState != null) {
                return currentState.eventValue == CarDrivingStateEvent.DRIVING_STATE_IDLING
                        || currentState.eventValue == CarDrivingStateEvent.DRIVING_STATE_MOVING;
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Cannot determine current driving state. Car not connected", e);
        }

        return false; // Default to false.
    }

    /**
     * Establishes connection with the Car service.
     */
    public void connectToCarService() {
        mCar = Car.createCar(mContext, mCarConnectionListener);
        if (mCar != null) {
            mCar.connect();
        }
    }

    /**
     * Disconnects from Car service and cleans up listeners.
     */
    public void disconnectFromCarService() {
        if (mCar != null) {
            mCar.disconnect();
        }
    }

    private final ServiceConnection mCarConnectionListener =
            new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder service) {
                    logD("Car Service connected");
                    try {
                        mDrivingStateManager = (CarDrivingStateManager) mCar.getCarManager(
                                Car.CAR_DRIVING_STATE_SERVICE);
                        if (mDrivingStateManager != null) {
                            mDrivingStateManager.registerListener(mDrivingStateHandler);
                            mDrivingStateHandler.onDrivingStateChanged(
                                    mDrivingStateManager.getCurrentCarDrivingState());
                        } else {
                            Log.e(TAG, "CarDrivingStateService service not available");
                        }
                    } catch (CarNotConnectedException e) {
                        Log.e(TAG, "Car not connected", e);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    destroyDrivingStateManager();
                }
            };

    private void destroyDrivingStateManager() {
        try {
            if (mDrivingStateManager != null) {
                mDrivingStateManager.unregisterListener();
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Error unregistering listeners", e);
        }
    }

    private void logD(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message);
        }
    }
}
