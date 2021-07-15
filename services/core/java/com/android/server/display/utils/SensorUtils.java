/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.display.utils;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.text.TextUtils;

import java.util.List;

/**
 * Provides utility methods for dealing with sensors.
 */
public class SensorUtils {
    public static final int NO_FALLBACK = 0;

    /**
     * Finds the specified sensor by type and name using SensorManager.
     */
    public static Sensor findSensor(SensorManager sensorManager, String sensorType,
            String sensorName, int fallbackType) {
        final boolean isNameSpecified = !TextUtils.isEmpty(sensorName);
        final boolean isTypeSpecified = !TextUtils.isEmpty(sensorType);
        if (isNameSpecified || isTypeSpecified) {
            final List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
            for (Sensor sensor : sensors) {
                if ((!isNameSpecified || sensorName.equals(sensor.getName()))
                        && (!isTypeSpecified || sensorType.equals(sensor.getStringType()))) {
                    return sensor;
                }
            }
        }
        if (fallbackType != NO_FALLBACK) {
            return sensorManager.getDefaultSensor(fallbackType);
        }

        return null;
    }

}
