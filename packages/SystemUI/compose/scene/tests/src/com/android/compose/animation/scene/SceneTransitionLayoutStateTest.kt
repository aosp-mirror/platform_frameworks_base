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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.TestScenes.SceneD
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.subjects.assertThat
import com.android.compose.animation.scene.transition.link.StateLink
import com.android.compose.animation.scene.transition.seekToScene
import com.android.compose.test.MonotonicClockTestScope
import com.android.compose.test.TestSceneTransition
import com.android.compose.test.runMonotonicClockTest
import com.android.compose.test.transition
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
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
    fun isTransitioningTo_transition() = runTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)
        state.startTransitionImmediately(
            animationScope = backgroundScope,
            transition(from = SceneA, to = SceneB),
        )

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
        assertThat(state.setTargetScene(SceneA, animationScope = this)).isNull()
    }

    @Test
    fun setTargetScene_idleToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        val (transition, job) = checkNotNull(state.setTargetScene(SceneB, animationScope = this))
        assertThat(state.transitionState).isEqualTo(transition)

        job.join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        val (_, job) = checkNotNull(state.setTargetScene(SceneB, animationScope = this))
        assertThat(state.setTargetScene(SceneB, animationScope = this)).isNull()

        job.join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        assertThat(state.setTargetScene(SceneB, animationScope = this)).isNotNull()
        val (_, job) = checkNotNull(state.setTargetScene(SceneC, animationScope = this))

        job.join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun setTargetScene_coroutineScopeCancelled() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)

        lateinit var transition: TransitionState.Transition
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                transition = checkNotNull(state.setTargetScene(SceneB, animationScope = this)).first
            }
        assertThat(state.transitionState).isEqualTo(transition)

        // Cancelling the scope/job still sets the state to Idle(targetScene).
        job.cancelAndJoin()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    private fun setupLinkedStates(
        parentInitialScene: SceneKey = SceneC,
        childInitialScene: SceneKey = SceneA,
        sourceFrom: SceneKey? = SceneA,
        sourceTo: SceneKey? = SceneB,
        targetFrom: SceneKey? = SceneC,
        targetTo: SceneKey = SceneD,
    ): Pair<MutableSceneTransitionLayoutStateImpl, MutableSceneTransitionLayoutStateImpl> {
        val parentState = MutableSceneTransitionLayoutState(parentInitialScene)
        val link =
            listOf(
                StateLink(
                    parentState,
                    listOf(StateLink.TransitionLink(sourceFrom, sourceTo, targetFrom, targetTo)),
                )
            )
        val childState = MutableSceneTransitionLayoutState(childInitialScene, stateLinks = link)
        return Pair(
            parentState as MutableSceneTransitionLayoutStateImpl,
            childState as MutableSceneTransitionLayoutStateImpl,
        )
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInToState() = runTest {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneA, SceneB)

        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()

        childTransition.finish()
        job.join()
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
    }

    @Test
    fun linkedTransition_transitiveLink() = runTest {
        val parentParentState =
            MutableSceneTransitionLayoutState(SceneB) as MutableSceneTransitionLayoutStateImpl
        val parentLink =
            listOf(
                StateLink(
                    parentParentState,
                    listOf(StateLink.TransitionLink(SceneC, SceneD, SceneB, SceneC)),
                )
            )
        val parentState =
            MutableSceneTransitionLayoutState(SceneC, stateLinks = parentLink)
                as MutableSceneTransitionLayoutStateImpl
        val link =
            listOf(
                StateLink(
                    parentState,
                    listOf(StateLink.TransitionLink(SceneA, SceneB, SceneC, SceneD)),
                )
            )
        val childState =
            MutableSceneTransitionLayoutState(SceneA, stateLinks = link)
                as MutableSceneTransitionLayoutStateImpl

        val childTransition = transition(SceneA, SceneB)

        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()
        assertThat(parentParentState.isTransitioning(SceneB, SceneC)).isTrue()

        childTransition.finish()
        job.join()
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
        assertThat(parentParentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_linkProgressIsEqual() = runTest {
        val (parentState, childState) = setupLinkedStates()

        var progress = 0f
        val childTransition = transition(SceneA, SceneB, progress = { progress })

        childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(parentState.currentTransition?.progress).isEqualTo(0f)

        progress = .5f
        assertThat(parentState.currentTransition?.progress).isEqualTo(.5f)
    }

    @Test
    fun linkedTransition_reverseTransitionIsNotLinked() = runTest {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneB, SceneA, current = { SceneB })

        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(childState.isTransitioning(SceneB, SceneA)).isTrue()
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))

        childTransition.finish()
        job.join()
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkAndFinishesLinkInFromState() = runTest {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneA, SceneB, current = { SceneA })
        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)

        childTransition.finish()
        job.join()
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneA))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_startsLinkButLinkedStateIsTakenOver() = runTest {
        val (parentState, childState) = setupLinkedStates()

        val childTransition = transition(SceneA, SceneB)
        val parentTransition = transition(SceneC, SceneA)
        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        parentState.startTransitionImmediately(animationScope = backgroundScope, parentTransition)

        childTransition.finish()
        job.join()
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

        // Default transition from A to B.
        assertThat(state.setTargetScene(SceneB, animationScope = this)).isNotNull()
        assertThat(state.currentTransition?.transformationSpec?.transformations).hasSize(1)

        // Go back to A.
        state.setTargetScene(SceneA, animationScope = this)
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneA)

        // Specific transition from A to B.
        assertThat(
                state.setTargetScene(SceneB, animationScope = this, transitionKey = transitionkey)
            )
            .isNotNull()
        assertThat(state.currentTransition?.transformationSpec?.transformations).hasSize(2)
    }

    @Test
    fun snapToIdleIfClose_snapToStart() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)
        state.startTransitionImmediately(
            animationScope = backgroundScope,
            transition(from = SceneA, to = SceneB, current = { SceneA }, progress = { 0.2f }),
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
        state.startTransitionImmediately(
            animationScope = backgroundScope,
            transition(from = SceneA, to = SceneB, progress = { 0.8f }),
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

        val aToB = transition(from = SceneA, to = SceneB, progress = { 0.5f })
        state.startTransitionImmediately(animationScope = backgroundScope, aToB)
        assertThat(state.currentTransitions).containsExactly(aToB).inOrder()

        val bToC = transition(from = SceneB, to = SceneC, progress = { 0.8f })
        state.startTransitionImmediately(animationScope = backgroundScope, bToC)
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
    fun snapToIdleIfClose_closeButNotCurrentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, SceneTransitions.Empty)
        var progress by mutableStateOf(0f)
        var currentScene by mutableStateOf(SceneB)
        state.startTransitionImmediately(
            animationScope = backgroundScope,
            transition(
                from = SceneA,
                to = SceneB,
                current = { currentScene },
                progress = { progress },
            ),
        )
        assertThat(state.isTransitioning()).isTrue()

        // Ignore the request if we are close to a scene that is not the current scene
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.isTransitioning()).isTrue()

        progress = 1f
        currentScene = SceneA
        assertThat(state.snapToIdleIfClose(threshold = 0.1f)).isFalse()
        assertThat(state.isTransitioning()).isTrue()
    }

    @Test
    fun linkedTransition_fuzzyLinksAreMatchedAndStarted() = runTest {
        val (parentState, childState) = setupLinkedStates(SceneC, SceneA, null, null, null, SceneD)
        val childTransition = transition(SceneA, SceneB)

        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()

        childTransition.finish()
        job.join()
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneB))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneD))
    }

    @Test
    fun linkedTransition_fuzzyLinksAreMatchedAndResetToProperPreviousScene() = runTest {
        val (parentState, childState) =
            setupLinkedStates(SceneC, SceneA, SceneA, null, null, SceneD)

        val childTransition = transition(SceneA, SceneB, current = { SceneA })

        val job =
            childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isTrue()

        childTransition.finish()
        job.join()
        assertThat(childState.transitionState).isEqualTo(TransitionState.Idle(SceneA))
        assertThat(parentState.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun linkedTransition_fuzzyLinksAreNotMatched() = runTest {
        val (parentState, childState) =
            setupLinkedStates(SceneC, SceneA, SceneB, null, SceneC, SceneD)
        val childTransition = transition(SceneA, SceneB)

        childState.startTransitionImmediately(animationScope = backgroundScope, childTransition)
        assertThat(childState.isTransitioning(SceneA, SceneB)).isTrue()
        assertThat(parentState.isTransitioning(SceneC, SceneD)).isFalse()
    }

    private fun MonotonicClockTestScope.startOverscrollableTransistionFromAtoB(
        progress: () -> Float,
        sceneTransitions: SceneTransitions,
    ): MutableSceneTransitionLayoutStateImpl {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, sceneTransitions)
        state.startTransitionImmediately(
            animationScope = backgroundScope,
            transition(
                from = SceneA,
                to = SceneB,
                progress = progress,
                orientation = Orientation.Vertical,
            ),
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
                    },
            )
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasNoOverscrollSpec()

        // overscroll for SceneA is NOT defined
        progress.value = -0.1f
        assertThat(transition).hasNoOverscrollSpec()

        // scroll from SceneA to SceneB
        progress.value = 0.5f
        assertThat(transition).hasNoOverscrollSpec()

        progress.value = 1f
        assertThat(transition).hasNoOverscrollSpec()

        // overscroll for SceneB is defined
        progress.value = 1.1f
        val overscrollSpec = assertThat(transition).hasOverscrollSpec()
        assertThat(overscrollSpec.content).isEqualTo(SceneB)
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
                    },
            )

        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasNoOverscrollSpec()

        // overscroll for SceneA is defined
        progress.value = -0.1f
        val overscrollSpec = assertThat(transition).hasOverscrollSpec()
        assertThat(overscrollSpec.content).isEqualTo(SceneA)

        // scroll from SceneA to SceneB
        progress.value = 0.5f
        assertThat(transition).hasNoOverscrollSpec()

        progress.value = 1f
        assertThat(transition).hasNoOverscrollSpec()

        // overscroll for SceneB is NOT defined
        progress.value = 1.1f
        assertThat(transition).hasNoOverscrollSpec()
    }

    @Test
    fun overscrollDsl_notDefinedScenes() = runMonotonicClockTest {
        val progress = mutableStateOf(0f)
        val state =
            startOverscrollableTransistionFromAtoB(
                progress = { progress.value },
                sceneTransitions = transitions {},
            )

        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasNoOverscrollSpec()

        // overscroll for SceneA is NOT defined
        progress.value = -0.1f
        assertThat(transition).hasNoOverscrollSpec()

        // scroll from SceneA to SceneB
        progress.value = 0.5f
        assertThat(transition).hasNoOverscrollSpec()

        progress.value = 1f
        assertThat(transition).hasNoOverscrollSpec()

        // overscroll for SceneB is NOT defined
        progress.value = 1.1f
        assertThat(transition).hasNoOverscrollSpec()
    }

    @Test
    fun multipleTransitions() = runTest {
        val frozenTransitions = mutableSetOf<TestSceneTransition>()
        fun onFreezeAndAnimate(transition: TestSceneTransition): () -> Unit {
            // Instead of letting the transition finish when it is frozen, we put the transition in
            // the frozenTransitions set so that we can verify that freezeAndAnimateToCurrentState()
            // is called when expected and then we call finish() ourselves to finish the
            // transitions.
            frozenTransitions.add(transition)

            return { /* do nothing */ }
        }

        val state = MutableSceneTransitionLayoutStateImpl(SceneA, EmptyTestTransitions)
        val aToB = transition(SceneA, SceneB, onFreezeAndAnimate = ::onFreezeAndAnimate)
        val bToC = transition(SceneB, SceneC, onFreezeAndAnimate = ::onFreezeAndAnimate)
        val cToA = transition(SceneC, SceneA, onFreezeAndAnimate = ::onFreezeAndAnimate)

        // Starting state.
        assertThat(frozenTransitions).isEmpty()
        assertThat(state.currentTransitions).isEmpty()

        // A => B.
        val aToBJob = state.startTransitionImmediately(animationScope = backgroundScope, aToB)
        assertThat(frozenTransitions).isEmpty()
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB).inOrder()

        // B => C. This should automatically call freezeAndAnimateToCurrentState() on aToB.
        val bToCJob = state.startTransitionImmediately(animationScope = backgroundScope, bToC)
        assertThat(frozenTransitions).containsExactly(aToB)
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB, bToC).inOrder()

        // C => A. This should automatically call freezeAndAnimateToCurrentState() on bToC.
        state.startTransitionImmediately(animationScope = backgroundScope, cToA)
        assertThat(frozenTransitions).containsExactly(aToB, bToC)
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        // Mark bToC as finished. The list of current transitions does not change because aToB is
        // still not marked as finished.
        bToC.finish()
        bToCJob.join()
        assertThat(state.finishedTransitions).containsExactly(bToC)
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        // Mark aToB as finished. This will remove both aToB and bToC from the list of transitions.
        aToB.finish()
        aToBJob.join()
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(cToA).inOrder()
    }

    @Test
    fun tooManyTransitionsLogsWtfAndClearsTransitions() = runTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA, EmptyTestTransitions)

        fun startTransition() {
            val transition =
                transition(SceneA, SceneB, onFreezeAndAnimate = { launch { /* do nothing */ } })
            state.startTransitionImmediately(animationScope = backgroundScope, transition)
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
        state.setTargetScene(SceneB, animationScope = this)
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasCurrentScene(SceneB)

        // Snap to C.
        state.snapToScene(SceneC)
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneC)
    }

    @Test
    fun snapToScene_freezesCurrentTransition() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateImpl(SceneA)

        // Start a transition that is never finished. We don't use backgroundScope on purpose so
        // that this test would fail if the transition was not frozen when snapping.
        state.startTransitionImmediately(animationScope = this, transition(SceneA, SceneB))
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)

        // Snap to C.
        state.snapToScene(SceneC)
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneC)
    }

    @Test
    fun seekToScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        val progress = Channel<Float>()

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                state.seekToScene(SceneB, progress.consumeAsFlow())
            }

        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0f)

        // Change progress.
        progress.send(0.4f)
        assertThat(transition).hasProgress(0.4f)

        // Close the channel normally to confirm the transition.
        progress.close()
        job.join()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun seekToScene_cancelled() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        val progress = Channel<Float>()

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                state.seekToScene(SceneB, progress.consumeAsFlow())
            }

        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0f)

        // Change progress.
        progress.send(0.4f)
        assertThat(transition).hasProgress(0.4f)

        // Close the channel with a CancellationException to cancel the transition.
        progress.close(CancellationException())
        job.join()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneA)
    }

    @Test
    fun seekToScene_interrupted() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(SceneA)
        val progress = Channel<Float>()

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                state.seekToScene(SceneB, progress.consumeAsFlow())
            }

        assertThat(state.transitionState).isSceneTransition()

        // Start a new transition, interrupting the seek transition.
        state.setTargetScene(SceneB, animationScope = this)

        // The previous job is cancelled and does not infinitely collect the progress.
        job.join()
    }
}
