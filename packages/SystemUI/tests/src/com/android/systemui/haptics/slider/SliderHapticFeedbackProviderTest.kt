/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.haptics.slider

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.view.VelocityTracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import kotlin.math.max
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class SliderHapticFeedbackProviderTest : SysuiTestCase() {

    @Mock private lateinit var velocityTracker: VelocityTracker
    @Mock private lateinit var vibratorHelper: VibratorHelper

    private val config = SliderHapticFeedbackConfig()
    private val clock = FakeSystemClock()

    private val lowTickDuration = 12 // Mocked duration of a low tick
    private val dragTextureThresholdMillis =
        lowTickDuration * config.numberOfLowTicks + config.deltaMillisForDragInterval
    private lateinit var sliderHapticFeedbackProvider: SliderHapticFeedbackProvider

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(vibratorHelper.getPrimitiveDurations(any()))
            .thenReturn(intArrayOf(lowTickDuration))
        whenever(velocityTracker.isAxisSupported(config.velocityAxis)).thenReturn(true)
        whenever(velocityTracker.getAxisVelocity(config.velocityAxis))
            .thenReturn(config.maxVelocityToScale)
        sliderHapticFeedbackProvider =
            SliderHapticFeedbackProvider(vibratorHelper, velocityTracker, config, clock)
    }

    @Test
    fun playHapticAtLowerBookend_playsClick() {
        val vibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                )
                .compose()

        sliderHapticFeedbackProvider.onLowerBookend()

        verify(vibratorHelper).vibrate(eq(vibration), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtLowerBookend_twoTimes_playsClickOnlyOnce() {
        val vibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale)
                )
                .compose()

        sliderHapticFeedbackProvider.onLowerBookend()
        sliderHapticFeedbackProvider.onLowerBookend()

        verify(vibratorHelper).vibrate(eq(vibration), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtUpperBookend_playsClick() {
        val vibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                )
                .compose()

        sliderHapticFeedbackProvider.onUpperBookend()

        verify(vibratorHelper).vibrate(eq(vibration), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtUpperBookend_twoTimes_playsClickOnlyOnce() {
        val vibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                )
                .compose()

        sliderHapticFeedbackProvider.onUpperBookend()
        sliderHapticFeedbackProvider.onUpperBookend()

        verify(vibratorHelper, times(1))
            .vibrate(eq(vibration), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtProgress_onQuickSuccession_playsLowTicksOnce() {
        // GIVEN max velocity and slider progress
        val progress = 1f
        val expectedScale =
            sliderHapticFeedbackProvider.scaleOnDragTexture(
                config.maxVelocityToScale,
                progress,
            )
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, expectedScale)
        }

        // GIVEN system running for 1s
        clock.advanceTime(1000)

        // WHEN two calls to play occur immediately
        sliderHapticFeedbackProvider.onProgress(progress)
        sliderHapticFeedbackProvider.onProgress(progress)

        // THEN the correct composition only plays once
        verify(vibratorHelper, times(1))
            .vibrate(eq(ticks.compose()), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtProgress_beforeNextDragThreshold_playsLowTicksOnce() {
        // GIVEN max velocity and a slider progress at half progress
        val firstProgress = 0.5f
        val firstTicks = generateTicksComposition(config.maxVelocityToScale, firstProgress)

        // Given a second slider progress event smaller than the progress threshold
        val secondProgress = firstProgress + max(0f, config.deltaProgressForDragThreshold - 0.01f)

        // GIVEN system running for 1s
        clock.advanceTime(1000)

        // WHEN two calls to play occur with the required threshold separation (time and progress)
        sliderHapticFeedbackProvider.onProgress(firstProgress)
        clock.advanceTime(dragTextureThresholdMillis.toLong())
        sliderHapticFeedbackProvider.onProgress(secondProgress)

        // THEN Only the first compositions plays
        verify(vibratorHelper, times(1))
            .vibrate(eq(firstTicks), any(VibrationAttributes::class.java))
        verify(vibratorHelper, times(1))
            .vibrate(any(VibrationEffect::class.java), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtProgress_afterNextDragThreshold_playsLowTicksTwice() {
        // GIVEN max velocity and a slider progress at half progress
        val firstProgress = 0.5f
        val firstTicks = generateTicksComposition(config.maxVelocityToScale, firstProgress)

        // Given a second slider progress event beyond progress threshold
        val secondProgress = firstProgress + config.deltaProgressForDragThreshold + 0.01f
        val secondTicks = generateTicksComposition(config.maxVelocityToScale, secondProgress)

        // GIVEN system running for 1s
        clock.advanceTime(1000)

        // WHEN two calls to play occur with the required threshold separation (time and progress)
        sliderHapticFeedbackProvider.onProgress(firstProgress)
        clock.advanceTime(dragTextureThresholdMillis.toLong())
        sliderHapticFeedbackProvider.onProgress(secondProgress)

        // THEN the correct compositions play
        verify(vibratorHelper, times(1))
            .vibrate(eq(firstTicks), any(VibrationAttributes::class.java))
        verify(vibratorHelper, times(1))
            .vibrate(eq(secondTicks), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtLowerBookend_afterPlayingAtProgress_playsTwice() {
        // GIVEN max velocity and slider progress
        val progress = 1f
        val expectedScale =
            sliderHapticFeedbackProvider.scaleOnDragTexture(
                config.maxVelocityToScale,
                progress,
            )
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, expectedScale)
        }
        val bookendVibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                )
                .compose()

        // GIVEN a vibration at the lower bookend followed by a request to vibrate at progress
        sliderHapticFeedbackProvider.onLowerBookend()
        sliderHapticFeedbackProvider.onProgress(progress)

        // WHEN a vibration is to trigger again at the lower bookend
        sliderHapticFeedbackProvider.onLowerBookend()

        // THEN there are two bookend vibrations
        verify(vibratorHelper, times(2))
            .vibrate(eq(bookendVibration), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtUpperBookend_afterPlayingAtProgress_playsTwice() {
        // GIVEN max velocity and slider progress
        val progress = 1f
        val expectedScale =
            sliderHapticFeedbackProvider.scaleOnDragTexture(
                config.maxVelocityToScale,
                progress,
            )
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, expectedScale)
        }
        val bookendVibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                )
                .compose()

        // GIVEN a vibration at the upper bookend followed by a request to vibrate at progress
        sliderHapticFeedbackProvider.onUpperBookend()
        sliderHapticFeedbackProvider.onProgress(progress)

        // WHEN a vibration is to trigger again at the upper bookend
        sliderHapticFeedbackProvider.onUpperBookend()

        // THEN there are two bookend vibrations
        verify(vibratorHelper, times(2))
            .vibrate(eq(bookendVibration), any(VibrationAttributes::class.java))
    }

    fun dragTextureLastProgress_afterDragTextureHaptics_keepsLastDragTextureProgress() {
        // GIVEN max velocity and a slider progress at half progress
        val progress = 0.5f

        // GIVEN system running for 1s
        clock.advanceTime(1000)

        // WHEN a drag texture plays
        sliderHapticFeedbackProvider.onProgress(progress)

        // THEN the dragTextureLastProgress remembers the latest progress
        assertEquals(progress, sliderHapticFeedbackProvider.dragTextureLastProgress)
    }

    @Test
    fun dragTextureLastProgress_afterDragTextureHaptics_resetsOnHandleReleased() {
        // GIVEN max velocity and a slider progress at half progress
        val progress = 0.5f

        // GIVEN system running for 1s
        clock.advanceTime(1000)

        // WHEN a drag texture plays
        sliderHapticFeedbackProvider.onProgress(progress)

        // WHEN the handle is released
        sliderHapticFeedbackProvider.onHandleReleasedFromTouch()

        // THEN the dragTextureLastProgress tracker is reset
        assertEquals(-1f, sliderHapticFeedbackProvider.dragTextureLastProgress)
    }

    private fun generateTicksComposition(velocity: Float, progress: Float): VibrationEffect {
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                sliderHapticFeedbackProvider.scaleOnDragTexture(velocity, progress),
            )
        }
        return ticks.compose()
    }
}
