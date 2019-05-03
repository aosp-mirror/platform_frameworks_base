/*
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

import com.android.internal.util.custom.FileUtils;

/**
 * Reader mode
 */
public class ReadingEnhancement {

    private static final String TAG = "ReadingEnhancement";

    private static final String FILE_READING = "/sys/class/graphics/fb0/reading_mode";

    /**
     * Whether device supports Reader Mode
     *
     * @return boolean Supported devices must return always true
     */
    public static boolean isSupported() {
        return FileUtils.isFileReadable(FILE_READING) && FileUtils.isFileWritable(FILE_READING);
    }

    /**
     * This method return the current activation status of Reader Mode
     *
     * @return boolean Must be false when Reader Mode is not supported or not activated,
     * or the operation failed while reading the status; true in any other case.
     */
    public static boolean isEnabled() {
        return Integer.parseInt(FileUtils.readOneLine(FILE_READING)) > 0;
    }

    /**
     * This method allows to setup Reader Mode
     *
     * @param status The new Reader Mode status
     * @return boolean Must be false if Reader Mode is not supported or the operation
     * failed; true in any other case.
     */
    public static boolean setEnabled(boolean status) {
        return FileUtils.writeLine(FILE_READING, status ? "1" : "0");
    }

}
