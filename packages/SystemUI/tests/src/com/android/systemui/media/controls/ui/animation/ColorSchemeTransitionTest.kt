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

package com.android.systemui.media.controls.ui.animation

import android.animation.ValueAnimator
import android.graphics.Color
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.ui.view.GutsViewHolder
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.monet.ColorScheme
import com.android.systemui.surfaceeffects.ripple.MultiRippleController
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseController
import junit.framework.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val DEFAULT_COLOR = Color.RED
private const val TARGET_COLOR = Color.BLUE

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class ColorSchemeTransitionTest : SysuiTestCase() {

    private interface ExtractCB : (ColorScheme) -> Int
    private interface ApplyCB : (Int) -> Unit
    private lateinit var colorTransition: AnimatingColorTransition
    private lateinit var colorSchemeTransition: ColorSchemeTransition

    @Mock private lateinit var mockAnimatingTransition: AnimatingColorTransition
    @Mock private lateinit var valueAnimator: ValueAnimator
    @Mock private lateinit var colorScheme: ColorScheme
    @Mock private lateinit var extractColor: ExtractCB
    @Mock private lateinit var applyColor: ApplyCB

    private lateinit var animatingColorTransitionFactory: AnimatingColorTransitionFactory
    @Mock private lateinit var mediaViewHolder: MediaViewHolder
    @Mock private lateinit var gutsViewHolder: GutsViewHolder
    @Mock private lateinit var multiRippleController: MultiRippleController
    @Mock private lateinit var turbulenceNoiseController: TurbulenceNoiseController

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        whenever(mediaViewHolder.gutsViewHolder).thenReturn(gutsViewHolder)
        animatingColorTransitionFactory = { _, _, _ -> mockAnimatingTransition }
        whenever(extractColor.invoke(colorScheme)).thenReturn(TARGET_COLOR)

        colorSchemeTransition =
            ColorSchemeTransition(
                context,
                mediaViewHolder,
                multiRippleController,
                turbulenceNoiseController,
                animatingColorTransitionFactory
            )

        colorTransition =
            object : AnimatingColorTransition(DEFAULT_COLOR, extractColor, applyColor) {
                override fun buildAnimator(): ValueAnimator {
                    return valueAnimator
                }
            }
    }

    @After fun tearDown() {}

    @Test
    fun testColorTransition_nullColorScheme_keepsDefault() {
        colorTransition.updateColorScheme(null)
        verify(applyColor, times(1)).invoke(DEFAULT_COLOR)
        verify(valueAnimator, never()).start()
        assertEquals(DEFAULT_COLOR, colorTransition.sourceColor)
        assertEquals(DEFAULT_COLOR, colorTransition.targetColor)
    }

    @Test
    fun testColorTransition_newColor_startsAnimation() {
        colorTransition.updateColorScheme(colorScheme)
        verify(applyColor, times(1)).invoke(DEFAULT_COLOR)
        verify(valueAnimator, times(1)).start()
        assertEquals(DEFAULT_COLOR, colorTransition.sourceColor)
        assertEquals(TARGET_COLOR, colorTransition.targetColor)
    }

    @Test
    fun testColorTransition_sameColor_noAnimation() {
        whenever(extractColor.invoke(colorScheme)).thenReturn(DEFAULT_COLOR)
        colorTransition.updateColorScheme(colorScheme)
        verify(valueAnimator, never()).start()
        assertEquals(DEFAULT_COLOR, colorTransition.sourceColor)
        assertEquals(DEFAULT_COLOR, colorTransition.targetColor)
    }

    @Test
    fun testColorTransition_colorAnimation_startValues() {
        val expectedColor = DEFAULT_COLOR
        whenever(valueAnimator.animatedFraction).thenReturn(0f)
        colorTransition.updateColorScheme(colorScheme)
        colorTransition.onAnimationUpdate(valueAnimator)

        assertEquals(expectedColor, colorTransition.currentColor)
        assertEquals(expectedColor, colorTransition.sourceColor)
        verify(applyColor, times(2)).invoke(expectedColor) // applied once in constructor
    }

    @Test
    fun testColorTransition_colorAnimation_endValues() {
        val expectedColor = TARGET_COLOR
        whenever(valueAnimator.animatedFraction).thenReturn(1f)
        colorTransition.updateColorScheme(colorScheme)
        colorTransition.onAnimationUpdate(valueAnimator)

        assertEquals(expectedColor, colorTransition.currentColor)
        assertEquals(expectedColor, colorTransition.targetColor)
        verify(applyColor).invoke(expectedColor)
    }

    @Test
    fun testColorTransition_colorAnimation_interpolatedMidpoint() {
        val expectedColor = Color.rgb(186, 0, 186)
        whenever(valueAnimator.animatedFraction).thenReturn(0.5f)
        colorTransition.updateColorScheme(colorScheme)
        colorTransition.onAnimationUpdate(valueAnimator)

        assertEquals(expectedColor, colorTransition.currentColor)
        verify(applyColor).invoke(expectedColor)
    }

    @Test
    fun testColorSchemeTransition_update() {
        colorSchemeTransition.updateColorScheme(colorScheme)
        verify(mockAnimatingTransition, times(8)).updateColorScheme(colorScheme)
        verify(gutsViewHolder).colorScheme = colorScheme
    }
}
