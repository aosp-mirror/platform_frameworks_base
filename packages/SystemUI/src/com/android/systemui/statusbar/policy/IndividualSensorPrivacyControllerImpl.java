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

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.RequiresPermission;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManager.Sensors.Sensor;
import android.hardware.SensorPrivacyManager.Sources.Source;
import android.hardware.SensorPrivacyManager.ToggleType;
import android.util.ArraySet;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;

import com.android.internal.camera.flags.Flags;
import com.android.systemui.util.ListenerSet;

import java.util.Set;

public class IndividualSensorPrivacyControllerImpl implements IndividualSensorPrivacyController {

    private static final int[] SENSORS = new int[] {CAMERA, MICROPHONE};

    private final @NonNull SensorPrivacyManager mSensorPrivacyManager;
    private final SparseBooleanArray mSoftwareToggleState = new SparseBooleanArray();
    private final SparseBooleanArray mHardwareToggleState = new SparseBooleanArray();
    private Boolean mRequiresAuthentication;
    private final ListenerSet<Callback> mCallbacks = new ListenerSet<>();

    public IndividualSensorPrivacyControllerImpl(
            @NonNull SensorPrivacyManager sensorPrivacyManager) {
        mSensorPrivacyManager = sensorPrivacyManager;
    }

    @Override
    public void init() {
        mSensorPrivacyManager.addSensorPrivacyListener(
                new SensorPrivacyManager.OnSensorPrivacyChangedListener() {
                    @Override
                    public void onSensorPrivacyChanged(SensorPrivacyChangedParams params) {
                        IndividualSensorPrivacyControllerImpl.this.onSensorPrivacyChanged(
                                params.getSensor(), params.getToggleType(), params.isEnabled());
                    }

                    @Override
                    public void onSensorPrivacyChanged(int sensor, boolean enabled) {
                        // handled in onSensorPrivacyChanged(SensorPrivacyChangedParams)
                    }
                });

        for (int sensor : SENSORS) {
            boolean softwarePrivacyEnabled = mSensorPrivacyManager.isSensorPrivacyEnabled(
                    SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, sensor);
            boolean hardwarePrivacyEnabled = mSensorPrivacyManager.isSensorPrivacyEnabled(
                    SensorPrivacyManager.TOGGLE_TYPE_HARDWARE, sensor);
            mSoftwareToggleState.put(sensor, softwarePrivacyEnabled);
            mHardwareToggleState.put(sensor, hardwarePrivacyEnabled);
        }
    }

    @Override
    public boolean supportsSensorToggle(int sensor) {
        return mSensorPrivacyManager.supportsSensorToggle(sensor);
    }

    @Override
    public boolean isSensorBlocked(@Sensor int sensor) {
        return mSoftwareToggleState.get(sensor, false) || mHardwareToggleState.get(sensor, false);
    }

    @Override
    public boolean isSensorBlockedByHardwareToggle(@Sensor int sensor) {
        return mHardwareToggleState.get(sensor, false);
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
    public boolean requiresAuthentication() {
        return mSensorPrivacyManager.requiresAuthentication();
    }

    @Override
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public boolean isCameraPrivacyEnabled(String packageName) {
        return mSensorPrivacyManager.isCameraPrivacyEnabled(packageName);
    }

    @Override
    public void addCallback(@NonNull Callback listener) {
        mCallbacks.addIfAbsent(listener);
    }

    @Override
    public void removeCallback(@NonNull Callback listener) {
        mCallbacks.remove(listener);
    }

    private void onSensorPrivacyChanged(@Sensor int sensor, @ToggleType int toggleType,
            boolean enabled) {
        if (toggleType == SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE) {
            mSoftwareToggleState.put(sensor, enabled);
        } else if (toggleType == SensorPrivacyManager.TOGGLE_TYPE_HARDWARE) {
            mHardwareToggleState.put(sensor, enabled);
        }

        Set<Callback> copy = new ArraySet<>(mCallbacks);
        for (Callback callback : copy) {
            callback.onSensorBlockedChanged(sensor, isSensorBlocked(sensor));
        }
    }
}
