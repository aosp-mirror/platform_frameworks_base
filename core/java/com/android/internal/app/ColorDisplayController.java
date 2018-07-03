/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.ColorDisplayManager.AutoMode;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.util.Slog;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalTime;

/**
 * Controller for managing night display and color mode settings.
 * <p/>
 * Night display tints your screen red at night. This makes it easier to look at your screen in
 * dim light and may help you fall asleep more easily.
 */
public final class ColorDisplayController {

    private static final String TAG = "ColorDisplayController";
    private static final boolean DEBUG = false;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({COLOR_MODE_NATURAL, COLOR_MODE_BOOSTED, COLOR_MODE_SATURATED, COLOR_MODE_AUTOMATIC})
    public @interface ColorMode {}

    /**
     * Color mode with natural colors.
     *
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_NATURAL = 0;
    /**
     * Color mode with boosted colors.
     *
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_BOOSTED = 1;
    /**
     * Color mode with saturated colors.
     *
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_SATURATED = 2;
    /**
     * Color mode with automatic colors.
     *
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_AUTOMATIC = 3;

    private final Context mContext;
    private final int mUserId;
    private final ColorDisplayManager mColorDisplayManager;

    private ContentObserver mContentObserver;
    private Callback mCallback;

    public ColorDisplayController(@NonNull Context context) {
        this(context, ActivityManager.getCurrentUser());
    }

    public ColorDisplayController(@NonNull Context context, int userId) {
        mContext = context.getApplicationContext();
        mUserId = userId;
        mColorDisplayManager = mContext.getSystemService(ColorDisplayManager.class);
    }

    /**
     * Returns {@code true} when Night display is activated (the display is tinted red).
     */
    public boolean isActivated() {
        return mColorDisplayManager.isNightDisplayActivated();
    }

    /**
     * Sets whether Night display should be activated. This also sets the last activated time.
     *
     * @param activated {@code true} if Night display should be activated
     * @return {@code true} if the activated value was set successfully
     */
    public boolean setActivated(boolean activated) {
        return mColorDisplayManager.setNightDisplayActivated(activated);
    }

    /**
     * Returns the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link ColorDisplayManager#AUTO_MODE_DISABLED}, {@link
     * ColorDisplayManager#AUTO_MODE_CUSTOM_TIME} or {@link ColorDisplayManager#AUTO_MODE_TWILIGHT}.
     */
    public @AutoMode int getAutoMode() {
        return mColorDisplayManager.getNightDisplayAutoMode();
    }

    /**
     * Returns the current auto mode value, without validation, or {@code 1} if the auto mode has
     * never been set.
     */
    public int getAutoModeRaw() {
        return mColorDisplayManager.getNightDisplayAutoModeRaw();
    }

    /**
     * Sets the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link ColorDisplayManager#AUTO_MODE_DISABLED}, {@link
     * ColorDisplayManager#AUTO_MODE_CUSTOM_TIME} or {@link ColorDisplayManager#AUTO_MODE_TWILIGHT}.
     *
     * @param autoMode the new auto mode to use
     * @return {@code true} if new auto mode was set successfully
     */
    public boolean setAutoMode(@AutoMode int autoMode) {
        return mColorDisplayManager.setNightDisplayAutoMode(autoMode);
    }

    /**
     * Returns the local time when Night display will be automatically activated when using {@link
     * ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     */
    public @NonNull LocalTime getCustomStartTime() {
        return mColorDisplayManager.getNightDisplayCustomStartTime();
    }

    /**
     * Sets the local time when Night display will be automatically activated when using {@link
     * ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     *
     * @param startTime the local time to automatically activate Night display
     * @return {@code true} if the new custom start time was set successfully
     */
    public boolean setCustomStartTime(@NonNull LocalTime startTime) {
        return mColorDisplayManager.setNightDisplayCustomStartTime(startTime);
    }

    /**
     * Returns the local time when Night display will be automatically deactivated when using {@link
     * ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     */
    public @NonNull LocalTime getCustomEndTime() {
        return mColorDisplayManager.getNightDisplayCustomEndTime();
    }

