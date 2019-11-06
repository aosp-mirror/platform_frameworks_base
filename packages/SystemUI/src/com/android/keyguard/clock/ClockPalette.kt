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

package com.android.keyguard.clock

import android.graphics.Color
import android.util.MathUtils

private const val PRIMARY_INDEX = 5
private const val SECONDARY_DARK_INDEX = 8
private const val SECONDARY_LIGHT_INDEX = 2

/**
 * A helper class to extract colors from a clock face.
 */
class ClockPalette {

    private var darkAmount: Float = 0f
    private var accentPrimary: Int = Color.WHITE
    private var accentSecondaryLight: Int = Color.WHITE
    private var accentSecondaryDark: Int = Color.BLACK
    private val lightHSV: FloatArray = FloatArray(3)
    private val darkHSV: FloatArray = FloatArray(3)
    private val hsv: FloatArray = FloatArray(3)

    /** Returns a color from the palette as an RGB packed int. */
    fun getPrimaryColor(): Int {
        return accentPrimary
    }

    /** Returns either a light or dark color from the palette as an RGB packed int. */
    fun getSecondaryColor(): Int {
        Color.colorToHSV(accentSecondaryLight, lightHSV)
        Color.colorToHSV(accentSecondaryDark, darkHSV)
        for (i in 0..2) {
            hsv[i] = MathUtils.lerp(darkHSV[i], lightHSV[i], darkAmount)
        }
        return Color.HSVToColor(hsv)
    }

    /** See {@link ClockPlugin#setColorPalette}. */
    fun setColorPalette(supportsDarkText: Boolean, colorPalette: IntArray?) {
        if (colorPalette == null || colorPalette.isEmpty()) {
            accentPrimary = Color.WHITE
            accentSecondaryLight = Color.WHITE
            accentSecondaryDark = if (supportsDarkText) Color.BLACK else Color.WHITE
            return
        }
        val length = colorPalette.size
        accentPrimary = colorPalette[Math.max(0, length - PRIMARY_INDEX)]
        accentSecondaryLight = colorPalette[Math.max(0, length - SECONDARY_LIGHT_INDEX)]
        accentSecondaryDark = colorPalette[Math.max(0,
                length - if (supportsDarkText) SECONDARY_DARK_INDEX else SECONDARY_LIGHT_INDEX)]
    }

    /** See {@link ClockPlugin#setDarkAmount}. */
    fun setDarkAmount(darkAmount: Float) {
        this.darkAmount = darkAmount
    }
}
