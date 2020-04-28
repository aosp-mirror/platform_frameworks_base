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
package com.android.systemui.car;

import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Connects to the car service a single time for shared use across all of system ui.
 */
public class CarServiceProvider {

    private final Context mContext;
    private final List<CarServiceLifecycleListener> mListeners = new ArrayList<>();
    private Car mCar;

    public CarServiceProvider(Context context) {
        mContext = context;
        mCar = Car.createCar(mContext, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                (car, ready) -> {
                    mCar = car;

                    synchronized (mListeners) {
                        for (CarServiceLifecycleListener listener : mListeners) {
                            listener.onLifecycleChanged(mCar, ready);
                        }
                    }
                });
    }

    /**
     * Let's other components hook into the connection to the car service. If we're already
     * connected
     * to the car service, the callback is immediately triggered.
     */
    public void addListener(CarServiceLifecycleListener listener) {
        if (mCar.isConnected()) {
            listener.onLifecycleChanged(mCar, /* ready= */ true);
        }
        mListeners.add(listener);
    }
}