    /**
     * Sets the local time when Night display will be automatically deactivated when using {@link
     * ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     *
     * @param endTime the local time to automatically deactivate Night display
     * @return {@code true} if the new custom end time was set successfully
     */
    public boolean setCustomEndTime(@NonNull LocalTime endTime) {
        return mColorDisplayManager.setNightDisplayCustomEndTime(endTime);
    }

    /**
     * Returns the color temperature (in Kelvin) to tint the display when activated.
     */
    public int getColorTemperature() {
        return mColorDisplayManager.getNightDisplayColorTemperature();
    }

    /**
     * Sets the current temperature.
     *
     * @param colorTemperature the temperature, in Kelvin.
     * @return {@code true} if new temperature was set successfully.
     */
    public boolean setColorTemperature(int colorTemperature) {
        return mColorDisplayManager.setNightDisplayColorTemperature(colorTemperature);
    }

    /**
     * Get the current color mode from system properties, or return -1.
     *
     * See com.android.server.display.DisplayTransformManager.
     */
    private @ColorMode int getCurrentColorModeFromSystemProperties() {
        final int displayColorSetting = SystemProperties.getInt("persist.sys.sf.native_mode", 0);
        if (displayColorSetting == 0) {
            return "1.0".equals(SystemProperties.get("persist.sys.sf.color_saturation"))
                    ? COLOR_MODE_NATURAL : COLOR_MODE_BOOSTED;
        } else if (displayColorSetting == 1) {
            return COLOR_MODE_SATURATED;
        } else if (displayColorSetting == 2) {
            return COLOR_MODE_AUTOMATIC;
        } else {
            return -1;
        }
    }

