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

package com.android.systemui.communal.domain.interactor

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.ObservableTransitionState.Idle
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.Flags.FLAG_COMMUNAL_SCENE_KTF_REFACTOR
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.keyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.realKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(FLAG_COMMUNAL_HUB, FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
@DisableSceneContainer
class CommunalSceneTransitionInteractorTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply { keyguardTransitionRepository = realKeyguardTransitionRepository }
    private val testScope = kosmos.testScope

    private val underTest by lazy { kosmos.communalSceneTransitionInteractor }
    private val keyguardTransitionRepository by lazy { kosmos.realKeyguardTransitionRepository }
    private val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }

    private val ownerName = CommunalSceneTransitionInteractor::class.java.simpleName
    private val progress = MutableSharedFlow<Float>()

    private val sceneTransitions =
        MutableStateFlow<ObservableTransitionState>(Idle(CommunalScenes.Blank))

    private val blankToHub =
        ObservableTransitionState.Transition(
            fromScene = CommunalScenes.Blank,
            toScene = CommunalScenes.Communal,
            currentScene = flowOf(CommunalScenes.Blank),
            progress = progress,
            isInitiatedByUserInput = false,
            isUserInputOngoing = flowOf(false),
        )

    private val hubToBlank =
        ObservableTransitionState.Transition(
            fromScene = CommunalScenes.Communal,
            toScene = CommunalScenes.Blank,
            currentScene = flowOf(CommunalScenes.Communal),
            progress = progress,
            isInitiatedByUserInput = false,
            isUserInputOngoing = flowOf(false),
        )

    @Before
    fun setup() {
        kosmos.fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)
        underTest.start()
        kosmos.communalSceneRepository.setTransitionState(sceneTransitions)
        testScope.launch { keyguardTransitionRepository.emitInitialStepsFromOff(LOCKSCREEN) }
    }

    /** Transition from blank to glanceable hub. This is the default case. */
    @Test
    fun transition_from_blank_end_in_hub() =
        testScope.runTest {
            sceneTransitions.value = blankToHub

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(1f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = RUNNING,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = Idle(CommunalScenes.Communal)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /** Transition from hub to lockscreen. */
    @Test
    fun transition_from_hub_end_in_lockscreen() =
        testScope.runTest {
            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = Idle(CommunalScenes.Blank)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /** Transition from hub to dream. */
    @Test
    fun transition_from_hub_end_in_dream() =
        testScope.runTest {
            // Device is dreaming and occluded.
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            kosmos.fakeKeyguardRepository.setDreaming(true)
            kosmos.fakeKeyguardRepository.setDreamingWithOverlay(true)
            runCurrent()

            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = DREAMING,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = DREAMING,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = Idle(CommunalScenes.Blank)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = DREAMING,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /** Transition from hub to occluded. */
    @Test
    fun transition_from_hub_end_in_occluded() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = OCCLUDED,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = Idle(CommunalScenes.Blank)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = OCCLUDED,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /** Transition from hub to gone. */
    @Test
    fun transition_from_hub_end_in_gone() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardGoingAway(true)
            runCurrent()

            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = GONE,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = GONE,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = Idle(CommunalScenes.Blank)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = GONE,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /** Transition from blank to hub, then settle back in blank. */
    @Test
    fun transition_from_blank_end_in_blank() =
        testScope.runTest {
            sceneTransitions.value = blankToHub

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)
            val allSteps by collectValues(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            val numToDrop = allSteps.size
            // Settle back in blank
            sceneTransitions.value = Idle(CommunalScenes.Blank)

            // Assert that KTF reversed transition back to lockscreen.
            assertThat(allSteps.drop(numToDrop))
                .containsExactly(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = CANCELED,
                        value = 0.4f,
                        ownerName = ownerName,
                    ),
                    // Transition back to lockscreen
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = STARTED,
                        value = 0.6f,
                        ownerName = ownerName,
                    ),
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    ),
                )
                .inOrder()
        }

    @Test
    fun transition_to_occluded_with_changed_scene_respected_just_once() =
        testScope.runTest {
            underTest.onSceneAboutToChange(CommunalScenes.Blank, OCCLUDED)
            runCurrent()
            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = blankToHub
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = OCCLUDED,
                        to = GLANCEABLE_HUB,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            sceneTransitions.value = hubToBlank
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )
        }

    @Test
    fun transition_from_blank_interrupted() =
        testScope.runTest {
            sceneTransitions.value = blankToHub

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)
            val allSteps by collectValues(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            val numToDrop = allSteps.size
            // Transition back from hub to blank, interrupting
            // the current transition.
            sceneTransitions.value = hubToBlank

            assertThat(allSteps.drop(numToDrop))
                .containsExactly(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        value = 1f,
                        transitionState = FINISHED,
                        ownerName = ownerName,
                    ),
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        value = 0f,
                        transitionState = STARTED,
                        ownerName = ownerName,
                    ),
                )
                .inOrder()

            progress.emit(0.1f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = RUNNING,
                        value = 0.1f,
                        ownerName = ownerName,
                    )
                )
        }

    /**
     * Blank -> Hub transition interrupted by a new Blank -> Hub transition. KTF state should not be
     * updated in this case.
     */
    @Test
    fun transition_to_hub_duplicate_does_not_change_ktf() =
        testScope.runTest {
            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Blank,
                    toScene = CommunalScenes.Communal,
                    currentScene = flowOf(CommunalScenes.Blank),
                    progress = progress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)
            val allSteps by collectValues(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            val sizeBefore = allSteps.size
            val newProgress = MutableSharedFlow<Float>()
            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Blank,
                    toScene = CommunalScenes.Communal,
                    currentScene = flowOf(CommunalScenes.Blank),
                    progress = newProgress,
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                )

            // No new KTF steps emitted as a result of the new transition.
            assertThat(allSteps).hasSize(sizeBefore)

            // Progress is now tracked by the new flow.
            newProgress.emit(0.1f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = RUNNING,
                        value = 0.1f,
                        ownerName = ownerName,
                    )
                )
        }

    /**
     * STL: Hub -> Blank, then interrupt in KTF LS -> OCCLUDED, then STL still finishes in Blank.
     * After a KTF transition is started (GLANCEABLE_HUB -> LOCKSCREEN) KTF immediately considers
     * the active scene to be LOCKSCREEN. This means that all listeners for LOCKSCREEN are active
     * and may start a new transition LOCKSCREEN -> *. Here we test LOCKSCREEN -> OCCLUDED.
     *
     * KTF is allowed to already start and play the other transition, while the STL transition may
     * finish later (gesture completes much later). When we eventually settle the STL transition in
     * Blank we do not want to force KTF back to its original destination (LOCKSCREEN). Instead, for
     * this scenario the settle can be ignored.
     */
    @Test
    fun transition_to_blank_interrupted_by_ktf_transition_then_finish_in_blank() =
        testScope.runTest {
            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            // Start another transition externally while our scene
            // transition is happening.
            keyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = "external",
                    from = LOCKSCREEN,
                    to = OCCLUDED,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET,
                )
            )

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = "external",
                    )
                )

            // Scene progress should not affect KTF transition anymore
            progress.emit(0.7f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = "external",
                    )
                )

            // Scene transition still finishes but should not impact KTF transition
            sceneTransitions.value = Idle(CommunalScenes.Blank)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = "external",
                    )
                )
        }

    /**
     * STL: Hub -> Blank, then interrupt in KTF LS -> OCCLUDED, then STL finishes back in Hub.
     *
     * This is similar to the previous scenario but the gesture may have been interrupted by any
     * other transition. KTF needs to immediately finish in GLANCEABLE_HUB (there is a jump cut).
     */
    @Test
    fun transition_to_blank_interrupted_by_ktf_transition_then_finish_in_hub() =
        testScope.runTest {
            sceneTransitions.value = hubToBlank

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    )
                )

            progress.emit(0.4f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    )
                )

            // Start another transition externally while our scene
            // transition is happening.
            keyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = "external",
                    from = LOCKSCREEN,
                    to = OCCLUDED,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET,
                )
            )

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = "external",
                    )
                )

            // Scene progress should not affect KTF transition anymore
            progress.emit(0.7f)
            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = OCCLUDED,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = "external",
                    )
                )

            // We land back in communal.
            sceneTransitions.value = Idle(CommunalScenes.Communal)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = OCCLUDED,
                        to = GLANCEABLE_HUB,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /** Verifies that we correctly transition to GONE after keyguard goes away */
    @Test
    fun transition_to_blank_after_unlock_should_go_to_gone() =
        testScope.runTest {
            keyguardRepository.setKeyguardShowing(true)
            sceneTransitions.value = Idle(CommunalScenes.Communal)

            val currentStep by collectLastValue(keyguardTransitionRepository.transitions)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )

            // Keyguard starts exiting after a while, then fully exits after some time.
            advanceTimeBy(1.seconds)
            keyguardRepository.setKeyguardGoingAway(true)
            advanceTimeBy(2.seconds)
            keyguardRepository.setKeyguardGoingAway(false)
            keyguardRepository.setKeyguardShowing(false)
            runCurrent()

            // We snap to the blank scene as a result of keyguard going away.
            sceneTransitions.value = Idle(CommunalScenes.Blank)

            assertThat(currentStep)
                .isEqualTo(
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = GONE,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    )
                )
        }

    /**
     * KTF: LOCKSCREEN -> ALTERNATE_BOUNCER starts but then STL: GLANCEABLE_HUB -> BLANK interrupts.
     *
     * Verifies that we correctly cancel the previous KTF state before starting the glanceable hub
     * transition.
     */
    @Test
    fun transition_to_blank_after_ktf_started_another_transition() =
        testScope.runTest {
            // Another transition has already started to the alternate bouncer.
            keyguardTransitionRepository.startTransition(
                TransitionInfo(
                    from = LOCKSCREEN,
                    to = ALTERNATE_BOUNCER,
                    animator = null,
                    ownerName = "external",
                    modeOnCanceled = TransitionModeOnCanceled.RESET,
                )
            )

            val allSteps by collectValues(keyguardTransitionRepository.transitions)
            // Keep track of existing size to drop any pre-existing steps that we don't
            // care about.
            val numToDrop = allSteps.size

            sceneTransitions.value = hubToBlank
            runCurrent()
            progress.emit(0.4f)
            runCurrent()
            // We land on blank.
            sceneTransitions.value = Idle(CommunalScenes.Blank)

            // We should cancel the previous ALTERNATE_BOUNCER transition and transition back
            // to the GLANCEABLE_HUB before we can transition away from it.
            assertThat(allSteps.drop(numToDrop))
                .containsExactly(
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = ALTERNATE_BOUNCER,
                        transitionState = CANCELED,
                        value = 0f,
                        ownerName = "external",
                    ),
                    TransitionStep(
                        from = ALTERNATE_BOUNCER,
                        to = GLANCEABLE_HUB,
                        transitionState = STARTED,
                        value = 1f,
                        ownerName = ownerName,
                    ),
                    TransitionStep(
                        from = ALTERNATE_BOUNCER,
                        to = GLANCEABLE_HUB,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    ),
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = STARTED,
                        value = 0f,
                        ownerName = ownerName,
                    ),
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = RUNNING,
                        value = 0.4f,
                        ownerName = ownerName,
                    ),
                    TransitionStep(
                        from = GLANCEABLE_HUB,
                        to = LOCKSCREEN,
                        transitionState = FINISHED,
                        value = 1f,
                        ownerName = ownerName,
                    ),
                )
                .inOrder()
        }
}
