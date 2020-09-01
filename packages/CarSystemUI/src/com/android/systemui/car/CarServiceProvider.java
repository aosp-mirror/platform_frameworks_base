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
import android.content.Context;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Provides a common connection to the car service that can be shared. */
@Singleton
public class CarServiceProvider {

    private final Context mContext;
    private final List<CarServiceOnConnectedListener> mListeners = new ArrayList<>();
    private Car mCar;

    @Inject
    public CarServiceProvider(Context context) {
        mContext = context;
        mCar = Car.createCar(mContext, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                (car, ready) -> {
                    mCar = car;

                    synchronized (mListeners) {
                        for (CarServiceOnConnectedListener listener : mListeners) {
                            if (ready) {
                                listener.onConnected(mCar);
                            }
                        }
                    }
                });
    }

    @VisibleForTesting
    public CarServiceProvider(Context context, Car car) {
        mContext = context;
        mCar = car;
    }

    /**
     * Let's other components hook into the connection to the car service. If we're already
     * connected to the car service, the callback is immediately triggered.
     */
    public void addListener(CarServiceOnConnectedListener listener) {
        if (mCar.isConnected()) {
            listener.onConnected(mCar);
        }
        mListeners.add(listener);
    }

    /**
     * Listener which is triggered when Car Service is connected.
     */
    public interface CarServiceOnConnectedListener {
        /** This will be called when the car service has successfully been connected. */
        void onConnected(Car car);
    }
}
