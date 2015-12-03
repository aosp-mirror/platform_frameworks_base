/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.preference.PreferenceManager;

public class LocalPreferences {
    private static final String KEY_ADVANCED_DEVICES = "advancedDevices";
    private static final String KEY_FILE_SIZE = "fileSize";

    public static boolean getDisplayAdvancedDevices(Context context) {
        boolean defaultAdvanced = context.getResources()
                .getBoolean(R.bool.config_defaultAdvancedDevices);
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_ADVANCED_DEVICES, defaultAdvanced);
    }

    public static boolean getDisplayFileSize(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_FILE_SIZE, false);
    }

    public static void setDisplayAdvancedDevices(Context context, boolean display) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_ADVANCED_DEVICES, display).apply();
    }

    public static void setDisplayFileSize(Context context, boolean display) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_FILE_SIZE, display).apply();
    }
}
