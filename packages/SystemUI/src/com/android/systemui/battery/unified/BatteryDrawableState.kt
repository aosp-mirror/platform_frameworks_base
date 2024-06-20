/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.battery.unified

import android.graphics.Color
import android.graphics.drawable.Drawable

/**
 * States that might set a color profile (e.g., red for low battery) and are mutually exclusive.
 * This enum allows us to address which colors we want to use based on their function.
 */
enum class ColorProfile {
    // Grayscale is the default color
    None,
    // Green for e.g., charging
    Active,
    // Yellow for e.g., battery saver
    Warning,
    // Red for e.g., low battery
    Error,
}

/**
 * Encapsulates all drawing information needed by BatteryMeterDrawable to render properly. Rendered
 * state will be equivalent to the most recent state passed in.
 */
data class BatteryDrawableState(
    /** [0-100] description of the battery level */
    val level: Int,
    /** Whether or not to render the percent as a foreground text layer */
    val showPercent: Boolean,

    /** Set the [ColorProfile] to get the appropriate fill colors */
    val color: ColorProfile = ColorProfile.None,

    /**
     * An attribution is a drawable that shows either alongside the percent, or centered in the
     * foreground of the overall drawable.
     *
     * When space sharing with the percent text, the default rect is 6x6, positioned directly next
     * to the percent and left-aligned.
     *
     * When the attribution is the only foreground layer, then we use a 16x8 canvas and center this
     * drawable.
     *
     * In both cases, we use a FIT_CENTER style scaling. Note that for now the attributions will
     * have to configure their own padding inside of their vector definitions. Future versions
     * should abstract the side- and center- canvases and allow attributions to be defined with
     * separate designs for each case.
     */
    val attribution: Drawable?
) {
    fun hasForegroundContent() = showPercent || attribution != null

    companion object {
        val DefaultInitialState =
            BatteryDrawableState(
                level = 50,
                showPercent = false,
                attribution = null,
            )
    }
}

sealed interface BatteryColors {
    /** The color for the frame and any foreground attributions for the battery */
    val fg: Int
    /**
     * Default color for the frame background. Configured to be a transparent white or black that
     * matches the current mode (white for light theme, black for dark theme) and provides extra
     * contrast for the drawable
     */
    val bg: Int

    /** Color for the level fill when there is an attribution on top */
    val fill: Int
    /**
     * When there is no attribution, [fillOnlyColor] describes an opaque color with more contrast
     */
    val fillOnly: Int

    /** Used when charging */
    val activeFill: Int

    /** Warning color is used for battery saver mode */
    val warnFill: Int

    /** Error colors are used for low battery states typically */
    val errorFill: Int

    /** Color scheme appropriate for light mode (dark icons) */
    data object LightThemeColors : BatteryColors {
        override val fg = Color.BLACK
        // 22% alpha white
        override val bg: Int = Color.valueOf(1f, 1f, 1f, 0.22f).toArgb()

        // GM Gray 500
        override val fill = Color.parseColor("#9AA0A6")
        // GM Gray 600
        override val fillOnly = Color.parseColor("#80868B")

        // GM Green 500
        override val activeFill = Color.parseColor("#34A853")
        // GM Yellow 500
        override val warnFill = Color.parseColor("#FBBC04")
        // GM Red 500
        override val errorFill = Color.parseColor("#EA4335")
    }

    /** Color scheme appropriate for dark mode (light icons) */
    data object DarkThemeColors : BatteryColors {
        override val fg = Color.WHITE
        // 18% alpha black
        override val bg: Int = Color.valueOf(0f, 0f, 0f, 0.18f).toArgb()

        // GM Gray 700
        override val fill = Color.parseColor("#5F6368")
        // GM Gray 400
        override val fillOnly = Color.parseColor("#BDC1C6")

        // GM Green 700
        override val activeFill = Color.parseColor("#188038")
        // GM Yellow 700
        override val warnFill = Color.parseColor("#F29900")
        // GM Red 700
        override val errorFill = Color.parseColor("#C5221F")
    }

    companion object {
        /** For use from java */
        @JvmField val LIGHT_THEME_COLORS = LightThemeColors

        @JvmField val DARK_THEME_COLORS = DarkThemeColors
    }
}
