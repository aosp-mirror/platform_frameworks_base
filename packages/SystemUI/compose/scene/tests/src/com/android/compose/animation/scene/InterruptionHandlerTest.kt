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

package com.android.compose.animation.scene

import androidx.compose.animation.core.tween
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.test.runMonotonicClockTest
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterruptionHandlerTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun default() = runMonotonicClockTest {
        val state =
            MutableSceneTransitionLayoutState(
                SceneA,
                transitions { /* default interruption handler */},
            )

        state.setTargetScene(SceneB, coroutineScope = this)
        state.setTargetScene(SceneC, coroutineScope = this)

        assertThat(state.currentTransitions)
            .comparingElementsUsing(FromToCurrentTriple)
            .containsExactly(
                // A to B.
                Triple(SceneA, SceneB, SceneB),

                // B to C.
                Triple(SceneB, SceneC, SceneC),
            )
            .inOrder()
    }

    @Test
    fun chainingDisabled() = runMonotonicClockTest {
        val state =
            MutableSceneTransitionLayoutState(
                SceneA,
                transitions {
                    // Handler that animates from currentScene (default) but disables chaining.
                    interruptionHandler =
                        object : InterruptionHandler {
                            override fun onInterruption(
                                interrupted: TransitionState.Transition,
                                newTargetScene: SceneKey
                            ): InterruptionResult {
                                return InterruptionResult(
                                    animateFrom = interrupted.currentScene,
                                    chain = false,
                                )
                            }
                        }
                },
            )

        state.setTargetScene(SceneB, coroutineScope = this)
        state.setTargetScene(SceneC, coroutineScope = this)

        assertThat(state.currentTransitions)
            .comparingElementsUsing(FromToCurrentTriple)
            .containsExactly(
                // B to C.
                Triple(SceneB, SceneC, SceneC),
            )
            .inOrder()
    }

    @Test
    fun animateFromOtherScene() = runMonotonicClockTest {
        val duration = 500
        val state =
            MutableSceneTransitionLayoutState(
                SceneA,
                transitions {
                    // Handler that animates from the scene that is not currentScene.
                    interruptionHandler =
                        object : InterruptionHandler {
                            override fun onInterruption(
                                interrupted: TransitionState.Transition,
                                newTargetScene: SceneKey
                            ): InterruptionResult {
                                return InterruptionResult(
                                    animateFrom =
                                        if (interrupted.currentScene == interrupted.toScene) {
                                            interrupted.fromScene
                                        } else {
                                            interrupted.toScene
                                        }
                                )
                            }
                        }

                    from(SceneA, to = SceneB) { spec = tween(duration) }
                },
            )

        // Animate to B and advance the transition a little bit so that progress > visibility
        // threshold and that reversing from B back to A won't immediately snap to A.
        state.setTargetScene(SceneB, coroutineScope = this)
        testScheduler.advanceTimeBy(duration / 2L)

        state.setTargetScene(SceneC, coroutineScope = this)

        assertThat(state.currentTransitions)
            .comparingElementsUsing(FromToCurrentTriple)
            .containsExactly(
                // Initial transition A to B. This transition will never be consumed by anyone given
                // that it has the same (from, to) pair as the next transition.
                Triple(SceneA, SceneB, SceneB),

                // Initial transition reversed, B back to A.
                Triple(SceneA, SceneB, SceneA),

                // A to C.
                Triple(SceneA, SceneC, SceneC),
            )
            .inOrder()
    }

    companion object {
        val FromToCurrentTriple =
            Correspondence.transforming(
                { transition: TransitionState.Transition? ->
                    Triple(transition?.fromScene, transition?.toScene, transition?.currentScene)
                },
                "(from, to, current) triple"
            )
    }
}
