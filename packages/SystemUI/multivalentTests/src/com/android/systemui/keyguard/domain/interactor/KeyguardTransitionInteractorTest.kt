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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
@android.platform.test.annotations.EnabledOnRavenwood
class KeyguardTransitionInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val underTest = kosmos.keyguardTransitionInteractor
    val repository = kosmos.fakeKeyguardTransitionRepository
    val testScope = kosmos.testScope

    @Test
    fun transitionCollectorsReceivesOnlyAppropriateEvents() = runTest {
        val lockscreenToAodSteps by collectValues(underTest.lockscreenToAodTransition)
        val aodToLockscreenSteps by collectValues(underTest.aodToLockscreenTransition)

        val steps = mutableListOf<TransitionStep>()
        steps.add(TransitionStep(AOD, GONE, 0f, STARTED))
        steps.add(TransitionStep(AOD, GONE, 1f, FINISHED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.1f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.2f, RUNNING))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(aodToLockscreenSteps).isEqualTo(steps.subList(2, 5))
        assertThat(lockscreenToAodSteps).isEqualTo(steps.subList(5, 8))
    }

    @Test
    fun dozeAmountTransitionTest_AodToFromLockscreen() =
        testScope.runTest {
            val dozeAmountSteps by collectValues(underTest.dozeAmountTransition)

            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.8f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))

            steps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            assertThat(dozeAmountSteps.subList(0, 3))
                .isEqualTo(
                    listOf(
                        steps[0].copy(value = 1f - steps[0].value),
                        steps[1].copy(value = 1f - steps[1].value),
                        steps[2].copy(value = 1f - steps[2].value),
                    )
                )
            assertThat(dozeAmountSteps.subList(3, 7)).isEqualTo(steps.subList(3, 7))
        }

    @Test
    fun dozeAmountTransitionTest_AodToFromGone() =
        testScope.runTest {
            val dozeAmountSteps by collectValues(underTest.dozeAmountTransition)

            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, GONE, 0f, STARTED))
            steps.add(TransitionStep(AOD, GONE, 0.3f, RUNNING))
            steps.add(TransitionStep(AOD, GONE, 1f, FINISHED))
            steps.add(TransitionStep(GONE, AOD, 0f, STARTED))
            steps.add(TransitionStep(GONE, AOD, 0.1f, RUNNING))
            steps.add(TransitionStep(GONE, AOD, 0.3f, RUNNING))
            steps.add(TransitionStep(GONE, AOD, 1f, FINISHED))

            steps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            assertThat(dozeAmountSteps.subList(0, 3))
                .isEqualTo(
                    listOf(
                        steps[0].copy(value = 1f - steps[0].value),
                        steps[1].copy(value = 1f - steps[1].value),
                        steps[2].copy(value = 1f - steps[2].value),
                    )
                )
            assertThat(dozeAmountSteps.subList(3, 7)).isEqualTo(steps.subList(3, 7))
        }

    @Test
    fun finishedKeyguardStateTests() =
        testScope.runTest {
            val finishedSteps by collectValues(underTest.finishedKeyguardState)
            runCurrent()
            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, PRIMARY_BOUNCER, 0f, STARTED))
            steps.add(TransitionStep(AOD, PRIMARY_BOUNCER, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, PRIMARY_BOUNCER, 1f, FINISHED))
            steps.add(TransitionStep(PRIMARY_BOUNCER, AOD, 0f, STARTED))
            steps.add(TransitionStep(PRIMARY_BOUNCER, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(PRIMARY_BOUNCER, AOD, 1f, FINISHED))
            steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

            steps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            assertThat(finishedSteps).isEqualTo(listOf(LOCKSCREEN, PRIMARY_BOUNCER, AOD))
        }

    @Test
    fun startedKeyguardStateTests() =
        testScope.runTest {
            val startedStates by collectValues(underTest.startedKeyguardState)
            runCurrent()
            val steps = mutableListOf<TransitionStep>()

            steps.add(TransitionStep(AOD, PRIMARY_BOUNCER, 0f, STARTED))
            steps.add(TransitionStep(AOD, PRIMARY_BOUNCER, 0.5f, RUNNING))
            steps.add(TransitionStep(AOD, PRIMARY_BOUNCER, 1f, FINISHED))
            steps.add(TransitionStep(PRIMARY_BOUNCER, AOD, 0f, STARTED))
            steps.add(TransitionStep(PRIMARY_BOUNCER, AOD, 0.9f, RUNNING))
            steps.add(TransitionStep(PRIMARY_BOUNCER, AOD, 1f, FINISHED))
            steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

            steps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            assertThat(startedStates).isEqualTo(listOf(LOCKSCREEN, PRIMARY_BOUNCER, AOD, GONE))
        }

    @Test
    fun finishedKeyguardTransitionStepTests() = runTest {
        val finishedSteps by collectValues(underTest.finishedKeyguardTransitionStep)

        val steps = mutableListOf<TransitionStep>()

        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        // Ignore the default state.
        assertThat(finishedSteps.subList(1, finishedSteps.size))
            .isEqualTo(listOf(steps[2], steps[5]))
    }

    @Test
    fun startedKeyguardTransitionStepTests() = runTest {
        val startedSteps by collectValues(underTest.startedKeyguardTransitionStep)

        val steps = mutableListOf<TransitionStep>()

        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
        steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(startedSteps)
            .isEqualTo(
                listOf(
                    // The initial transition will also get sent when collect started
                    TransitionStep(OFF, LOCKSCREEN, 0f, STARTED),
                    steps[0],
                    steps[3],
                    steps[6]
                )
            )
    }

    @Test
    fun transitionValue() =
        testScope.runTest {
            val startedSteps by collectValues(underTest.transitionValue(state = DOZING))

            val toSteps =
                listOf(
                    TransitionStep(AOD, DOZING, 0f, STARTED),
                    TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                    TransitionStep(AOD, DOZING, 1f, FINISHED),
                )
            toSteps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            val fromSteps =
                listOf(
                    TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
                    TransitionStep(DOZING, LOCKSCREEN, 0.5f, RUNNING),
                    TransitionStep(DOZING, LOCKSCREEN, 1f, FINISHED),
                )
            fromSteps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            assertThat(startedSteps).isEqualTo(listOf(0f, 0.5f, 1f, 1f, 0.5f, 0f))
        }

    @Test
    fun isInTransitionToAnyState() =
        testScope.runTest {
            val inTransition by collectValues(underTest.isInTransitionToAnyState)

            assertEquals(
                listOf(
                    true, // The repo is seeded with a transition from OFF to LOCKSCREEN.
                    false,
                ),
                inTransition
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0f, STARTED),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                ),
                inTransition
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0.5f, RUNNING),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                ),
                inTransition
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 1f, FINISHED),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                    false,
                ),
                inTransition
            )
        }

    @Test
    fun isInTransitionToAnyState_finishedStateIsStartedStateAfterCancels() =
        testScope.runTest {
            val inTransition by collectValues(underTest.isInTransitionToAnyState)

            assertEquals(
                listOf(
                    true,
                    false,
                ),
                inTransition
            )

            // Start FINISHED in GONE.
            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0f, STARTED),
                TransitionStep(LOCKSCREEN, GONE, 0.5f, RUNNING),
                TransitionStep(LOCKSCREEN, GONE, 1f, FINISHED),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                    false,
                ),
                inTransition
            )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                    false,
                    true,
                ),
                inTransition
            )

            sendSteps(
                TransitionStep(GONE, DOZING, 0.5f, RUNNING),
                TransitionStep(GONE, DOZING, 0.6f, CANCELED),
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
                TransitionStep(DOZING, LOCKSCREEN, 0.5f, RUNNING),
                TransitionStep(DOZING, LOCKSCREEN, 0.6f, CANCELED),
                TransitionStep(LOCKSCREEN, GONE, 0f, STARTED),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                    false,
                    // We should have been in transition throughout the entire transition, including
                    // both cancellations, and we should still be in transition despite now
                    // transitioning to GONE, the state we're also FINISHED in.
                    true,
                ),
                inTransition
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0.5f, RUNNING),
                TransitionStep(LOCKSCREEN, GONE, 1f, FINISHED),
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                    false,
                    true,
                    false,
                ),
                inTransition
            )
        }

    @Test
    fun isInTransitionToState() =
        testScope.runTest {
            val results by collectValues(underTest.isInTransitionToState(GONE))

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
                TransitionStep(GONE, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun isInTransitionFromState() =
        testScope.runTest {
            val results by collectValues(underTest.isInTransitionFromState(DOZING))

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
                TransitionStep(GONE, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun isInTransitionFromStateWhere() =
        testScope.runTest {
            val results by collectValues(underTest.isInTransitionFromStateWhere { it == DOZING })

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
                TransitionStep(GONE, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun isInTransitionWhere() =
        testScope.runTest {
            val results by
                collectValues(
                    underTest.isInTransitionWhere(
                        fromStatePredicate = { it == DOZING },
                        toStatePredicate = { it == GONE },
                    )
                )

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
                TransitionStep(GONE, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun isInTransitionWhere_withCanceledStep() =
        testScope.runTest {
            val results by
                collectValues(
                    underTest.isInTransitionWhere(
                        fromStatePredicate = { it == DOZING },
                        toStatePredicate = { it == GONE },
                    )
                )

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, CANCELED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
                TransitionStep(GONE, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun isFinishedInStateWhere() =
        testScope.runTest {
            val results by collectValues(underTest.isFinishedInStateWhere { it == GONE })

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false, // Finished in DOZING, not GONE.
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 0f, STARTED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 0f, RUNNING))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 1f, FINISHED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(TransitionStep(GONE, DOZING, 1f, FINISHED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 1f, FINISHED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun isFinishedInState() =
        testScope.runTest {
            val results by collectValues(underTest.isFinishedInState(GONE))

            sendSteps(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false, // Finished in DOZING, not GONE.
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 0f, STARTED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 0f, RUNNING))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 1f, FINISHED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(TransitionStep(GONE, DOZING, 1f, FINISHED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, GONE, 0f, STARTED),
                TransitionStep(DOZING, GONE, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            sendSteps(TransitionStep(DOZING, GONE, 1f, FINISHED))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                        true,
                    )
                )
        }

    @Test
    fun finishedKeyguardState_emitsAgainIfCancelledAndReversed() =
        testScope.runTest {
            val finishedStates by collectValues(underTest.finishedKeyguardState)

            // We default FINISHED in LOCKSCREEN.
            assertEquals(listOf(LOCKSCREEN), finishedStates)

            sendSteps(
                TransitionStep(LOCKSCREEN, AOD, 0f, STARTED),
                TransitionStep(LOCKSCREEN, AOD, 0.5f, RUNNING),
                TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED),
            )

            // We're FINISHED in AOD.
            assertEquals(
                listOf(
                    LOCKSCREEN,
                    AOD,
                ),
                finishedStates
            )

            // Transition back to LOCKSCREEN.
            sendSteps(
                TransitionStep(AOD, LOCKSCREEN, 0f, STARTED),
                TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING),
                TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED),
            )

            // We're FINISHED in LOCKSCREEN.
            assertEquals(
                listOf(
                    LOCKSCREEN,
                    AOD,
                    LOCKSCREEN,
                ),
                finishedStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0f, STARTED),
                TransitionStep(LOCKSCREEN, GONE, 0.5f, RUNNING),
            )

            // We've STARTED a transition to GONE but not yet finished it so we're still FINISHED in
            // LOCKSCREEN.
            assertEquals(
                listOf(
                    LOCKSCREEN,
                    AOD,
                    LOCKSCREEN,
                ),
                finishedStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0.6f, CANCELED),
            )

            // We've CANCELED a transition to GONE, we're still FINISHED in LOCKSCREEN.
            assertEquals(
                listOf(
                    LOCKSCREEN,
                    AOD,
                    LOCKSCREEN,
                ),
                finishedStates
            )

            sendSteps(
                TransitionStep(GONE, LOCKSCREEN, 0.6f, STARTED),
                TransitionStep(GONE, LOCKSCREEN, 0.9f, RUNNING),
                TransitionStep(GONE, LOCKSCREEN, 1f, FINISHED),
            )

            // Expect another emission of LOCKSCREEN, as we have FINISHED a second transition to
            // LOCKSCREEN after the cancellation.
            assertEquals(
                listOf(
                    LOCKSCREEN,
                    AOD,
                    LOCKSCREEN,
                    LOCKSCREEN,
                ),
                finishedStates
            )
        }

    @Test
    fun testCurrentState() =
        testScope.runTest {
            val currentStates by collectValues(underTest.currentKeyguardState)

            // We init the repo with a transition from OFF -> LOCKSCREEN.
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, AOD, 0f, STARTED),
            )

            // The current state should continue to be LOCKSCREEN as we transition to AOD.
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, AOD, 0.5f, RUNNING),
            )

            // The current state should continue to be LOCKSCREEN as we transition to AOD.
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, AOD, 0.6f, CANCELED),
            )

            // Once CANCELED, we're still currently in LOCKSCREEN...
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(AOD, LOCKSCREEN, 0.6f, STARTED),
            )

            // ...until STARTING back to LOCKSCREEN, at which point the "current" state should be
            // the
            // one we're transitioning from, despite never FINISHING in that state.
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    AOD,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(AOD, LOCKSCREEN, 0.8f, RUNNING),
                TransitionStep(AOD, LOCKSCREEN, 0.8f, FINISHED),
            )

            // FINSHING in LOCKSCREEN should update the current state to LOCKSCREEN.
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    AOD,
                    LOCKSCREEN,
                ),
                currentStates
            )
        }

    @Test
    fun testCurrentState_multipleCancellations_backToLastFinishedState() =
        testScope.runTest {
            val currentStates by collectValues(underTest.currentKeyguardState)

            // We init the repo with a transition from OFF -> LOCKSCREEN.
            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0f, STARTED),
                TransitionStep(LOCKSCREEN, GONE, 0.5f, RUNNING),
                TransitionStep(LOCKSCREEN, GONE, 1f, FINISHED),
            )

            assertEquals(
                listOf(
                    // Default transition from OFF -> LOCKSCREEN
                    OFF,
                    LOCKSCREEN,
                    // Transitioned to GONE
                    GONE,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(GONE, DOZING, 0f, STARTED),
                TransitionStep(GONE, DOZING, 0.5f, RUNNING),
                TransitionStep(GONE, DOZING, 0.6f, CANCELED),
            )

            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    GONE,
                    // Current state should not be DOZING until the post-cancelation transition is
                    // STARTED
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
            )

            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    GONE,
                    // DOZING -> LS STARTED, DOZING is now the current state.
                    DOZING,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(DOZING, LOCKSCREEN, 0.5f, RUNNING),
                TransitionStep(DOZING, LOCKSCREEN, 0.6f, CANCELED),
            )

            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    GONE,
                    DOZING,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0f, STARTED),
            )

            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    GONE,
                    DOZING,
                    // LS -> GONE STARTED, LS is now the current state.
                    LOCKSCREEN,
                ),
                currentStates
            )

            sendSteps(
                TransitionStep(LOCKSCREEN, GONE, 0.5f, RUNNING),
                TransitionStep(LOCKSCREEN, GONE, 1f, FINISHED),
            )

            assertEquals(
                listOf(
                    OFF,
                    LOCKSCREEN,
                    GONE,
                    DOZING,
                    LOCKSCREEN,
                    // FINISHED in GONE, GONE is now the current state.
                    GONE,
                ),
                currentStates
            )
        }

    private suspend fun sendSteps(vararg steps: TransitionStep) {
        steps.forEach {
            repository.sendTransitionStep(it)
            testScope.runCurrent()
        }
    }
}
