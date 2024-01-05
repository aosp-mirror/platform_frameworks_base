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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObservableTransitionStateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testObservableTransitionState() = runTest {
        lateinit var state: SceneTransitionLayoutState

        // Collect the current observable state into [observableState].
        // TODO(b/290184746): Use collectValues {} once it is extracted into a library that can be
        // reused by non-SystemUI testing code.
        var observableState: ObservableTransitionState? = null
        backgroundScope.launch {
            state.observableTransitionState().collect { observableState = it }
        }

        fun observableState(): ObservableTransitionState {
            runCurrent()
            return observableState!!
        }

        fun ObservableTransitionState.Transition.progress(): Float {
            var lastProgress = -1f
            backgroundScope.launch { progress.collect { lastProgress = it } }
            runCurrent()
            return lastProgress
        }

        rule.testTransition(
            from = TestScenes.SceneA,
            to = TestScenes.SceneB,
            transitionLayout = { currentScene, onChangeScene ->
                state =
                    updateSceneTransitionLayoutState(
                        currentScene,
                        onChangeScene,
                        EmptyTestTransitions
                    )

                SceneTransitionLayout(state = state) {
                    scene(TestScenes.SceneA) {}
                    scene(TestScenes.SceneB) {}
                }
            }
        ) {
            before {
                assertThat(observableState())
                    .isEqualTo(ObservableTransitionState.Idle(TestScenes.SceneA))
            }
            at(0) {
                val state = observableState()
                assertThat(state).isInstanceOf(ObservableTransitionState.Transition::class.java)
                assertThat((state as ObservableTransitionState.Transition).fromScene)
                    .isEqualTo(TestScenes.SceneA)
                assertThat(state.toScene).isEqualTo(TestScenes.SceneB)
                assertThat(state.progress()).isEqualTo(0f)
            }
            at(TestTransitionDuration / 2) {
                val state = observableState()
                assertThat((state as ObservableTransitionState.Transition).fromScene)
                    .isEqualTo(TestScenes.SceneA)
                assertThat(state.toScene).isEqualTo(TestScenes.SceneB)
                assertThat(state.progress()).isEqualTo(0.5f)
            }
            after {
                assertThat(observableState())
                    .isEqualTo(ObservableTransitionState.Idle(TestScenes.SceneB))
            }
        }
    }
}
