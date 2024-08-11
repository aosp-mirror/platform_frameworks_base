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

package com.android.systemui.keyguard.data.repository

import android.animation.ValueAnimator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import com.android.app.animation.Interpolators
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.KeyguardTransitionRunner
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@FlakyTest(bugId = 270760395)
class KeyguardTransitionRepositoryTest : SysuiTestCase() {
    val kosmos = testKosmos()

    private val testScope = kosmos.testScope

    private lateinit var underTest: KeyguardTransitionRepository
    private lateinit var runner: KeyguardTransitionRunner

    @Before
    fun setUp() {
        underTest = KeyguardTransitionRepositoryImpl(Dispatchers.Main)
        runner = KeyguardTransitionRunner(underTest)
    }

    @Test
    fun startTransitionRunsAnimatorToCompletion() =
        testScope.runTest {
            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)

            runner.startTransition(
                this,
                TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, getAnimator()),
                maxFrames = 100
            )

            assertSteps(steps, listWithStep(BigDecimal(.1)), AOD, LOCKSCREEN)
            job.cancel()
        }

    @Test
    fun startingSecondTransitionWillCancelTheFirstTransitionAndUseLastValue() =
        testScope.runTest {
            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)
            runner.startTransition(
                this,
                TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, getAnimator()),
                maxFrames = 3,
            )

            // Now start 2nd transition, which will interrupt the first
            val job2 = underTest.transition(LOCKSCREEN, AOD).onEach { steps.add(it) }.launchIn(this)
            runner.startTransition(
                this,
                TransitionInfo(
                    OWNER_NAME,
                    LOCKSCREEN,
                    AOD,
                    getAnimator(),
                    TransitionModeOnCanceled.LAST_VALUE
                ),
            )

            val firstTransitionSteps = listWithStep(step = BigDecimal(.1), stop = BigDecimal(.1))
            assertSteps(steps.subList(0, 4), firstTransitionSteps, AOD, LOCKSCREEN)

            // Second transition starts from .1 (LAST_VALUE)
            val secondTransitionSteps = listWithStep(step = BigDecimal(.1), start = BigDecimal(.1))
            assertSteps(steps.subList(4, steps.size), secondTransitionSteps, LOCKSCREEN, AOD)

            job.cancel()
            job2.cancel()
        }

    @Test
    fun startingSecondTransitionWillCancelTheFirstTransitionAndUseReset() =
        testScope.runTest {
            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)
            runner.startTransition(
                this,
                TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, getAnimator()),
                maxFrames = 3,
            )

            // Now start 2nd transition, which will interrupt the first
            val job2 = underTest.transition(LOCKSCREEN, AOD).onEach { steps.add(it) }.launchIn(this)
            runner.startTransition(
                this,
                TransitionInfo(
                    OWNER_NAME,
                    LOCKSCREEN,
                    AOD,
                    getAnimator(),
                    TransitionModeOnCanceled.RESET
                ),
            )

            val firstTransitionSteps = listWithStep(step = BigDecimal(.1), stop = BigDecimal(.1))
            assertSteps(steps.subList(0, 4), firstTransitionSteps, AOD, LOCKSCREEN)

            // Second transition starts from 0 (RESET)
            val secondTransitionSteps = listWithStep(start = BigDecimal(0), step = BigDecimal(.1))
            assertSteps(steps.subList(4, steps.size), secondTransitionSteps, LOCKSCREEN, AOD)

            job.cancel()
            job2.cancel()
        }

    @Test
    fun startingSecondTransitionWillCancelTheFirstTransitionAndUseReverse() =
        testScope.runTest {
            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)
            runner.startTransition(
                this,
                TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, getAnimator()),
                maxFrames = 3,
            )

            // Now start 2nd transition, which will interrupt the first
            val job2 = underTest.transition(LOCKSCREEN, AOD).onEach { steps.add(it) }.launchIn(this)
            runner.startTransition(
                this,
                TransitionInfo(
                    OWNER_NAME,
                    LOCKSCREEN,
                    AOD,
                    getAnimator(),
                    TransitionModeOnCanceled.REVERSE
                ),
            )

            val firstTransitionSteps = listWithStep(step = BigDecimal(.1), stop = BigDecimal(.1))
            assertSteps(steps.subList(0, 4), firstTransitionSteps, AOD, LOCKSCREEN)

            // Second transition starts from .9 (REVERSE)
            val secondTransitionSteps = listWithStep(start = BigDecimal(0.9), step = BigDecimal(.1))
            assertSteps(steps.subList(4, steps.size), secondTransitionSteps, LOCKSCREEN, AOD)

            job.cancel()
            job2.cancel()
        }

    @Test
    fun nullAnimatorEnablesManualControlWithUpdateTransition() =
        testScope.runTest {
            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)

            val uuid =
                underTest.startTransition(
                    TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator = null)
                )
            runCurrent()

            checkNotNull(uuid).let {
                underTest.updateTransition(it, 0.5f, TransitionState.RUNNING)
                underTest.updateTransition(it, 1f, TransitionState.FINISHED)
            }
            runCurrent()

            assertThat(steps.size).isEqualTo(3)
            assertThat(steps[0])
                .isEqualTo(TransitionStep(AOD, LOCKSCREEN, 0f, TransitionState.STARTED, OWNER_NAME))
            assertThat(steps[1])
                .isEqualTo(
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, TransitionState.RUNNING, OWNER_NAME)
                )
            assertThat(steps[2])
                .isEqualTo(
                    TransitionStep(AOD, LOCKSCREEN, 1f, TransitionState.FINISHED, OWNER_NAME)
                )
            job.cancel()
        }

    @Test
    fun startingSecondManualTransitionWillCancelPreviousManualTransition() =
        testScope.runTest {
            // Drop initial steps from OFF which are sent in the constructor
            val steps = mutableListOf<TransitionStep>()
            val job =
                underTest.transitions
                    .dropWhile { step -> step.from == OFF }
                    .onEach { steps.add(it) }
                    .launchIn(this)

            val firstUuid =
                underTest.startTransition(
                    TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator = null)
                )
            runCurrent()

            checkNotNull(firstUuid)
            underTest.updateTransition(firstUuid, 0.5f, TransitionState.RUNNING)
            runCurrent()

            val secondUuid =
                underTest.startTransition(
                    TransitionInfo(OWNER_NAME, AOD, DREAMING, animator = null)
                )
            runCurrent()

            checkNotNull(secondUuid)
            underTest.updateTransition(secondUuid, 0.7f, TransitionState.RUNNING)
            // Trying to transition the old uuid should be ignored.
            underTest.updateTransition(firstUuid, 0.6f, TransitionState.RUNNING)
            runCurrent()

            assertThat(steps)
                .containsExactly(
                    TransitionStep(AOD, LOCKSCREEN, 0f, TransitionState.STARTED, OWNER_NAME),
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, TransitionState.RUNNING, OWNER_NAME),
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, TransitionState.CANCELED, OWNER_NAME),
                    TransitionStep(AOD, DREAMING, 0.5f, TransitionState.STARTED, OWNER_NAME),
                    TransitionStep(AOD, DREAMING, 0.7f, TransitionState.RUNNING, OWNER_NAME),
                )
                .inOrder()

            job.cancel()
        }

    @Test
    fun startingSecondTransitionWillCancelPreviousManualTransition() =
        testScope.runTest {
            // Drop initial steps from OFF which are sent in the constructor
            val steps = mutableListOf<TransitionStep>()
            val job =
                underTest.transitions
                    .dropWhile { step -> step.from == OFF }
                    .onEach { steps.add(it) }
                    .launchIn(this)

            val uuid =
                underTest.startTransition(
                    TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator = null)
                )
            runCurrent()

            checkNotNull(uuid)
            underTest.updateTransition(uuid, 0.5f, TransitionState.RUNNING)
            runCurrent()

            // Start new transition to dreaming, should cancel previous one.
            runner.startTransition(
                this,
                TransitionInfo(
                    OWNER_NAME,
                    AOD,
                    DREAMING,
                    getAnimator(),
                    TransitionModeOnCanceled.RESET,
                ),
            )
            runCurrent()

            // Trying to transition the old uuid should be ignored.
            underTest.updateTransition(uuid, 0.6f, TransitionState.RUNNING)
            runCurrent()

            assertThat(steps.take(3))
                .containsExactly(
                    TransitionStep(AOD, LOCKSCREEN, 0f, TransitionState.STARTED, OWNER_NAME),
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, TransitionState.RUNNING, OWNER_NAME),
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, TransitionState.CANCELED, OWNER_NAME),
                )
                .inOrder()

            job.cancel()
        }

    @Test
    fun attemptTomanuallyUpdateTransitionWithInvalidUUIDEmitsNothing() =
        testScope.runTest {
            val steps by collectValues(underTest.transitions.dropWhile { step -> step.from == OFF })
            underTest.updateTransition(UUID.randomUUID(), 0f, TransitionState.RUNNING)
            assertThat(steps.size).isEqualTo(0)
        }

    @Test
    fun attemptToManuallyUpdateTransitionAfterFINISHEDstateEmitsNothing() =
        testScope.runTest {
            val steps by collectValues(underTest.transitions.dropWhile { step -> step.from == OFF })
            val uuid =
                underTest.startTransition(
                    TransitionInfo(
                        ownerName = OWNER_NAME,
                        from = AOD,
                        to = LOCKSCREEN,
                        animator = null,
                    )
                )

            checkNotNull(uuid).let {
                underTest.updateTransition(it, 1f, TransitionState.FINISHED)
                underTest.updateTransition(it, 0.5f, TransitionState.RUNNING)
            }
            assertThat(steps.size).isEqualTo(2)
            assertThat(steps[0])
                .isEqualTo(TransitionStep(AOD, LOCKSCREEN, 0f, TransitionState.STARTED, OWNER_NAME))
            assertThat(steps[1])
                .isEqualTo(
                    TransitionStep(AOD, LOCKSCREEN, 1f, TransitionState.FINISHED, OWNER_NAME)
                )
        }

    @Test
    fun attemptToManuallyUpdateTransitionAfterCANCELEDstateEmitsNothing() =
        testScope.runTest {
            val steps by collectValues(underTest.transitions.dropWhile { step -> step.from == OFF })
            val uuid =
                underTest.startTransition(
                    TransitionInfo(
                        ownerName = OWNER_NAME,
                        from = AOD,
                        to = LOCKSCREEN,
                        animator = null,
                    )
                )

            checkNotNull(uuid).let {
                underTest.updateTransition(it, 0.2f, TransitionState.CANCELED)
                underTest.updateTransition(it, 0.5f, TransitionState.RUNNING)
            }
            assertThat(steps.size).isEqualTo(2)
            assertThat(steps[0])
                .isEqualTo(TransitionStep(AOD, LOCKSCREEN, 0f, TransitionState.STARTED, OWNER_NAME))
            assertThat(steps[1])
                .isEqualTo(
                    TransitionStep(AOD, LOCKSCREEN, 0.2f, TransitionState.CANCELED, OWNER_NAME)
                )
        }

    @Test
    fun simulateRaceConditionIsProcessedInOrder() =
        testScope.runTest {
            val ktr = KeyguardTransitionRepositoryImpl(kosmos.testDispatcher)
            val steps by collectValues(ktr.transitions.dropWhile { step -> step.from == OFF })

            // Add a delay to the first transition in order to attempt to have the second transition
            // be processed first
            val info1 = TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator = null)
            launch {
                ktr.forceDelayForRaceConditionTest = true
                ktr.startTransition(info1)
            }
            val info2 = TransitionInfo(OWNER_NAME, LOCKSCREEN, OCCLUDED, animator = null)
            launch {
                ktr.forceDelayForRaceConditionTest = false
                ktr.startTransition(info2)
            }

            runCurrent()
            assertThat(steps.isEmpty()).isTrue()

            advanceTimeBy(60L)
            assertThat(steps[0])
                .isEqualTo(
                    TransitionStep(info1.from, info1.to, 0f, TransitionState.STARTED, OWNER_NAME)
                )
            assertThat(steps[1])
                .isEqualTo(
                    TransitionStep(info1.from, info1.to, 0f, TransitionState.CANCELED, OWNER_NAME)
                )
            assertThat(steps[2])
                .isEqualTo(
                    TransitionStep(info2.from, info2.to, 0f, TransitionState.STARTED, OWNER_NAME)
                )
        }

    @Test
    fun simulateRaceConditionIsProcessedInOrderUsingUpdateTransition() =
        testScope.runTest {
            val ktr = KeyguardTransitionRepositoryImpl(kosmos.testDispatcher)
            val steps by collectValues(ktr.transitions.dropWhile { step -> step.from == OFF })

            // Begin a manual transition
            val info1 = TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator = null)
            launch {
                ktr.forceDelayForRaceConditionTest = false
                val uuid = ktr.startTransition(info1)

                // Pause here to allow another transition to start
                delay(20)

                // Attempt to send an update, which should fail
                ktr.updateTransition(uuid!!, 0.5f, TransitionState.RUNNING)
            }

            // Now start another transition, which should acquire the preempt the first
            val info2 = TransitionInfo(OWNER_NAME, LOCKSCREEN, OCCLUDED, animator = null)
            launch {
                delay(10)
                ktr.forceDelayForRaceConditionTest = true
                ktr.startTransition(info2)
            }

            runCurrent()

            // Manual transition has started
            assertThat(steps[0])
                .isEqualTo(
                    TransitionStep(info1.from, info1.to, 0f, TransitionState.STARTED, OWNER_NAME)
                )

            // The second transition has requested to start, and grabbed the mutex. But it is
            // delayed
            advanceTimeBy(15L)

            // Advancing another 10ms should now trigger the first transition to request an update,
            // which should not happen as the second transition has the mutex
            advanceTimeBy(10L)

            // Finally, advance past the delay in the second transition so it can run
            advanceTimeBy(50L)

            assertThat(steps[1])
                .isEqualTo(
                    TransitionStep(info1.from, info1.to, 0f, TransitionState.CANCELED, OWNER_NAME)
                )
            assertThat(steps[2])
                .isEqualTo(
                    TransitionStep(info2.from, info2.to, 0f, TransitionState.STARTED, OWNER_NAME)
                )

            assertThat(steps.size).isEqualTo(3)
        }

    private fun listWithStep(
        step: BigDecimal,
        start: BigDecimal = BigDecimal.ZERO,
        stop: BigDecimal = BigDecimal.ONE,
    ): List<BigDecimal> {
        val steps = mutableListOf<BigDecimal>()

        var i = start
        while (i.compareTo(stop) <= 0) {
            steps.add(i)
            i = (i + step).setScale(2, RoundingMode.HALF_UP)
        }

        return steps
    }

    private fun assertSteps(
        steps: List<TransitionStep>,
        fractions: List<BigDecimal>,
        from: KeyguardState,
        to: KeyguardState,
    ) {
        assertThat(steps[0])
            .isEqualTo(
                TransitionStep(
                    from,
                    to,
                    fractions[0].toFloat(),
                    TransitionState.STARTED,
                    OWNER_NAME
                )
            )
        fractions.forEachIndexed { index, fraction ->
            val step = steps[index + 1]
            val truncatedValue =
                BigDecimal(step.value.toDouble()).setScale(2, RoundingMode.HALF_UP).toFloat()
            assertThat(step.copy(value = truncatedValue))
                .isEqualTo(
                    TransitionStep(
                        from,
                        to,
                        fraction.toFloat(),
                        TransitionState.RUNNING,
                        OWNER_NAME
                    )
                )
        }
        val lastValue = fractions[fractions.size - 1].toFloat()
        val status =
            if (lastValue < 1f) {
                TransitionState.CANCELED
            } else {
                TransitionState.FINISHED
            }
        assertThat(steps[steps.size - 1])
            .isEqualTo(TransitionStep(from, to, lastValue, status, OWNER_NAME))
    }

    private fun getAnimator(): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(10)
        }
    }

    companion object {
        private const val OWNER_NAME = "KeyguardTransitionRunner"
    }
}
