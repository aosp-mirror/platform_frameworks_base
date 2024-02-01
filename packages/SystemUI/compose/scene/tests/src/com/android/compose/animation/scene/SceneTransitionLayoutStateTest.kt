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

package com.android.compose.animation.scene

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.TestScenes.SceneD
import com.android.compose.animation.scene.transition.link.StateLink
import com.android.compose.test.runMonotonicClockTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransitionLayoutStateTest {
    @get:Rule val rule = createComposeRule()

    class TestableTransition(
        fromScene: SceneKey,
        toScene: SceneKey,
    ) : TransitionState.Transition(fromScene, toScene) {
        override var currentScene: SceneKey = fromScene
        override var progress: Float = 0.0f
        override var isInitiatedByUserInput: Boolean = false
        override var isUserInputOngoing: Boolean = false
    }

    @Test
    fun isTransitioningTo_idle() {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)

        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.isTransitioning(from = SceneA)).isFalse()
        assertThat(state.isTransitioning(to = SceneB)).isFalse()
        assertThat(state.isTransitioning(from = SceneA, to = SceneB)).isFalse()
    }

    @Test
    fun isTransitioningTo_transition() {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)
        state.startTransition(transition(from = SceneA, to = SceneB), transitionKey = null)

        assertThat(state.isTransitioning()).isTrue()
        assertThat(state.isTransitioning(from = SceneA)).isTrue()
        assertThat(state.isTransitioning(from = SceneB)).isFalse()
        assertThat(state.isTransitioning(to = SceneB)).isTrue()
        assertThat(state.isTransitioning(to = SceneA)).isFalse()
        assertThat(state.isTransitioning(from = SceneA, to = SceneB)).isTrue()
    }

    @Test
    fun setTargetScene_idleToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        assertThat(state.setTargetScene(SceneA, coroutineScope = this)).isNull()
    }

    @Test
    fun setTargetScene_idleToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        val transition = state.setTargetScene(SceneB, coroutineScope = this)
        assertThat(transition).isNotNull()
        assertThat(state.transitionState).isEqualTo(transition)

        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNotNull()
        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNull()
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNotNull()
        assertThat(state.setTargetScene(SceneC, coroutineScope = this)).isNotNull()
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun setTargetScene_transitionToOriginalScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNotNull()

        // Progress is 0f, so we don't animate at all and directly snap back to A.
        assertThat(state.setTargetScene(SceneA, coroutineScope = this)).isNull()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneA))
    }

    @Test
    fun setTargetScene_coroutineScopeCancelled() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        lateinit var transition: TransitionState.Transition
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                transition = state.setTargetScene(SceneB, coroutineScope = this)!!
            }
        assertThat(state.transitionState).isEqualTo(transition)

        // Cancelling the scope/job still sets the state to Idle(targetScene).
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    private fun setupLinkedStates():
            Pair<BaseSceneTransitionLayoutState, BaseSceneTransitionLayoutState> {
        val parentState = MutableSceneTransitionLayoutState(SceneC)
        val link =
            listOf(
                StateLink(
                    parentState,
                    listOf(StateLink.TransitionLink(SceneA, SceneB, SceneC, SceneD))
                )
            )
        val childState = MutableSceneTransitionLayoutState(SceneA, stateLinks = link)
        return Pair(
            parentState as BaseSceneTransitionLayoutState,
            childState as BaseSceneTransitionLayoutState
        )
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInToState() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = TestableTransition(SceneA, SceneB)

        childState.startTransition(childTransition, null)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()

        childState.finishTransition(childTransition, SceneB)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
    }

    @Test
    fun linkedTransition_transitiveLink() {
        val parentParentState =
            MutableSceneTransitionLayoutState(SceneB) as BaseSceneTransitionLayoutState
        val parentLink =
            listOf(
                StateLink(
                    parentParentState,
                    listOf(StateLink.TransitionLink(SceneC, SceneD, SceneB, SceneC))
                )
            )
        val parentState =
            MutableSceneTransitionLayoutState(SceneC, stateLinks = parentLink)
                as BaseSceneTransitionLayoutState
        val link =
            listOf(
                StateLink(
                    parentState,
                    listOf(StateLink.TransitionLink(SceneA, SceneB, SceneC, SceneD))
                )
            )
        val childState =
            MutableSceneTransitionLayoutState(SceneA, stateLinks = link)
                as BaseSceneTransitionLayoutState

        val childTransition = TestableTransition(SceneA, SceneB)

        childState.startTransition(childTransition, null)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()
        assertThat(parentParentState.isTransitioning(SceneB, SceneC)).isTrue()

        childState.finishTransition(childTransition, SceneB)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
        assertThat(parentParentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_linkProgressIsEqual() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = TestableTransition(SceneA, SceneB)

        childState.startTransition(childTransition, null)
        assertThat(parentState.currentTransition?.progress).isEqualTo(0f)

        childTransition.progress = .5f
        assertThat(parentState.currentTransition?.progress).isEqualTo(.5f)
    }

    @Test
    fun linkedTransition_reverseTransitionIsNotLinked() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = TestableTransition(SceneB, SceneA)

        childState.startTransition(childTransition, null)
        assertThat(childState.isTransitioning(SceneB, SceneA)).isTrue()
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))

        childState.finishTransition(childTransition, SceneB)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInFromState() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = TestableTransition(SceneA, SceneB)
        childState.startTransition(childTransition, null)

        childState.finishTransition(childTransition, SceneA)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneA))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInUnknownState() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = TestableTransition(SceneA, SceneB)
        childState.startTransition(childTransition, null)

        childState.finishTransition(childTransition, SceneD)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkButLinkedStateIsTakenOver() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = TestableTransition(SceneA, SceneB)
        val parentTransition = TestableTransition(SceneC, SceneA)
        childState.startTransition(childTransition, null)
        parentState.startTransition(parentTransition, null)

        childState.finishTransition(childTransition, SceneB)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(parentTransition)
    }

    @Test
    fun setTargetScene_withTransitionKey() = runMonotonicClockTest {
        val transitionkey = TransitionKey(debugName = "foo")
        val state =
            MutableSceneTransitionLayoutState(
                SceneA,
                transitions =
                    transitions {
                        from(SceneA, to = SceneB) { fade(TestElements.Foo) }
                        from(SceneA, to = SceneB, key = transitionkey) {
                            fade(TestElements.Foo)
                            fade(TestElements.Bar)
                        }
                    },
            )
                as MutableSceneTransitionLayoutStateImpl

        // Default transition from A to B.
        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNotNull()
        assertThat(state.transformationSpec.transformations).hasSize(1)

        // Go back to A.
        state.setTargetScene(SceneA, coroutineScope = this)
        testScheduler.advanceUntilIdle()
        assertThat(state.currentTransition).isNull()
        assertThat(state.transitionState.currentScene).isEqualTo(SceneA)

        // Specific transition from A to B.
        assertThat(
                state.setTargetScene(
                    SceneB,
                    coroutineScope = this,
                    transitionKey = transitionkey,
                )
            )
            .isNotNull()
        assertThat(state.transformationSpec.transformations).hasSize(2)
    }

    @Test
    fun snapToIdleIfClose_snapToStart() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(TestScenes.SceneA, SceneTransitions.Empty)
        state.startTransition(
            transition(from = TestScenes.SceneA, to = TestScenes.SceneB, progress = { 0.2f }),
            transitionKey = null
        )
        assertThat(state.isTransitioning()).isTrue()

        // Ignore the request if the progress is not close to 0 or 1, using the threshold.
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.isTransitioning()).isTrue()

        // Go to the initial scene if it is close to 0.
        assertThat(state.snapToIdleIfClose(threshold = 0.2f)).isTrue()
        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneA))
    }

    @Test
    fun snapToIdleIfClose_snapToEnd() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(TestScenes.SceneA, SceneTransitions.Empty)
        state.startTransition(
            transition(from = TestScenes.SceneA, to = TestScenes.SceneB, progress = { 0.8f }),
            transitionKey = null
        )
        assertThat(state.isTransitioning()).isTrue()

        // Ignore the request if the progress is not close to 0 or 1, using the threshold.
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.isTransitioning()).isTrue()

        // Go to the final scene if it is close to 1.
        assertThat(state.snapToIdleIfClose(threshold = 0.2f)).isTrue()
        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneB))
    }
}
