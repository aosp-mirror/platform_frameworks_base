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

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Spline;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.feature.DisplayManagerFlags;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides a mapping between lux and brightness values in order to support auto-brightness.
 */
public class DisplayBrightnessMappingConfig {

    private static final String DEFAULT_BRIGHTNESS_MAPPING_KEY =
            AutoBrightnessModeName._default.getRawName() + "_"
                    + AutoBrightnessSettingName.normal.getRawName();

    /**
     * Array of desired screen brightness in nits corresponding to the lux values
     * in the mBrightnessLevelsLuxMap.get(DEFAULT_ID) array. The display brightness is defined as
     * the measured brightness of an all-white image. The brightness values must be non-negative and
     * non-decreasing. This must be overridden in platform specific overlays
     */
    private float[] mBrightnessLevelsNits;

    /**
     * Map of arrays of desired screen brightness corresponding to the lux values
     * in mBrightnessLevelsLuxMap, indexed by the auto-brightness mode and the brightness preset.
     * The brightness values must be non-negative and non-decreasing. They must be between
     * {@link PowerManager.BRIGHTNESS_MIN} and {@link PowerManager.BRIGHTNESS_MAX}.
     *
     * The keys are a concatenation of the auto-brightness mode and the brightness preset separated
     * by an underscore, e.g. default_normal, default_dim, default_bright, doze_normal, doze_dim,
     * doze_bright.
     *
     * The presets are used on devices that allow users to choose from a set of predefined options
     * in display auto-brightness settings.
     */
    private final Map<String, float[]> mBrightnessLevelsMap = new HashMap<>();

    /**
     * Map of arrays of light sensor lux values to define our levels for auto-brightness support,
     * indexed by the auto-brightness mode and the brightness preset.
     *
     * The first lux value in every array is always 0.
     *
     * The control points must be strictly increasing. Each control point corresponds to an entry
     * in the brightness values arrays. For example, if lux == luxLevels[1] (second element
     * of the levels array) then the brightness will be determined by brightnessLevels[1] (second
     * element of the brightness values array).
     *
     * Spline interpolation is used to determine the auto-brightness values for lux levels between
     * these control points.
     *
     * The keys are a concatenation of the auto-brightness mode and the brightness preset separated
     * by an underscore, e.g. default_normal, default_dim, default_bright, doze_normal, doze_dim,
     * doze_bright.
     *
     * The presets are used on devices that allow users to choose from a set of predefined options
     * in display auto-brightness settings.
     */
    private final Map<String, float[]> mBrightnessLevelsLuxMap = new HashMap<>();

    /**
     * Loads the auto-brightness display brightness mappings. Internally, this takes care of
     * loading the value from the display config, and if not present, falls back to config.xml.
     */
    public DisplayBrightnessMappingConfig(Context context, DisplayManagerFlags flags,
            AutoBrightness autoBrightnessConfig, Spline backlightToBrightnessSpline) {
        if (flags.areAutoBrightnessModesEnabled() && autoBrightnessConfig != null
                && autoBrightnessConfig.getLuxToBrightnessMapping() != null
                && autoBrightnessConfig.getLuxToBrightnessMapping().size() > 0) {
            for (LuxToBrightnessMapping mapping
                    : autoBrightnessConfig.getLuxToBrightnessMapping()) {
                final int size = mapping.getMap().getPoint().size();
                float[] brightnessLevels = new float[size];
                float[] brightnessLevelsLux = new float[size];
                for (int i = 0; i < size; i++) {
                    float backlight = mapping.getMap().getPoint().get(i).getSecond().floatValue();
                    brightnessLevels[i] = backlightToBrightnessSpline.interpolate(backlight);
                    brightnessLevelsLux[i] = mapping.getMap().getPoint().get(i).getFirst()
                            .floatValue();
                }
                if (size == 0) {
                    throw new IllegalArgumentException(
                            "A display brightness mapping should not be empty");
                }
                if (brightnessLevelsLux[0] != 0) {
                    throw new IllegalArgumentException(
                            "The first lux value in the display brightness mapping must be 0");
                }

                String key = (mapping.getMode() == null
                        ? AutoBrightnessModeName._default.getRawName()
                        : mapping.getMode().getRawName())
                        + "_"
                        + (mapping.getSetting() == null
                        ? AutoBrightnessSettingName.normal.getRawName()
                        : mapping.getSetting().getRawName());
                if (mBrightnessLevelsMap.containsKey(key)
                        || mBrightnessLevelsLuxMap.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "A display brightness mapping with key " + key + " already exists");
                }
                mBrightnessLevelsMap.put(key, brightnessLevels);
                mBrightnessLevelsLuxMap.put(key, brightnessLevelsLux);
            }
        }

