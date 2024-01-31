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
import com.android.compose.test.runMonotonicClockTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransitionLayoutStateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun isTransitioningTo_idle() {
        val state = MutableSceneTransitionLayoutStateImpl(TestScenes.SceneA, SceneTransitions.Empty)

        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.isTransitioning(from = TestScenes.SceneA)).isFalse()
        assertThat(state.isTransitioning(to = TestScenes.SceneB)).isFalse()
        assertThat(state.isTransitioning(from = TestScenes.SceneA, to = TestScenes.SceneB))
            .isFalse()
    }

    @Test
    fun isTransitioningTo_transition() {
        val state = MutableSceneTransitionLayoutStateImpl(TestScenes.SceneA, SceneTransitions.Empty)
        state.startTransition(
            transition(from = TestScenes.SceneA, to = TestScenes.SceneB),
            transitionKey = null
        )

        assertThat(state.isTransitioning()).isTrue()
        assertThat(state.isTransitioning(from = TestScenes.SceneA)).isTrue()
        assertThat(state.isTransitioning(from = TestScenes.SceneB)).isFalse()
        assertThat(state.isTransitioning(to = TestScenes.SceneB)).isTrue()
        assertThat(state.isTransitioning(to = TestScenes.SceneA)).isFalse()
        assertThat(state.isTransitioning(from = TestScenes.SceneA, to = TestScenes.SceneB)).isTrue()
    }

    @Test
    fun setTargetScene_idleToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(TestScenes.SceneA)
        assertThat(state.setTargetScene(TestScenes.SceneA, coroutineScope = this)).isNull()
    }

    @Test
    fun setTargetScene_idleToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(TestScenes.SceneA)
        val transition = state.setTargetScene(TestScenes.SceneB, coroutineScope = this)
        assertThat(transition).isNotNull()
        assertThat(state.transitionState).isEqualTo(transition)

        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneB))
    }

    @Test
    fun setTargetScene_transitionToSameScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(TestScenes.SceneA)
        assertThat(state.setTargetScene(TestScenes.SceneB, coroutineScope = this)).isNotNull()
        assertThat(state.setTargetScene(TestScenes.SceneB, coroutineScope = this)).isNull()
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneB))
    }

    @Test
    fun setTargetScene_transitionToDifferentScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(TestScenes.SceneA)
        assertThat(state.setTargetScene(TestScenes.SceneB, coroutineScope = this)).isNotNull()
        assertThat(state.setTargetScene(TestScenes.SceneC, coroutineScope = this)).isNotNull()
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneC))
    }

    @Test
    fun setTargetScene_transitionToOriginalScene() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(TestScenes.SceneA)
        assertThat(state.setTargetScene(TestScenes.SceneB, coroutineScope = this)).isNotNull()

        // Progress is 0f, so we don't animate at all and directly snap back to A.
        assertThat(state.setTargetScene(TestScenes.SceneA, coroutineScope = this)).isNull()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneA))
    }

    @Test
    fun setTargetScene_coroutineScopeCancelled() = runMonotonicClockTest {
        val state = MutableSceneTransitionLayoutState(TestScenes.SceneA)

        lateinit var transition: TransitionState.Transition
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                transition = state.setTargetScene(TestScenes.SceneB, coroutineScope = this)!!
            }
        assertThat(state.transitionState).isEqualTo(transition)

        // Cancelling the scope/job still sets the state to Idle(targetScene).
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertThat(state.transitionState).isEqualTo(TransitionState.Idle(TestScenes.SceneB))
    }

    @Test
    fun setTargetScene_withTransitionKey() = runMonotonicClockTest {
        val transitionkey = TransitionKey(debugName = "foo")
        val state =
            MutableSceneTransitionLayoutState(
                TestScenes.SceneA,
                transitions =
                    transitions {
                        from(TestScenes.SceneA, to = TestScenes.SceneB) { fade(TestElements.Foo) }
                        from(TestScenes.SceneA, to = TestScenes.SceneB, key = transitionkey) {
                            fade(TestElements.Foo)
                            fade(TestElements.Bar)
                        }
                    },
            )
                as MutableSceneTransitionLayoutStateImpl

        // Default transition from A to B.
        assertThat(state.setTargetScene(TestScenes.SceneB, coroutineScope = this)).isNotNull()
        assertThat(state.transformationSpec.transformations).hasSize(1)

        // Go back to A.
        state.setTargetScene(TestScenes.SceneA, coroutineScope = this)
        testScheduler.advanceUntilIdle()
        assertThat(state.currentTransition).isNull()
        assertThat(state.transitionState.currentScene).isEqualTo(TestScenes.SceneA)

        // Specific transition from A to B.
        assertThat(
                state.setTargetScene(
                    TestScenes.SceneB,
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
