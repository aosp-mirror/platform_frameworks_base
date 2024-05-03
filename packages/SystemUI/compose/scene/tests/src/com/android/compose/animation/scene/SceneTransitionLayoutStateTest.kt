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

import android.util.Log
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransitionLayoutStateTest {
    @get:Rule val rule = createComposeRule()

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

        transition!!.finish().join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        val transition = state.setTargetScene(SceneB, coroutineScope = this)
        assertThat(transition).isNotNull()
        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNull()

        transition!!.finish().join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        assertThat(state.setTargetScene(SceneB, coroutineScope = this)).isNotNull()
        val transition = state.setTargetScene(SceneC, coroutineScope = this)
        assertThat(transition).isNotNull()

        transition!!.finish().join()
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
        job.cancelAndJoin()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun transition_finishReturnsTheSameJobWhenCalledMultipleTimes() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        val transition = state.setTargetScene(SceneB, coroutineScope = this)
        assertThat(transition).isNotNull()

        val job = transition!!.finish()
        assertThat(transition.finish()).isSameInstanceAs(job)
        assertThat(transition.finish()).isSameInstanceAs(job)
        assertThat(transition.finish()).isSameInstanceAs(job)
    }

    private fun setupLinkedStates(
        parentInitialScene: SceneKey = SceneC,
        childInitialScene: SceneKey = SceneA,
        sourceFrom: SceneKey? = SceneA,
        sourceTo: SceneKey? = SceneB,
        targetFrom: SceneKey? = SceneC,
        targetTo: SceneKey = SceneD
    ): Pair<BaseSceneTransitionLayoutState, BaseSceneTransitionLayoutState> {
        val parentState = MutableSceneTransitionLayoutState(parentInitialScene)
        val link =
            listOf(
                StateLink(
                    parentState,
                    listOf(StateLink.TransitionLink(sourceFrom, sourceTo, targetFrom, targetTo))
                )
            )
        val childState = MutableSceneTransitionLayoutState(childInitialScene, stateLinks = link)
        return Pair(
            parentState as BaseSceneTransitionLayoutState,
            childState as BaseSceneTransitionLayoutState
        )
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInToState() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneA, SceneB)

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

        val childTransition = transition(SceneA, SceneB)

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

        var progress = 0f
        val childTransition = transition(SceneA, SceneB, progress = { progress })

        childState.startTransition(childTransition, null)
        assertThat(parentState.currentTransition?.progress).isEqualTo(0f)

        progress = .5f
        assertThat(parentState.currentTransition?.progress).isEqualTo(.5f)
    }

    @Test
    fun linkedTransition_reverseTransitionIsNotLinked() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneB, SceneA)

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

        val childTransition = transition(SceneA, SceneB)
        childState.startTransition(childTransition, null)

        childState.finishTransition(childTransition, SceneA)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneA))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInUnknownState() {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneA, SceneB)
        childState.startTransition(childTransition, null)

        childState.finishTransition(childTransition, SceneD)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkButLinkedStateIsTakenOver() = runTest {
        val (parentState, childState) = setupLinkedStates()

        val childTransition =
            transition(
                SceneA,
                SceneB,
                onFinish = { launch { /* Do nothing. */} },
            )
        val parentTransition =
            transition(
                SceneC,
                SceneA,
                onFinish = { launch { /* Do nothing. */} },
            )
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
        assertThat(state.currentTransition?.transformationSpec?.transformations).hasSize(1)

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
        assertThat(state.currentTransition?.transformationSpec?.transformations).hasSize(2)
    }

    @Test
    fun snapToIdleIfClose_snapToStart() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)
        state.startTransition(
            transition(from = SceneA, to = SceneB, progress = { 0.2f }),
            transitionKey = null
        )
        assertThat(state.isTransitioning()).isTrue()

        // Ignore the request if the progress is not close to 0 or 1, using the threshold.
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.isTransitioning()).isTrue()

        // Go to the initial scene if it is close to 0.
        assertThat(state.snapToIdleIfClose(threshold = 0.2f)).isTrue()
        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneA))
    }

    @Test
    fun snapToIdleIfClose_snapToEnd() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)
        state.startTransition(
            transition(from = SceneA, to = SceneB, progress = { 0.8f }),
            transitionKey = null
        )
        assertThat(state.isTransitioning()).isTrue()

        // Ignore the request if the progress is not close to 0 or 1, using the threshold.
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.isTransitioning()).isTrue()

        // Go to the final scene if it is close to 1.
        assertThat(state.snapToIdleIfClose(threshold = 0.2f)).isTrue()
        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun snapToIdleIfClose_multipleTransitions() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)

        val aToB =
            transition(
                from = SceneA,
                to = SceneB,
                progress = { 0.5f },
                onFinish = { launch { /* do nothing */} },
            )
        state.startTransition(aToB, transitionKey = null)
        assertThat(state.currentTransitions).containsExactly(aToB).inOrder()

        val bToC = transition(from = SceneB, to = SceneC, progress = { 0.8f })
        state.startTransition(bToC, transitionKey = null)
        assertThat(state.currentTransitions).containsExactly(aToB, bToC).inOrder()

        // Ignore the request if the progress is not close to 0 or 1, using the threshold.
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.currentTransitions).containsExactly(aToB, bToC).inOrder()

        // Go to the final scene if it is close to 1.
        assertThat(state.snapToIdleIfClose(threshold = 0.2f)).isTrue()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneC))
        assertThat(state.currentTransitions).isEmpty()
    }

    @Test
    fun linkedTransition_fuzzyLinksAreMatchedAndStarted() {
        val (parentState, childState) = setupLinkedStates(SceneC, SceneA, null, null, null, SceneD)
        val childTransition = transition(SceneA, SceneB)

        childState.startTransition(childTransition, null)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()

        childState.finishTransition(childTransition, SceneB)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
    }

    @Test
    fun linkedTransition_fuzzyLinksAreMatchedAndResetToProperPreviousScene() {
        val (parentState, childState) =
            setupLinkedStates(SceneC, SceneA, SceneA, null, null, SceneD)

        val childTransition = transition(SceneA, SceneB)

        childState.startTransition(childTransition, null)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()

        childState.finishTransition(childTransition, SceneA)
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneA))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_fuzzyLinksAreNotMatched() {
        val (parentState, childState) =
            setupLinkedStates(SceneC, SceneA, SceneB, null, SceneC, SceneD)
        val childTransition = transition(SceneA, SceneB)

        childState.startTransition(childTransition, null)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isFalse()
    }

    private fun startOverscrollableTransistionFromAtoB(
        progress: () -> Float,
        sceneTransitions: SceneTransitions,
    ): MutableSceneTransitionLayoutStateImpl {
        val state =
            MutableSceneTransitionLayoutStateImpl(
                SceneA,
                sceneTransitions,
            )
        state.startTransition(
            transition(
                from = SceneA,
                to = SceneB,
                progress = progress,
                orientation = Orientation.Vertical,
            ),
            transitionKey = null
        )
        assertThat(state.isTransitioning()).isTrue()
        return state
    }

    @Test
    fun overscrollDsl_definedForToScene() = runMonotonicClockTest {
        val progress = mutableStateOf(0f)
        val state =
            startOverscrollableTransistionFromAtoB(
                progress = { progress.value },
                sceneTransitions =
                    transitions {
                        overscroll(SceneB, Orientation.Vertical) { fade(TestElements.Foo) }
                    }
            )
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // overscroll for SceneA is NOT defined
        progress.value = -0.1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // scroll from SceneA to SceneB
        progress.value = 0.5f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        progress.value = 1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // overscroll for SceneB is defined
        progress.value = 1.1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        assertThat(state.currentTransition?.currentOverscrollSpec?.scene).isEqualTo(SceneB)
    }

    @Test
    fun overscrollDsl_definedForFromScene() = runMonotonicClockTest {
        val progress = mutableStateOf(0f)
        val state =
            startOverscrollableTransistionFromAtoB(
                progress = { progress.value },
                sceneTransitions =
                    transitions {
                        overscroll(SceneA, Orientation.Vertical) { fade(TestElements.Foo) }
                    }
            )
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // overscroll for SceneA is defined
        progress.value = -0.1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        assertThat(state.currentTransition?.currentOverscrollSpec?.scene).isEqualTo(SceneA)

        // scroll from SceneA to SceneB
        progress.value = 0.5f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        progress.value = 1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // overscroll for SceneB is NOT defined
        progress.value = 1.1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()
    }

    @Test
    fun overscrollDsl_notDefinedScenes() = runMonotonicClockTest {
        val progress = mutableStateOf(0f)
        val state =
            startOverscrollableTransistionFromAtoB(
                progress = { progress.value },
                sceneTransitions = transitions {}
            )
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // overscroll for SceneA is NOT defined
        progress.value = -0.1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // scroll from SceneA to SceneB
        progress.value = 0.5f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        progress.value = 1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // overscroll for SceneB is NOT defined
        progress.value = 1.1f
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()
    }

    @Test
    fun multipleTransitions() = runTest {
        val finishingTransitions = mutableSetOf<TransitionState.Transition>()
        fun onFinish(transition: TransitionState.Transition): Job {
            // Instead of letting the transition finish, we put the transition in the
            // finishingTransitions set so that we can verify that finish() is called when expected
            // and then we call state STLState.finishTransition() ourselves.
            finishingTransitions.add(transition)

            return backgroundScope.launch {
                // Try to acquire a locked mutex so that this code never completes.
                Mutex(locked = true).withLock {}
            }
        }

        val state = MutableSceneTransitionLayoutStateImpl(SceneA, EmptyTestTransitions)
        val aToB = transition(SceneA, SceneB, onFinish = ::onFinish)
        val bToC = transition(SceneB, SceneC, onFinish = ::onFinish)
        val cToA = transition(SceneC, SceneA, onFinish = ::onFinish)

        // Starting state.
        assertThat(finishingTransitions).isEmpty()
        assertThat(state.currentTransitions).isEmpty()

        // A => B.
        state.startTransition(aToB, transitionKey = null)
        assertThat(finishingTransitions).isEmpty()
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB).inOrder()

        // B => C. This should automatically call finish() on aToB.
        state.startTransition(bToC, transitionKey = null)
        assertThat(finishingTransitions).containsExactly(aToB)
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB, bToC).inOrder()

        // C => A. This should automatically call finish() on bToC.
        state.startTransition(cToA, transitionKey = null)
        assertThat(finishingTransitions).containsExactly(aToB, bToC)
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        // Mark bToC as finished. The list of current transitions does not change because aToB is
        // still not marked as finished.
        state.finishTransition(bToC, idleScene = bToC.currentScene)
        assertThat(state.finishedTransitions).containsExactly(bToC, bToC.currentScene)
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        // Mark aToB as finished. This will remove both aToB and bToC from the list of transitions.
        state.finishTransition(aToB, idleScene = aToB.currentScene)
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(cToA).inOrder()
    }

    @Test
    fun tooManyTransitionsLogsWtfAndClearsTransitions() = runTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, EmptyTestTransitions)

        fun startTransition() {
            val transition = transition(SceneA, SceneB, onFinish = { launch { /* do nothing */} })
            state.startTransition(transition, transitionKey = null)
        }

        var hasLoggedWtf = false
        val originalHandler = Log.setWtfHandler { _, _, _ -> hasLoggedWtf = true }
        try {
            repeat(100) { startTransition() }
            assertThat(hasLoggedWtf).isFalse()
            assertThat(state.currentTransitions).hasSize(100)

            startTransition()
            assertThat(hasLoggedWtf).isTrue()
            assertThat(state.currentTransitions).hasSize(1)
        } finally {
            Log.setWtfHandler(originalHandler)
        }
    }

    @Test
    fun snapToScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        // Transition to B.
        state.setTargetScene(SceneB, coroutineScope = this)
        val transition = assertThat(state.transitionState).isTransition()
        assertThat(transition).hasCurrentScene(SceneB)

        // Snap to C.
        state.snapToScene(SceneC)
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneC)
    }
}
