/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.haptics.qs

import android.os.VibrationEffect
import android.testing.TestableLooper.RunWithLooper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.view.MotionEventBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class QSLongPressEffectTest : SysuiTestCase() {

    @Rule @JvmField val mMockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var testView: View
    @get:Rule val animatorTestRule = AnimatorTestRule(this)
    private val kosmos = testKosmos()
    private val vibratorHelper = kosmos.vibratorHelper

    private val effectDuration = 400
    private val lowTickDuration = 12
    private val spinDuration = 133

    private lateinit var longPressEffect: QSLongPressEffect

    @Before
    fun setup() {
        vibratorHelper.primitiveDurations[VibrationEffect.Composition.PRIMITIVE_LOW_TICK] =
            lowTickDuration
        vibratorHelper.primitiveDurations[VibrationEffect.Composition.PRIMITIVE_SPIN] = spinDuration

        kosmos.fakeKeyguardRepository.setKeyguardDismissible(true)

        longPressEffect =
            QSLongPressEffect(
                vibratorHelper,
                kosmos.keyguardInteractor,
                CoroutineScope(kosmos.backgroundCoroutineContext),
            )
        longPressEffect.initializeEffect(effectDuration)
    }

    @Test
    fun onReset_whileIdle_resetsEffect() = testWithScope {
        // GIVEN a call to reset
        longPressEffect.resetEffect()

        // THEN the effect remains idle and has not been initialized
        val state by collectLastValue(longPressEffect.state)
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(longPressEffect.hasInitialized).isFalse()
    }

    @Test
    fun onReset_whileRunning_resetsEffect() = testWhileRunning {
        // GIVEN a call to reset
        longPressEffect.resetEffect()

        // THEN the effect remains idle and has not been initialized
        val state by collectLastValue(longPressEffect.state)
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(longPressEffect.hasInitialized).isFalse()
    }

    @Test
    fun onInitialize_withNegativeDuration_doesNotInitialize() = testWithScope {
        // GIVEN an effect that has reset
        longPressEffect.resetEffect()

        // WHEN attempting to initialize with a negative duration
        val couldInitialize = longPressEffect.initializeEffect(-1)

        // THEN the effect can't initialized and remains reset
        val state by collectLastValue(longPressEffect.state)
        assertThat(couldInitialize).isFalse()
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(longPressEffect.hasInitialized).isFalse()
    }

    @Test
    fun onInitialize_withPositiveDuration_initializes() = testWithScope {
        // GIVEN an effect that has reset
        longPressEffect.resetEffect()

        // WHEN attempting to initialize with a positive duration
        val couldInitialize = longPressEffect.initializeEffect(effectDuration)

        // THEN the effect is initialized
        val state by collectLastValue(longPressEffect.state)
        assertThat(couldInitialize).isTrue()
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(longPressEffect.hasInitialized).isTrue()
    }

    @Test
    fun onActionDown_whileIdle_startsWait() = testWithScope {
        // GIVEN an action down event occurs
        val downEvent = buildMotionEvent(MotionEvent.ACTION_DOWN)
        longPressEffect.onTouch(testView, downEvent)

        // THEN the effect moves to the TIMEOUT_WAIT state
        val state by collectLastValue(longPressEffect.state)
        assertThat(state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
    }

    @Test
    fun onActionCancel_whileWaiting_goesIdle() = testWhileWaiting {
        // GIVEN an action cancel occurs
        val cancelEvent = buildMotionEvent(MotionEvent.ACTION_CANCEL)
        longPressEffect.onTouch(testView, cancelEvent)

        // THEN the effect goes back to idle and does not start
        val state by collectLastValue(longPressEffect.state)
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertEffectDidNotStart()
    }

    @Test
    fun onActionUp_whileWaiting_performsClick() = testWhileWaiting {
        // GIVEN an action is being collected
        val action by collectLastValue(longPressEffect.actionType)

        // GIVEN an action up occurs
        val upEvent = buildMotionEvent(MotionEvent.ACTION_UP)
        longPressEffect.onTouch(testView, upEvent)

        // THEN the action to invoke is the click action and the effect does not start
        assertThat(action).isEqualTo(QSLongPressEffect.ActionType.CLICK)
        assertEffectDidNotStart()
    }

    @Test
    fun onWaitComplete_whileWaiting_beginsEffect() = testWhileWaiting {
        // GIVEN the pressed timeout is complete
        longPressEffect.handleTimeoutComplete()

        // THEN the effect starts
        assertEffectStarted()
    }

    @Test
    fun onActionUp_whileEffectHasBegun_reversesEffect() = testWhileRunning {
        // GIVEN that the effect is at the middle of its completion (progress of 50%)
        animatorTestRule.advanceTimeBy(effectDuration / 2L)

        // WHEN an action up occurs
        val upEvent = buildMotionEvent(MotionEvent.ACTION_UP)
        longPressEffect.onTouch(testView, upEvent)

        // THEN the effect gets reversed at 50% progress
        assertEffectReverses(0.5f)
    }

    @Test
    fun onActionCancel_whileEffectHasBegun_reversesEffect() = testWhileRunning {
        // GIVEN that the effect is at the middle of its completion (progress of 50%)
        animatorTestRule.advanceTimeBy(effectDuration / 2L)

        // WHEN an action cancel occurs
        val cancelEvent = buildMotionEvent(MotionEvent.ACTION_CANCEL)
        longPressEffect.onTouch(testView, cancelEvent)

        // THEN the effect gets reversed at 50% progress
        assertEffectReverses(0.5f)
    }

    @Test
    fun onAnimationComplete_keyguardDismissible_effectEndsWithLongPress() = testWhileRunning {
        // GIVEN that the animation completes
        animatorTestRule.advanceTimeBy(effectDuration + 10L)

        // THEN the long-press effect completes with a LONG_PRESS
        assertEffectCompleted(QSLongPressEffect.ActionType.LONG_PRESS)
    }

    @Test
    fun onAnimationComplete_keyguardNotDismissible_effectEndsWithResetAndLongPress() =
        testWhileRunning {
            // GIVEN that the keyguard is not dismissible
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(false)

            // GIVEN that the animation completes
            animatorTestRule.advanceTimeBy(effectDuration + 10L)

            // THEN the long-press effect completes with RESET_AND_LONG_PRESS
            assertEffectCompleted(QSLongPressEffect.ActionType.RESET_AND_LONG_PRESS)
        }

    @Test
    fun onActionDown_whileRunningBackwards_resets() = testWhileRunning {
        // GIVEN that the effect is at the middle of its completion (progress of 50%)
        animatorTestRule.advanceTimeBy(effectDuration / 2L)

        // GIVEN an action cancel occurs and the effect gets reversed
        val cancelEvent = buildMotionEvent(MotionEvent.ACTION_CANCEL)
        longPressEffect.onTouch(testView, cancelEvent)

        // GIVEN an action down occurs
        val downEvent = buildMotionEvent(MotionEvent.ACTION_DOWN)
        longPressEffect.onTouch(testView, downEvent)

        // THEN the effect resets
        assertEffectResets()
    }

    @Test
    fun onAnimationComplete_whileRunningBackwards_goesToIdle() = testWhileRunning {
        // GIVEN that the effect is at the middle of its completion (progress of 50%)
        animatorTestRule.advanceTimeBy(effectDuration / 2L)

        // GIVEN an action cancel occurs and the effect gets reversed
        val cancelEvent = buildMotionEvent(MotionEvent.ACTION_CANCEL)
        longPressEffect.onTouch(testView, cancelEvent)

        // GIVEN that the animation completes after a sufficient amount of time
        animatorTestRule.advanceTimeBy(effectDuration.toLong())

        // THEN the state goes to [QSLongPressEffect.State.IDLE]
        val state by collectLastValue(longPressEffect.state)
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
    }

    private fun buildMotionEvent(action: Int): MotionEvent =
        MotionEventBuilder.newBuilder().setAction(action).build()

    private fun testWithScope(test: suspend TestScope.() -> Unit) =
        with(kosmos) { testScope.runTest { test() } }

    private fun testWhileWaiting(test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                // GIVEN the TIMEOUT_WAIT state is entered
                longPressEffect.setState(QSLongPressEffect.State.TIMEOUT_WAIT)

                // THEN run the test
                test()
            }
        }

    private fun testWhileRunning(test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                // GIVEN that the effect starts after the tap timeout is complete
                longPressEffect.setState(QSLongPressEffect.State.TIMEOUT_WAIT)
                longPressEffect.handleTimeoutComplete()

                // THEN run the test
                test()
            }
        }

    /**
     * Asserts that the effect started by checking that:
     * 1. The effect progress is 0f
     * 2. Initial hint haptics are played
     * 3. The internal state is [QSLongPressEffect.State.RUNNING_FORWARD]
     */
    private fun TestScope.assertEffectStarted() {
        val effectProgress by collectLastValue(longPressEffect.effectProgress)
        val state by collectLastValue(longPressEffect.state)
        val longPressHint =
            LongPressHapticBuilder.createLongPressHint(
                lowTickDuration,
                spinDuration,
                effectDuration,
            )

        assertThat(state).isEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(effectProgress).isEqualTo(0f)
        assertThat(longPressHint).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(longPressHint!!)).isTrue()
    }

    /**
     * Asserts that the effect did not start by checking that:
     * 1. No effect progress is emitted
     * 2. No haptics are played
     * 3. The internal state is not [QSLongPressEffect.State.RUNNING_BACKWARDS] or
     *    [QSLongPressEffect.State.RUNNING_FORWARD]
     */
    private fun TestScope.assertEffectDidNotStart() {
        val effectProgress by collectLastValue(longPressEffect.effectProgress)
        val state by collectLastValue(longPressEffect.state)

        assertThat(state).isNotEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(state).isNotEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS)
        assertThat(effectProgress).isNull()
        assertThat(vibratorHelper.totalVibrations).isEqualTo(0)
    }

    /**
     * Asserts that the effect completes by checking that:
     * 1. The progress is null
     * 2. The final snap haptics are played
     * 3. The internal state goes back to [QSLongPressEffect.State.IDLE]
     * 4. The action to perform on the tile is the action given as a parameter
     */
    private fun TestScope.assertEffectCompleted(expectedAction: QSLongPressEffect.ActionType) {
        val action by collectLastValue(longPressEffect.actionType)
        val effectProgress by collectLastValue(longPressEffect.effectProgress)
        val snapEffect = LongPressHapticBuilder.createSnapEffect()
        val state by collectLastValue(longPressEffect.state)

        assertThat(effectProgress).isNull()
        assertThat(snapEffect).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(snapEffect!!)).isTrue()
        assertThat(state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(action).isEqualTo(expectedAction)
    }

    /**
     * Assert that the effect gets reverted by checking that:
     * 1. The internal state is [QSLongPressEffect.State.RUNNING_BACKWARDS]
     * 2. The reverse haptics plays at the point where the animation was paused
     */
    private fun TestScope.assertEffectReverses(pausedProgress: Float) {
        val reverseHaptics =
            LongPressHapticBuilder.createReversedEffect(
                pausedProgress,
                lowTickDuration,
                effectDuration,
            )
        val state by collectLastValue(longPressEffect.state)

        assertThat(state).isEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS)
        assertThat(reverseHaptics).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(reverseHaptics!!)).isTrue()
    }

    /**
     * Asserts that the effect resets by checking that:
     * 1. The effect progress resets to 0
     * 2. The internal state goes back to [QSLongPressEffect.State.TIMEOUT_WAIT]
     */
    private fun TestScope.assertEffectResets() {
        val effectProgress by collectLastValue(longPressEffect.effectProgress)
        val state by collectLastValue(longPressEffect.state)

        assertThat(effectProgress).isNull()
        assertThat(state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
    }
}
