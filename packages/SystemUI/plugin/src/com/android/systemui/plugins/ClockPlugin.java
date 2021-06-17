/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins;

import android.graphics.Bitmap;
import android.graphics.Paint.Style;
import android.view.View;

import com.android.systemui.plugins.annotations.ProvidesInterface;

import java.util.TimeZone;

/**
 * Plugin used to replace main clock in keyguard.
 */
@ProvidesInterface(action = ClockPlugin.ACTION, version = ClockPlugin.VERSION)
public interface ClockPlugin extends Plugin {

    String ACTION = "com.android.systemui.action.PLUGIN_CLOCK";
    int VERSION = 5;

    /**
     * Get the name of the clock face.
     *
     * This name should not be translated.
     */
    String getName();

    /**
     * Get the title of the clock face to be shown in the picker app.
     */
    String getTitle();

    /**
     * Get thumbnail of clock face to be shown in the picker app.
     */
    Bitmap getThumbnail();

    /**
     * Get preview images of clock face to be shown in the picker app.
     *
     * Preview image should be realistic and show what the clock face will look like on AOD and lock
     * screen.
     *
     * @param width width of the preview image, should be the same as device width in pixels.
     * @param height height of the preview image, should be the same as device height in pixels.
     */
    Bitmap getPreview(int width, int height);

    /**
     * Get clock view.
     * @return clock view from plugin.
     */
    View getView();

    /**
     * Get clock view for a large clock that appears behind NSSL.
     */
    default View getBigClockView() {
        return null;
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight Height of the parent container.
     * @return preferred Y position.
     */
    int getPreferredY(int totalHeight);

    /**
     * Allows the plugin to clean up resources when no longer needed.
     *
     * Called when the view previously created by {@link ClockPlugin#getView()} has been detached
     * from the view hierarchy.
     */
    void onDestroyView();

    /**
     * Set clock paint style.
     * @param style The new style to set in the paint.
     */
    void setStyle(Style style);

    /**
     * Set clock text color.
     * @param color A color value.
     */
    void setTextColor(int color);

    /**
     * Sets the color palette for the clock face.
     * @param supportsDarkText Whether dark text can be displayed.
     * @param colors Colors that should be used on the clock face, ordered from darker to lighter.
     */
    default void setColorPalette(boolean supportsDarkText, int[] colors) {}

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    default void setDarkAmount(float darkAmount) {}

    /**
     * Notifies that time tick alarm from doze service fired.
     *
     * Implement this method instead of registering a broadcast listener for TIME_TICK.
     */
    default void onTimeTick() {}

    /**
     * Notifies that the time zone has changed.
     *
     * Implement this method instead of registering a broadcast listener for TIME_ZONE_CHANGED.
     */
    default void onTimeZoneChanged(TimeZone timeZone) {}

    /**
     * Notifies that the time format has changed.
     *
     * @param timeFormat "12" for 12-hour format, "24" for 24-hour format
     */
    default void onTimeFormatChanged(String timeFormat) {}

    /**
     * Indicates whether the keyguard status area (date) should be shown below
     * the clock.
     */
    default boolean shouldShowStatusArea() {
        return true;
    }
}