        if (!mBrightnessLevelsMap.containsKey(DEFAULT_BRIGHTNESS_MAPPING_KEY)
                || !mBrightnessLevelsLuxMap.containsKey(DEFAULT_BRIGHTNESS_MAPPING_KEY)) {
            mBrightnessLevelsNits = DisplayDeviceConfig.getFloatArray(context.getResources()
                    .obtainTypedArray(com.android.internal.R.array
                            .config_autoBrightnessDisplayValuesNits), PowerManager
                    .BRIGHTNESS_OFF_FLOAT);

            float[] brightnessLevelsLux = DisplayDeviceConfig.getLuxLevels(context.getResources()
                    .getIntArray(com.android.internal.R.array
                            .config_autoBrightnessLevels));
            mBrightnessLevelsLuxMap.put(DEFAULT_BRIGHTNESS_MAPPING_KEY, brightnessLevelsLux);

            // Load the old configuration in the range [0, 255]. The values need to be normalized
            // to the range [0, 1].
            int[] brightnessLevels = context.getResources().getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLcdBacklightValues);
            mBrightnessLevelsMap.put(DEFAULT_BRIGHTNESS_MAPPING_KEY,
                    brightnessArrayIntToFloat(brightnessLevels, backlightToBrightnessSpline));
        }
    }

    /**
     * @param mode The auto-brightness mode
     * @param preset The brightness preset. Presets are used on devices that allow users to choose
     *               from a set of predefined options in display auto-brightness settings.
     * @return The default auto-brightness brightening ambient lux levels for the specified mode
     * and preset
     */
    public float[] getLuxArray(@AutomaticBrightnessController.AutomaticBrightnessMode int mode,
            int preset) {
        return mBrightnessLevelsLuxMap.get(
                autoBrightnessModeToString(mode) + "_" + autoBrightnessPresetToString(preset));
    }

    /**
     * @return Auto brightness brightening nits levels
     */
    public float[] getNitsArray() {
        return mBrightnessLevelsNits;
    }

    /**
     * @param mode The auto-brightness mode
     * @param preset The brightness preset. Presets are used on devices that allow users to choose
     *               from a set of predefined options in display auto-brightness settings.
     * @return The default auto-brightness brightening levels for the specified mode and preset
     */
    public float[] getBrightnessArray(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode, int preset) {
        return mBrightnessLevelsMap.get(
                autoBrightnessModeToString(mode) + "_" + autoBrightnessPresetToString(preset));
    }

    @Override
    public String toString() {
        StringBuilder brightnessLevelsLuxMapString = new StringBuilder("{");
        for (Map.Entry<String, float[]> entry : mBrightnessLevelsLuxMap.entrySet()) {
            brightnessLevelsLuxMapString.append(entry.getKey()).append("=").append(
                    Arrays.toString(entry.getValue())).append(", ");
        }
        if (brightnessLevelsLuxMapString.length() > 2) {
            brightnessLevelsLuxMapString.delete(brightnessLevelsLuxMapString.length() - 2,
                    brightnessLevelsLuxMapString.length());
        }
        brightnessLevelsLuxMapString.append("}");

        StringBuilder brightnessLevelsMapString = new StringBuilder("{");
        for (Map.Entry<String, float[]> entry : mBrightnessLevelsMap.entrySet()) {
            brightnessLevelsMapString.append(entry.getKey()).append("=").append(
                    Arrays.toString(entry.getValue())).append(", ");
        }
        if (brightnessLevelsMapString.length() > 2) {
            brightnessLevelsMapString.delete(brightnessLevelsMapString.length() - 2,
                    brightnessLevelsMapString.length());
        }
        brightnessLevelsMapString.append("}");

        return "mBrightnessLevelsNits= " + Arrays.toString(mBrightnessLevelsNits)
                + ", mBrightnessLevelsLuxMap= " + brightnessLevelsLuxMapString
                + ", mBrightnessLevelsMap= " + brightnessLevelsMapString;
    }

    /**
     * @param mode The auto-brightness mode
     * @return The string representing the mode
     */
    public static String autoBrightnessModeToString(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode) {
        switch (mode) {
            case AUTO_BRIGHTNESS_MODE_DEFAULT -> {
                return AutoBrightnessModeName._default.getRawName();
            }
            case AUTO_BRIGHTNESS_MODE_IDLE -> {
                return AutoBrightnessModeName.idle.getRawName();
            }
            case AUTO_BRIGHTNESS_MODE_DOZE -> {
                return AutoBrightnessModeName.doze.getRawName();
            }
            default -> throw new IllegalArgumentException("Unknown auto-brightness mode: " + mode);
        }
    }

    /**
     * @param preset The brightness preset. Presets are used on devices that allow users to choose
     *               from a set of predefined options in display auto-brightness settings.
     * @return The string representing the preset
     */
    public static String autoBrightnessPresetToString(int preset) {
        return switch (preset) {
            case Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM ->
                    AutoBrightnessSettingName.dim.getRawName();
            case Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL ->
                    AutoBrightnessSettingName.normal.getRawName();
            case Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT ->
                    AutoBrightnessSettingName.bright.getRawName();
            default -> throw new IllegalArgumentException(
                    "Unknown auto-brightness preset value: " + preset);
        };
    }

    private float[] brightnessArrayIntToFloat(int[] brightnessInt,
            Spline backlightToBrightnessSpline) {
        float[] brightnessFloat = new float[brightnessInt.length];
        for (int i = 0; i < brightnessInt.length; i++) {
            brightnessFloat[i] = backlightToBrightnessSpline.interpolate(
                    BrightnessSynchronizer.brightnessIntToFloat(brightnessInt[i]));
        }
        return brightnessFloat;
    }
}
