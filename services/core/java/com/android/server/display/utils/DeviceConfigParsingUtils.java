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

package com.android.server.display.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PowerManager;
import android.util.Slog;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayDeviceConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Provides utility methods for DeviceConfig string parsing
 */
public class DeviceConfigParsingUtils {
    private static final String TAG = "DeviceConfigParsingUtils";

    /**
     * Parses map from device config
     * Data format:
     * (displayId:String,numberOfPoints:Int,(state:T,value:Float){numberOfPoints},
     * dataSetId:String)?;)+
     * result : mapOf(displayId to mapOf(dataSetId to V))
     */
    @NonNull
    public static <T, V> Map<String, Map<String, V>> parseDeviceConfigMap(
            @Nullable String data,
            @NonNull BiFunction<String, String, T> dataPointMapper,
            @NonNull Function<List<T>, V> dataSetMapper) {
        if (data == null) {
            return Map.of();
        }
        Map<String, Map<String, V>> result = new HashMap<>();
        String[] dataSets = data.split(";"); // by displayId + dataSetId
        for (String dataSet : dataSets) {
            String[] items = dataSet.split(",");
            int noOfItems = items.length;
            // Validate number of items, at least: displayId,1,key1,value1
            if (noOfItems < 4) {
                Slog.e(TAG, "Invalid dataSet(not enough items):" + dataSet, new Throwable());
                return Map.of();
            }
            int i = 0;
            String uniqueDisplayId = items[i++];

            String numberOfPointsString = items[i++];
            int numberOfPoints;
            try {
                numberOfPoints = Integer.parseInt(numberOfPointsString);
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, "Invalid dataSet(invalid number of points):" + dataSet, nfe);
                return Map.of();
            }
            // Validate number of itmes based on numberOfPoints:
            // displayId,numberOfPoints,(key,value) x numberOfPoints,dataSetId(optional)
            int expectedMinItems = 2 + numberOfPoints * 2;
            if (noOfItems < expectedMinItems || noOfItems > expectedMinItems + 1) {
                Slog.e(TAG, "Invalid dataSet(wrong number of points):" + dataSet, new Throwable());
                return Map.of();
            }
            // Construct data points
            List<T> dataPoints = new ArrayList<>();
            for (int j = 0; j < numberOfPoints; j++) {
                String key = items[i++];
                String value = items[i++];
                T dataPoint = dataPointMapper.apply(key, value);
                if (dataPoint == null) {
                    Slog.e(TAG,
                            "Invalid dataPoint ,key=" + key + ",value=" + value + ",dataSet="
                                    + dataSet, new Throwable());
                    return Map.of();
                }
                dataPoints.add(dataPoint);
            }
            // Construct dataSet
            V dataSetMapped = dataSetMapper.apply(dataPoints);
            if (dataSetMapped == null) {
                Slog.e(TAG, "Invalid dataSetMapped dataPoints=" + dataPoints + ",dataSet="
                        + dataSet, new Throwable());
                return Map.of();
            }
            // Get dataSetId and dataSets map for displayId
            String dataSetId = (i < items.length) ? items[i] : DisplayDeviceConfig.DEFAULT_ID;
            Map<String, V> byDisplayId = result.computeIfAbsent(uniqueDisplayId,
                    k -> new HashMap<>());

            // Try to store dataSet in datasets for display
            if (byDisplayId.put(dataSetId, dataSetMapped) != null) {
                Slog.e(TAG, "Duplicate dataSetId=" + dataSetId + ",data=" + data, new Throwable());
                return Map.of();
            }
        }
        return result;
    }

    /**
     * Parses thermal string value from device config
     */
    @PowerManager.ThermalStatus
    public static int parseThermalStatus(@NonNull String value) throws IllegalArgumentException {
        switch (value) {
            case "none":
                return PowerManager.THERMAL_STATUS_NONE;
            case "light":
                return PowerManager.THERMAL_STATUS_LIGHT;
            case "moderate":
                return PowerManager.THERMAL_STATUS_MODERATE;
            case "severe":
                return PowerManager.THERMAL_STATUS_SEVERE;
            case "critical":
                return PowerManager.THERMAL_STATUS_CRITICAL;
            case "emergency":
                return PowerManager.THERMAL_STATUS_EMERGENCY;
            case "shutdown":
                return PowerManager.THERMAL_STATUS_SHUTDOWN;
            default:
                throw new IllegalArgumentException("Invalid Thermal Status: " + value);
        }
    }

    /**
     * Parses brightness value from device config
     */
    public static float parseBrightness(String stringVal) throws IllegalArgumentException {
        float value = Float.parseFloat(stringVal);
        if (value < PowerManager.BRIGHTNESS_MIN || value > PowerManager.BRIGHTNESS_MAX) {
            throw new IllegalArgumentException("Brightness value out of bounds: " + stringVal);
        }
        return value;
    }

    /**
     * Convert display brightness thresholds to a float array.
     * @param thresholdsInt The int array of the thresholds in the range [0, 255]
     * @return The float array of the thresholds
     */
    public static float[] displayBrightnessThresholdsIntToFloat(int[] thresholdsInt) {
        if (thresholdsInt == null) {
            return null;
        }

        float[] thresholds = new float[thresholdsInt.length];
        for (int i = 0; i < thresholds.length; i++) {
            if (thresholdsInt[i] < 0) {
                // A negative value means that there's no threshold
                thresholds[i] = thresholdsInt[i];
            } else {
                thresholds[i] = BrightnessSynchronizer.brightnessIntToFloat(thresholdsInt[i]);
            }
        }
        return thresholds;
    }

    /**
     * Convert ambient brightness thresholds to a float array.
     * @param thresholdsInt The int array of the thresholds
     * @return The float array of the thresholds
     */
    public static float[] ambientBrightnessThresholdsIntToFloat(int[] thresholdsInt) {
        if (thresholdsInt == null) {
            return null;
        }

        float[] thresholds = new float[thresholdsInt.length];
        for (int i = 0; i < thresholds.length; i++) {
            thresholds[i] = thresholdsInt[i];
        }
        return thresholds;
    }
}
