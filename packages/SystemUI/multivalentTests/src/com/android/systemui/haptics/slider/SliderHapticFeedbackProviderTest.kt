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

import android.os.VibrationEffect
import android.view.VelocityTracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.fakeVibratorHelper
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.fakeSystemClock
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class SliderHapticFeedbackProviderTest : SysuiTestCase() {

    @Mock private lateinit var velocityTracker: VelocityTracker

    private val kosmos = testKosmos()

    private val config = SliderHapticFeedbackConfig()

    private val lowTickDuration = 12 // Mocked duration of a low tick
    private val dragTextureThresholdMillis =
        lowTickDuration * config.numberOfLowTicks + config.deltaMillisForDragInterval
    private val vibratorHelper = kosmos.fakeVibratorHelper
    private lateinit var sliderHapticFeedbackProvider: SliderHapticFeedbackProvider

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(velocityTracker.isAxisSupported(config.velocityAxis)).thenReturn(true)
        whenever(velocityTracker.getAxisVelocity(config.velocityAxis))
            .thenReturn(config.maxVelocityToScale)

        vibratorHelper.primitiveDurations[VibrationEffect.Composition.PRIMITIVE_LOW_TICK] =
            lowTickDuration
        sliderHapticFeedbackProvider =
            SliderHapticFeedbackProvider(
                vibratorHelper,
                velocityTracker,
                config,
                kosmos.fakeSystemClock,
            )
    }

    @Test
    fun playHapticAtLowerBookend_playsClick() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(
                            config.maxVelocityToScale
                        ),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onLowerBookend()

            assertTrue(vibratorHelper.hasVibratedWithEffects(vibration))
        }

    @Test
    fun playHapticAtLowerBookend_twoTimes_playsClickOnlyOnce() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale)
                    )
                    .compose()

            sliderHapticFeedbackProvider.onLowerBookend()
            sliderHapticFeedbackProvider.onLowerBookend()

            assertTrue(vibratorHelper.hasVibratedWithEffects(vibration))
        }

    @Test
    fun playHapticAtUpperBookend_playsClick() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(
                            config.maxVelocityToScale
                        ),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onUpperBookend()

            assertTrue(vibratorHelper.hasVibratedWithEffects(vibration))
        }

    @Test
    fun playHapticAtUpperBookend_twoTimes_playsClickOnlyOnce() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(
                            config.maxVelocityToScale
                        ),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onUpperBookend()
            sliderHapticFeedbackProvider.onUpperBookend()

            assertEquals(/* expected= */ 1, vibratorHelper.timesVibratedWithEffect(vibration))
        }

    @Test
    fun playHapticAtProgress_onQuickSuccession_playsLowTicksOnce() =
        with(kosmos) {
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
            fakeSystemClock.advanceTime(1000)

            // WHEN two calls to play occur immediately
            sliderHapticFeedbackProvider.onProgress(progress)
            sliderHapticFeedbackProvider.onProgress(progress)

            // THEN the correct composition only plays once
            assertEquals(/* expected= */ 1, vibratorHelper.timesVibratedWithEffect(ticks.compose()))
        }

    @Test
    fun playHapticAtProgress_beforeNextDragThreshold_playsLowTicksOnce() =
        with(kosmos) {
            // GIVEN max velocity and a slider progress at half progress
            val firstProgress = 0.5f
            val firstTicks = generateTicksComposition(config.maxVelocityToScale, firstProgress)

            // Given a second slider progress event smaller than the progress threshold
            val secondProgress =
                firstProgress + max(0f, config.deltaProgressForDragThreshold - 0.01f)

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN two calls to play occur with the required threshold separation (time and
            // progress)
            sliderHapticFeedbackProvider.onProgress(firstProgress)
            fakeSystemClock.advanceTime(dragTextureThresholdMillis.toLong())
            sliderHapticFeedbackProvider.onProgress(secondProgress)

            // THEN Only the first compositions plays
            assertEquals(/* expected= */ 1, vibratorHelper.timesVibratedWithEffect(firstTicks))
            assertEquals(/* expected= */ 1, vibratorHelper.totalVibrations)
        }

    @Test
    fun playHapticAtProgress_afterNextDragThreshold_playsLowTicksTwice() =
        with(kosmos) {
            // GIVEN max velocity and a slider progress at half progress
            val firstProgress = 0.5f
            val firstTicks = generateTicksComposition(config.maxVelocityToScale, firstProgress)

            // Given a second slider progress event beyond progress threshold
            val secondProgress = firstProgress + config.deltaProgressForDragThreshold + 0.01f
            val secondTicks = generateTicksComposition(config.maxVelocityToScale, secondProgress)

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN two calls to play occur with the required threshold separation (time and
            // progress)
            sliderHapticFeedbackProvider.onProgress(firstProgress)
            fakeSystemClock.advanceTime(dragTextureThresholdMillis.toLong())
            sliderHapticFeedbackProvider.onProgress(secondProgress)

            // THEN the correct compositions play
            assertEquals(/* expected= */ 1, vibratorHelper.timesVibratedWithEffect(firstTicks))
            assertEquals(/* expected= */ 1, vibratorHelper.timesVibratedWithEffect(secondTicks))
        }

    @Test
    fun playHapticAtLowerBookend_afterPlayingAtProgress_playsTwice() =
        with(kosmos) {
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
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(
                            config.maxVelocityToScale
                        ),
                    )
                    .compose()

            // GIVEN a vibration at the lower bookend followed by a request to vibrate at progress
            sliderHapticFeedbackProvider.onLowerBookend()
            sliderHapticFeedbackProvider.onProgress(progress)

            // WHEN a vibration is to trigger again at the lower bookend
            sliderHapticFeedbackProvider.onLowerBookend()

            // THEN there are two bookend vibrations
            assertEquals(
                /* expected= */ 2,
                vibratorHelper.timesVibratedWithEffect(bookendVibration)
            )
        }

    @Test
    fun playHapticAtUpperBookend_afterPlayingAtProgress_playsTwice() =
        with(kosmos) {
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
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(
                            config.maxVelocityToScale
                        ),
                    )
                    .compose()

            // GIVEN a vibration at the upper bookend followed by a request to vibrate at progress
            sliderHapticFeedbackProvider.onUpperBookend()
            sliderHapticFeedbackProvider.onProgress(progress)

            // WHEN a vibration is to trigger again at the upper bookend
            sliderHapticFeedbackProvider.onUpperBookend()

            // THEN there are two bookend vibrations
            assertEquals(
                /* expected= */ 2,
                vibratorHelper.timesVibratedWithEffect(bookendVibration)
            )
        }

    fun dragTextureLastProgress_afterDragTextureHaptics_keepsLastDragTextureProgress() =
        with(kosmos) {
            // GIVEN max velocity and a slider progress at half progress
            val progress = 0.5f

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN a drag texture plays
            sliderHapticFeedbackProvider.onProgress(progress)

            // THEN the dragTextureLastProgress remembers the latest progress
            assertEquals(progress, sliderHapticFeedbackProvider.dragTextureLastProgress)
        }

    @Test
    fun dragTextureLastProgress_afterDragTextureHaptics_resetsOnHandleReleased() =
        with(kosmos) {
            // GIVEN max velocity and a slider progress at half progress
            val progress = 0.5f

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

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
