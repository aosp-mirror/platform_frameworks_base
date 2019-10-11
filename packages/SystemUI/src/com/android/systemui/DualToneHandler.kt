/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui

import android.animation.ArgbEvaluator
import android.content.Context
import android.view.ContextThemeWrapper
import com.android.settingslib.Utils

/**
 * A color blender for `Theme.SystemUI` and other themes.
 *
 * This class is used to handle colors from themes in [Context] in the following fashion:
 * * The theme associated has a `darkIconTheme` and a `lightIconTheme`
 * * Each of these themes define colors for the items `singleToneColor`, `backgroundColor`,
 * and `fillColor`.
 *
 * In particular, `Theme.SystemUI` is a valid [Context]. If the provided [Context] does not have
 * the correct themes, the colors that are not found will default to black.
 *
 * It provides a way to obtain these colors and blends for a given background intensity.
 */
class DualToneHandler(context: Context) {
    private lateinit var darkColor: Color
    private lateinit var lightColor: Color

    init {
        setColorsFromContext(context)
    }

    /**
     * Sets the colors in this object from the given [Context]
     *
     * @param[context] A context with the appropriate themes to extract the colors from.
     */
    fun setColorsFromContext(context: Context) {
        val dualToneDarkTheme = ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.darkIconTheme))
        val dualToneLightTheme = ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.lightIconTheme))
        darkColor = Color(
                Utils.getColorAttrDefaultColor(dualToneDarkTheme, R.attr.singleToneColor),
                Utils.getColorAttrDefaultColor(dualToneDarkTheme, R.attr.backgroundColor),
                Utils.getColorAttrDefaultColor(dualToneDarkTheme, R.attr.fillColor))
        lightColor = Color(
                Utils.getColorAttrDefaultColor(dualToneLightTheme, R.attr.singleToneColor),
                Utils.getColorAttrDefaultColor(dualToneLightTheme, R.attr.backgroundColor),
                Utils.getColorAttrDefaultColor(dualToneLightTheme, R.attr.fillColor))
    }

    private fun getColorForDarkIntensity(darkIntensity: Float, lightColor: Int, darkColor: Int) =
        ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor) as Int

    /**
     * Blends the single color associated with the light and dark theme
     *
     * @param[intensity] Intensity of the background. Correspond with the "percentage" of color
     * from `darkIconTheme` to use
     * @return The blended color
     */
    fun getSingleColor(intensity: Float) =
            getColorForDarkIntensity(intensity, lightColor.single, darkColor.single)

    /**
     * Blends the background color associated with the light and dark theme
     *
     * @param[intensity] Intensity of the background. Correspond with the "percentage" of color
     * from `darkIconTheme` to use
     * @return The blended color
     */
    fun getBackgroundColor(intensity: Float) =
            getColorForDarkIntensity(intensity, lightColor.background, darkColor.background)

    /**
     * Blends the fill color associated with the light and dark theme
     *
     * @param[intensity] Intensity of the background. Correspond with the "percentage" of color
     * from `darkIconTheme` to use
     * @return The blended color
     */
    fun getFillColor(intensity: Float) =
            getColorForDarkIntensity(intensity, lightColor.fill, darkColor.fill)

    private data class Color(val single: Int, val background: Int, val fill: Int)
}