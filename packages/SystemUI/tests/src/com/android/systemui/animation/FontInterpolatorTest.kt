/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.animation

import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.graphics.text.TextRunShaper
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class FontInterpolatorTest : SysuiTestCase() {

    private val sFont = TextRunShaper.shapeTextRun("A", 0, 1, 0, 1, 0f, 0f, false, Paint())
            .getFont(0)

    private fun assertSameAxes(expect: Font, actual: Font) {
        val expectAxes = expect.axes?.also { it.sortBy { axis -> axis.tag } }
        val actualAxes = actual.axes?.also { it.sortBy { axis -> axis.tag } }
        assertThat(expectAxes).isEqualTo(actualAxes)
    }

    private fun assertSameAxes(expectVarSettings: String, actual: Font) {

        val expectAxes = FontVariationAxis.fromFontVariationSettings(expectVarSettings)?.also {
            it.sortBy { axis -> axis.tag }
        }
        val actualAxes = actual.axes?.also { it.sortBy { axis -> axis.tag } }
        assertThat(expectAxes).isEqualTo(actualAxes)
    }

    @Test
    fun textInterpolation() {
        val startFont = Font.Builder(sFont)
                .setFontVariationSettings("'wght' 100, 'ital' 0, 'GRAD' 200")
                .build()
        val endFont = Font.Builder(sFont)
                .setFontVariationSettings("'wght' 900, 'ital' 1, 'GRAD' 700")
                .build()

        val interp = FontInterpolator()
        assertSameAxes(startFont, interp.lerp(startFont, endFont, 0f))
        assertSameAxes(endFont, interp.lerp(startFont, endFont, 1f))
        assertSameAxes("'wght' 500, 'ital' 0.5, 'GRAD' 450", interp.lerp(startFont, endFont, 0.5f))
    }

    @Test
    fun textInterpolation_DefaultValue() {
        val startFont = Font.Builder(sFont)
                .setFontVariationSettings("'wght' 100")
                .build()
        val endFont = Font.Builder(sFont)
                .setFontVariationSettings("'ital' 1")
                .build()

        val interp = FontInterpolator()
        assertSameAxes("'wght' 250, 'ital' 0.5", interp.lerp(startFont, endFont, 0.5f))
    }

    @Test
    fun testInterpCache() {
        val startFont = Font.Builder(sFont)
                .setFontVariationSettings("'wght' 100")
                .build()
        val endFont = Font.Builder(sFont)
                .setFontVariationSettings("'ital' 1")
                .build()

        val interp = FontInterpolator()
        val resultFont = interp.lerp(startFont, endFont, 0.5f)
        val cachedFont = interp.lerp(startFont, endFont, 0.5f)
        assertThat(resultFont).isSameInstanceAs(cachedFont)
    }

    @Test
    fun testAxesCache() {
        val startFont = Font.Builder(sFont)
                .setFontVariationSettings("'wght' 100")
                .build()
        val endFont = Font.Builder(sFont)
                .setFontVariationSettings("'ital' 1")
                .build()

        val interp = FontInterpolator()
        val resultFont = interp.lerp(startFont, endFont, 0.5f)
        val reversedFont = interp.lerp(endFont, startFont, 0.5f)
        assertThat(resultFont).isSameInstanceAs(reversedFont)
    }
}
