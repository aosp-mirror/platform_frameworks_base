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

import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey

/** Models the configuration of the scene container. */
data class SceneContainerConfig(

    /**
     * The keys to all scenes in the container, sorted by z-order such that the last one renders on
     * top of all previous ones. Scene keys within the same container must not repeat but it's okay
     * to have the same scene keys in different containers.
     *
     * Note that this doesn't control how back navigation works; for that, we have
     * [navigationDistances].
     */
    val sceneKeys: List<SceneKey>,

    /**
     * The key of the scene that is the initial current scene when the container is first set up,
     * before taking any application state in to account.
     */
    val initialSceneKey: SceneKey,

    /**
     * The keys to all overlays in the container, sorted by z-order such that the last one renders
     * on top of all previous ones. Overlay keys within the same container must not repeat but it's
     * okay to have the same overlay keys in different containers.
     */
    val overlayKeys: List<OverlayKey> = emptyList(),

    /**
     * Navigation distance of each scene.
     *
     * The navigation distance is a measure of how many non-back user action "steps" away from the
     * starting scene, each scene is.
     *
     * The framework uses these to help scene implementations decide which scene to go back to when
     * the user attempts to navigate back on them, if they need that.
     *
     * In general, the more non-back user actions are needed to get to a scene, the greater that
     * scene's distance should be. Navigating "back" then goes from scenes with a higher distance to
     * scenes with a lower distance.
     *
     * Note that this is not the z-order of rendering; that's determined by the order of declaration
     * of scenes in the [sceneKeys] list.
     */
    val navigationDistances: Map<SceneKey, Int>
) {
    init {
        check(sceneKeys.isNotEmpty()) { "A container must have at least one scene key." }

        check(sceneKeys.contains(initialSceneKey)) {
            "The initial key \"$initialSceneKey\" is not present in this container."
        }

        check(navigationDistances.keys == sceneKeys.toSet()) {
            "Scene keys and distance map must match."
        }
    }
}
