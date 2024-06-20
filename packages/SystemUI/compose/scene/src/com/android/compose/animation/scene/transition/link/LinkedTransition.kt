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

import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.TransitionState
import kotlinx.coroutines.Job

/** A linked transition which is driven by a [originalTransition]. */
internal class LinkedTransition(
    private val originalTransition: TransitionState.Transition,
    fromScene: SceneKey,
    toScene: SceneKey,
    override val key: TransitionKey? = null,
) : TransitionState.Transition(fromScene, toScene) {

    override val currentScene: SceneKey
        get() {
            return when (originalTransition.currentScene) {
                originalTransition.fromScene -> fromScene
                originalTransition.toScene -> toScene
                else -> error("Original currentScene is neither FromScene nor ToScene")
            }
        }

    override val isInitiatedByUserInput: Boolean
        get() = originalTransition.isInitiatedByUserInput

    override val isUserInputOngoing: Boolean
        get() = originalTransition.isUserInputOngoing

    override val progress: Float
        get() = originalTransition.progress

    override val progressVelocity: Float
        get() = originalTransition.progressVelocity

    override fun finish(): Job = originalTransition.finish()
}
