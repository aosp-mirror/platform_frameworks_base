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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class KeyguardTransitionInteractorTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardTransitionInteractor
    private lateinit var repository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        underTest = KeyguardTransitionInteractor(repository)
    }

    @Test
    fun `transition collectors receives only appropriate events`() =
        runBlocking(IMMEDIATE) {
            var lockscreenToAodSteps = mutableListOf<TransitionStep>()
            val job1 =
                underTest.lockscreenToAodTransition
                    .onEach { lockscreenToAodSteps.add(it) }
                    .launchIn(this)

            var aodToLockscreenSteps = mutableListOf<TransitionStep>()
            val job2 =
                underTest.aodToLockscreenTransition
                    .onEach { aodToLockscreenSteps.add(it) }
                    .launchIn(this)

            val steps = mutableListOf<TransitionStep>()
            steps.add(TransitionStep(AOD, GONE, 0f, STARTED))
            steps.add(TransitionStep(AOD, GONE, 1f, FINISHED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.1f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.2f, RUNNING))

            steps.forEach { repository.sendTransitionStep(it) }

            assertThat(aodToLockscreenSteps).isEqualTo(steps.subList(2, 5))
            assertThat(lockscreenToAodSteps).isEqualTo(steps.subList(5, 8))

            job1.cancel()
            job2.cancel()
        }

    @Test
    fun dozeAmountTransitionTest() =
        runBlocking(IMMEDIATE) {
            var dozeAmountSteps = mutableListOf<TransitionStep>()
            val job =
                underTest.dozeAmountTransition.onEach { dozeAmountSteps.add(it) }.launchIn(this)

            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.8f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))

            steps.forEach { repository.sendTransitionStep(it) }

            assertThat(dozeAmountSteps.subList(0, 3))
                .isEqualTo(
                    listOf(
                        steps[0].copy(value = 1f - steps[0].value),
                        steps[1].copy(value = 1f - steps[1].value),
                        steps[2].copy(value = 1f - steps[2].value),
                    )
                )
            assertThat(dozeAmountSteps.subList(3, 7)).isEqualTo(steps.subList(3, 7))

            job.cancel()
        }

    @Test
    fun keyguardStateTests() =
        runBlocking(IMMEDIATE) {
            var finishedSteps = mutableListOf<KeyguardState>()
            val job =
                underTest.finishedKeyguardState.onEach { finishedSteps.add(it) }.launchIn(this)

            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
            steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

            steps.forEach { repository.sendTransitionStep(it) }

            assertThat(finishedSteps).isEqualTo(listOf(LOCKSCREEN, AOD))

            job.cancel()
        }

    @Test
    fun finishedKeyguardTransitionStepTests() =
        runBlocking(IMMEDIATE) {
            var finishedSteps = mutableListOf<TransitionStep>()
            val job =
                underTest.finishedKeyguardTransitionStep
                    .onEach { finishedSteps.add(it) }
                    .launchIn(this)

            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
            steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

            steps.forEach { repository.sendTransitionStep(it) }

            assertThat(finishedSteps).isEqualTo(listOf(steps[2], steps[5]))

            job.cancel()
        }

    @Test
    fun startedKeyguardTransitionStepTests() =
        runBlocking(IMMEDIATE) {
            var startedSteps = mutableListOf<TransitionStep>()
            val job =
                underTest.startedKeyguardTransitionStep
                    .onEach { startedSteps.add(it) }
                    .launchIn(this)

            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
            steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

            steps.forEach { repository.sendTransitionStep(it) }

            assertThat(startedSteps).isEqualTo(listOf(steps[0], steps[3], steps[6]))

            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
