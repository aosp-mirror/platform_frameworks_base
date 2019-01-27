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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.ColorDisplayManager.AutoMode;
import android.hardware.display.ColorDisplayManager.ColorMode;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;
import android.util.Slog;

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
     * Get the current color mode.
     */
    public int getColorMode() {
        return mColorDisplayManager.getColorMode();
    }

    /**
     * Set the current color mode.
     *
     * @param colorMode the color mode
     */
    public void setColorMode(@ColorMode int colorMode) {
        mColorDisplayManager.setColorMode(colorMode);
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
    }
}
