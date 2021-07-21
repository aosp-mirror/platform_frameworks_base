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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.systemui.R;

/**
 * Class to store the policy for AOD, which comes from
 * {@link android.provider.Settings.Global}
 */
public class AlwaysOnDisplayPolicy {
    public static final String TAG = "AlwaysOnDisplayPolicy";

    private static final long DEFAULT_PROX_SCREEN_OFF_DELAY_MS = 10 * DateUtils.SECOND_IN_MILLIS;
    private static final long DEFAULT_PROX_COOLDOWN_TRIGGER_MS = 2 * DateUtils.SECOND_IN_MILLIS;
    private static final long DEFAULT_PROX_COOLDOWN_PERIOD_MS = 5 * DateUtils.SECOND_IN_MILLIS;
    private static final long DEFAULT_WALLPAPER_VISIBILITY_MS = 60 * DateUtils.SECOND_IN_MILLIS;
    private static final long DEFAULT_WALLPAPER_FADE_OUT_MS = 400;

    static final String KEY_SCREEN_BRIGHTNESS_ARRAY = "screen_brightness_array";
    static final String KEY_DIMMING_SCRIM_ARRAY = "dimming_scrim_array";
    static final String KEY_PROX_SCREEN_OFF_DELAY_MS = "prox_screen_off_delay";
    static final String KEY_PROX_COOLDOWN_TRIGGER_MS = "prox_cooldown_trigger";
    static final String KEY_PROX_COOLDOWN_PERIOD_MS = "prox_cooldown_period";
    static final String KEY_WALLPAPER_VISIBILITY_MS = "wallpaper_visibility_timeout";
    static final String KEY_WALLPAPER_FADE_OUT_MS = "wallpaper_fade_out_duration";


    /**
     * Integer used to dim the screen while dozing.
     *
     * @see R.integer.config_screenBrightnessDoze
     */
    public int defaultDozeBrightness;

    /**
     * Integer used to dim the screen just before the screen turns off.
     *
     * @see R.integer.config_screenBrightnessDim
     */
    public int dimBrightness;

    /**
     * Integer array to map ambient brightness type to real screen brightness.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_SCREEN_BRIGHTNESS_ARRAY
     */
    public int[] screenBrightnessArray;

    /**
     * Integer array to map ambient brightness type to dimming scrim.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_DIMMING_SCRIM_ARRAY
     */
    public int[] dimmingScrimArray;

    /**
     * Delay time(ms) from covering the prox to turning off the screen.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_PROX_SCREEN_OFF_DELAY_MS
     */
    public long proxScreenOffDelayMs;

    /**
     * The threshold time(ms) to trigger the cooldown timer, which will
     * turn off prox sensor for a period.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_PROX_COOLDOWN_TRIGGER_MS
     */
    public long proxCooldownTriggerMs;

    /**
     * The period(ms) to turning off the prox sensor if
     * {@link #KEY_PROX_COOLDOWN_TRIGGER_MS} is triggered.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_PROX_COOLDOWN_PERIOD_MS
     */
    public long proxCooldownPeriodMs;

    /**
     * For how long(ms) the wallpaper should still be visible
     * after entering AoD.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_WALLPAPER_VISIBILITY_MS
     */
    public long wallpaperVisibilityDuration;

    /**
     * Duration(ms) of the fade out animation after
     * {@link #KEY_WALLPAPER_VISIBILITY_MS} elapses.
     *
     * @see Settings.Global#ALWAYS_ON_DISPLAY_CONSTANTS
     * @see #KEY_WALLPAPER_FADE_OUT_MS
     */
    public long wallpaperFadeOutDuration;

    private final KeyValueListParser mParser;
    private final Context mContext;
    private SettingsObserver mSettingsObserver;

    public AlwaysOnDisplayPolicy(Context context) {
        context = context.getApplicationContext();
        mContext = context;
        mParser = new KeyValueListParser(',');
        mSettingsObserver = new SettingsObserver(context.getMainThreadHandler());
        mSettingsObserver.observe();
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri ALWAYS_ON_DISPLAY_CONSTANTS_URI
                = Settings.Global.getUriFor(Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(ALWAYS_ON_DISPLAY_CONSTANTS_URI,
                    false, this, UserHandle.USER_ALL);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (uri == null || ALWAYS_ON_DISPLAY_CONSTANTS_URI.equals(uri)) {
                final Resources resources = mContext.getResources();
                final String value = Settings.Global.getString(mContext.getContentResolver(),
                        Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS);

                try {
                    mParser.setString(value);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Bad AOD constants");
                }

                proxScreenOffDelayMs = mParser.getLong(KEY_PROX_SCREEN_OFF_DELAY_MS,
                        DEFAULT_PROX_SCREEN_OFF_DELAY_MS);
                proxCooldownTriggerMs = mParser.getLong(KEY_PROX_COOLDOWN_TRIGGER_MS,
                        DEFAULT_PROX_COOLDOWN_TRIGGER_MS);
                proxCooldownPeriodMs = mParser.getLong(KEY_PROX_COOLDOWN_PERIOD_MS,
                        DEFAULT_PROX_COOLDOWN_PERIOD_MS);
                wallpaperFadeOutDuration = mParser.getLong(KEY_WALLPAPER_FADE_OUT_MS,
                        DEFAULT_WALLPAPER_FADE_OUT_MS);
                wallpaperVisibilityDuration = mParser.getLong(KEY_WALLPAPER_VISIBILITY_MS,
                        DEFAULT_WALLPAPER_VISIBILITY_MS);
                defaultDozeBrightness = resources.getInteger(
                        com.android.internal.R.integer.config_screenBrightnessDoze);
                dimBrightness = resources.getInteger(
                        com.android.internal.R.integer.config_screenBrightnessDim);
                screenBrightnessArray = mParser.getIntArray(KEY_SCREEN_BRIGHTNESS_ARRAY,
                        resources.getIntArray(
                                R.array.config_doze_brightness_sensor_to_brightness));
                dimmingScrimArray = mParser.getIntArray(KEY_DIMMING_SCRIM_ARRAY,
                        resources.getIntArray(
                                R.array.config_doze_brightness_sensor_to_scrim_opacity));
            }
        }
    }
}
