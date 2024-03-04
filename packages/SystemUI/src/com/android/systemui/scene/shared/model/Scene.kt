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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Defines interface for classes that can describe a "scene".
 *
 * In the scene framework, there can be multiple scenes in a single scene "container". The container
 * takes care of rendering the current scene and allowing scenes to be switched from one to another
 * based on either user action (for example, swiping down while on the lock screen scene may switch
 * to the shade scene).
 */
interface Scene {

    /** Uniquely-identifying key for this scene. The key must be unique within its container. */
    val key: SceneKey

    /**
     * The mapping between [UserAction] and destination [UserActionResult]s.
     *
     * When the scene framework detects a user action, if the current scene has a map entry for that
     * user action, the framework starts a transition to the scene in the map.
     *
     * Once the [Scene] becomes the current one, the scene framework will read this property and set
     * up a collector to watch for new mapping values. If every map entry provided by the scene, the
     * framework will set up user input handling for its [UserAction] and, if such a user action is
     * detected, initiate a transition to the specified [UserActionResult].
     *
     * Note that reading from this method does _not_ mean that any user action has occurred.
     * Instead, the property is read before any user action/gesture is detected so that the
     * framework can decide whether to set up gesture/input detectors/listeners in case user actions
     * of the given types ever occur.
     *
     * Note that a missing value for a specific [UserAction] means that the user action of the given
     * type is not currently active in the scene and should be ignored by the framework, while the
     * current scene is this one.
     */
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>>
}
