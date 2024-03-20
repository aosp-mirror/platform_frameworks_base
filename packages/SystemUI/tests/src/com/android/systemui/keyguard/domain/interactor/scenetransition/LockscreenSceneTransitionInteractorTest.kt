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

package com.android.systemui.keyguard.domain.interactor.scenetransition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.keyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.realKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenSceneTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply { keyguardTransitionRepository = realKeyguardTransitionRepository }

    private val testScope = kosmos.testScope
    private val underTest = kosmos.lockscreenSceneTransitionInteractor

    private val progress = MutableStateFlow(0f)

    private val sceneTransitions =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(Scenes.Lockscreen)
        )

    private val lsToGone =
        ObservableTransitionState.Transition(
            Scenes.Lockscreen,
            Scenes.Gone,
            flowOf(Scenes.Lockscreen),
            progress,
            false,
            flowOf(false)
        )

    private val goneToLs =
        ObservableTransitionState.Transition(
            Scenes.Gone,
            Scenes.Lockscreen,
            flowOf(Scenes.Lockscreen),
            progress,
            false,
            flowOf(false)
        )

    @Before
    fun setUp() {
        underTest.start()
        kosmos.sceneContainerRepository.setTransitionState(sceneTransitions)
        testScope.launch {
            kosmos.realKeyguardTransitionRepository.emitInitialStepsFromOff(
                KeyguardState.LOCKSCREEN
            )
        }
    }

    /** STL: Ls -> Gone, then settle with Idle(Gone). This is the default case. */
    @Test
    fun transition_from_ls_scene_end_in_gone() =
        testScope.runTest {
            sceneTransitions.value = lsToGone

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Ls -> Gone, then settle with Idle(Ls). KTF in this scenario needs to invert the
     * transition LS -> UNDEFINED to UNDEFINED -> LS as there is no mechanism in KTF to
     * finish/settle to progress 0.0f.
     */
    @Test
    fun transition_from_ls_scene_end_in_ls() =
        testScope.runTest {
            sceneTransitions.value = lsToGone

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Lockscreen)

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.STARTED,
                progress = 0.6f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Ls -> Gone, then settle with Idle(Ls). KTF starts in AOD and needs to inverse correctly
     * back to AOD.
     */
    @Test
    fun transition_from_ls_scene_on_aod_end_in_ls() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )
            sceneTransitions.value = lsToGone

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Lockscreen)

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0.6f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.AOD,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Gone -> Ls, then settle with Idle(Ls). This is the default case in the reverse
     * direction.
     */
    @Test
    fun transition_to_ls_scene_end_in_ls() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = goneToLs

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Lockscreen)

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /** STL: Gone -> Ls (AOD), will transition to AOD once */
    @Test
    fun transition_to_ls_scene_with_changed_next_scene_is_respected_just_once() =
        testScope.runTest {
            underTest.onSceneAboutToChange(Scenes.Lockscreen, KeyguardState.AOD)
            sceneTransitions.value = goneToLs

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.AOD,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Shade,
                    Scenes.Lockscreen,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0f,
            )
        }

    /**
     * STL: Gone -> Ls, then settle with Idle(Gone). KTF in this scenario needs to invert the
     * transition UNDEFINED -> LS to LS -> UNDEFINED as there is no mechanism in KTF to
     * finish/settle to progress 0.0f.
     */
    @Test
    fun transition_to_ls_scene_end_in_from_scene() =
        testScope.runTest {
            sceneTransitions.value = goneToLs

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)
            val stepM3 = allSteps[allSteps.size - 3]
            val stepM2 = allSteps[allSteps.size - 2]

            assertTransition(
                step = stepM3,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = stepM2,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0.6f,
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupted by Shade -> Ls. KTF in this scenario needs to invert the
     * transition UNDEFINED -> LS to LS -> UNDEFINED as there is no mechanism in KTF to
     * finish/settle to progress 0.0f. Then restart a different transition UNDEFINED -> Ls.
     */
    @Test
    fun transition_to_ls_scene_end_in_to_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = goneToLs
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Shade,
                    Scenes.Lockscreen,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 5],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 4],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0.6f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.2f
            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.2f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupted by Ls -> Shade. This is like continuing the transition from
     * Ls before the transition before has properly settled. This can happen in STL e.g. with an
     * accelerated swipe (quick successive fling gestures).
     */
    @Test
    fun transition_to_ls_scene_end_in_from_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = goneToLs
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Lockscreen,
                    Scenes.Shade,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0.0f,
            )

            progress.value = 0.2f
            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.2f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupted by Gone -> Shade. This is going back to Gone but starting a
     * transition from Gone before settling in Gone. KTF needs to make sure the transition is
     * properly inversed and settled in UNDEFINED.
     */
    @Test
    fun transition_to_ls_scene_end_in_other_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = goneToLs
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Gone,
                    Scenes.Shade,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0.6f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupt in KTF LS -> AOD, then stl still finishes in Ls. After a KTF
     * transition is started (UNDEFINED -> LOCKSCREEN) KTF immediately considers the active scene to
     * be LOCKSCREEN. This means that all listeners for LOCKSCREEN are active and may start a new
     * transition LOCKSCREEN -> *. Here we test LS -> AOD.
     *
     * KTF is allowed to already start and play the other transition, while the STL transition may
     * finish later (gesture completes much later). When we eventually settle the STL transition in
     * Ls we do not want to force KTF back to its original destination (LOCKSCREEN). Instead, for
     * this scenario the settle can be ignored.
     */
    @Test
    fun transition_to_ls_scene_interrupted_by_ktf_transition_then_finish_in_lockscreen() =
        testScope.runTest {
            sceneTransitions.value = goneToLs

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            // Scene progress should not affect KTF transition anymore
            progress.value = 0.7f
            assertTransition(currentStep!!, progress = 0f)

            // Scene transition still finishes but should not impact KTF transition
            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Lockscreen)

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupt in KTF LS -> AOD, then stl finishes in Gone.
     *
     * Refers to: `transition_to_ls_scene_interrupted_by_ktf_transition_then_finish_in_lockscreen`
     *
     * This is similar to the previous scenario but the gesture may have gone back to its origin. In
     * this case we can not ignore the settlement, because whatever KTF has done in the meantime it
     * needs to immediately finish in UNDEFINED (there is a jump cut).
     */
    @Test
    fun transition_to_ls_scene_interrupted_by_ktf_transition_then_finish_in_gone() =
        testScope.runTest {
            sceneTransitions.value = goneToLs

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.7f
            assertThat(currentStep?.value).isEqualTo(0f)

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupt in KTF LS -> AOD, then STL Gone -> Shade
     *
     * Refers to: `transition_to_ls_scene_interrupted_by_ktf_transition_then_finish_in_lockscreen`
     *
     * This is similar to the previous scenario but the gesture may have been interrupted by any
     * other transition. KTF needs to immediately finish in UNDEFINED (there is a jump cut).
     */
    @Test
    fun transition_to_ls_interrupted_by_ktf_transition_then_interrupted_by_other_transition() =
        testScope.runTest {
            sceneTransitions.value = goneToLs

            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            assertTransition(
                step = currentStep!!,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.7f
            assertTransition(currentStep!!, progress = 0f)

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Gone,
                    Scenes.Shade,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupt in KTF LS -> AOD, then STL Ls -> Shade
     *
     * In this scenario it is important that the last STL transition Ls -> Shade triggers a cancel
     * of the * -> AOD transition but then also properly starts a transition AOD (not LOCKSCREEN) ->
     * UNDEFINED transition.
     */
    @Test
    fun transition_to_ls_interrupted_by_ktf_transition_then_interrupted_by_from_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = goneToLs
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.7f
            assertTransition(currentStep!!, progress = 0f)

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Lockscreen,
                    Scenes.Shade,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )
            allSteps[allSteps.size - 3]

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.CANCELED,
                progress = 0f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.2f
            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.2f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupt in KTF LS -> AOD, then STL Shade -> Ls
     *
     * In this scenario it is important KTF is brought back into a FINISHED UNDEFINED state
     * considering the state is already on AOD from where a new UNDEFINED -> LOCKSCREEN transition
     * can be started.
     */
    @Test
    fun transition_to_ls_interrupted_by_ktf_transition_then_interrupted_by_to_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = goneToLs
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.7f
            assertTransition(currentStep!!, progress = 0f)

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Shade,
                    Scenes.Lockscreen,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 5],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.CANCELED,
                progress = 0f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 4],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 1f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.7f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupt multiple canceled KTF transitions, then STL Ls -> Shade
     *
     * Similar to
     * `transition_to_ls_scene_interrupted_by_ktf_transition_then_interrupted_by_from_ls_transition`
     * but here KTF is canceled multiple times such that in the end OCCLUDED -> UNDEFINED is
     * properly started. (not from AOD or LOCKSCREEN)
     *
     * Note: there is no test which tests multiple cancels from the STL side, this is because all
     * STL transitions trigger a response from LockscreenSceneTransitionInteractor which forces KTF
     * into a specific state, so testing each pair is enough. Meanwhile KTF can move around without
     * any reaction from LockscreenSceneTransitionInteractor.
     */
    @Test
    fun transition_to_ls_interrupted_by_ktf_cancel_sequence_interrupted_by_from_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = lsToGone
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.AOD,
                    to = KeyguardState.DOZING,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.AOD,
                to = KeyguardState.DOZING,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            kosmos.realKeyguardTransitionRepository.startTransition(
                TransitionInfo(
                    ownerName = this.javaClass.simpleName,
                    from = KeyguardState.DOZING,
                    to = KeyguardState.OCCLUDED,
                    animator = null,
                    modeOnCanceled = TransitionModeOnCanceled.RESET
                )
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.DOZING,
                to = KeyguardState.OCCLUDED,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.7f
            assertTransition(currentStep!!, progress = 0f)

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Lockscreen,
                    Scenes.Shade,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.DOZING,
                to = KeyguardState.OCCLUDED,
                state = TransitionState.CANCELED,
                progress = 0f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.2f
            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.2f,
            )
        }

    /**
     * STL: Gone -> Ls, then interrupted by KTF LS -> AOD which is FINISHED before STL Ls -> Shade
     *
     * Similar to
     * `transition_to_ls_scene_interrupted_by_ktf_transition_then_interrupted_by_from_ls_transition`
     * but here KTF is finishing the transition and only then gets interrupted. Should correctly
     * start AOD -> UNDEFINED.
     */
    @Test
    fun transition_to_ls_scene_interrupted_and_finished_by_ktf_interrupted_by_from_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = lsToGone
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            val ktfUuid =
                kosmos.realKeyguardTransitionRepository.startTransition(
                    TransitionInfo(
                        ownerName = this.javaClass.simpleName,
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.AOD,
                        animator = null,
                        modeOnCanceled = TransitionModeOnCanceled.RESET
                    )
                )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            kosmos.realKeyguardTransitionRepository.updateTransition(
                ktfUuid!!,
                1f,
                TransitionState.FINISHED
            )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.FINISHED,
                progress = 1f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Lockscreen,
                    Scenes.Shade,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                state = TransitionState.FINISHED,
                progress = 1f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.2f
            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.2f,
            )
        }

    /**
     * STL: Ls -> Gone, then interrupted by Ls -> Bouncer. This happens when the next transition is
     * immediately started from Gone without settling in Idle. This specifically happens when
     * dragging down on Ls and then changing direction. The transition will switch from -> Shade to
     * -> Bouncer without settling or signaling any cancellation as STL considers this to be the
     * same gesture.
     *
     * In STL there is no guarantee that transitions settle in Idle before continuing.
     */
    @Ignore("Suffers from a race condition that will be fixed in followup CL")
    @Test
    fun transition_from_ls_scene_interrupted_by_other_from_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = lsToGone

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0f,
            )

            progress.value = 0.4f
            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Lockscreen,
                    Scenes.Bouncer,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 5],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.CANCELED,
                progress = 0.4f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 4],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.STARTED,
                progress = 0.6f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.FINISHED,
                progress = 1f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.STARTED,
                progress = 0f,
            )
        }

    /**
     * STL: Ls -> Gone, then interrupted by Gone -> Ls. This happens when the next transition is
     * immediately started from Gone without settling in Idle. In STL there is no guarantee that
     * transitions settle in Idle before continuing.
     */
    @Test
    fun transition_from_ls_scene_interrupted_by_to_ls_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            val allSteps by collectValues(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = lsToGone
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Gone,
                    Scenes.Lockscreen,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = allSteps[allSteps.size - 3],
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )

            assertTransition(
                step = allSteps[allSteps.size - 2],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.STARTED,
                progress = 0f,
            )

            progress.value = 0.2f
            assertTransition(
                step = allSteps[allSteps.size - 1],
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.LOCKSCREEN,
                state = TransitionState.RUNNING,
                progress = 0.2f,
            )
        }

    /**
     * STL: Ls -> Gone, then interrupted by Gone -> Bouncer. This happens when the next transition
     * is immediately started from Gone without settling in Idle. In STL there is no guarantee that
     * transitions settle in Idle before continuing.
     */
    @Test
    fun transition_from_ls_scene_interrupted_by_other_stl_transition() =
        testScope.runTest {
            val currentStep by collectLastValue(kosmos.realKeyguardTransitionRepository.transitions)
            sceneTransitions.value = lsToGone
            progress.value = 0.4f

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.RUNNING,
                progress = 0.4f,
            )

            sceneTransitions.value =
                ObservableTransitionState.Transition(
                    Scenes.Gone,
                    Scenes.Bouncer,
                    flowOf(Scenes.Lockscreen),
                    progress,
                    false,
                    flowOf(false)
                )

            assertTransition(
                step = currentStep!!,
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                state = TransitionState.FINISHED,
                progress = 1f,
            )
        }

    private fun assertTransition(
        step: TransitionStep,
        from: KeyguardState? = null,
        to: KeyguardState? = null,
        state: TransitionState? = null,
        progress: Float? = null
    ) {
        if (from != null) assertThat(step.from).isEqualTo(from)
        if (to != null) assertThat(step.to).isEqualTo(to)
        if (state != null) assertThat(step.transitionState).isEqualTo(state)
        if (progress != null) assertThat(step.value).isEqualTo(progress)
    }
}
