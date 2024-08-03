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

package com.android.compose.animation.scene.content.state

import androidx.compose.runtime.Stable
import com.android.compose.animation.scene.SceneKey

/** The state associated to one or more scenes. */
// TODO(b/353679003): Rename to SceneState.
@Stable
sealed interface TransitionState : ContentState<SceneKey> {
    /**
     * The current effective scene. If a new transition was triggered, it would start from this
     * scene.
     *
     * For instance, when swiping from scene A to scene B, the [currentScene] is A when the swipe
     * gesture starts, but then if the user flings their finger and commits the transition to scene
     * B, then [currentScene] becomes scene B even if the transition is not finished yet and is
     * still animating to settle to scene B.
     */
    val currentScene: SceneKey

    /** The scene [currentScene] is idle. */
    data class Idle(
        override val currentScene: SceneKey,
    ) : TransitionState, ContentState.Idle<SceneKey>(currentScene)

    /** There is a transition animating between [fromScene] and [toScene]. */
    abstract class Transition(
        /** The scene this transition is starting from. Can't be the same as toScene */
        val fromScene: SceneKey,

        /** The scene this transition is going to. Can't be the same as fromScene */
        val toScene: SceneKey,

        /** The transition that `this` transition is replacing, if any. */
        replacedTransition: Transition? = null,
    ) : TransitionState, ContentState.Transition<SceneKey>(fromScene, toScene, replacedTransition)
}
