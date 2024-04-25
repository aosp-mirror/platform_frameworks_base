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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class QSLongPressEffectTest : SysuiTestCase() {

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
            )
    }

    @Test
    fun onInitialize_withNegativeDuration_doesNotInitialize() =
        testWithScope(false) {
            // WHEN attempting to initialize with a negative duration
            val couldInitialize = longPressEffect.initializeEffect(-1)

            // THEN the effect can't initialized and remains reset
            assertThat(couldInitialize).isFalse()
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
            assertThat(longPressEffect.hasInitialized).isFalse()
        }

    @Test
    fun onInitialize_withPositiveDuration_initializes() = testWithScope {
        // WHEN attempting to initialize with a positive duration
        val couldInitialize = longPressEffect.initializeEffect(effectDuration)

        // THEN the effect is initialized
        assertThat(couldInitialize).isTrue()
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(longPressEffect.hasInitialized).isTrue()
    }

    @Test
    fun onActionDown_whileIdle_startsWait() = testWithScope {
        // GIVEN an action down event occurs
        longPressEffect.handleActionDown()

        // THEN the effect moves to the TIMEOUT_WAIT state
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
    }

    @Test
    fun onActionCancel_whileWaiting_goesIdle() =
        testWhileInState(QSLongPressEffect.State.TIMEOUT_WAIT) {
            // GIVEN an action cancel occurs
            longPressEffect.handleActionCancel()

            // THEN the effect goes back to idle and does not start
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
            assertEffectDidNotStart()
        }

    @Test
    fun onActionUp_whileWaiting_performsClick() =
        testWhileInState(QSLongPressEffect.State.TIMEOUT_WAIT) {
            // GIVEN an action is being collected
            val action by collectLastValue(longPressEffect.actionType)

            // GIVEN an action up occurs
            longPressEffect.handleActionUp()

            // THEN the action to invoke is the click action and the effect does not start
            assertThat(action).isEqualTo(QSLongPressEffect.ActionType.CLICK)
            assertEffectDidNotStart()
        }

    @Test
    fun onWaitComplete_whileWaiting_beginsEffect() =
        testWhileInState(QSLongPressEffect.State.TIMEOUT_WAIT) {
            // GIVEN the pressed timeout is complete
            longPressEffect.handleTimeoutComplete()

            // THEN the effect emits the action to start an animator
            val action by collectLastValue(longPressEffect.actionType)
            assertThat(action).isEqualTo(QSLongPressEffect.ActionType.START_ANIMATOR)
        }

    @Test
    fun onAnimationStart_whileWaiting_effectBegins() =
        testWhileInState(QSLongPressEffect.State.TIMEOUT_WAIT) {
            // GIVEN that the animator starts
            longPressEffect.handleAnimationStart()

            // THEN the effect begins
            assertEffectStarted()
        }

    @Test
    fun onActionUp_whileEffectHasBegun_reversesEffect() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN an action up occurs
            longPressEffect.handleActionUp()

            // THEN the effect reverses
            assertEffectReverses()
        }

    @Test
    fun onPlayReverseHaptics_reverseHapticsArePlayed() = testWithScope {
        // GIVEN a call to play reverse haptics at the effect midpoint
        val progress = 0.5f
        longPressEffect.playReverseHaptics(progress)

        // THEN the expected texture is played
        val reverseHaptics =
            LongPressHapticBuilder.createReversedEffect(
                progress,
                lowTickDuration,
                effectDuration,
            )
        assertThat(reverseHaptics).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(reverseHaptics!!)).isTrue()
    }

    @Test
    fun onActionCancel_whileEffectHasBegun_reversesEffect() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // WHEN an action cancel occurs
            longPressEffect.handleActionCancel()

            // THEN the effect gets reversed
            assertEffectReverses()
        }

    @Test
    fun onAnimationComplete_keyguardDismissible_effectEndsWithLongPress() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the long-press effect completes with a LONG_PRESS
            assertEffectCompleted(QSLongPressEffect.ActionType.LONG_PRESS)
        }

    @Test
    fun onAnimationComplete_keyguardNotDismissible_effectEndsWithResetAndLongPress() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN that the keyguard is not dismissible
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(false)

            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the long-press effect completes with RESET_AND_LONG_PRESS
            assertEffectCompleted(QSLongPressEffect.ActionType.RESET_AND_LONG_PRESS)
        }

    @Test
    fun onActionDown_whileRunningBackwards_cancels() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN an action cancel occurs and the effect gets reversed
            longPressEffect.handleActionCancel()

            // GIVEN an action down occurs
            longPressEffect.handleActionDown()

            // THEN the effect posts an action to cancel the animator
            val action by collectLastValue(longPressEffect.actionType)
            assertThat(action).isEqualTo(QSLongPressEffect.ActionType.CANCEL_ANIMATOR)
        }

    @Test
    fun onAnimatorCancel_effectGoesBackToWait() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN that the animator was cancelled
            longPressEffect.handleAnimationCancel()

            // THEN the state goes to the timeout wait
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
        }

    @Test
    fun onAnimationComplete_whileRunningBackwards_goesToIdle() =
        testWhileInState(QSLongPressEffect.State.RUNNING_BACKWARDS) {
            // GIVEN an action cancel occurs and the effect gets reversed
            longPressEffect.handleActionCancel()

            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the state goes to [QSLongPressEffect.State.IDLE]
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
        }

    private fun testWithScope(initialize: Boolean = true, test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                if (initialize) {
                    longPressEffect.initializeEffect(effectDuration)
                }
                test()
            }
        }

    private fun testWhileInState(
        state: QSLongPressEffect.State,
        initialize: Boolean = true,
        test: suspend TestScope.() -> Unit,
    ) =
        with(kosmos) {
            testScope.runTest {
                if (initialize) {
                    longPressEffect.initializeEffect(effectDuration)
                }
                // GIVEN a state
                longPressEffect.setState(state)

                // THEN run the test
                test()
            }
        }

    /**
     * Asserts that the effect started by checking that:
     * 1. Initial hint haptics are played
     * 2. The internal state is [QSLongPressEffect.State.RUNNING_FORWARD]
     */
    private fun assertEffectStarted() {
        val longPressHint =
            LongPressHapticBuilder.createLongPressHint(
                lowTickDuration,
                spinDuration,
                effectDuration,
            )

        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(longPressHint).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(longPressHint!!)).isTrue()
    }

    /**
     * Asserts that the effect did not start by checking that:
     * 1. No haptics are played
     * 2. The internal state is not [QSLongPressEffect.State.RUNNING_BACKWARDS] or
     *    [QSLongPressEffect.State.RUNNING_FORWARD]
     */
    private fun assertEffectDidNotStart() {
        assertThat(longPressEffect.state).isNotEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(longPressEffect.state).isNotEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS)
        assertThat(vibratorHelper.totalVibrations).isEqualTo(0)
    }

    /**
     * Asserts that the effect completes by checking that:
     * 1. The final snap haptics are played
     * 2. The internal state goes back to [QSLongPressEffect.State.IDLE]
     * 3. The action to perform on the tile is the action given as a parameter
     */
    private fun TestScope.assertEffectCompleted(expectedAction: QSLongPressEffect.ActionType) {
        val action by collectLastValue(longPressEffect.actionType)
        val snapEffect = LongPressHapticBuilder.createSnapEffect()

        assertThat(snapEffect).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(snapEffect!!)).isTrue()
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(action).isEqualTo(expectedAction)
    }

    /**
     * Assert that the effect gets reverted by checking that:
     * 1. The internal state is [QSLongPressEffect.State.RUNNING_BACKWARDS]
     * 2. An action to reverse the animator is emitted
     */
    private fun TestScope.assertEffectReverses() {
        val action by collectLastValue(longPressEffect.actionType)

        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS)
        assertThat(action).isEqualTo(QSLongPressEffect.ActionType.REVERSE_ANIMATOR)
    }
}