    private boolean isColorModeAvailable(@ColorMode int colorMode) {
        final int[] availableColorModes = mContext.getResources().getIntArray(
                R.array.config_availableColorModes);
        if (availableColorModes != null) {
            for (int mode : availableColorModes) {
                if (mode == colorMode) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get the current color mode.
     */
    public int getColorMode() {
        if (getAccessibilityTransformActivated()) {
            if (isColorModeAvailable(COLOR_MODE_SATURATED)) {
                return COLOR_MODE_SATURATED;
            } else if (isColorModeAvailable(COLOR_MODE_AUTOMATIC)) {
                return COLOR_MODE_AUTOMATIC;
            }
        }

        int colorMode = System.getIntForUser(mContext.getContentResolver(),
                System.DISPLAY_COLOR_MODE, -1, mUserId);
        if (colorMode == -1) {
            // There might be a system property controlling color mode that we need to respect; if
            // not, this will set a suitable default.
            colorMode = getCurrentColorModeFromSystemProperties();
        }

        // This happens when a color mode is no longer available (e.g., after system update or B&R)
        // or the device does not support any color mode.
        if (!isColorModeAvailable(colorMode)) {
            if (colorMode == COLOR_MODE_BOOSTED && isColorModeAvailable(COLOR_MODE_NATURAL)) {
                colorMode = COLOR_MODE_NATURAL;
            } else if (colorMode == COLOR_MODE_SATURATED
                    && isColorModeAvailable(COLOR_MODE_AUTOMATIC)) {
                colorMode = COLOR_MODE_AUTOMATIC;
            } else if (colorMode == COLOR_MODE_AUTOMATIC
                    && isColorModeAvailable(COLOR_MODE_SATURATED)) {
                colorMode = COLOR_MODE_SATURATED;
            } else {
                colorMode = -1;
            }
        }

        return colorMode;
    }

    /**
     * Set the current color mode.
     *
     * @param colorMode the color mode
     */
    public void setColorMode(@ColorMode int colorMode) {
        if (!isColorModeAvailable(colorMode)) {
            throw new IllegalArgumentException("Invalid colorMode: " + colorMode);
        }
        System.putIntForUser(mContext.getContentResolver(), System.DISPLAY_COLOR_MODE, colorMode,
                mUserId);
    }

    /**
     * Returns the minimum allowed color temperature (in Kelvin) to tint the display when activated.
     */
    public int getMinimumColorTemperature() {
        return ColorDisplayManager.getMinimumColorTemperature(mContext);
    }

    /**
     * Returns the maximum allowed color temperature (in Kelvin) to tint the display when activated.
     */
    public int getMaximumColorTemperature() {
        return ColorDisplayManager.getMaximumColorTemperature(mContext);
    }

    /**
     * Returns true if any Accessibility color transforms are enabled.
     */
    public boolean getAccessibilityTransformActivated() {
        final ContentResolver cr = mContext.getContentResolver();
        return
            Secure.getIntForUser(cr, Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                    0, mUserId) == 1
            || Secure.getIntForUser(cr, Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
                    0, mUserId) == 1;
    }

    private void onSettingChanged(@NonNull String setting) {
        if (DEBUG) {
            Slog.d(TAG, "onSettingChanged: " + setting);
        }

        if (mCallback != null) {
            switch (setting) {
                case Secure.NIGHT_DISPLAY_ACTIVATED:
                    mCallback.onActivated(isActivated());
                    break;
                case Secure.NIGHT_DISPLAY_AUTO_MODE:
                    mCallback.onAutoModeChanged(getAutoMode());
                    break;
                case Secure.NIGHT_DISPLAY_CUSTOM_START_TIME:
                    mCallback.onCustomStartTimeChanged(getCustomStartTime());
                    break;
                case Secure.NIGHT_DISPLAY_CUSTOM_END_TIME:
                    mCallback.onCustomEndTimeChanged(getCustomEndTime());
                    break;
                case Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE:
                    mCallback.onColorTemperatureChanged(getColorTemperature());
                    break;
                case System.DISPLAY_COLOR_MODE:
                    mCallback.onDisplayColorModeChanged(getColorMode());
                    break;
                case Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED:
                case Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED:
                    mCallback.onAccessibilityTransformChanged(getAccessibilityTransformActivated());
                    break;
            }
        }
    }

    /**
     * Register a callback to be invoked whenever the Night display settings are changed.
     */
    public void setListener(Callback callback) {
        final Callback oldCallback = mCallback;
        if (oldCallback != callback) {
            mCallback = callback;

            if (mContentObserver == null) {
                mContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        super.onChange(selfChange, uri);

                        final String setting = uri == null ? null : uri.getLastPathSegment();
                        if (setting != null) {
                            onSettingChanged(setting);
                        }
                    }
                };
            }

            if (callback == null) {
                // Stop listening for changes now that there IS NOT a listener.
                mContext.getContentResolver().unregisterContentObserver(mContentObserver);
            } else if (oldCallback == null) {
                // Start listening for changes now that there IS a listener.
                final ContentResolver cr = mContext.getContentResolver();
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_ACTIVATED),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_AUTO_MODE),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_START_TIME),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_END_TIME),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(System.getUriFor(System.DISPLAY_COLOR_MODE),
                        false /* notifyForDecendants */, mContentObserver, mUserId);
                cr.registerContentObserver(
                        Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED),
                        false /* notifyForDecendants */, mContentObserver, mUserId);
                cr.registerContentObserver(
                        Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                        false /* notifyForDecendants */, mContentObserver, mUserId);
            }
        }
    }

    /**
     * Callback invoked whenever the Night display settings are changed.
     */
    public interface Callback {
        /**
         * Callback invoked when the activated state changes.
         *
         * @param activated {@code true} if Night display is activated
         */
        default void onActivated(boolean activated) {}
        /**
         * Callback invoked when the auto mode changes.
         *
         * @param autoMode the auto mode to use
         */
        default void onAutoModeChanged(int autoMode) {}
        /**
         * Callback invoked when the time to automatically activate Night display changes.
         *
         * @param startTime the local time to automatically activate Night display
         */
        default void onCustomStartTimeChanged(LocalTime startTime) {}
        /**
         * Callback invoked when the time to automatically deactivate Night display changes.
         *
         * @param endTime the local time to automatically deactivate Night display
         */
        default void onCustomEndTimeChanged(LocalTime endTime) {}

        /**
         * Callback invoked when the color temperature changes.
         *
         * @param colorTemperature the color temperature to tint the screen
         */
        default void onColorTemperatureChanged(int colorTemperature) {}

        /**
         * Callback invoked when the color mode changes.
         *
         * @param displayColorMode the color mode
         */
        default void onDisplayColorModeChanged(int displayColorMode) {}

        /**
         * Callback invoked when Accessibility color transforms change.
         *
         * @param state the state Accessibility color transforms (true of active)
         */
        default void onAccessibilityTransformChanged(boolean state) {}
    }
}
