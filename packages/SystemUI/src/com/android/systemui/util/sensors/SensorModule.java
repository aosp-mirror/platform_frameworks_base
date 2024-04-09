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

import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Dagger module for Sensor related classes.
 */
@Module
public class SensorModule {
    @Provides
    @PrimaryProxSensor
    static ThresholdSensor providePrimaryProximitySensor(
            SensorManager sensorManager,
            ThresholdSensorImpl.Builder thresholdSensorBuilder
    ) {
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
    static ThresholdSensor provideSecondaryProximitySensor(
            ThresholdSensorImpl.Builder thresholdSensorBuilder
    ) {
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

    /**
     * If postures are supported on the device, returns a posture dependent proximity sensor
     * which switches proximity sensors based on the current posture.
     *
     * If postures are not supported the regular {@link ProximitySensorImpl} will be returned.
     */
    @Provides
    static ProximitySensor provideProximitySensor(
            @Main Resources resources,
            Lazy<PostureDependentProximitySensor> postureDependentProximitySensorProvider,
            Lazy<ProximitySensorImpl> proximitySensorProvider
    ) {
        if (hasPostureSupport(
                resources.getStringArray(R.array.proximity_sensor_posture_mapping))) {
            return postureDependentProximitySensorProvider.get();
        } else  {
            return proximitySensorProvider.get();
        }
    }

    @Provides
    static ProximityCheck provideProximityCheck(
            ProximitySensor proximitySensor,
            @Main DelayableExecutor delayableExecutor
    ) {
        return new ProximityCheck(
                proximitySensor,
                delayableExecutor
        );
    }

    @Provides
    @PrimaryProxSensor
    @NonNull
    static ThresholdSensor[] providePostureToProximitySensorMapping(
            ThresholdSensorImpl.BuilderFactory thresholdSensorImplBuilderFactory,
            @Main Resources resources
    ) {
        return createPostureToSensorMapping(
                thresholdSensorImplBuilderFactory,
                resources.getStringArray(R.array.proximity_sensor_posture_mapping),
                R.dimen.proximity_sensor_threshold,
                R.dimen.proximity_sensor_threshold_latch
        );
    }

    @Provides
    @SecondaryProxSensor
    @NonNull
    static ThresholdSensor[] providePostureToSecondaryProximitySensorMapping(
            ThresholdSensorImpl.BuilderFactory thresholdSensorImplBuilderFactory,
            @Main Resources resources
    ) {
        return createPostureToSensorMapping(
                thresholdSensorImplBuilderFactory,
                resources.getStringArray(R.array.proximity_sensor_secondary_posture_mapping),
                R.dimen.proximity_sensor_secondary_threshold,
                R.dimen.proximity_sensor_secondary_threshold_latch
        );
    }

    /**
     * Builds sensors to use per posture.
     *
     * @param sensorTypes an array where the index represents
     *                    {@link DevicePostureController.DevicePostureInt} and the value
     *                    at the given index is the sensorType. Empty values represent
     *                    no sensor desired.
     * @param proximitySensorThresholdResourceId resource id for the threshold for all sensor
     *                                           postures. This currently only supports one value.
     *                                           This needs to be updated in the future if postures
     *                                           use different sensors with differing thresholds.
     * @param proximitySensorThresholdLatchResourceId resource id for the latch for all sensor
     *                                                postures. This currently only supports one
     *                                                value. This needs to be updated in the future
     *                                                if postures use different sensors with
     *                                                differing latches.
     * @return an array where the index represents the device posture
     * {@link DevicePostureController.DevicePostureInt} and the value at the index is the sensor to
     * use when the device is in that posture.
     */
    @NonNull
    private static ThresholdSensor[] createPostureToSensorMapping(
            ThresholdSensorImpl.BuilderFactory thresholdSensorImplBuilderFactory,
            String[] sensorTypes,
            int proximitySensorThresholdResourceId,
            int proximitySensorThresholdLatchResourceId

    ) {
        ThresholdSensor noProxSensor = thresholdSensorImplBuilderFactory
                .createBuilder()
                .setSensor(null).setThresholdValue(0).build();


        // length and index of sensorMap correspond to DevicePostureController.DevicePostureInt:
        final ThresholdSensor[] sensorMap =
                new ThresholdSensor[DevicePostureController.SUPPORTED_POSTURES_SIZE];
        Arrays.fill(sensorMap, noProxSensor);

        if (!hasPostureSupport(sensorTypes)) {
            Log.e("SensorModule", "config doesn't support postures,"
                    + " but attempting to retrieve proxSensorMapping");
            return sensorMap;
        }

        // Map of sensorType => Sensor, so we reuse the same sensor if it's the same between
        // postures
        Map<String, ThresholdSensor> typeToSensorMap = new HashMap<>();
        for (int i = 0; i < sensorTypes.length; i++) {
            try {
                final String sensorType = sensorTypes[i];
                if (typeToSensorMap.containsKey(sensorType)) {
                    sensorMap[i] = typeToSensorMap.get(sensorType);
                } else {
                    sensorMap[i] = thresholdSensorImplBuilderFactory
                            .createBuilder()
                            .setSensorType(sensorTypes[i], true)
                            .setThresholdResourceId(proximitySensorThresholdResourceId)
                            .setThresholdLatchResourceId(proximitySensorThresholdLatchResourceId)
                            .build();
                    typeToSensorMap.put(sensorType, sensorMap[i]);
                }
            } catch (IllegalStateException e) {
                // do nothing, sensor at this posture is already set to noProxSensor
            }
        }

        return sensorMap;
    }

    /**
     * Returns true if there's at least one non-empty sensor type in the given array.
     */
    private static boolean hasPostureSupport(String[] postureToSensorTypeMapping) {
        if (postureToSensorTypeMapping == null || postureToSensorTypeMapping.length == 0) {
            return false;
        }

        for (String sensorType : postureToSensorTypeMapping) {
            if (!TextUtils.isEmpty(sensorType)) {
                return true;
            }
        }

        return false;
    }
}
