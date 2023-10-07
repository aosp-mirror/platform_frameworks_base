/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.shared.model

import kotlinx.coroutines.flow.Flow

/**
 * This is a fork of a class by the same name in the `com.android.compose.animation.scene` package.
 *
 * TODO(b/293899074): remove this fork, once we can compile Compose into System UI.
 */
sealed class ObservableTransitionState {
    /** No transition/animation is currently running. */
    data class Idle(val scene: SceneKey) : ObservableTransitionState()

    /** There is a transition animating between two scenes. */
    data class Transition(
        val fromScene: SceneKey,
        val toScene: SceneKey,
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
    ) : ObservableTransitionState()
}
