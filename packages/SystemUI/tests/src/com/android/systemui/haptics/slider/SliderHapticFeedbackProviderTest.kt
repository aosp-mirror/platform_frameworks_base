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
import android.view.animation.AccelerateInterpolator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
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
    private val progressInterpolator = AccelerateInterpolator(config.progressInterpolatorFactor)
    private val velocityInterpolator = AccelerateInterpolator(config.velocityInterpolatorFactor)
    private lateinit var sliderHapticFeedbackProvider: SliderHapticFeedbackProvider

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(vibratorHelper.getPrimitiveDurations(any()))
            .thenReturn(intArrayOf(lowTickDuration))
        whenever(velocityTracker.xVelocity).thenReturn(config.maxVelocityToScale)
        sliderHapticFeedbackProvider =
            SliderHapticFeedbackProvider(vibratorHelper, velocityTracker, config, clock)
    }

    @Test
    fun playHapticAtLowerBookend_playsClick() {
        val vibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    scaleAtBookends(config.maxVelocityToScale)
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
                    scaleAtBookends(config.maxVelocityToScale)
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
                    scaleAtBookends(config.maxVelocityToScale)
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
                    scaleAtBookends(config.maxVelocityToScale)
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
        val expectedScale = scaleAtProgressChange(config.maxVelocityToScale.toFloat(), progress)
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
    fun playHapticAtProgress_afterNextDragThreshold_playsLowTicksTwice() {
        // GIVEN max velocity and slider progress
        val progress = 1f
        val expectedScale = scaleAtProgressChange(config.maxVelocityToScale.toFloat(), progress)
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, expectedScale)
        }

        // GIVEN system running for 1s
        clock.advanceTime(1000)

        // WHEN two calls to play occur with the required threshold separation
        sliderHapticFeedbackProvider.onProgress(progress)
        clock.advanceTime(dragTextureThresholdMillis.toLong())
        sliderHapticFeedbackProvider.onProgress(progress)

        // THEN the correct composition plays two times
        verify(vibratorHelper, times(2))
            .vibrate(eq(ticks.compose()), any(VibrationAttributes::class.java))
    }

    @Test
    fun playHapticAtLowerBookend_afterPlayingAtProgress_playsTwice() {
        // GIVEN max velocity and slider progress
        val progress = 1f
        val expectedScale = scaleAtProgressChange(config.maxVelocityToScale.toFloat(), progress)
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, expectedScale)
        }
        val bookendVibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    scaleAtBookends(config.maxVelocityToScale)
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
        val expectedScale = scaleAtProgressChange(config.maxVelocityToScale.toFloat(), progress)
        val ticks = VibrationEffect.startComposition()
        repeat(config.numberOfLowTicks) {
            ticks.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, expectedScale)
        }
        val bookendVibration =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    scaleAtBookends(config.maxVelocityToScale)
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

    private fun scaleAtBookends(velocity: Float): Float {
        val range = config.upperBookendScale - config.lowerBookendScale
        val interpolatedVelocity =
            velocityInterpolator.getInterpolation(velocity / config.maxVelocityToScale)
        return interpolatedVelocity * range + config.lowerBookendScale
    }

    private fun scaleAtProgressChange(velocity: Float, progress: Float): Float {
        val range = config.progressBasedDragMaxScale - config.progressBasedDragMinScale
        val interpolatedVelocity =
            velocityInterpolator.getInterpolation(velocity / config.maxVelocityToScale)
        val interpolatedProgress = progressInterpolator.getInterpolation(progress)
        val bump = interpolatedVelocity * config.additionalVelocityMaxBump
        return interpolatedProgress * range + config.progressBasedDragMinScale + bump
    }
}
