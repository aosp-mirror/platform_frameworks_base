/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.plugins.clocks

import android.graphics.Rect
import com.android.systemui.plugins.annotations.ProtectedInterface

/** Events that have specific data about the related face */
@ProtectedInterface
interface ClockFaceEvents {
    /** Call every tick to update the rendered time */
    fun onTimeTick()

    /**
     * Call whenever the theme or seedColor is updated
     *
     * Theme can be specific to the clock face.
     * - isDarkTheme -> clock should be light
     * - !isDarkTheme -> clock should be dark
     */
    fun onThemeChanged(theme: ThemeConfig)

    /**
     * Call whenever font settings change. Pass in a target font size in pixels. The specific clock
     * design is allowed to ignore this target size on a case-by-case basis.
     */
    fun onFontSettingChanged(fontSizePx: Float)

    /**
     * Target region information for the clock face. For small clock, this will match the bounds of
     * the parent view mostly, but have a target height based on the height of the default clock.
     * For large clocks, the parent view is the entire device size, but most clocks will want to
     * render within the centered targetRect to avoid obstructing other elements. The specified
     * targetRegion is relative to the parent view.
     */
    fun onTargetRegionChanged(targetRegion: Rect?)

    /** Called to notify the clock about its display. */
    fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean)
}

/** Contains Theming information for the clock face */
data class ThemeConfig(
    /** True if the clock should use dark theme (light text on dark background) */
    val isDarkTheme: Boolean,

    /**
     * A clock specific seed color to use when theming, if any was specified by the user. A null
     * value denotes that we should use the seed color for the current system theme.
     */
    val seedColor: Int?,
)
