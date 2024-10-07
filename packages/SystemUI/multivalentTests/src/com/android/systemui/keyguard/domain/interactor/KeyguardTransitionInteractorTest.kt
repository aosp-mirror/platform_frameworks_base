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
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.UNDEFINED
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardTransitionInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val underTest = kosmos.keyguardTransitionInteractor
    val repository = kosmos.fakeKeyguardTransitionRepository
    val testScope = kosmos.testScope

    @Test
    fun transitionCollectorsReceivesOnlyAppropriateEvents() =
        testScope.runTest {
            val lockscreenToAodSteps by
                collectValues(underTest.transition(Edge.create(LOCKSCREEN, AOD)))
            val aodToLockscreenSteps by
                collectValues(underTest.transition(Edge.create(AOD, LOCKSCREEN)))

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
    fun startedKeyguardTransitionStepTests() =
        testScope.runTest {
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
                        TransitionStep(
                            OFF,
                            LOCKSCREEN,
                            0f,
                            STARTED,
                            ownerName = "KeyguardTransitionRepository(boot)"
                        ),
                        steps[0],
                        steps[3],
                        steps[6]
                    )
                )
        }

    @Test
    fun transitionValue() =
        testScope.runTest {
            resetTransitionValueReplayCache(setOf(AOD, DOZING, LOCKSCREEN))
            val transitionValues by collectValues(underTest.transitionValue(state = DOZING))

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

            assertThat(transitionValues).isEqualTo(listOf(0f, 0.5f, 1f, 1f, 0.5f, 0f))
        }

    @Test
    fun transitionValue_badTransitionResetsTransitionValue() =
        testScope.runTest {
            resetTransitionValueReplayCache(setOf(AOD, DOZING, LOCKSCREEN))
            val transitionValues by collectValues(underTest.transitionValue(state = DOZING))

            val toSteps =
                listOf(
                    TransitionStep(AOD, DOZING, 0f, STARTED),
                    TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                )
            toSteps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            // This is an intentionally bad sequence that will leave the transitionValue for
            // DOZING in a bad place, since no CANCELED will be issued for DOZING
            val fromSteps =
                listOf(
                    TransitionStep(AOD, LOCKSCREEN, 0f, STARTED),
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING),
                    TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED),
                )
            fromSteps.forEach {
                repository.sendTransitionStep(it)
                runCurrent()
            }

            assertThat(transitionValues).isEqualTo(listOf(0f, 0.5f, 0f))
        }

    @Test
    fun transitionValue_canceled_toAnotherState() =
        testScope.runTest {
            resetTransitionValueReplayCache(setOf(AOD, GONE, LOCKSCREEN))

            val transitionValuesGone by collectValues(underTest.transitionValue(state = GONE))
            val transitionValuesAod by collectValues(underTest.transitionValue(state = AOD))
            val transitionValuesLs by collectValues(underTest.transitionValue(state = LOCKSCREEN))

            listOf(
                    TransitionStep(GONE, AOD, 0f, STARTED),
                    TransitionStep(GONE, AOD, 0.5f, RUNNING),
                    TransitionStep(GONE, AOD, 0.5f, CANCELED),
                    TransitionStep(AOD, LOCKSCREEN, 0.5f, STARTED),
                    TransitionStep(AOD, LOCKSCREEN, 0.7f, RUNNING),
                    TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED),
                )
                .forEach {
                    repository.sendTransitionStep(it)
                    runCurrent()
                }

            assertThat(transitionValuesGone).isEqualTo(listOf(1f, 0.5f, 0f))
            assertThat(transitionValuesAod).isEqualTo(listOf(0f, 0.5f, 0.5f, 0.3f, 0f))
            assertThat(transitionValuesLs).isEqualTo(listOf(0.5f, 0.7f, 1f))
        }

    @Test
    fun transitionValue_canceled_backToOriginalState() =
        testScope.runTest {
            resetTransitionValueReplayCache(setOf(AOD, GONE))

            val transitionValuesGone by collectValues(underTest.transitionValue(state = GONE))
            val transitionValuesAod by collectValues(underTest.transitionValue(state = AOD))

            listOf(
                    TransitionStep(GONE, AOD, 0f, STARTED),
                    TransitionStep(GONE, AOD, 0.5f, RUNNING),
                    TransitionStep(GONE, AOD, 1f, CANCELED),
                    TransitionStep(AOD, GONE, 0.5f, STARTED),
                    TransitionStep(AOD, GONE, 0.7f, RUNNING),
                    TransitionStep(AOD, GONE, 1f, FINISHED),
                )
                .forEach {
                    repository.sendTransitionStep(it)
                    runCurrent()
                }

            assertThat(transitionValuesGone).isEqualTo(listOf(1f, 0.5f, 0.5f, 0.7f, 1f))
            assertThat(transitionValuesAod).isEqualTo(listOf(0f, 0.5f, 0.5f, 0.3f, 0f))
        }

    @Test
    fun isInTransitionToAnyState() =
        testScope.runTest {
            val inTransition by collectValues(underTest.isInTransition)

            assertEquals(
                listOf(
                    false,
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
                    false,
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
                    false,
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
    @EnableSceneContainer
    fun isInTransition_withScene() =
        testScope.runTest {
            val inTransition by collectValues(underTest.isInTransition)

            assertEquals(
                listOf(
                    false,
                    true, // The repo is seeded with a transition from OFF to LOCKSCREEN.
                    false,
                ),
                inTransition
            )

            kosmos.setSceneTransition(Transition(Scenes.Gone, Scenes.Bouncer))

            assertEquals(
                listOf(
                    false,
                    true,
                    false,
                    true,
                ),
                inTransition
            )

            kosmos.setSceneTransition(Idle(Scenes.Bouncer))

            assertEquals(
                listOf(
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
    fun isInTransitionToAnyState_finishedStateIsStartedStateAfterCancels() =
        testScope.runTest {
            val inTransition by collectValues(underTest.isInTransition)

            assertEquals(
                listOf(
                    false,
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
                    false,
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
                    false,
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
                    false,
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
                    false,
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
    @DisableSceneContainer
    fun isInTransitionToState() =
        testScope.runTest {
            val results by
                collectValues(
                    underTest.isInTransition(
                        edge = Edge.create(OFF, OFF),
                        edgeWithoutSceneContainer = Edge.create(to = LOCKSCREEN)
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
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, LOCKSCREEN, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, LOCKSCREEN, 0f, FINISHED),
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
                TransitionStep(LOCKSCREEN, DOZING, 0f, STARTED),
                TransitionStep(LOCKSCREEN, DOZING, 0f, RUNNING),
                TransitionStep(LOCKSCREEN, DOZING, 1f, FINISHED),
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
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
                TransitionStep(DOZING, LOCKSCREEN, 0f, RUNNING),
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
    @EnableSceneContainer
    fun isInTransitionFromScene() =
        testScope.runTest {
            val results by
                collectValues(underTest.isInTransition(edge = Edge.create(Scenes.Lockscreen, null)))

            kosmos.setSceneTransition(Transition(from = Scenes.Gone, to = Scenes.Lockscreen))
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            kosmos.setSceneTransition(Transition(from = Scenes.Lockscreen, to = Scenes.Shade))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            kosmos.setSceneTransition(Idle(Scenes.Shade))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )
        }

    @Test
    @EnableSceneContainer
    fun isInTransitionToScene() =
        testScope.runTest {
            val results by
                collectValues(underTest.isInTransition(edge = Edge.create(null, Scenes.Lockscreen)))

            kosmos.setSceneTransition(Transition(from = Scenes.Gone, to = Scenes.Lockscreen))
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            kosmos.setSceneTransition(Transition(from = Scenes.Lockscreen, to = Scenes.Gone))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )
        }

    @Test
    @EnableSceneContainer
    fun isInTransitionStateToScene() =
        testScope.runTest {
            val results by
                collectValues(underTest.isInTransition(edge = Edge.create(AOD, Scenes.Gone)))

            kosmos.setSceneTransition(Transition(from = Scenes.Lockscreen, to = Scenes.Gone))

            sendSteps(
                TransitionStep(AOD, UNDEFINED, 0f, STARTED),
                TransitionStep(AOD, UNDEFINED, 0.5f, RUNNING),
                TransitionStep(AOD, UNDEFINED, 1f, FINISHED),
            )

            kosmos.setSceneTransition(Idle(Scenes.Gone))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )
        }

    @Test
    fun isInTransitionFromState() =
        testScope.runTest {
            val results by collectValues(underTest.isInTransition(Edge.create(from = DOZING)))

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
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, LOCKSCREEN, 0f, RUNNING),
            )

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            sendSteps(
                TransitionStep(DOZING, LOCKSCREEN, 0f, FINISHED),
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
                TransitionStep(LOCKSCREEN, DOZING, 0f, STARTED),
                TransitionStep(LOCKSCREEN, DOZING, 0f, RUNNING),
                TransitionStep(LOCKSCREEN, DOZING, 1f, FINISHED),
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
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
                TransitionStep(DOZING, LOCKSCREEN, 0f, RUNNING),
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
    @DisableSceneContainer
    fun isFinishedInState() =
        testScope.runTest {
            val results by collectValues(underTest.isFinishedIn(Scenes.Gone, GONE))

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
    @EnableSceneContainer
    fun isFinishedIn() =
        testScope.runTest {
            val results by collectValues(underTest.isFinishedIn(Scenes.Gone, GONE))

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

            kosmos.setSceneTransition(Transition(from = Scenes.Lockscreen, to = Scenes.Gone))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                    )
                )

            kosmos.setSceneTransition(Idle(Scenes.Gone))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            kosmos.setSceneTransition(Transition(from = Scenes.Gone, to = Scenes.Lockscreen))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                    )
                )

            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            kosmos.setSceneTransition(Transition(from = Scenes.Lockscreen, to = Scenes.Gone))

            assertThat(results)
                .isEqualTo(
                    listOf(
                        false,
                        true,
                        false,
                    )
                )

            kosmos.setSceneTransition(Idle(Scenes.Gone))

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

    @Test
    @DisableSceneContainer
    fun transition_no_conversion_with_flag_off() =
        testScope.runTest {
            val currentStates by
                collectValues(underTest.transition(Edge.create(PRIMARY_BOUNCER, GONE)))

            val sendStep1 = TransitionStep(PRIMARY_BOUNCER, GONE, 0f, STARTED)
            sendSteps(sendStep1)

            assertEquals(listOf(sendStep1), currentStates)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_with_flag_on() =
        testScope.runTest {
            val currentStates by
                collectValues(underTest.transition(Edge.create(PRIMARY_BOUNCER, GONE)))

            val sendStep1 = TransitionStep(PRIMARY_BOUNCER, GONE, 0f, STARTED)
            sendSteps(sendStep1)

            assertEquals(listOf<TransitionStep>(), currentStates)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_emits_values_with_sceneContainer_in_correct_state() =
        testScope.runTest {
            val currentStates by collectValues(underTest.transition(Edge.create(LOCKSCREEN, GONE)))
            val currentStatesConverted by
                collectValues(underTest.transition(Edge.create(LOCKSCREEN, UNDEFINED)))

            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))
            val sendStep1 = TransitionStep(LOCKSCREEN, UNDEFINED, 0f, STARTED)
            sendSteps(sendStep1)
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            val sendStep2 = TransitionStep(LOCKSCREEN, UNDEFINED, 1f, FINISHED)
            val sendStep3 = TransitionStep(LOCKSCREEN, AOD, 0f, STARTED)
            sendSteps(sendStep2, sendStep3)

            assertEquals(listOf(sendStep1, sendStep2), currentStates)
            assertEquals(listOf(sendStep1, sendStep2), currentStatesConverted)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_emits_nothing_with_sceneContainer_in_wrong_state() =
        testScope.runTest {
            val currentStates by collectValues(underTest.transition(Edge.create(LOCKSCREEN, GONE)))

            kosmos.setSceneTransition(Transition(Scenes.Gone, Scenes.Lockscreen))
            val sendStep1 = TransitionStep(LOCKSCREEN, UNDEFINED, 0f, STARTED)
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            val sendStep2 = TransitionStep(LOCKSCREEN, UNDEFINED, 1f, FINISHED)
            val sendStep3 = TransitionStep(LOCKSCREEN, AOD, 0f, STARTED)
            sendSteps(sendStep1, sendStep2, sendStep3)

            assertEquals(listOf<TransitionStep>(), currentStates)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_emits_values_when_edge_within_lockscreen_scene() =
        testScope.runTest {
            val currentStates by
                collectValues(underTest.transition(Edge.create(LOCKSCREEN, DOZING)))

            kosmos.setSceneTransition(Transition(Scenes.Gone, Scenes.Lockscreen))
            val sendStep1 = TransitionStep(LOCKSCREEN, DOZING, 0f, STARTED)
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            val sendStep2 = TransitionStep(LOCKSCREEN, DOZING, 1f, FINISHED)
            val sendStep3 = TransitionStep(LOCKSCREEN, AOD, 0f, STARTED)
            sendSteps(sendStep1, sendStep2, sendStep3)

            assertEquals(listOf(sendStep1, sendStep2), currentStates)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_emits_values_with_null_edge_within_lockscreen_scene() =
        testScope.runTest {
            val currentStates by collectValues(underTest.transition(Edge.create(LOCKSCREEN, null)))
            val currentStatesReversed by
                collectValues(underTest.transition(Edge.create(null, LOCKSCREEN)))

            kosmos.setSceneTransition(Transition(Scenes.Gone, Scenes.Lockscreen))
            val sendStep1 = TransitionStep(LOCKSCREEN, DOZING, 0f, STARTED)
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            val sendStep2 = TransitionStep(LOCKSCREEN, DOZING, 1f, FINISHED)
            val sendStep3 = TransitionStep(LOCKSCREEN, AOD, 0f, STARTED)
            val sendStep4 = TransitionStep(AOD, LOCKSCREEN, 0f, STARTED)
            sendSteps(sendStep1, sendStep2, sendStep3, sendStep4)

            assertEquals(listOf(sendStep1, sendStep2, sendStep3), currentStates)
            assertEquals(listOf(sendStep4), currentStatesReversed)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_emits_values_with_null_edge_out_of_lockscreen_scene() =
        testScope.runTest {
            val currentStates by collectValues(underTest.transition(Edge.create(null, UNDEFINED)))
            val currentStatesMapped by collectValues(underTest.transition(Edge.create(null, GONE)))

            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))
            val sendStep1 = TransitionStep(LOCKSCREEN, UNDEFINED, 0f, STARTED)
            sendSteps(sendStep1)
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            val sendStep2 = TransitionStep(LOCKSCREEN, UNDEFINED, 1f, FINISHED)
            val sendStep3 = TransitionStep(UNDEFINED, AOD, 0f, STARTED)
            val sendStep4 = TransitionStep(AOD, LOCKSCREEN, 0f, STARTED)
            sendSteps(sendStep2, sendStep3, sendStep4)

            assertEquals(listOf(sendStep1, sendStep2), currentStates)
            assertEquals(listOf(sendStep1, sendStep2), currentStatesMapped)
        }

    @Test
    @EnableSceneContainer
    fun transition_conversion_does_not_emit_with_null_edge_with_wrong_stl_state() =
        testScope.runTest {
            val currentStatesMapped by collectValues(underTest.transition(Edge.create(null, GONE)))

            kosmos.setSceneTransition(Transition(Scenes.Gone, Scenes.Lockscreen))
            val sendStep1 = TransitionStep(LOCKSCREEN, UNDEFINED, 0f, STARTED)
            sendSteps(sendStep1)
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            val sendStep2 = TransitionStep(LOCKSCREEN, UNDEFINED, 1f, FINISHED)
            val sendStep3 = TransitionStep(UNDEFINED, AOD, 0f, STARTED)
            val sendStep4 = TransitionStep(AOD, LOCKSCREEN, 0f, STARTED)
            sendSteps(sendStep2, sendStep3, sendStep4)

            assertEquals(listOf<TransitionStep>(), currentStatesMapped)
        }

    @Test
    @EnableSceneContainer
    fun transition_null_edges_throw() =
        testScope.runTest {
            assertThrows(IllegalStateException::class.java) {
                underTest.transition(Edge.create(null, null))
            }
        }

    private suspend fun sendSteps(vararg steps: TransitionStep) {
        steps.forEach {
            repository.sendTransitionStep(it)
            testScope.runCurrent()
        }
    }

    private fun resetTransitionValueReplayCache(states: Set<KeyguardState>) {
        states.forEach { (underTest.transitionValue(it) as MutableSharedFlow).resetReplayCache() }
    }
}
