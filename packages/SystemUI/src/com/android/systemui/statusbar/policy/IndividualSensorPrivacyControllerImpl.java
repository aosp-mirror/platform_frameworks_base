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

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;

import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManager.Sensors.Sensor;
import android.hardware.SensorPrivacyManager.Sources.Source;
import android.util.ArraySet;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;

import java.util.Set;

public class IndividualSensorPrivacyControllerImpl implements IndividualSensorPrivacyController {

    private static final int[] SENSORS = new int[] {CAMERA, MICROPHONE};

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
                    (s, enabled) -> onSensorPrivacyChanged(sensor, enabled));

            mState.put(sensor, mSensorPrivacyManager.isSensorPrivacyEnabled(sensor));
        }
    }

    @Override
    public boolean supportsSensorToggle(int sensor) {
        return mSensorPrivacyManager.supportsSensorToggle(sensor);
    }

    @Override
    public boolean isSensorBlocked(@Sensor int sensor) {
        return mState.get(sensor, false);
    }

    @Override
    public void setSensorBlocked(@Source int source, @Sensor int sensor, boolean blocked) {
        mSensorPrivacyManager.setSensorPrivacyForProfileGroup(source, sensor, blocked);
    }

    @Override
    public void suppressSensorPrivacyReminders(int sensor, boolean suppress) {
        mSensorPrivacyManager.suppressSensorPrivacyReminders(sensor, suppress);
    }

    @Override
    public void addCallback(@NonNull Callback listener) {
        mCallbacks.add(listener);
    }

    @Override
    public void removeCallback(@NonNull Callback listener) {
        mCallbacks.remove(listener);
    }

    private void onSensorPrivacyChanged(@Sensor int sensor, boolean blocked) {
        mState.put(sensor, blocked);

        for (Callback callback : mCallbacks) {
            callback.onSensorBlockedChanged(sensor, blocked);
        }
    }
}
