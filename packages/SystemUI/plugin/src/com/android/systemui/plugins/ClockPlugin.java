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

import android.graphics.Paint.Style;
import android.view.View;

import com.android.systemui.plugins.annotations.ProvidesInterface;

import java.util.TimeZone;

/**
 * This plugin is used to replace main clock in keyguard.
 */
@ProvidesInterface(action = ClockPlugin.ACTION, version = ClockPlugin.VERSION)
public interface ClockPlugin extends Plugin {

    String ACTION = "com.android.systemui.action.PLUGIN_CLOCK";
    int VERSION = 1;

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
     * Notifies that time tick alarm from doze service fired.
     */
    default void dozeTimeTick() { }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    default void setDarkAmount(float darkAmount) {}

    /**
     * Notifies that the time zone has changed.
     */
    default void onTimeZoneChanged(TimeZone timeZone) {}

    /**
     * Indicates whether the keyguard status area (date) should be shown below
     * the clock.
     */
    default boolean shouldShowStatusArea() {
        return true;
    }
}
