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
import android.provider.Settings;

public class WirelessUtils {

    public static boolean isRadioAllowed(Context context, String type) {
        if (!isAirplaneModeOn(context)) {
            return true;
        }
        String toggleable = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
        return toggleable != null && toggleable.contains(type);
    }

    public static boolean isAirplaneModeOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

}
