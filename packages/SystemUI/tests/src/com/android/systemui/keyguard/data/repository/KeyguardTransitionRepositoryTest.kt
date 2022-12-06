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

import android.animation.AnimationHandler.AnimationFrameCallbackProvider
import android.animation.ValueAnimator
import android.util.Log
import android.util.Log.TerribleFailure
import android.util.Log.TerribleFailureHandler
import android.view.Choreographer.FrameCallback
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.fail
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

    @Before
    fun setUp() {
        underTest = KeyguardTransitionRepositoryImpl()
        wtfHandler = WtfHandler()
        oldWtfHandler = Log.setWtfHandler(wtfHandler)
    }

    @After
    fun tearDown() {
        oldWtfHandler?.let { Log.setWtfHandler(it) }
    }

    @Test
    fun `startTransition runs animator to completion`() =
        runBlocking(IMMEDIATE) {
            val (animator, provider) = setupAnimator(this)

            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)

            underTest.startTransition(TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator))

            val startTime = System.currentTimeMillis()
            while (animator.isRunning()) {
                yield()
                if (System.currentTimeMillis() - startTime > MAX_TEST_DURATION) {
                    fail("Failed test due to excessive runtime of: $MAX_TEST_DURATION")
                }
            }

            assertSteps(steps, listWithStep(BigDecimal(.1)), AOD, LOCKSCREEN)

            job.cancel()
            provider.stop()
        }

    @Test
    @FlakyTest(bugId = 260213291)
    fun `starting second transition will cancel the first transition`() {
        runBlocking(IMMEDIATE) {
            val (animator, provider) = setupAnimator(this)

            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)

            underTest.startTransition(TransitionInfo(OWNER_NAME, AOD, LOCKSCREEN, animator))
            // 3 yields(), alternating with the animator, results in a value 0.1, which can be
            // canceled and tested against
            yield()
            yield()
            yield()

            // Now start 2nd transition, which will interrupt the first
            val job2 = underTest.transition(LOCKSCREEN, AOD).onEach { steps.add(it) }.launchIn(this)
            val (animator2, provider2) = setupAnimator(this)
            underTest.startTransition(TransitionInfo(OWNER_NAME, LOCKSCREEN, AOD, animator2))

            val startTime = System.currentTimeMillis()
            while (animator2.isRunning()) {
                yield()
                if (System.currentTimeMillis() - startTime > MAX_TEST_DURATION) {
                    fail("Failed test due to excessive runtime of: $MAX_TEST_DURATION")
                }
            }

            val firstTransitionSteps = listWithStep(step = BigDecimal(.1), stop = BigDecimal(.1))
            assertSteps(steps.subList(0, 4), firstTransitionSteps, AOD, LOCKSCREEN)

            val secondTransitionSteps = listWithStep(step = BigDecimal(.1), start = BigDecimal(.9))
            assertSteps(steps.subList(4, steps.size), secondTransitionSteps, LOCKSCREEN, AOD)

            job.cancel()
            job2.cancel()
            provider.stop()
            provider2.stop()
        }
    }

    @Test
    fun `Null animator enables manual control with updateTransition`() =
        runBlocking(IMMEDIATE) {
            val steps = mutableListOf<TransitionStep>()
            val job = underTest.transition(AOD, LOCKSCREEN).onEach { steps.add(it) }.launchIn(this)

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
                underTest.updateTransition(it, 0.5f, TransitionState.RUNNING)
                underTest.updateTransition(it, 1f, TransitionState.FINISHED)
            }

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
            assertThat(steps[index + 1])
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

    private fun setupAnimator(
        scope: CoroutineScope
    ): Pair<ValueAnimator, TestFrameCallbackProvider> {
        val animator =
            ValueAnimator().apply {
                setInterpolator(Interpolators.LINEAR)
                setDuration(ANIMATION_DURATION)
            }

        val provider = TestFrameCallbackProvider(animator, scope)
        provider.start()

        return Pair(animator, provider)
    }

    /** Gives direct control over ValueAnimator. See [AnimationHandler] */
    private class TestFrameCallbackProvider(
        private val animator: ValueAnimator,
        private val scope: CoroutineScope,
    ) : AnimationFrameCallbackProvider {

        private var frameCount = 1L
        private var frames = MutableStateFlow(Pair<Long, FrameCallback?>(0L, null))
        private var job: Job? = null

        fun start() {
            animator.getAnimationHandler().setProvider(this)

            job =
                scope.launch {
                    frames.collect {
                        // Delay is required for AnimationHandler to properly register a callback
                        yield()
                        val (frameNumber, callback) = it
                        callback?.doFrame(frameNumber)
                    }
                }
        }

        fun stop() {
            job?.cancel()
            animator.getAnimationHandler().setProvider(null)
        }

        override fun postFrameCallback(cb: FrameCallback) {
            frames.value = Pair(frameCount++, cb)
        }
        override fun postCommitCallback(runnable: Runnable) {}
        override fun getFrameTime() = frameCount
        override fun getFrameDelay() = 1L
        override fun setFrameDelay(delay: Long) {}
    }

    private class WtfHandler : TerribleFailureHandler {
        var failed = false
        override fun onTerribleFailure(tag: String, what: TerribleFailure, system: Boolean) {
            failed = true
        }
    }

    companion object {
        private const val MAX_TEST_DURATION = 100L
        private const val ANIMATION_DURATION = 10L
        private const val OWNER_NAME = "Test"
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
