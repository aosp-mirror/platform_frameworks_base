/*
 * Copyright (C) 2013 The CyanogenMod Project
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
package com.android.server.power;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Certain display devices require that we set a special mode when enabling auto-brightness.
 * This is done by setting a sysfs node. This behavior can be configured by setting the
 * config_panelAutoBrightnessValue to the integer value to write when enabled.
 *
 * Some high-end panels might support alternative brightness modes such as high gamma. This
 * is highly device-specific, but we will support these via a persistent system property.
 *
 * @hide
 */
public class AutoBrightnessHandler {

    private static final String NODE = "/sys/class/lcd/panel/panel/auto_brightness";

    private static final String ALT_BRIGHTNESS_PROP = "persist.sys.alt.brightness";

    private static final int PANEL_MANUAL = 0;

    private final int mPanelAutoValue;

    public AutoBrightnessHandler(Context context) {
        mPanelAutoValue = context.getResources().getInteger(
                com.android.internal.R.integer.config_panelAutoBrightnessValue);
    }

    public void onAutoBrightnessChanged(int mode) {
        if (mPanelAutoValue > -1) {
            int override = SystemProperties.getInt(ALT_BRIGHTNESS_PROP, -1);
            writeValue(NODE, mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ?
                    (override > -1 ? override : mPanelAutoValue) : PANEL_MANUAL);
        }
    }

    private static void writeValue(String filename, int value) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(filename));
            fos.write(String.valueOf(value).getBytes());
            fos.flush();
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
