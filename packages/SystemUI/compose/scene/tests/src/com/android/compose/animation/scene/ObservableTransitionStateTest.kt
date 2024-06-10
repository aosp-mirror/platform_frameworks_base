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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
            from = SceneA,
            to = SceneB,
            transitionLayout = { currentScene, onChangeScene ->
                state =
                    updateSceneTransitionLayoutState(
                        currentScene,
                        onChangeScene,
                        EmptyTestTransitions
                    )

                SceneTransitionLayout(state = state) {
                    scene(SceneA) {}
                    scene(SceneB) {}
                }
            }
        ) {
            before {
                assertThat(observableState()).isEqualTo(ObservableTransitionState.Idle(SceneA))
            }
            at(0) {
                val state = observableState()
                assertThat(state).isInstanceOf(ObservableTransitionState.Transition::class.java)
                assertThat((state as ObservableTransitionState.Transition).fromScene)
                    .isEqualTo(SceneA)
                assertThat(state.toScene).isEqualTo(SceneB)
                assertThat(state.progress()).isEqualTo(0f)
            }
            at(TestTransitionDuration / 2) {
                val state = observableState()
                assertThat((state as ObservableTransitionState.Transition).fromScene)
                    .isEqualTo(SceneA)
                assertThat(state.toScene).isEqualTo(SceneB)
                assertThat(state.progress()).isEqualTo(0.5f)
            }
            after {
                assertThat(observableState()).isEqualTo(ObservableTransitionState.Idle(SceneB))
            }
        }
    }

    @Test
    fun observableCurrentScene() = runTestWithSnapshots {
        val state =
            MutableSceneTransitionLayoutStateImpl(
                initialScene = SceneA,
                transitions = transitions {},
            )
        val observableCurrentScene =
            state.observableTransitionState().flatMapLatest { it.currentScene() }

        // Collect observableCurrentScene into currentScene (unfortunately we can't use
        // collectValues in this test target).
        val currentScene =
            object {
                private var _value: SceneKey? = null
                val value: SceneKey
                    get() {
                        runCurrent()
                        return _value ?: error("observableCurrentScene has no value")
                    }

                init {
                    backgroundScope.launch { observableCurrentScene.collect { _value = it } }
                }
            }

        assertThat(currentScene.value).isEqualTo(SceneA)

        // Start a transition to Scene B.
        var transitionCurrentScene by mutableStateOf(SceneA)
        val transition =
            transition(from = SceneA, to = SceneB, current = { transitionCurrentScene })
        state.startTransition(transition)
        assertThat(currentScene.value).isEqualTo(SceneA)

        // Change the transition current scene.
        transitionCurrentScene = SceneB
        assertThat(currentScene.value).isEqualTo(SceneB)

        transitionCurrentScene = SceneA
        assertThat(currentScene.value).isEqualTo(SceneA)
    }

    // See http://shortn/_hj4Mhikmos for inspiration.
    private fun runTestWithSnapshots(testBody: suspend TestScope.() -> Unit) {
        val globalWriteObserverHandle =
            Snapshot.registerGlobalWriteObserver {
                // This is normally done by the compose runtime.
                Snapshot.sendApplyNotifications()
            }

        try {
            runTest(testBody = testBody)
        } finally {
            globalWriteObserverHandle.dispose()
        }
    }
}
