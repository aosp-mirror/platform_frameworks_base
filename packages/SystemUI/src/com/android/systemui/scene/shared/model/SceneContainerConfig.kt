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

import com.android.compose.animation.scene.SceneKey

/** Models the configuration of the scene container. */
data class SceneContainerConfig(

    /**
     * The keys to all scenes in the container, sorted by z-order such that the last one renders on
     * top of all previous ones. Scene keys within the same container must not repeat but it's okay
     * to have the same scene keys in different containers.
     */
    val sceneKeys: List<SceneKey>,

    /**
     * The key of the scene that is the initial current scene when the container is first set up,
     * before taking any application state in to account.
     */
    val initialSceneKey: SceneKey,
) {
    init {
        check(sceneKeys.isNotEmpty()) { "A container must have at least one scene key." }

        check(sceneKeys.contains(initialSceneKey)) {
            "The initial key \"$initialSceneKey\" is not present in this container."
        }
    }
}
