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

package com.android.systemui.statusbar.policy;

import static android.hardware.SensorPrivacyManager.INDIVIDUAL_SENSOR_CAMERA;
import static android.hardware.SensorPrivacyManager.INDIVIDUAL_SENSOR_MICROPHONE;

import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManager.IndividualSensor;
import android.util.ArraySet;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;

import java.util.Set;

public class IndividualSensorPrivacyControllerImpl implements IndividualSensorPrivacyController {

    private static final int[] SENSORS = new int[] {INDIVIDUAL_SENSOR_CAMERA,
            INDIVIDUAL_SENSOR_MICROPHONE};

    private final @NonNull SensorPrivacyManager mSensorPrivacyManager;
    private final SparseBooleanArray mState = new SparseBooleanArray();
    private final Set<Callback> mCallbacks = new ArraySet<>();

    public IndividualSensorPrivacyControllerImpl(
            @NonNull SensorPrivacyManager sensorPrivacyManager) {
        mSensorPrivacyManager = sensorPrivacyManager;
    }

    @Override
    public void init() {
        for (int sensor : SENSORS) {
            mSensorPrivacyManager.addSensorPrivacyListener(sensor,
                    (enabled) -> onSensorPrivacyChanged(sensor, enabled));

            mState.put(sensor, mSensorPrivacyManager.isIndividualSensorPrivacyEnabled(sensor));
        }
    }

    @Override
    public boolean isSensorBlocked(@IndividualSensor int sensor) {
        return mState.get(sensor, false);
    }

    @Override
    public void setSensorBlocked(@IndividualSensor int sensor, boolean blocked) {
        mSensorPrivacyManager.setIndividualSensorPrivacyForProfileGroup(sensor, blocked);
    }

    @Override
    public void addCallback(@NonNull Callback listener) {
        mCallbacks.add(listener);
    }

    @Override
    public void removeCallback(@NonNull Callback listener) {
        mCallbacks.remove(listener);
    }

    private void onSensorPrivacyChanged(@IndividualSensor int sensor, boolean blocked) {
        mState.put(sensor, blocked);

        for (Callback callback : mCallbacks) {
            callback.onSensorBlockedChanged(sensor, blocked);
        }
    }
}
