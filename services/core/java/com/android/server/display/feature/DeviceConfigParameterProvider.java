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

package com.android.server.display.feature;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.display.DisplayManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.Slog;

import com.android.server.display.utils.DeviceConfigParsingUtils;

import java.util.concurrent.Executor;

/**
 * Helper class to access all DeviceConfig features for display_manager namespace
 *
 **/
public class DeviceConfigParameterProvider {

    private static final String TAG = "DisplayFeatureProvider";

    private final DeviceConfigInterface mDeviceConfig;

    public DeviceConfigParameterProvider(DeviceConfigInterface deviceConfig) {
        mDeviceConfig = deviceConfig;
    }

    // feature: hdr_output_control
    // parameter: enable_hdr_output_control
    public boolean isHdrOutputControlFeatureEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.HDR_OUTPUT_CONTROL_FLAG, true);
    }

    // feature: flexible_brightness_range_feature
    // parameter: normal_brightness_mode_controller_enabled
    public boolean isNormalBrightnessControllerFeatureEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_USE_NORMAL_BRIGHTNESS_MODE_CONTROLLER, false);
    }

    public boolean isDisableScreenWakeLocksWhileCachedFeatureEnabled() {
        return mDeviceConfig.getBoolean(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_DISABLE_SCREEN_WAKE_LOCKS_WHILE_CACHED, true);
    }

    // feature: smooth_display_feature
    // parameter: peak_refresh_rate_default
    public float getPeakRefreshRateDefault() {
        return mDeviceConfig.getFloat(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT, -1);
    }

    // Test parameters
    // usage e.g.: adb shell device_config put display_manager refresh_rate_in_hbm_sunlight 90

    // allows to customize power throttling data
    public String getPowerThrottlingData() {
        return mDeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_POWER_THROTTLING_DATA, null);
    }

    // allows to customize brightness throttling data
    public String getBrightnessThrottlingData() {
        return mDeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_BRIGHTNESS_THROTTLING_DATA, null);
    }

    public int getRefreshRateInHbmSunlight() {
        return mDeviceConfig.getInt(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HBM_SUNLIGHT, -1);
    }

    public int getRefreshRateInHbmHdr() {
        return mDeviceConfig.getInt(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HBM_HDR, -1);
    }


    public int getRefreshRateInHighZone() {
        return mDeviceConfig.getInt(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HIGH_ZONE, -1);
    }

    public int getRefreshRateInLowZone() {
        return mDeviceConfig.getInt(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_LOW_ZONE, -1);
    }

    /**
     * Get the high ambient brightness thresholds for the configured refresh rate zone. The values
     * are paired with brightness thresholds.
     *
     * A negative value means that only the display brightness threshold should be used.
     *
     * Return null if no such property or wrong format (not comma separated integers).
     */
    @Nullable
    public float[] getHighAmbientBrightnessThresholds() {
        return DeviceConfigParsingUtils.ambientBrightnessThresholdsIntToFloat(
                getIntArrayProperty(DisplayManager.DeviceConfig
                        .KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS));
    }

    /**
     * Get the high display brightness thresholds for the configured refresh rate zone. The values
     * are paired with lux thresholds.
     *
     * A negative value means that only the ambient threshold should be used.
     *
     * Return null if no such property or wrong format (not comma separated integers).
     */
    @Nullable
    public float[] getHighDisplayBrightnessThresholds() {
        return DeviceConfigParsingUtils.displayBrightnessThresholdsIntToFloat(
                getIntArrayProperty(DisplayManager.DeviceConfig
                        .KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS));
    }

    /**
     * Get the low display brightness thresholds for the configured refresh rate zone. The values
     * are paired with lux thresholds.
     *
     * A negative value means that only the ambient threshold should be used.
     *
     * Return null if no such property or wrong format (not comma separated integers).
     */
    @Nullable
    public float[] getLowDisplayBrightnessThresholds() {
        return DeviceConfigParsingUtils.displayBrightnessThresholdsIntToFloat(
                getIntArrayProperty(DisplayManager.DeviceConfig
                        .KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS));
    }

    /**
     * Get the low ambient brightness thresholds for the configured refresh rate zone. The values
     * are paired with brightness thresholds.
     *
     * A negative value means that only the display brightness threshold should be used.
     *
     * Return null if no such property or wrong format (not comma separated integers).
     */
    @Nullable
    public float[] getLowAmbientBrightnessThresholds() {
        return DeviceConfigParsingUtils.ambientBrightnessThresholdsIntToFloat(
                getIntArrayProperty(DisplayManager.DeviceConfig
                        .KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS));
    }

    /** add property change listener to DeviceConfig */
    public void addOnPropertiesChangedListener(Executor executor,
            DeviceConfig.OnPropertiesChangedListener listener) {
        mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                executor, listener);
    }

    /** remove property change listener from DeviceConfig */
    public void removeOnPropertiesChangedListener(
            DeviceConfig.OnPropertiesChangedListener listener) {
        mDeviceConfig.removeOnPropertiesChangedListener(listener);
    }

    @Nullable
    private int[] getIntArrayProperty(String prop) {
        String strArray = mDeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER, prop,
                null);

        if (strArray != null) {
            return parseIntArray(strArray);
        }
        return null;
    }

    @Nullable
    private int[] parseIntArray(@NonNull String strArray) {
        String[] items = strArray.split(",");
        int[] array = new int[items.length];

        try {
            for (int i = 0; i < array.length; i++) {
                array[i] = Integer.parseInt(items[i]);
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Incorrect format for array: '" + strArray + "'", e);
            array = null;
        }

        return array;
    }
}
