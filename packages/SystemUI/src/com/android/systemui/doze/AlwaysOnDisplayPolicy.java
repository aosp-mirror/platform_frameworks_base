/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.systemui.R;

import java.util.Arrays;

/**
 * Class to store the policy for AOD, which comes from
 * {@link android.provider.Settings.Global}
 */
public class AlwaysOnDisplayPolicy {
    public static final String TAG = "AlwaysOnDisplayPolicy";

    static final String KEY_SCREEN_BRIGHTNESS_ARRAY = "screen_brightness_array";
    static final String KEY_DIMMING_SCRIM_ARRAY = "dimming_scrim_array";
    static final String KEY_PROX_SCREEN_OFF_DELAY_MS = "prox_screen_off_delay";
    static final String KEY_PROX_COOLDOWN_TRIGGER_MS = "prox_cooldown_trigger";
    static final String KEY_PROX_COOLDOWN_PERIOD_MS = "prox_cooldown_period";

    /**
     * Integer array to map ambient brightness type to real screen brightness.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_SCREEN_BRIGHTNESS_ARRAY
     */
    public final int[] screenBrightnessArray;

    /**
     * Integer array to map ambient brightness type to dimming scrim.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_DIMMING_SCRIM_ARRAY
     */
    public final int[] dimmingScrimArray;

    /**
     * Delay time(ms) from covering the prox to turning off the screen.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_PROX_SCREEN_OFF_DELAY_MS
     */
    public final long proxScreenOffDelayMs;

    /**
     * The threshold time(ms) to trigger the cooldown timer, which will
     * turn off prox sensor for a period.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_PROX_COOLDOWN_TRIGGER_MS
     */
    public final long proxCooldownTriggerMs;

    /**
     * The period(ms) to turning off the prox sensor if
     * {@link #KEY_PROX_COOLDOWN_TRIGGER_MS} is triggered.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_PROX_COOLDOWN_PERIOD_MS
     */
    public final long proxCooldownPeriodMs;

    private final KeyValueListParser mParser;

    public AlwaysOnDisplayPolicy(Context context) {
        final Resources resources = context.getResources();
        mParser = new KeyValueListParser(',');

        final String value = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS);

        try {
            mParser.setString(value);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Bad AOD constants");
        }

        proxScreenOffDelayMs = mParser.getLong(KEY_PROX_SCREEN_OFF_DELAY_MS,
                10 * DateUtils.SECOND_IN_MILLIS);
        proxCooldownTriggerMs = mParser.getLong(KEY_PROX_COOLDOWN_TRIGGER_MS,
                2 * DateUtils.SECOND_IN_MILLIS);
        proxCooldownPeriodMs = mParser.getLong(KEY_PROX_COOLDOWN_PERIOD_MS,
                5 * DateUtils.SECOND_IN_MILLIS);
        screenBrightnessArray = parseIntArray(KEY_SCREEN_BRIGHTNESS_ARRAY,
                resources.getIntArray(R.array.config_doze_brightness_sensor_to_brightness));
        dimmingScrimArray = parseIntArray(KEY_DIMMING_SCRIM_ARRAY,
                resources.getIntArray(R.array.config_doze_brightness_sensor_to_scrim_opacity));
    }

    private int[] parseIntArray(final String key, final int[] defaultArray) {
        final String value = mParser.getString(key, null);
        if (value != null) {
            return Arrays.stream(value.split(":")).map(String::trim).mapToInt(
                    Integer::parseInt).toArray();
        } else {
            return defaultArray;
        }
    }

}
