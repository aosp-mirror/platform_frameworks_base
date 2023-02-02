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

package com.android.systemui.keyguard.ui

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class KeyguardTransitionAnimationFlowTest : SysuiTestCase() {
    private lateinit var underTest: KeyguardTransitionAnimationFlow
    private lateinit var repository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        underTest =
            KeyguardTransitionAnimationFlow(
                1000.milliseconds,
                repository.transitions,
            )
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDurationThrowsException() = runTest {
        val flow = underTest.createFlow(duration = 0.milliseconds, onStep = { it })
    }

    @Test(expected = IllegalArgumentException::class)
    fun startTimePlusDurationGreaterThanTransitionDurationThrowsException() = runTest {
        val flow =
            underTest.createFlow(
                startTime = 300.milliseconds,
                duration = 800.milliseconds,
                onStep = { it }
            )
    }

    @Test
    fun onFinishRunsWhenSpecified() = runTest {
        val flow =
            underTest.createFlow(
                duration = 100.milliseconds,
                onStep = { it },
                onFinish = { 10f },
            )
        var animationValues = collectLastValue(flow)
        repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
        assertThat(animationValues()).isEqualTo(10f)
    }

    @Test
    fun onCancelRunsWhenSpecified() = runTest {
        val flow =
            underTest.createFlow(
                duration = 100.milliseconds,
                onStep = { it },
                onCancel = { 100f },
            )
        var animationValues = collectLastValue(flow)
        repository.sendTransitionStep(step(0.5f, TransitionState.CANCELED))
        assertThat(animationValues()).isEqualTo(100f)
    }

    @Test
    fun usesStartTime() = runTest {
        val flow =
            underTest.createFlow(
                startTime = 500.milliseconds,
                duration = 500.milliseconds,
                onStep = { it },
            )
        var animationValues = collectLastValue(flow)
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        assertThat(animationValues()).isEqualTo(0f)

        // Should not emit a value
        repository.sendTransitionStep(step(0.1f, TransitionState.RUNNING))

        repository.sendTransitionStep(step(0.5f, TransitionState.RUNNING))
        assertFloat(animationValues(), 0f)
        repository.sendTransitionStep(step(0.6f, TransitionState.RUNNING))
        assertFloat(animationValues(), 0.2f)
        repository.sendTransitionStep(step(0.8f, TransitionState.RUNNING))
        assertFloat(animationValues(), 0.6f)
        repository.sendTransitionStep(step(1f, TransitionState.RUNNING))
        assertFloat(animationValues(), 1f)
    }

    @Test
    fun usesInterpolator() = runTest {
        val flow =
            underTest.createFlow(
                duration = 1000.milliseconds,
                interpolator = EMPHASIZED_ACCELERATE,
                onStep = { it },
            )
        var animationValues = collectLastValue(flow)
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        assertFloat(animationValues(), EMPHASIZED_ACCELERATE.getInterpolation(0f))
        repository.sendTransitionStep(step(0.5f, TransitionState.RUNNING))
        assertFloat(animationValues(), EMPHASIZED_ACCELERATE.getInterpolation(0.5f))
        repository.sendTransitionStep(step(0.6f, TransitionState.RUNNING))
        assertFloat(animationValues(), EMPHASIZED_ACCELERATE.getInterpolation(0.6f))
        repository.sendTransitionStep(step(0.8f, TransitionState.RUNNING))
        assertFloat(animationValues(), EMPHASIZED_ACCELERATE.getInterpolation(0.8f))
        repository.sendTransitionStep(step(1f, TransitionState.RUNNING))
        assertFloat(animationValues(), EMPHASIZED_ACCELERATE.getInterpolation(1f))
    }

    @Test
    fun usesOnStepToDoubleValue() = runTest {
        val flow =
            underTest.createFlow(
                duration = 1000.milliseconds,
                onStep = { it * 2 },
            )
        var animationValues = collectLastValue(flow)
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        assertFloat(animationValues(), 0f)
        repository.sendTransitionStep(step(0.3f, TransitionState.RUNNING))
        assertFloat(animationValues(), 0.6f)
        repository.sendTransitionStep(step(0.6f, TransitionState.RUNNING))
        assertFloat(animationValues(), 1.2f)
        repository.sendTransitionStep(step(0.8f, TransitionState.RUNNING))
        assertFloat(animationValues(), 1.6f)
        repository.sendTransitionStep(step(1f, TransitionState.RUNNING))
        assertFloat(animationValues(), 2f)
    }

    private fun assertFloat(actual: Float?, expected: Float) {
        assertThat(actual!!).isWithin(0.01f).of(expected)
    }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.GONE,
            to = KeyguardState.DREAMING,
            value = value,
            transitionState = state,
            ownerName = "GoneToDreamingTransitionViewModelTest"
        )
    }
}
