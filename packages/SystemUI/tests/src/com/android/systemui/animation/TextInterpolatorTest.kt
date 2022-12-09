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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.testing.AndroidTestingRunner
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.ceil
import org.junit.Test
import org.junit.runner.RunWith

private const val TEXT = "Hello, World."
private const val BIDI_TEXT = "abc\u05D0\u05D1\u05D2"
private const val BMP_WIDTH = 400
private const val BMP_HEIGHT = 300

// Due to b/189235998 the weight 400 of the default font is no longer variable font. To be able to
// test variable behavior, create the interpolatable typefaces with manually here.
private val VF_FONT = Font.Builder(File("/system/fonts/Roboto-Regular.ttf")).build()

private fun Font.toTypeface() =
        Typeface.CustomFallbackBuilder(FontFamily.Builder(this).build()).build()

private val PAINT = TextPaint().apply {
    typeface = Font.Builder(VF_FONT).setFontVariationSettings("'wght' 400").build().toTypeface()
    textSize = 32f
}

private val START_PAINT = TextPaint(PAINT).apply {
    typeface = Font.Builder(VF_FONT).setFontVariationSettings("'wght' 400").build().toTypeface()
}

private val END_PAINT = TextPaint(PAINT).apply {
    typeface = Font.Builder(VF_FONT).setFontVariationSettings("'wght' 700").build().toTypeface()
}

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TextInterpolatorTest : SysuiTestCase() {

    private fun makeLayout(
        text: String,
        paint: TextPaint,
        dir: TextDirectionHeuristic = TextDirectionHeuristics.LTR
    ): Layout {
        val width = ceil(Layout.getDesiredWidth(text, 0, text.length, paint)).toInt()
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setTextDirection(dir).build()
    }

    @Test
    fun testStartState() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        assertThat(interp.progress).isEqualTo(0f)
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(TEXT, START_PAINT).toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testEndState() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        interp.progress = 1f
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(TEXT, END_PAINT).toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testMiddleState() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // We cannot expect exact text layout of the middle position since we don't use text shaping
        // result for the middle state for performance reason. Just check it is not equals to start
        // end state.
        interp.progress = 0.5f
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        assertThat(actual.sameAs(makeLayout(TEXT, START_PAINT)
            .toBitmap(BMP_WIDTH, BMP_HEIGHT))).isFalse()
        assertThat(actual.sameAs(makeLayout(TEXT, END_PAINT)
            .toBitmap(BMP_WIDTH, BMP_HEIGHT))).isFalse()
    }

    @Test
    fun testRebase() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        interp.progress = 0.5f
        val expected = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)

        // Rebase base state to the current state of progress 0.5.
        interp.rebase()
        assertThat(interp.progress).isEqualTo(0f)
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testBidi_LTR() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.LTR)

        val interp = TextInterpolator(layout)
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        assertThat(interp.progress).isEqualTo(0f)
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.LTR)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testBidi_RTL() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.RTL)

        val interp = TextInterpolator(layout)
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        assertThat(interp.progress).isEqualTo(0f)
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.RTL)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testGlyphCallback_Empty() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.RTL)

        val interp = TextInterpolator(layout).apply {
            glyphFilter = { glyph, progress ->
            }
        }
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.RTL)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testGlyphCallback_Xcoordinate() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.RTL)

        val interp = TextInterpolator(layout).apply {
            glyphFilter = { glyph, progress ->
                glyph.x += 30f
            }
        }
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.RTL)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        // The glyph position was modified by callback, so the bitmap should not be the same.
        // We cannot modify the result of StaticLayout, so we cannot expect the exact  bitmaps.
        assertThat(expected.sameAs(actual)).isFalse()
    }

    @Test
    fun testGlyphCallback_Ycoordinate() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.RTL)

        val interp = TextInterpolator(layout).apply {
            glyphFilter = { glyph, progress ->
                glyph.y += 30f
            }
        }
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.RTL)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        // The glyph position was modified by callback, so the bitmap should not be the same.
        // We cannot modify the result of StaticLayout, so we cannot expect the exact  bitmaps.
        assertThat(expected.sameAs(actual)).isFalse()
    }

    @Test
    fun testGlyphCallback_TextSize() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.RTL)

        val interp = TextInterpolator(layout).apply {
            glyphFilter = { glyph, progress ->
                glyph.textSize += 10f
            }
        }
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.RTL)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        // The glyph position was modified by callback, so the bitmap should not be the same.
        // We cannot modify the result of StaticLayout, so we cannot expect the exact  bitmaps.
        assertThat(expected.sameAs(actual)).isFalse()
    }

    @Test
    fun testGlyphCallback_Color() {
        val layout = makeLayout(BIDI_TEXT, PAINT, TextDirectionHeuristics.RTL)

        val interp = TextInterpolator(layout).apply {
            glyphFilter = { glyph, progress ->
                glyph.color = Color.RED
            }
        }
        interp.basePaint.set(START_PAINT)
        interp.onBasePaintModified()

        interp.targetPaint.set(END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(BIDI_TEXT, START_PAINT, TextDirectionHeuristics.RTL)
                .toBitmap(BMP_WIDTH, BMP_HEIGHT)

        // The glyph position was modified by callback, so the bitmap should not be the same.
        // We cannot modify the result of StaticLayout, so we cannot expect the exact  bitmaps.
        assertThat(expected.sameAs(actual)).isFalse()
    }
}

private fun Layout.toBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }!!

private fun TextInterpolator.toBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
