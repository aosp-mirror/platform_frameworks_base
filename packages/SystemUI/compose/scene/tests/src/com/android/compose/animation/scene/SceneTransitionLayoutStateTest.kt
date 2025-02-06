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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.subjects.assertThat
import com.android.compose.test.TestSceneTransition
import com.android.compose.test.runMonotonicClockTest
import com.android.compose.test.transition
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransitionLayoutStateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun isTransitioningTo_idle() {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA, SceneTransitions.Empty)

        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.isTransitioning(from = SceneA)).isFalse()
        assertThat(state.isTransitioning(to = SceneB)).isFalse()
        assertThat(state.isTransitioning(from = SceneA, to = SceneB)).isFalse()
    }

    @Test
    fun isTransitioningTo_transition() = runTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA, SceneTransitions.Empty)
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
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)
        assertThat(state.setTargetScene(SceneA, animationScope = this)).isNull()
    }

    @Test
    fun setTargetScene_idleToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)
        val (transition, job) = checkNotNull(state.setTargetScene(SceneB, animationScope = this))
        assertThat(state.transitionState).isEqualTo(transition)

        job.join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

        val (_, job) = checkNotNull(state.setTargetScene(SceneB, animationScope = this))
        assertThat(state.setTargetScene(SceneB, animationScope = this)).isNull()

        job.join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneB))
    }

    @Test
    fun setTargetScene_transitionToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

        assertThat(state.setTargetScene(SceneB, animationScope = this)).isNotNull()
        val (_, job) = checkNotNull(state.setTargetScene(SceneC, animationScope = this))

        job.join()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(SceneC))
    }

    @Test
    fun setTargetScene_coroutineScopeCancelled() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

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

    @Test
    fun setTargetScene_withTransitionKey() = runMonotonicClockTest {
        val transitionkey = TransitionKey(debugName = "foo")
        val state =
            MutableSceneTransitionLayoutStateForTests(
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
        assertThat(state.currentTransition?.transformationSpec?.transformationMatchers).hasSize(1)

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
        assertThat(state.currentTransition?.transformationSpec?.transformationMatchers).hasSize(2)
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

        val state = MutableSceneTransitionLayoutStateForTests(SceneA, EmptyTestTransitions)
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
        val cToAJob = state.startTransitionImmediately(animationScope = backgroundScope, cToA)
        assertThat(frozenTransitions).containsExactly(aToB, bToC)
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        // Mark aToB and bToC as finished. The list of current transitions does not change because
        // cToA is still running.
        aToB.finish()
        aToBJob.join()
        assertThat(state.finishedTransitions).containsExactly(aToB)
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        bToC.finish()
        bToCJob.join()
        assertThat(state.finishedTransitions).containsExactly(aToB, bToC)
        assertThat(state.currentTransitions).containsExactly(aToB, bToC, cToA).inOrder()

        // Mark cToA as finished. This should clear all transitions and settle to idle.
        cToA.finish()
        cToAJob.join()
        assertThat(state.finishedTransitions).isEmpty()
        assertThat(state.currentTransitions).isEmpty()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneA)
    }

    @Test
    fun tooManyTransitionsLogsWtfAndClearsTransitions() = runTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA, EmptyTestTransitions)

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
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

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
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

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
    fun replacedTransitionIsRemovedFromFinishedTransitions() = runTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

        val aToB =
            transition(
                SceneA,
                SceneB,
                onFreezeAndAnimate = {
                    // Do nothing, so that this transition stays in the transitionStates list and we
                    // can finish() it manually later.
                },
            )
        val replacingAToB = transition(SceneB, SceneC)
        val replacingBToC = transition(SceneB, SceneC, replacedTransition = replacingAToB)

        // Start A => B.
        val aToBJob = state.startTransitionImmediately(animationScope = this, aToB)

        // Start B => C and immediately finish it. It will be flagged as finished in
        // STLState.finishedTransitions given that A => B is not finished yet.
        val bToCJob = state.startTransitionImmediately(animationScope = this, replacingAToB)
        replacingAToB.finish()
        bToCJob.join()

        // Start a new B => C that replaces the previously finished B => C.
        val replacingBToCJob =
            state.startTransitionImmediately(animationScope = this, replacingBToC)

        // Finish A => B.
        aToB.finish()
        aToBJob.join()

        // Finish the new B => C.
        replacingBToC.finish()
        replacingBToCJob.join()

        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneC)
    }

    @Test
    fun transition_progressTo() {
        val transition = transition(from = SceneA, to = SceneB, progress = { 0.2f })
        assertThat(transition.progressTo(SceneB)).isEqualTo(0.2f)
        assertThat(transition.progressTo(SceneA)).isEqualTo(1f - 0.2f)
        assertThrows(IllegalArgumentException::class.java) { transition.progressTo(SceneC) }
    }

    @Test
    fun transitionCanBeStartedOnlyOnce() = runTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)
        val transition = transition(from = SceneA, to = SceneB)

        state.startTransitionImmediately(backgroundScope, transition)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { state.startTransition(transition) }
        }
    }

    @Test
    fun transitionFinishedWhenScopeIsEmpty() = runTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

        // Start a transition.
        val transition = transition(from = SceneA, to = SceneB)
        state.startTransitionImmediately(backgroundScope, transition)
        assertThat(state.transitionState).isSceneTransition()

        // Start a job in the transition scope.
        val jobCompletable = CompletableDeferred<Unit>()
        transition.coroutineScope.launch { jobCompletable.await() }

        // Finish the transition (i.e. make its #run() method return). The transition should not be
        // considered as finished yet given that there is a job still running in its scope.
        transition.finish()
        runCurrent()
        assertThat(state.transitionState).isSceneTransition()

        // Finish the job in the scope. Now the transition should be considered as finished.
        jobCompletable.complete(Unit)
        runCurrent()
        assertThat(state.transitionState).isIdle()
    }

    @Test
    fun transitionScopeIsCancelledWhenTransitionIsForceFinished() = runTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)

        // Start a transition.
        val transition = transition(from = SceneA, to = SceneB)
        state.startTransitionImmediately(backgroundScope, transition)
        assertThat(state.transitionState).isSceneTransition()

        // Start a job in the transition scope that never finishes.
        val job = transition.coroutineScope.launch { awaitCancellation() }

        // Force snap state to SceneB to force finish all current transitions.
        state.snapToScene(SceneB)
        assertThat(state.transitionState).isIdle()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun badTransitionSpecThrowsMeaningfulMessageWhenStartingTransition() {
        val state =
            MutableSceneTransitionLayoutStateForTests(
                SceneA,
                transitions {
                    // This transition definition is bad because they both match when transitioning
                    // from A to B.
                    from(SceneA) {}
                    to(SceneB) {}
                },
            )

        val exception =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { state.startTransition(transition(from = SceneA, to = SceneB)) }
            }

        assertThat(exception)
            .hasMessageThat()
            .isEqualTo(
                "Found multiple transition specs for transition SceneKey(debugName=SceneA) => " +
                    "SceneKey(debugName=SceneB)"
            )
    }

    @Test
    fun snapToScene_multipleTransitions() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutStateForTests(SceneA)
        state.startTransitionImmediately(this, transition(SceneA, SceneB))
        state.startTransitionImmediately(this, transition(SceneB, SceneC))
        state.snapToScene(SceneC)

        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneC)
    }

    @Test
    fun trackTransitionCujs() = runTest {
        val started = mutableSetOf<TransitionState.Transition>()
        val finished = mutableSetOf<TransitionState.Transition>()
        val cujWhenStarting = mutableMapOf<TransitionState.Transition, Int?>()
        val state =
            MutableSceneTransitionLayoutStateForTests(
                SceneA,
                transitions {
                    // A <=> B.
                    from(SceneA, to = SceneB, cuj = 1)

                    // A <=> C.
                    from(SceneA, to = SceneC, cuj = 2)
                    from(SceneC, to = SceneA, cuj = 3)
                },
                onTransitionStart = { transition ->
                    started.add(transition)
                    cujWhenStarting[transition] = transition.cuj
                },
                onTransitionEnd = { finished.add(it) },
            )

        val aToB = transition(SceneA, SceneB)
        val bToA = transition(SceneB, SceneA)
        val aToC = transition(SceneA, SceneC)
        val cToA = transition(SceneC, SceneA)

        val animationScope = this
        state.startTransitionImmediately(animationScope, aToB)
        assertThat(started).containsExactly(aToB)
        assertThat(finished).isEmpty()

        state.startTransitionImmediately(animationScope, bToA)
        assertThat(started).containsExactly(aToB, bToA)
        assertThat(finished).isEmpty()

        aToB.finish()
        runCurrent()
        assertThat(finished).containsExactly(aToB)

        state.startTransitionImmediately(animationScope, aToC)
        assertThat(started).containsExactly(aToB, bToA, aToC)
        assertThat(finished).containsExactly(aToB)

        state.startTransitionImmediately(animationScope, cToA)
        assertThat(started).containsExactly(aToB, bToA, aToC, cToA)
        assertThat(finished).containsExactly(aToB)

        bToA.finish()
        aToC.finish()
        cToA.finish()
        runCurrent()
        assertThat(started).containsExactly(aToB, bToA, aToC, cToA)
        assertThat(finished).containsExactly(aToB, bToA, aToC, cToA)

        assertThat(cujWhenStarting[aToB]).isEqualTo(1)
        assertThat(cujWhenStarting[bToA]).isEqualTo(1)
        assertThat(cujWhenStarting[aToC]).isEqualTo(2)
        assertThat(cujWhenStarting[cToA]).isEqualTo(3)
    }
}
