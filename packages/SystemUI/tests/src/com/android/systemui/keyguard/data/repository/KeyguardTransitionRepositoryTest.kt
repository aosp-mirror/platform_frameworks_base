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
import android.util.Log
import android.util.Log.TerribleFailure
import android.util.Log.TerribleFailureHandler
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.KeyguardTransitionRunner
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class KeyguardTransitionRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardTransitionRepository
    private lateinit var oldWtfHandler: TerribleFailureHandler
    private lateinit var wtfHandler: WtfHandler
    private lateinit var runner: KeyguardTransitionRunner

    @Before
    fun setUp() {
        underTest = KeyguardTransitionRepositoryImpl()
        wtfHandler = WtfHandler()
        oldWtfHandler = Log.setWtfHandler(wtfHandler)
        runner = KeyguardTransitionRunner(underTest)
    }

    @After
    fun tearDown() {
        oldWtfHandler?.let { Log.setWtfHandler(it) }
    }

    @Test
    fun `startTransition runs animator to completion`() =
        TestScope().runTest {
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
    fun `starting second transition will cancel the first transition`() =
        TestScope().runTest {
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
                TransitionInfo(OWNER_NAME, LOCKSCREEN, AOD, getAnimator()),
            )

            val firstTransitionSteps = listWithStep(step = BigDecimal(.1), stop = BigDecimal(.1))
            assertSteps(steps.subList(0, 4), firstTransitionSteps, AOD, LOCKSCREEN)

            val secondTransitionSteps = listWithStep(step = BigDecimal(.1), start = BigDecimal(.1))
            assertSteps(steps.subList(4, steps.size), secondTransitionSteps, LOCKSCREEN, AOD)

            job.cancel()
            job2.cancel()
        }

    @Test
    fun `Null animator enables manual control with updateTransition`() =
        TestScope().runTest {
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
    fun `Attempt to  manually update transition with invalid UUID throws exception`() {
        underTest.updateTransition(UUID.randomUUID(), 0f, TransitionState.RUNNING)
        assertThat(wtfHandler.failed).isTrue()
    }

    @Test
    fun `Attempt to manually update transition after FINISHED state throws exception`() {
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
        assertThat(wtfHandler.failed).isTrue()
    }

    @Test
    fun `Attempt to manually update transition after CANCELED state throws exception`() {
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
        assertThat(wtfHandler.failed).isTrue()
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

        assertThat(wtfHandler.failed).isFalse()
    }

    private fun getAnimator(): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(10)
        }
    }

    private class WtfHandler : TerribleFailureHandler {
        var failed = false
        override fun onTerribleFailure(tag: String, what: TerribleFailure, system: Boolean) {
            failed = true
        }
    }

    companion object {
        private const val OWNER_NAME = "KeyguardTransitionRunner"
    }
}
