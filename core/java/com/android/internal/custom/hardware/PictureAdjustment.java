/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (C) 2018 The LineageOS Project
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

package com.android.internal.custom.hardware;

import android.util.Range;

import com.android.internal.custom.hardware.HSIC;

/**
 * Picture adjustment support
 *
 * Allows tuning of hue, saturation, intensity, and contrast levels
 * of the display
 */
public class PictureAdjustment {

    /**
     * Whether device supports picture adjustment
     *
     * @return boolean Supported devices must return always true
     */
    public static boolean isSupported() {
        return false;
    }

    /**
     * This method returns the current picture adjustment values based
     * on the selected DisplayMode.
     *
     * @return the HSIC object or null if not supported
     */
    public static HSIC getHSIC() {
        return null;
    }

    /**
     * This method returns the default picture adjustment values.
     *
     * If DisplayModes are available, this may change depending on the
     * selected mode.
     *
     * @return the HSIC object or null if not supported
     */
    public static HSIC getDefaultHSIC() {
        return null;
    }

    /**
     * This method allows to set the picture adjustment
     *
     * @param hsic
     * @return boolean Must be false if feature is not supported or the operation
     * failed; true in any other case.
     */
    public static boolean setHSIC(final HSIC hsic) {
        return false;
    }

    /**
     * Get the range available for hue adjustment
     * @return range of floats
     */
    public static Range<Float> getHueRange() {
        return new Range(0.0f, 0.0f);
    }

    /**
     * Get the range available for saturation adjustment
     * @return range of floats
     */
    public static Range<Float> getSaturationRange() {
        return new Range(0.0f, 0.0f);
    }

    /**
     * Get the range available for intensity adjustment
     * @return range of floats
     */
    public static Range<Float> getIntensityRange() {
        return new Range(0.0f, 0.0f);
    }

    /**
     * Get the range available for contrast adjustment
     * @return range of floats
     */
    public static Range<Float> getContrastRange() {
        return new Range(0.0f, 0.0f);
    }

    /**
     * Get the range available for saturation threshold adjustment
     *
     * This is the threshold where the display becomes fully saturated
     *
     * @return range of floats
     */
    public static Range<Float> getSaturationThresholdRange() {
        return new Range(0.0f, 0.0f);
    }
}
