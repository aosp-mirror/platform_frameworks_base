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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Defines interface for classes that can describe a "scene".
 *
 * In the scene framework, there can be multiple scenes in a single scene "container". The container
 * takes care of rendering the current scene and allowing scenes to be switched from one to another
 * based on either user action (for example, swiping down while on the lock screen scene may switch
 * to the shade scene).
 *
 * The framework also supports multiple containers, each one with its own configuration.
 */
interface Scene {

    /** Uniquely-identifying key for this scene. The key must be unique within its container. */
    val key: SceneKey

    /**
     * Returns a mapping between [UserAction] and flows that emit a [SceneModel].
     *
     * When the scene framework detects the user action, it starts a transition to the scene
     * described by the latest value in the flow that's mapped from that user action.
     *
     * Once the [Scene] becomes the current one, the scene framework will invoke this method and set
     * up collectors to watch for new values emitted to each of the flows. If a value is added to
     * the map at a given [UserAction], the framework will set up user input handling for that
     * [UserAction] and, if such a user action is detected, the framework will initiate a transition
     * to that [SceneModel].
     *
     * Note that calling this method does _not_ mean that the given user action has occurred.
     * Instead, the method is called before any user action/gesture is detected so that the
     * framework can decide whether to set up gesture/input detectors/listeners for that type of
     * user action.
     *
     * Note that a missing value for a specific [UserAction] means that the user action of the given
     * type is not currently active in the scene and should be ignored by the framework, while the
     * current scene is this one.
     *
     * The API is designed such that it's possible to emit ever-changing values for each
     * [UserAction] to enable, disable, or change the destination scene of a given user action.
     */
    fun destinationScenes(): StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow(emptyMap<UserAction, SceneModel>()).asStateFlow()
}

/** Enumerates all scene framework supported user actions. */
sealed interface UserAction {

    /** The user is scrolling, dragging, swiping, or flinging. */
    data class Swipe(
        /** The direction of the swipe. */
        val direction: Direction,
        /** The number of pointers that were used (for example, one or two fingers). */
        val pointerCount: Int = 1,
    ) : UserAction

    /** The user has hit the back button or performed the back navigation gesture. */
    object Back : UserAction
}

/** Enumerates all known "cardinal" directions for user actions. */
enum class Direction {
    LEFT,
    UP,
    RIGHT,
    DOWN,
}
