/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib;

import android.content.Context;

import com.android.internal.logging.MetricsLogger;
import com.android.settingslib.wifi.AccessPoint.Speed;

/** Utilites for Tron Logging. */
public final class TronUtils {

    private static final String TAG = "TronUtils";

    private TronUtils() {};

    public static void logWifiSettingsSpeed(Context context, @Speed int speedEnum) {
        MetricsLogger.histogram(context, "settings_wifi_speed_labels", speedEnum);
    }
}
