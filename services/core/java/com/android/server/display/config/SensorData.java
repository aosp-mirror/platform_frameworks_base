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

package com.android.server.display.config;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.feature.DisplayManagerFlags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Uniquely identifies a Sensor, with the combination of Type and Name.
 */
public class SensorData {

    public static final String TEMPERATURE_TYPE_DISPLAY = "DISPLAY";
    public static final String TEMPERATURE_TYPE_SKIN = "SKIN";

    @Nullable
    public final String type;
    @Nullable
    public final String name;
    public final float minRefreshRate;
    public final float maxRefreshRate;
    public final List<SupportedMode> supportedModes;

    @VisibleForTesting
    public SensorData() {
        this(/* type= */ null, /* name= */ null);
    }

    @VisibleForTesting
    public SensorData(String type, String name) {
        this(type, name, /* minRefreshRate= */ 0f, /* maxRefreshRate= */ Float.POSITIVE_INFINITY);
    }

    @VisibleForTesting
    public SensorData(String type, String name, float minRefreshRate, float maxRefreshRate) {
        this(type, name, minRefreshRate, maxRefreshRate, /* supportedModes= */ List.of());
    }

    @VisibleForTesting
    public SensorData(String type, String name, float minRefreshRate, float maxRefreshRate,
            List<SupportedMode> supportedModes) {
        this.type = type;
        this.name = name;
        this.minRefreshRate = minRefreshRate;
        this.maxRefreshRate = maxRefreshRate;
        this.supportedModes = Collections.unmodifiableList(supportedModes);
    }

    /**
     * @return True if the sensor matches both the specified name and type, or one if only one
     * is specified (not-empty). Always returns false if both parameters are null or empty.
     */
    public boolean matches(String sensorName, String sensorType) {
        final boolean isNameSpecified = !TextUtils.isEmpty(sensorName);
        final boolean isTypeSpecified = !TextUtils.isEmpty(sensorType);
        return (isNameSpecified || isTypeSpecified)
                && (!isNameSpecified || sensorName.equals(name))
                && (!isTypeSpecified || sensorType.equals(type));
    }

    @Override
    public String toString() {
        return "SensorData{"
                + "type= " + type
                + ", name= " + name
                + ", refreshRateRange: [" + minRefreshRate + ", " + maxRefreshRate + "]"
                + ", supportedModes=" + supportedModes
                + '}';
    }

    /**
     * Loads ambient light sensor data from DisplayConfiguration and if missing from resources xml
     */
    public static SensorData loadAmbientLightSensorConfig(DisplayConfiguration config,
            Resources resources) {
        SensorDetails sensorDetails = config.getLightSensor();
        if (sensorDetails != null) {
            return loadSensorData(sensorDetails);
        } else {
            return loadAmbientLightSensorConfig(resources);
        }
    }

    /**
     * Loads ambient light sensor data from resources xml
     */
    public static SensorData loadAmbientLightSensorConfig(Resources resources) {
        return new SensorData(
                resources.getString(com.android.internal.R.string.config_displayLightSensorType),
                /* name= */ "");
    }

    /**
     * Loads screen off brightness sensor data from DisplayConfiguration
     */
    public static SensorData loadScreenOffBrightnessSensorConfig(DisplayConfiguration config) {
        SensorDetails sensorDetails = config.getScreenOffBrightnessSensor();
        if (sensorDetails != null) {
            return loadSensorData(sensorDetails);
        } else {
            return new SensorData();
        }
    }

    /**
     * Loads proximity sensor data from DisplayConfiguration
     */
    @Nullable
    public static SensorData loadProxSensorConfig(DisplayConfiguration config) {
        SensorDetails sensorDetails = config.getProxSensor();
        if (sensorDetails != null) {
            String name = sensorDetails.getName();
            String type = sensorDetails.getType();
            if ("".equals(name) && "".equals(type)) {
                // <proxSensor> with empty values to the config means no sensor should be used.
                // See also {@link com.android.server.display.utils.SensorUtils}
                return null;
            } else {
                return loadSensorData(sensorDetails);
            }
        } else {
            return new SensorData();
        }
    }

    /**
     * Loads temperature sensor data for no config case. (Type: SKIN, Name: null)
     */
    public static SensorData loadTempSensorUnspecifiedConfig() {
        return new SensorData(TEMPERATURE_TYPE_SKIN, null);
    }

    /**
     * Loads temperature sensor data from given display config.
     * If empty or null config given default to (Type: SKIN, Name: null)
     */
    public static SensorData loadTempSensorConfig(DisplayManagerFlags flags,
            DisplayConfiguration config) {
        SensorDetails sensorDetails = config.getTempSensor();
        if (!flags.isSensorBasedBrightnessThrottlingEnabled() || sensorDetails == null) {
            return new SensorData(TEMPERATURE_TYPE_SKIN, null);
        }
        String name = sensorDetails.getName();
        String type = sensorDetails.getType();
        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
            type = TEMPERATURE_TYPE_SKIN;
            name = null;
        }
        return new SensorData(type, name);
    }

    /**
     * Loads sensor unspecified config, this means system should use default sensor.
     * See also {@link com.android.server.display.utils.SensorUtils}
     */
    @NonNull
    public static SensorData loadSensorUnspecifiedConfig() {
        return new SensorData();
    }

    private static SensorData loadSensorData(@NonNull SensorDetails sensorDetails) {
        float minRefreshRate = 0f;
        float maxRefreshRate = Float.POSITIVE_INFINITY;
        RefreshRateRange rr = sensorDetails.getRefreshRate();
        if (rr != null) {
            minRefreshRate = rr.getMinimum().floatValue();
            maxRefreshRate = rr.getMaximum().floatValue();
        }
        ArrayList<SupportedMode> supportedModes = new ArrayList<>();
        NonNegativeFloatToFloatMap configSupportedModes = sensorDetails.getSupportedModes();
        if (configSupportedModes != null) {
            for (NonNegativeFloatToFloatPoint supportedMode : configSupportedModes.getPoint()) {
                supportedModes.add(new SupportedMode(supportedMode.getFirst().floatValue(),
                        supportedMode.getSecond().floatValue()));
            }
        }

        return new SensorData(sensorDetails.getType(), sensorDetails.getName(), minRefreshRate,
                maxRefreshRate, supportedModes);
    }

    public static class SupportedMode {
        public final float refreshRate;
        public final float vsyncRate;

        public SupportedMode(float refreshRate, float vsyncRate) {
            this.refreshRate = refreshRate;
            this.vsyncRate = vsyncRate;
        }
    }
}
