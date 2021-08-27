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

package com.android.systemui.util.sensors;

import android.hardware.Sensor;
import android.hardware.SensorManager;

import com.android.systemui.R;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for Sensor related classes.
 */
@Module
public class SensorModule {
    @Provides
    @PrimaryProxSensor
    static ThresholdSensor providePrimaryProxSensor(SensorManager sensorManager,
            ThresholdSensorImpl.Builder thresholdSensorBuilder) {
        try {
            return thresholdSensorBuilder
                    .setSensorDelay(SensorManager.SENSOR_DELAY_NORMAL)
                    .setSensorResourceId(R.string.proximity_sensor_type, true)
                    .setThresholdResourceId(R.dimen.proximity_sensor_threshold)
                    .setThresholdLatchResourceId(R.dimen.proximity_sensor_threshold_latch)
                    .build();
        } catch (IllegalStateException e) {
            Sensor defaultSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY,
                    true);
            return thresholdSensorBuilder
                    .setSensor(defaultSensor)
                    .setThresholdValue(defaultSensor != null ? defaultSensor.getMaximumRange() : 0)
                    .build();
        }
    }

    @Provides
    @SecondaryProxSensor
    static ThresholdSensor provideSecondaryProxSensor(
            ThresholdSensorImpl.Builder thresholdSensorBuilder) {
        try {
            return thresholdSensorBuilder
                    .setSensorResourceId(R.string.proximity_sensor_secondary_type, true)
                    .setThresholdResourceId(R.dimen.proximity_sensor_secondary_threshold)
                    .setThresholdLatchResourceId(R.dimen.proximity_sensor_secondary_threshold_latch)
                    .build();
        } catch (IllegalStateException e) {
            return thresholdSensorBuilder.setSensor(null).setThresholdValue(0).build();
        }
    }
}
