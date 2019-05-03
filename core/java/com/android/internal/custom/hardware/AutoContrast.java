/*
 * Copyright (C) 2014 The CyanogenMod Project
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

import com.android.internal.util.custom.FileUtils;

import android.util.Log;

/**
 * Auto Contrast Optimization
 */
public class AutoContrast {

    private static final String TAG = "AutoContrast";

    private static final String FILE_ACO = "/sys/class/graphics/fb0/aco";

    /**
     * Whether device supports ACO
     *
     * @return boolean Supported devices must return always true
     */
    public static boolean isSupported() {
        return FileUtils.isFileReadable(FILE_ACO) && FileUtils.isFileWritable(FILE_ACO);
    }

    /**
     * This method return the current activation status of ACO
     *
     * @return boolean Must be false when ACO is not supported or not activated, or
     * the operation failed while reading the status; true in any other case.
     */
    public static boolean isEnabled() {
        try {
            return Integer.parseInt(FileUtils.readOneLine(FILE_ACO)) > 0;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
        return false;
    }

    /**
     * This method allows to setup ACO
     *
     * @param status The new ACO status
     * @return boolean Must be false if ACO is not supported or the operation
     * failed; true in any other case.
     */
    public static boolean setEnabled(boolean status) {
        return FileUtils.writeLine(FILE_ACO, status ? "1" : "0");
    }

    /**
     * Whether adaptive backlight (CABL / CABC) is required to be enabled
     *
     * @return boolean False if adaptive backlight is not a dependency
     */
    public static boolean isAdaptiveBacklightRequired() {
        return false;
    }
}
