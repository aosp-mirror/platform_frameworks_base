/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.app.IActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

/**
 * Keep track of the sensors that is supported by the HAL.
 * @param <T> T is either face sensor or fingerprint sensor.
 */
public class SensorList<T> {
    private static final String TAG = "SensorList";
    private final SparseArray<T> mSensors;
    private final IActivityManager mActivityManager;

    public SensorList(IActivityManager activityManager) {
        mSensors = new SparseArray<T>();
        mActivityManager = activityManager;
    }

    /**
     * Adding sensor to the map with the sensor id as key. Also, starts a session if the user Id is
     * NULL.
     */
    public void addSensor(int sensorId, T sensor, int sessionUserId,
            SynchronousUserSwitchObserver userSwitchObserver) {
        mSensors.put(sensorId, sensor);
        registerUserSwitchObserver(sessionUserId, userSwitchObserver);
    }

    private void registerUserSwitchObserver(int sessionUserId,
            SynchronousUserSwitchObserver userSwitchObserver) {
        try {
            mActivityManager.registerUserSwitchObserver(userSwitchObserver,
                    TAG);
            if (sessionUserId == UserHandle.USER_NULL) {
                userSwitchObserver.onUserSwitching(UserHandle.USER_SYSTEM);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register user switch observer");
        }
    }

    /**
     * Returns the sensor corresponding to the key at a specific position.
     */
    public T valueAt(int position) {
        return mSensors.valueAt(position);
    }

    /**
     * Returns the sensor associated with sensorId as key.
     */
    public T get(int sensorId) {
        return mSensors.get(sensorId);
    }

    /**
     * Returns the sensorId at the specified position.
     */
    public int keyAt(int position) {
        return mSensors.keyAt(position);
    }

    /**
     * Returns the number of sensors added.
     */
    public int size() {
        return mSensors.size();
    }

    /**
     * Returns true if a sensor exists for the specified sensorId.
     */
    public boolean contains(int sensorId) {
        return mSensors.contains(sensorId);
    }
}
