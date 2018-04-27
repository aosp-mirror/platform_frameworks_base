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
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

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
    @IntDef({ AUTO_MODE_DISABLED, AUTO_MODE_CUSTOM, AUTO_MODE_TWILIGHT })
    public @interface AutoMode {}

    /**
     * Auto mode value to prevent Night display from being automatically activated. It can still
     * be activated manually via {@link #setActivated(boolean)}.
     *
     * @see #setAutoMode(int)
     */
    public static final int AUTO_MODE_DISABLED = 0;
    /**
     * Auto mode value to automatically activate Night display at a specific start and end time.
     *
     * @see #setAutoMode(int)
     * @see #setCustomStartTime(LocalTime)
     * @see #setCustomEndTime(LocalTime)
     */
    public static final int AUTO_MODE_CUSTOM = 1;
    /**
     * Auto mode value to automatically activate Night display from sunset to sunrise.
     *
     * @see #setAutoMode(int)
     */
    public static final int AUTO_MODE_TWILIGHT = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ COLOR_MODE_NATURAL, COLOR_MODE_BOOSTED, COLOR_MODE_SATURATED, COLOR_MODE_AUTOMATIC })
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
    private final ContentObserver mContentObserver;

    private Callback mCallback;
    private MetricsLogger mMetricsLogger;

    public ColorDisplayController(@NonNull Context context) {
        this(context, ActivityManager.getCurrentUser());
    }

    public ColorDisplayController(@NonNull Context context, int userId) {
        mContext = context.getApplicationContext();
        mUserId = userId;

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

    /**
     * Returns {@code true} when Night display is activated (the display is tinted red).
     */
    public boolean isActivated() {
        return Secure.getIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_ACTIVATED, 0, mUserId) == 1;
    }

    /**
     * Sets whether Night display should be activated. This also sets the last activated time.
     *
     * @param activated {@code true} if Night display should be activated
     * @return {@code true} if the activated value was set successfully
     */
    public boolean setActivated(boolean activated) {
        if (isActivated() != activated) {
            Secure.putStringForUser(mContext.getContentResolver(),
                    Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                    LocalDateTime.now().toString(),
                    mUserId);
        }
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_ACTIVATED, activated ? 1 : 0, mUserId);
    }

    /**
     * Returns the time when Night display's activation state last changed, or {@code null} if it
     * has never been changed.
     */
    public LocalDateTime getLastActivatedTime() {
        final ContentResolver cr = mContext.getContentResolver();
        final String lastActivatedTime = Secure.getStringForUser(
                cr, Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME, mUserId);
        if (lastActivatedTime != null) {
            try {
                return LocalDateTime.parse(lastActivatedTime);
            } catch (DateTimeParseException ignored) {}
            // Uses the old epoch time.
            try {
                return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(lastActivatedTime)),
                    ZoneId.systemDefault());
            } catch (DateTimeException|NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Returns the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link #AUTO_MODE_DISABLED}, {@link #AUTO_MODE_CUSTOM}, or
     * {@link #AUTO_MODE_TWILIGHT}.
     */
    public @AutoMode int getAutoMode() {
        int autoMode = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_AUTO_MODE, -1, mUserId);
        if (autoMode == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: " + Secure.NIGHT_DISPLAY_AUTO_MODE);
            }
            autoMode = mContext.getResources().getInteger(
                    R.integer.config_defaultNightDisplayAutoMode);
        }

        if (autoMode != AUTO_MODE_DISABLED
                && autoMode != AUTO_MODE_CUSTOM
                && autoMode != AUTO_MODE_TWILIGHT) {
            Slog.e(TAG, "Invalid autoMode: " + autoMode);
            autoMode = AUTO_MODE_DISABLED;
        }

        return autoMode;
    }

    /**
     * Returns the current auto mode value, without validation, or {@code 1} if the auto mode has
     * never been set.
     */
    public int getAutoModeRaw() {
        return Secure.getIntForUser(mContext.getContentResolver(), Secure.NIGHT_DISPLAY_AUTO_MODE,
                -1, mUserId);
    }

    /**
     * Sets the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link #AUTO_MODE_DISABLED}, {@link #AUTO_MODE_CUSTOM}, or
     * {@link #AUTO_MODE_TWILIGHT}.
     *
     * @param autoMode the new auto mode to use
     * @return {@code true} if new auto mode was set successfully
     */
    public boolean setAutoMode(@AutoMode int autoMode) {
        if (autoMode != AUTO_MODE_DISABLED
                && autoMode != AUTO_MODE_CUSTOM
                && autoMode != AUTO_MODE_TWILIGHT) {
            throw new IllegalArgumentException("Invalid autoMode: " + autoMode);
        }

        if (getAutoMode() != autoMode) {
            Secure.putStringForUser(mContext.getContentResolver(),
                    Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                    null,
                    mUserId);
            getMetricsLogger().write(new LogMaker(
                    MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CHANGED)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(autoMode));
        }

        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_AUTO_MODE, autoMode, mUserId);
    }

    /**
     * Returns the local time when Night display will be automatically activated when using
     * {@link #AUTO_MODE_CUSTOM}.
     */
    public @NonNull LocalTime getCustomStartTime() {
        int startTimeValue = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_START_TIME, -1, mUserId);
        if (startTimeValue == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: "
                        + Secure.NIGHT_DISPLAY_CUSTOM_START_TIME);
            }
            startTimeValue = mContext.getResources().getInteger(
                    R.integer.config_defaultNightDisplayCustomStartTime);
        }

        return LocalTime.ofSecondOfDay(startTimeValue / 1000);
    }

    /**
     * Sets the local time when Night display will be automatically activated when using
     * {@link #AUTO_MODE_CUSTOM}.
     *
     * @param startTime the local time to automatically activate Night display
     * @return {@code true} if the new custom start time was set successfully
     */
    public boolean setCustomStartTime(@NonNull LocalTime startTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("startTime cannot be null");
        }
        getMetricsLogger().write(new LogMaker(
                MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CUSTOM_TIME_CHANGED)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(0));
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_START_TIME, startTime.toSecondOfDay() * 1000, mUserId);
    }

    /**
     * Returns the local time when Night display will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM}.
     */
    public @NonNull LocalTime getCustomEndTime() {
        int endTimeValue = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_END_TIME, -1, mUserId);
        if (endTimeValue == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: "
                        + Secure.NIGHT_DISPLAY_CUSTOM_END_TIME);
            }
            endTimeValue = mContext.getResources().getInteger(
                    R.integer.config_defaultNightDisplayCustomEndTime);
        }

        return LocalTime.ofSecondOfDay(endTimeValue / 1000);
    }

    /**
     * Sets the local time when Night display will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM}.
     *
     * @param endTime the local time to automatically deactivate Night display
     * @return {@code true} if the new custom end time was set successfully
     */
    public boolean setCustomEndTime(@NonNull LocalTime endTime) {
        if (endTime == null) {
            throw new IllegalArgumentException("endTime cannot be null");
        }
        getMetricsLogger().write(new LogMaker(
                MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CUSTOM_TIME_CHANGED)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(1));
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_END_TIME, endTime.toSecondOfDay() * 1000, mUserId);
    }

    /**
     * Returns the color temperature (in Kelvin) to tint the display when activated.
     */
    public int getColorTemperature() {
        int colorTemperature = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, -1, mUserId);
        if (colorTemperature == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: "
                    + Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE);
            }
            colorTemperature = getDefaultColorTemperature();
        }
        final int minimumTemperature = getMinimumColorTemperature();
        final int maximumTemperature = getMaximumColorTemperature();
        if (colorTemperature < minimumTemperature) {
            colorTemperature = minimumTemperature;
        } else if (colorTemperature > maximumTemperature) {
            colorTemperature = maximumTemperature;
        }

        return colorTemperature;
    }

    /**
     * Sets the current temperature.
     *
     * @param colorTemperature the temperature, in Kelvin.
     * @return {@code true} if new temperature was set successfully.
     */
    public boolean setColorTemperature(int colorTemperature) {
        return Secure.putIntForUser(mContext.getContentResolver(),
            Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, colorTemperature, mUserId);
    }

    /**
     * Get the current color mode from system properties, or return -1.
     *
     * See com.android.server.display.DisplayTransformManager.
     */
    private @ColorMode int getCurrentColorModeFromSystemProperties() {
        int displayColorSetting = SystemProperties.getInt("persist.sys.sf.native_mode", 0);
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
        // SATURATED is always allowed
        if (colorMode == COLOR_MODE_SATURATED) {
            return true;
        }

        final int[] availableColorModes = mContext.getResources().getIntArray(
                R.array.config_availableColorModes);
        for (int mode : availableColorModes) {
            if (mode == colorMode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the current color mode.
     */
    public int getColorMode() {
        if (getAccessibilityTransformActivated()) {
            return COLOR_MODE_SATURATED;
        }

        int colorMode = System.getIntForUser(mContext.getContentResolver(),
            System.DISPLAY_COLOR_MODE, -1, mUserId);
        if (colorMode == -1) {
            // There still might be a legacy system property controlling color mode that we need to
            // respect.
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
            } else {
                colorMode = COLOR_MODE_SATURATED;
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
        return mContext.getResources().getInteger(
                R.integer.config_nightDisplayColorTemperatureMin);
    }

    /**
     * Returns the maximum allowed color temperature (in Kelvin) to tint the display when activated.
     */
    public int getMaximumColorTemperature() {
        return mContext.getResources().getInteger(
                R.integer.config_nightDisplayColorTemperatureMax);
    }

    /**
     * Returns the default color temperature (in Kelvin) to tint the display when activated.
     */
    public int getDefaultColorTemperature() {
        return mContext.getResources().getInteger(
                R.integer.config_nightDisplayColorTemperatureDefault);
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

    private MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    /**
     * Returns {@code true} if Night display is supported by the device.
     */
    public static boolean isAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_nightDisplayAvailable);
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
