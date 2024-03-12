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
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWithLooper(setAsMainLooper = true)
class QSLongPressEffectTest : SysuiTestCase() {

    @Rule @JvmField val mMockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var vibratorHelper: VibratorHelper
    @Mock private lateinit var testView: View
    @get:Rule val animatorTestRule = AnimatorTestRule(this)
    private val kosmos = testKosmos()

    private val effectDuration = 400
    private val lowTickDuration = 12
    private val spinDuration = 133

    private lateinit var longPressEffect: QSLongPressEffect

    @Before
    fun setup() {
        whenever(
                vibratorHelper.getPrimitiveDurations(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_SPIN,
                )
            )
            .thenReturn(intArrayOf(lowTickDuration, spinDuration))

        longPressEffect =
            QSLongPressEffect(
                vibratorHelper,
                effectDuration,
            )
    }

    @Test
    fun onActionDown_whileIdle_startsWait() = testWithScope {
        // GIVEN an action down event occurs
        val downEvent = buildMotionEvent(MotionEvent.ACTION_DOWN)
        longPressEffect.onTouch(testView, downEvent)

        // THEN the effect moves to the TIMEOUT_WAIT state
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
    }

    @Test
    fun onActionCancel_whileWaiting_goesIdle() = testWhileWaiting {
        // GIVEN an action cancel occurs
        val cancelEvent = buildMotionEvent(MotionEvent.ACTION_CANCEL)
        longPressEffect.onTouch(testView, cancelEvent)

        // THEN the effect goes back to idle and does not start
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
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
        advanceTimeBy(QSLongPressEffect.PRESSED_TIMEOUT + 10L)

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
    fun onAnimationComplete_effectEnds() = testWhileRunning {
        // GIVEN that the animation completes
        animatorTestRule.advanceTimeBy(effectDuration + 10L)

        // THEN the long-press effect completes
        assertEffectCompleted()
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
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
    }

    private fun buildMotionEvent(action: Int): MotionEvent =
        MotionEventBuilder.newBuilder().setAction(action).build()

    private fun testWithScope(test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                // GIVEN an effect with a testing scope
                longPressEffect.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

                // THEN run the test
                test()
            }
        }

    private fun testWhileWaiting(test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                // GIVEN an effect with a testing scope
                longPressEffect.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

                // GIVEN the TIMEOUT_WAIT state is entered
                val downEvent =
                    MotionEventBuilder.newBuilder().setAction(MotionEvent.ACTION_DOWN).build()
                longPressEffect.onTouch(testView, downEvent)

                // THEN run the test
                test()
            }
        }

    private fun testWhileRunning(test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                // GIVEN an effect with a testing scope
                longPressEffect.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

                // GIVEN the down event that enters the TIMEOUT_WAIT state
                val downEvent =
                    MotionEventBuilder.newBuilder().setAction(MotionEvent.ACTION_DOWN).build()
                longPressEffect.onTouch(testView, downEvent)

                // GIVEN that the timeout completes and the effect starts
                advanceTimeBy(QSLongPressEffect.PRESSED_TIMEOUT + 10L)

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
        val longPressHint =
            LongPressHapticBuilder.createLongPressHint(
                lowTickDuration,
                spinDuration,
                effectDuration,
            )

        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(effectProgress).isEqualTo(0f)
        assertThat(longPressHint).isNotNull()
        verify(vibratorHelper).vibrate(longPressHint!!)
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

        assertThat(longPressEffect.state).isNotEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(longPressEffect.state).isNotEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS)
        assertThat(effectProgress).isNull()
        verify(vibratorHelper, never()).vibrate(any(/* type= */ VibrationEffect::class.java))
    }

    /**
     * Asserts that the effect completes by checking that:
     * 1. The progress is null
     * 2. The final snap haptics are played
     * 3. The internal state goes back to [QSLongPressEffect.State.IDLE]
     * 4. The action to perform on the tile is the long-press action
     */
    private fun TestScope.assertEffectCompleted() {
        val action by collectLastValue(longPressEffect.actionType)
        val effectProgress by collectLastValue(longPressEffect.effectProgress)
        val snapEffect = LongPressHapticBuilder.createSnapEffect()

        assertThat(effectProgress).isNull()
        assertThat(snapEffect).isNotNull()
        verify(vibratorHelper).vibrate(snapEffect!!)
        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
        assertThat(action).isEqualTo(QSLongPressEffect.ActionType.LONG_PRESS)
    }

    /**
     * Assert that the effect gets reverted by checking that:
     * 1. The internal state is [QSLongPressEffect.State.RUNNING_BACKWARDS]
     * 2. The reverse haptics plays at the point where the animation was paused
     */
    private fun assertEffectReverses(pausedProgress: Float) {
        val reverseHaptics =
            LongPressHapticBuilder.createReversedEffect(
                pausedProgress,
                lowTickDuration,
                effectDuration,
            )

        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS)
        assertThat(reverseHaptics).isNotNull()
        verify(vibratorHelper).vibrate(reverseHaptics!!)
    }

    /**
     * Asserts that the effect resets by checking that:
     * 1. The effect progress resets to 0
     * 2. The internal state goes back to [QSLongPressEffect.State.TIMEOUT_WAIT]
     */
    private fun TestScope.assertEffectResets() {
        val effectProgress by collectLastValue(longPressEffect.effectProgress)
        assertThat(effectProgress).isEqualTo(0f)

        assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
    }
}
