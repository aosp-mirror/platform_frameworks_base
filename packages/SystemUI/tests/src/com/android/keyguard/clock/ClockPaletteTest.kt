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
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ClockPaletteTest : SysuiTestCase() {

    private lateinit var clockPalette: ClockPalette
    private lateinit var colors: IntArray

    @Before
    fun setUp() {
        clockPalette = ClockPalette()
        // colors used are reds from light to dark.
        val hsv: FloatArray = FloatArray(3)
        Color.colorToHSV(Color.RED, hsv)
        colors = IntArray(10)
        val step: Float = (0f - hsv[2]) / colors.size
        for (i in 0 until colors.size) {
            hsv[2] += step
            colors[i] = Color.HSVToColor(hsv)
        }
    }

    @Test
    fun testDark() {
        // GIVEN on AOD
        clockPalette.setDarkAmount(1f)
        // AND GIVEN that wallpaper doesn't support dark text
        clockPalette.setColorPalette(false, colors)
        // THEN the secondary color should be lighter than the primary color
        assertThat(value(clockPalette.getPrimaryColor()))
                .isGreaterThan(value(clockPalette.getSecondaryColor()))
    }

    @Test
    fun testDarkText() {
        // GIVEN on lock screen
        clockPalette.setDarkAmount(0f)
        // AND GIVEN that wallpaper supports dark text
        clockPalette.setColorPalette(true, colors)
        // THEN the secondary color should be darker the primary color
        assertThat(value(clockPalette.getPrimaryColor()))
                .isLessThan(value(clockPalette.getSecondaryColor()))
    }

    @Test
    fun testLightText() {
        // GIVEN on lock screen
        clockPalette.setDarkAmount(0f)
        // AND GIVEN that wallpaper doesn't support dark text
        clockPalette.setColorPalette(false, colors)
        // THEN the secondary color should be darker than the primary color
        assertThat(value(clockPalette.getPrimaryColor()))
                .isGreaterThan(value(clockPalette.getSecondaryColor()))
    }

    @Test
    fun testNullColors() {
        // GIVEN on AOD
        clockPalette.setDarkAmount(1f)
        // AND GIVEN that wallpaper colors are null
        clockPalette.setColorPalette(false, null)
        // THEN the primary color should be whilte
        assertThat(clockPalette.getPrimaryColor()).isEqualTo(Color.WHITE)
    }

    private fun value(color: Int): Float {
        val hsv: FloatArray = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[2]
    }
}
