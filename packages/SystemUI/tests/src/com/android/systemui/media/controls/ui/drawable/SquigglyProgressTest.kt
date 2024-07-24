/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.drawable

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.graphics.ColorUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SquigglyProgressTest : SysuiTestCase() {

    private val colorFilter = LightingColorFilter(Color.RED, Color.BLUE)
    private val strokeWidth = 5f
    private val alpha = 128
    private val tint = Color.GREEN

    lateinit var squigglyProgress: SquigglyProgress
    @Mock lateinit var canvas: Canvas
    @Captor lateinit var paintCaptor: ArgumentCaptor<Paint>
    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    @Before
    fun setup() {
        squigglyProgress = SquigglyProgress()
        squigglyProgress.waveLength = 30f
        squigglyProgress.lineAmplitude = 10f
        squigglyProgress.phaseSpeed = 8f
        squigglyProgress.strokeWidth = strokeWidth
        squigglyProgress.bounds = Rect(0, 0, 300, 30)
    }

    @Test
    fun testDrawPathAndLine() {
        squigglyProgress.draw(canvas)

        verify(canvas, times(2)).drawPath(any(), paintCaptor.capture())
    }

    @Test
    fun testOnLevelChanged() {
        assertThat(squigglyProgress.setLevel(5)).isFalse()
        squigglyProgress.animate = true
        assertThat(squigglyProgress.setLevel(4)).isTrue()
    }

    @Test
    fun testStrokeWidth() {
        squigglyProgress.draw(canvas)

        verify(canvas, times(2)).drawPath(any(), paintCaptor.capture())
        val (wavePaint, linePaint) = paintCaptor.getAllValues()

        assertThat(wavePaint.strokeWidth).isEqualTo(strokeWidth)
        assertThat(linePaint.strokeWidth).isEqualTo(strokeWidth)
    }

    @Test
    fun testAlpha() {
        squigglyProgress.alpha = alpha
        squigglyProgress.draw(canvas)

        verify(canvas, times(2)).drawPath(any(), paintCaptor.capture())
        val (wavePaint, linePaint) = paintCaptor.getAllValues()

        assertThat(squigglyProgress.alpha).isEqualTo(alpha)
        assertThat(wavePaint.alpha).isEqualTo(alpha)
        assertThat(linePaint.alpha).isEqualTo((alpha / 255f * DISABLED_ALPHA).toInt())
    }

    @Test
    fun testColorFilter() {
        squigglyProgress.colorFilter = colorFilter
        squigglyProgress.draw(canvas)

        verify(canvas, times(2)).drawPath(any(), paintCaptor.capture())
        val (wavePaint, linePaint) = paintCaptor.getAllValues()

        assertThat(wavePaint.colorFilter).isEqualTo(colorFilter)
        assertThat(linePaint.colorFilter).isEqualTo(colorFilter)
    }

    @Test
    fun testTint() {
        squigglyProgress.setTint(tint)
        squigglyProgress.draw(canvas)

        verify(canvas, times(2)).drawPath(any(), paintCaptor.capture())
        val (wavePaint, linePaint) = paintCaptor.getAllValues()

        assertThat(wavePaint.color).isEqualTo(tint)
        assertThat(linePaint.color).isEqualTo(ColorUtils.setAlphaComponent(tint, DISABLED_ALPHA))
    }
}
