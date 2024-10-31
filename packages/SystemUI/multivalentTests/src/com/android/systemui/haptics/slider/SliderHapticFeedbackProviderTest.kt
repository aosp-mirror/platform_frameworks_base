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
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.fakeVibratorHelper
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.InteractionProperties
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SliderHapticFeedbackProviderTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private var config = SliderHapticFeedbackConfig()

    private val dragVelocityProvider = SliderDragVelocityProvider { config.maxVelocityToScale }

    private val lowTickDuration = 12 // Mocked duration of a low tick
    private val dragTextureThresholdMillis =
        lowTickDuration * config.numberOfLowTicks + config.deltaMillisForDragInterval
    private val vibratorHelper = kosmos.fakeVibratorHelper
    private val msdlPlayer = kosmos.fakeMSDLPlayer
    private lateinit var sliderHapticFeedbackProvider: SliderHapticFeedbackProvider
    private val pipeliningAttributes =
        VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH)
            .setFlags(VibrationAttributes.FLAG_PIPELINED_EFFECT)
            .build()
    private lateinit var dynamicProperties: InteractionProperties.DynamicVibrationScale

    @Before
    fun setup() {

        vibratorHelper.primitiveDurations[VibrationEffect.Composition.PRIMITIVE_LOW_TICK] =
            lowTickDuration
        sliderHapticFeedbackProvider =
            SliderHapticFeedbackProvider(
                vibratorHelper,
                msdlPlayer,
                dragVelocityProvider,
                config,
                kosmos.fakeSystemClock,
            )
        dynamicProperties =
            InteractionProperties.DynamicVibrationScale(
                sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                pipeliningAttributes,
            )
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtLowerBookend_playsClick() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onLowerBookend()

            assertTrue(vibratorHelper.hasVibratedWithEffects(vibration))
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtLowerBookend_playsDragThresholdLimitToken() =
        testScope.runTest {
            sliderHapticFeedbackProvider.onLowerBookend()

            assertThat(msdlPlayer.latestTokenPlayed)
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(dynamicProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtLowerBookend_twoTimes_playsClickOnlyOnce() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onLowerBookend()
            sliderHapticFeedbackProvider.onLowerBookend()

            assertTrue(vibratorHelper.hasVibratedWithEffects(vibration))
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtLowerBookend_twoTimes_playsDragThresholdLimitTokenOnlyOnce() =
        testScope.runTest {
            sliderHapticFeedbackProvider.onLowerBookend()
            sliderHapticFeedbackProvider.onLowerBookend()

            assertThat(msdlPlayer.latestTokenPlayed)
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(dynamicProperties)
            assertThat(msdlPlayer.getHistory().size).isEqualTo(1)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtUpperBookend_playsClick() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onUpperBookend()

            assertTrue(vibratorHelper.hasVibratedWithEffects(vibration))
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtUpperBookend_playsDragThresholdLimitToken() =
        testScope.runTest {
            sliderHapticFeedbackProvider.onUpperBookend()

            assertThat(msdlPlayer.latestTokenPlayed)
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(dynamicProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtUpperBookend_twoTimes_playsClickOnlyOnce() =
        with(kosmos) {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                    )
                    .compose()

            sliderHapticFeedbackProvider.onUpperBookend()
            sliderHapticFeedbackProvider.onUpperBookend()

            assertEquals(/* expected= */ 1, vibratorHelper.timesVibratedWithEffect(vibration))
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtUpperBookend_twoTimes_playsDragThresholdLimitTokenOnlyOnce() =
        testScope.runTest {
            sliderHapticFeedbackProvider.onUpperBookend()
            sliderHapticFeedbackProvider.onUpperBookend()

            assertThat(msdlPlayer.latestTokenPlayed)
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(dynamicProperties)
            assertThat(msdlPlayer.getHistory().size).isEqualTo(1)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtProgress_onQuickSuccession_playsLowTicksOnce() =
        with(kosmos) {
            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedScale =
                sliderHapticFeedbackProvider.scaleOnDragTexture(config.maxVelocityToScale, progress)
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
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtProgress_forDiscreteSlider_playsTick() =
        with(kosmos) {
            config = SliderHapticFeedbackConfig(sliderStepSize = 0.2f)
            sliderHapticFeedbackProvider =
                SliderHapticFeedbackProvider(
                    vibratorHelper,
                    msdlPlayer,
                    dragVelocityProvider,
                    config,
                    kosmos.fakeSystemClock,
                )

            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedScale =
                sliderHapticFeedbackProvider.scaleOnDragTexture(config.maxVelocityToScale, progress)
            val tick =
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, expectedScale)
                    .compose()

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN called to play haptics
            sliderHapticFeedbackProvider.onProgress(progress)

            // THEN the correct composition only plays once
            assertEquals(expected = 1, vibratorHelper.timesVibratedWithEffect(tick))
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtProgress_forDiscreteSlider_playsDiscreteSliderToken() =
        with(kosmos) {
            config = SliderHapticFeedbackConfig(sliderStepSize = 0.2f)
            sliderHapticFeedbackProvider =
                SliderHapticFeedbackProvider(
                    vibratorHelper,
                    msdlPlayer,
                    dragVelocityProvider,
                    config,
                    kosmos.fakeSystemClock,
                )

            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedScale =
                sliderHapticFeedbackProvider.scaleOnDragTexture(config.maxVelocityToScale, progress)
            val expectedProperties =
                InteractionProperties.DynamicVibrationScale(expectedScale, pipeliningAttributes)

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN called to play haptics
            sliderHapticFeedbackProvider.onProgress(progress)

            // THEN the correct token plays once
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.DRAG_INDICATOR_DISCRETE)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(expectedProperties)
            assertThat(msdlPlayer.getHistory().size).isEqualTo(1)
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtProgress_onQuickSuccession_playsContinuousDragTokenOnce() =
        with(kosmos) {
            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedScale =
                sliderHapticFeedbackProvider.scaleOnDragTexture(config.maxVelocityToScale, progress)
            val expectedProperties =
                InteractionProperties.DynamicVibrationScale(expectedScale, pipeliningAttributes)

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN two calls to play occur immediately
            sliderHapticFeedbackProvider.onProgress(progress)
            sliderHapticFeedbackProvider.onProgress(progress)

            // THEN the correct token plays once
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.DRAG_INDICATOR_CONTINUOUS)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(expectedProperties)
            assertThat(msdlPlayer.getHistory().size).isEqualTo(1)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
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
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtProgress_beforeNextDragThreshold_playsContinousDragTokenOnce() =
        with(kosmos) {
            // GIVEN max velocity and a slider progress at half progress
            val firstProgress = 0.5f

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

            // THEN Only the first event plays the expected token and propertiesv
            val expectedProperties =
                InteractionProperties.DynamicVibrationScale(
                    sliderHapticFeedbackProvider.scaleOnDragTexture(
                        config.maxVelocityToScale,
                        firstProgress,
                    ),
                    pipeliningAttributes,
                )
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.DRAG_INDICATOR_CONTINUOUS)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(expectedProperties)
            assertThat(msdlPlayer.getHistory().size).isEqualTo(1)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
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
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtProgress_afterNextDragThreshold_playsContinuousDragTokenTwice() =
        with(kosmos) {
            // GIVEN max velocity and a slider progress at half progress
            val firstProgress = 0.5f

            // Given a second slider progress event beyond progress threshold
            val secondProgress = firstProgress + config.deltaProgressForDragThreshold + 0.01f

            // GIVEN system running for 1s
            fakeSystemClock.advanceTime(1000)

            // WHEN two calls to play occur with the required threshold separation (time and
            // progress)
            sliderHapticFeedbackProvider.onProgress(firstProgress)
            fakeSystemClock.advanceTime(dragTextureThresholdMillis.toLong())
            sliderHapticFeedbackProvider.onProgress(secondProgress)

            // THEN the correct token plays twice with the correct properties
            val firstProperties =
                InteractionProperties.DynamicVibrationScale(
                    sliderHapticFeedbackProvider.scaleOnDragTexture(
                        config.maxVelocityToScale,
                        firstProgress,
                    ),
                    pipeliningAttributes,
                )
            val secondProperties =
                InteractionProperties.DynamicVibrationScale(
                    sliderHapticFeedbackProvider.scaleOnDragTexture(
                        config.maxVelocityToScale,
                        secondProgress,
                    ),
                    pipeliningAttributes,
                )

            assertThat(msdlPlayer.getHistory().size).isEqualTo(2)
            assertThat(msdlPlayer.tokensPlayed[0]).isEqualTo(MSDLToken.DRAG_INDICATOR_CONTINUOUS)
            assertThat(msdlPlayer.propertiesPlayed[0]).isEqualTo(firstProperties)
            assertThat(msdlPlayer.tokensPlayed[1]).isEqualTo(MSDLToken.DRAG_INDICATOR_CONTINUOUS)
            assertThat(msdlPlayer.propertiesPlayed[1]).isEqualTo(secondProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtLowerBookend_afterPlayingAtProgress_playsTwice() =
        with(kosmos) {
            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedScale =
                sliderHapticFeedbackProvider.scaleOnDragTexture(config.maxVelocityToScale, progress)
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
            assertEquals(
                /* expected= */ 2,
                vibratorHelper.timesVibratedWithEffect(bookendVibration),
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtLowerBookend_afterPlayingAtProgress_playsTokensTwice() =
        with(kosmos) {
            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedProperties =
                InteractionProperties.DynamicVibrationScale(
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                    pipeliningAttributes,
                )

            // GIVEN a vibration at the lower bookend followed by a request to vibrate at progress
            sliderHapticFeedbackProvider.onLowerBookend()
            sliderHapticFeedbackProvider.onProgress(progress)

            // WHEN a vibration is to trigger again at the lower bookend
            sliderHapticFeedbackProvider.onLowerBookend()

            // THEN there are two bookend token vibrations
            assertThat(msdlPlayer.getHistory().size).isEqualTo(2)
            assertThat(msdlPlayer.tokensPlayed[0])
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.propertiesPlayed[0]).isEqualTo(expectedProperties)
            assertThat(msdlPlayer.tokensPlayed[1])
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.propertiesPlayed[1]).isEqualTo(expectedProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtUpperBookend_afterPlayingAtProgress_playsTwice() =
        with(kosmos) {
            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedScale =
                sliderHapticFeedbackProvider.scaleOnDragTexture(config.maxVelocityToScale, progress)
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
            assertEquals(
                /* expected= */ 2,
                vibratorHelper.timesVibratedWithEffect(bookendVibration),
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playHapticAtUpperBookend_afterPlayingAtProgress_playsTokensTwice() =
        with(kosmos) {
            // GIVEN max velocity and slider progress
            val progress = 1f
            val expectedProperties =
                InteractionProperties.DynamicVibrationScale(
                    sliderHapticFeedbackProvider.scaleOnEdgeCollision(config.maxVelocityToScale),
                    pipeliningAttributes,
                )

            // GIVEN a vibration at the upper bookend followed by a request to vibrate at progress
            sliderHapticFeedbackProvider.onUpperBookend()
            sliderHapticFeedbackProvider.onProgress(progress)

            // WHEN a vibration is to trigger again at the upper bookend
            sliderHapticFeedbackProvider.onUpperBookend()

            // THEN there are two bookend vibrations
            assertThat(msdlPlayer.getHistory().size).isEqualTo(2)
            assertThat(msdlPlayer.tokensPlayed[0])
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.propertiesPlayed[0]).isEqualTo(expectedProperties)
            assertThat(msdlPlayer.tokensPlayed[1])
                .isEqualTo(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT)
            assertThat(msdlPlayer.propertiesPlayed[1]).isEqualTo(expectedProperties)
        }

    @Test
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
