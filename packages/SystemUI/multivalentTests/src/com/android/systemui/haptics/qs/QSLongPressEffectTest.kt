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
import android.service.quicksettings.Tile
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.classifier.falsingManager
import com.android.systemui.haptics.fakeVibratorHelper
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class QSLongPressEffectTest : SysuiTestCase() {

    @Rule @JvmField val mMockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos()
    private val vibratorHelper = kosmos.fakeVibratorHelper
    private val qsTile = kosmos.qsTileFactory.createTile("Test Tile")
    @Mock private lateinit var callback: QSLongPressEffect.Callback
    @Mock private lateinit var controller: ActivityTransitionAnimator.Controller

    private val effectDuration = 400
    private val lowTickDuration = 12
    private val spinDuration = 133

    private lateinit var longPressEffect: QSLongPressEffect

    @Before
    fun setup() {
        vibratorHelper.primitiveDurations[VibrationEffect.Composition.PRIMITIVE_LOW_TICK] =
            lowTickDuration
        vibratorHelper.primitiveDurations[VibrationEffect.Composition.PRIMITIVE_SPIN] = spinDuration

        whenever(kosmos.keyguardStateController.isUnlocked).thenReturn(true)
        kosmos.falsingManager.setFalseLongTap(false)

        longPressEffect =
            QSLongPressEffect(
                vibratorHelper,
                kosmos.keyguardStateController,
                kosmos.falsingManager,
                FakeLogBuffer.Factory.create(),
            )
        longPressEffect.callback = callback
        longPressEffect.qsTile = qsTile
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
    fun onActionDown_whileClicked_startsWait() =
        testWhileInState(QSLongPressEffect.State.CLICKED) {
            // GIVEN an action down event occurs
            longPressEffect.handleActionDown()

            // THEN the effect moves to the TIMEOUT_WAIT state
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.TIMEOUT_WAIT)
        }

    @Test
    fun onActionDown_whileLongClicked_startsWait() =
        testWhileInState(QSLongPressEffect.State.LONG_CLICKED) {
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
    fun onWaitComplete_whileWaiting_beginsEffect() =
        testWhileInState(QSLongPressEffect.State.TIMEOUT_WAIT) {
            // GIVEN the pressed timeout is complete
            longPressEffect.handleTimeoutComplete()

            // THEN the effect emits the action to start an animator
            verify(callback, times(1)).onStartAnimator()
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
            assertEffectReverses(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_UP)
        }

    @Test
    fun onPlayReverseHaptics_reverseHapticsArePlayed() = testWithScope {
        // GIVEN a call to play reverse haptics at the effect midpoint
        val progress = 0.5f
        longPressEffect.playReverseHaptics(progress)

        // THEN the expected texture is played
        val reverseHaptics =
            LongPressHapticBuilder.createReversedEffect(progress, lowTickDuration, effectDuration)
        assertThat(reverseHaptics).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(reverseHaptics!!)).isTrue()
    }

    @Test
    fun onActionCancel_whileEffectHasBegun_reversesEffect() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // WHEN an action cancel occurs
            longPressEffect.handleActionCancel()

            // THEN the effect gets reversed
            assertEffectReverses(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL)
        }

    @Test
    fun onAnimationComplete_keyguardDismissible_effectEndsInLongClicked() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the long-press effect completes with a long-click state
            assertEffectCompleted(QSLongPressEffect.State.LONG_CLICKED)
        }

    @Test
    fun onAnimationComplete_keyguardNotDismissible_effectEndsInIdleWithReset() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN that the keyguard is not dismissible
            whenever(kosmos.keyguardStateController.isUnlocked).thenReturn(false)

            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the long-press effect ends in the idle state and the properties are reset
            assertEffectCompleted(QSLongPressEffect.State.IDLE)
            verify(callback, times(1)).onResetProperties()
        }

    @Test
    fun onAnimationComplete_isFalseLongClick_effectEndsInIdleWithReset() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN that the long-click is false
            kosmos.falsingManager.setFalseLongTap(true)

            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the long-press effect ends in the idle state and the properties are reset
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
            verify(callback, times(1)).onResetProperties()
        }

    @Test
    fun onAnimationComplete_whenRunningBackwardsFromUp_endsWithFinishedReversingAndClick() =
        testWhileInState(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_UP) {
            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the callback for finished reversing is used and the effect ends with a click.
            verify(callback, times(1)).onEffectFinishedReversing()
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.CLICKED)
        }

    @Test
    fun onAnimationComplete_whenRunningBackwardsFromCancel_endsInIdle() =
        testWhileInState(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL) {
            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the effect ends in the idle state and the reversed callback is used.
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
            verify(callback, times(1)).onEffectFinishedReversing()
        }

    @Test
    fun onActionDown_whileRunningBackwards_cancels() =
        testWhileInState(QSLongPressEffect.State.RUNNING_FORWARD) {
            // GIVEN an action cancel occurs and the effect gets reversed
            longPressEffect.handleActionCancel()

            // GIVEN an action down occurs
            longPressEffect.handleActionDown()

            // THEN the effect posts an action to cancel the animator
            verify(callback, times(1)).onCancelAnimator()
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
    fun onAnimationComplete_whileRunningBackwardsFromCancel_goesToIdle() =
        testWhileInState(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL) {
            // GIVEN that the animation completes
            longPressEffect.handleAnimationComplete()

            // THEN the state goes to [QSLongPressEffect.State.IDLE]
            assertThat(longPressEffect.state).isEqualTo(QSLongPressEffect.State.IDLE)
        }

    @Test
    fun onTileClick_whileWaiting_withQSTile_clicks() =
        testWhileInState(QSLongPressEffect.State.TIMEOUT_WAIT) {
            // GIVEN that a click was detected
            val couldClick = longPressEffect.onTileClick()

            // THEN the click is successful
            assertThat(couldClick).isTrue()
        }

    @Test
    fun onTileClick_whileIdle_withQSTile_clicks() =
        testWhileInState(QSLongPressEffect.State.IDLE) {
            // GIVEN that a click was detected
            val couldClick = longPressEffect.onTileClick()

            // THEN the click is successful
            assertThat(couldClick).isTrue()
        }

    @Test
    fun onTileClick_whenBouncerIsShowing_ignoresClick() =
        testWhileInState(QSLongPressEffect.State.IDLE) {
            // GIVEN that the bouncer is showing
            whenever(kosmos.keyguardStateController.isPrimaryBouncerShowing).thenReturn(true)

            // WHEN a click is detected by the tile view
            val couldClick = longPressEffect.onTileClick()

            // THEN the click is not successful
            assertThat(couldClick).isFalse()
        }

    @Test
    fun getStateForClick_withUnavailableTile_returnsIdle() {
        // GIVEN an unavailable tile
        qsTile.state?.state = Tile.STATE_UNAVAILABLE

        // WHEN determining the state of a click action
        val clickState = longPressEffect.getStateForClick()

        // THEN the state is IDLE
        assertThat(clickState).isEqualTo(QSLongPressEffect.State.IDLE)
    }

    @Test
    fun getStateForClick_whenKeyguardsIsShowing_returnsIdle() {
        // GIVEN an active tile
        qsTile.state?.state = Tile.STATE_ACTIVE

        // GIVEN that the keyguard is showing
        whenever(kosmos.keyguardStateController.isShowing).thenReturn(true)

        // WHEN determining the state of a click action
        val clickState = longPressEffect.getStateForClick()

        // THEN the state is IDLE
        assertThat(clickState).isEqualTo(QSLongPressEffect.State.IDLE)
    }

    @Test
    fun getStateForClick_withValidTapAndTile_returnsClicked() {
        // GIVEN an active tile
        qsTile.state?.state = Tile.STATE_ACTIVE

        // GIVEN that the keyguard is not showing
        whenever(kosmos.keyguardStateController.isShowing).thenReturn(false)

        // WHEN determining the state of a click action
        val clickState = longPressEffect.getStateForClick()

        // THEN the state is CLICKED
        assertThat(clickState).isEqualTo(QSLongPressEffect.State.CLICKED)
    }

    @Test
    fun getStateForClick_withNullTile_returnsIdle() {
        // GIVEN that the tile is null
        longPressEffect.qsTile = null

        // GIVEN that the keyguard is not showing
        whenever(kosmos.keyguardStateController.isShowing).thenReturn(false)

        // WHEN determining the state of a click action
        val clickState = longPressEffect.getStateForClick()

        // THEN the state is IDLE
        assertThat(clickState).isEqualTo(QSLongPressEffect.State.IDLE)
    }

    @Test
    fun onLongClickTransitionCancelled_whileInLongClickState_reversesEffect() =
        testWhileInState(QSLongPressEffect.State.LONG_CLICKED) {
            // GIVEN a transition controller delegate
            val delegate = longPressEffect.createTransitionControllerDelegate(controller)

            // WHEN the activity launch animation is cancelled
            val newOccludedState = false
            delegate.onTransitionAnimationCancelled(newOccludedState)

            // THEN the effect reverses and ends in RUNNING_BACKWARDS_FROM_CANCEL
            assertThat(longPressEffect.state)
                .isEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL)
            verify(callback, times(1)).onReverseAnimator(false)
            verify(controller).onTransitionAnimationCancelled(newOccludedState)
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
     * 2. The internal state is not [QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_UP] or
     *    [QSLongPressEffect.State.RUNNING_FORWARD] or
     *    [QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL]
     */
    private fun assertEffectDidNotStart() {
        assertThat(longPressEffect.state).isNotEqualTo(QSLongPressEffect.State.RUNNING_FORWARD)
        assertThat(longPressEffect.state)
            .isNotEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_UP)
        assertThat(longPressEffect.state)
            .isNotEqualTo(QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL)
        assertThat(vibratorHelper.totalVibrations).isEqualTo(0)
    }

    /**
     * Asserts that the effect completes by checking that:
     * 1. The final snap haptics are played
     * 2. The internal state goes back to specified end state.
     */
    private fun assertEffectCompleted(endState: QSLongPressEffect.State) {
        val snapEffect = LongPressHapticBuilder.createSnapEffect()

        assertThat(snapEffect).isNotNull()
        assertThat(vibratorHelper.hasVibratedWithEffects(snapEffect!!)).isTrue()
        assertThat(longPressEffect.state).isEqualTo(endState)
    }

    /**
     * Assert that the effect gets reverted by checking that the callback to reverse the animator is
     * used, and that the state is given reversing state.
     *
     * @param[reversingState] Either [QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL] or
     *   [QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_UP]
     */
    private fun assertEffectReverses(reversingState: QSLongPressEffect.State) {
        assertThat(longPressEffect.state).isEqualTo(reversingState)
        verify(callback, times(1)).onReverseAnimator()
    }
}
