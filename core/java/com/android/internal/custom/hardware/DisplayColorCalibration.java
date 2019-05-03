/*
 * Copyright (C) 2014 The CyanogenMod Project
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

public class DisplayColorCalibration {

    private static final String TAG = "DisplayColorCalibration";

    private static final String COLOR_FILE = "/sys/class/graphics/fb0/rgb";

    private static final int MIN = 255;
    private static final int MAX = 32768;

    public static boolean isSupported() {
        return FileUtils.isFileReadable(COLOR_FILE) && FileUtils.isFileWritable(COLOR_FILE);
    }

    public static int getMaxValue()  {
        return MAX;
    }

    public static int getMinValue()  {
        return MIN;
    }

    public static int getDefValue() {
        return getMaxValue();
    }

    public static String getCurColors()  {
        return FileUtils.readOneLine(COLOR_FILE);
    }

    public static boolean setColors(String colors) {
        return FileUtils.writeLine(COLOR_FILE, colors);
    }

}
