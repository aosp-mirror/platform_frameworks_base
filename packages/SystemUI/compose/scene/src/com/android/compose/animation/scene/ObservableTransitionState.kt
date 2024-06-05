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

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * A scene transition state.
 *
 * This models the same thing as [TransitionState], with the following distinctions:
 * 1. [TransitionState] values are backed by the Snapshot system (Compose State objects) and can be
 *    used by callers tracking State reads, for instance in Compose code during the composition,
 *    layout or Compose drawing phases.
 * 2. [ObservableTransitionState] values are backed by Kotlin [Flow]s and can be collected by
 *    non-Compose code to observe value changes.
 * 3. [ObservableTransitionState.Transition.fromScene] and
 *    [ObservableTransitionState.Transition.toScene] will never be equal, while
 *    [TransitionState.Transition.fromScene] and [TransitionState.Transition.toScene] can be equal.
 */
sealed interface ObservableTransitionState {
    /**
     * The current effective scene. If a new transition was triggered, it would start from this
     * scene.
     */
    fun currentScene(): Flow<SceneKey> {
        return when (this) {
            is Idle -> flowOf(currentScene)
            is Transition -> currentScene
        }
    }

    /** No transition/animation is currently running. */
    data class Idle(val currentScene: SceneKey) : ObservableTransitionState

    /** There is a transition animating between two scenes. */
    class Transition(
        val fromScene: SceneKey,
        val toScene: SceneKey,
        val currentScene: Flow<SceneKey>,
        val progress: Flow<Float>,

        /**
         * Whether the transition was originally triggered by user input rather than being
         * programmatic. If this value is initially true, it will remain true until the transition
         * fully completes, even if the user input that triggered the transition has ended. Any
         * sub-transitions launched by this one will inherit this value. For example, if the user
         * drags a pointer but does not exceed the threshold required to transition to another
         * scene, this value will remain true after the pointer is no longer touching the screen and
         * will be true in any transition created to animate back to the original position.
         */
        val isInitiatedByUserInput: Boolean,

        /**
         * Whether user input is currently driving the transition. For example, if a user is
         * dragging a pointer, this emits true. Once they lift their finger, this emits false while
         * the transition completes/settles.
         */
        val isUserInputOngoing: Flow<Boolean>,
    ) : ObservableTransitionState {
        override fun toString(): String =
            """Transition
                |(from=$fromScene,
                | to=$toScene,
                | isInitiatedByUserInput=$isInitiatedByUserInput,
                | isUserInputOngoing=$isUserInputOngoing
                |)"""
                .trimMargin()
    }

    fun isIdle(scene: SceneKey?): Boolean {
        return this is Idle && (scene == null || this.currentScene == scene)
    }

    fun isTransitioning(from: SceneKey? = null, to: SceneKey? = null): Boolean {
        return this is Transition &&
            (from == null || this.fromScene == from) &&
            (to == null || this.toScene == to)
    }
}

/**
 * The current [ObservableTransitionState]. This models the same thing as
 * [SceneTransitionLayoutState.transitionState], except that it is backed by Flows and can be used
 * by non-Compose code to observe state changes.
 */
fun SceneTransitionLayoutState.observableTransitionState(): Flow<ObservableTransitionState> {
    return snapshotFlow {
            when (val state = transitionState) {
                is TransitionState.Idle -> ObservableTransitionState.Idle(state.currentScene)
                is TransitionState.Transition -> {
                    ObservableTransitionState.Transition(
                        fromScene = state.fromScene,
                        toScene = state.toScene,
                        currentScene = snapshotFlow { state.currentScene },
                        progress = snapshotFlow { state.progress },
                        isInitiatedByUserInput = state.isInitiatedByUserInput,
                        isUserInputOngoing = snapshotFlow { state.isUserInputOngoing },
                    )
                }
            }
        }
        .distinctUntilChanged()
}
