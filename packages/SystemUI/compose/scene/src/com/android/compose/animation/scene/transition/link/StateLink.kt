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

package com.android.compose.animation.scene.transition.link

import com.android.compose.animation.scene.BaseSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.TransitionState

/** A link between a source (implicit) and [target] `SceneTransitionLayoutState`. */
class StateLink(target: SceneTransitionLayoutState, val transitionLinks: List<TransitionLink>) {

    internal val target = target as BaseSceneTransitionLayoutState

    /**
     * Links two transitions (source and target) together.
     *
     * `null` can be passed to indicate that any SceneKey should match. e.g. passing `null`, `null`,
     * `null`, `SceneA` means that any transition at the source will trigger a transition in the
     * target to `SceneA` from any current scene.
     */
    class TransitionLink(
        val sourceFrom: SceneKey?,
        val sourceTo: SceneKey?,
        val targetFrom: SceneKey?,
        val targetTo: SceneKey,
        val targetTransitionKey: TransitionKey? = null,
    ) {
        init {
            if (
                (sourceFrom != null && sourceFrom == sourceTo) ||
                    (targetFrom != null && targetFrom == targetTo)
            )
                error("From and To can't be the same")
        }

        internal fun isMatchingLink(transition: TransitionState.Transition): Boolean {
            return (sourceFrom == null || sourceFrom == transition.fromScene) &&
                (sourceTo == null || sourceTo == transition.toScene)
        }

        internal fun targetIsInValidState(targetCurrentScene: SceneKey): Boolean {
            return (targetFrom == null || targetFrom == targetCurrentScene) &&
                targetTo != targetCurrentScene
        }
    }
}
