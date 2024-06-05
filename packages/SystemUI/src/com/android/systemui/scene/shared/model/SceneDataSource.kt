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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import kotlinx.coroutines.flow.StateFlow

/** Defines interface for classes that provide access to scene state. */
interface SceneDataSource {

    /**
     * The current scene, as seen by the real data source in the UI layer.
     *
     * During a transition between two scenes, the original scene will still be reflected in
     * [currentScene] until a time when the UI layer decides to commit the change, which is when
     * [currentScene] will have the value of the target/new scene.
     */
    val currentScene: StateFlow<SceneKey>

    /**
     * Asks for an asynchronous scene switch to [toScene], which will use the corresponding
     * installed transition or the one specified by [transitionKey], if provided.
     */
    fun changeScene(
        toScene: SceneKey,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Asks for an instant scene switch to [toScene], without an animated transition of any kind.
     */
    fun snapToScene(
        toScene: SceneKey,
    )
}
