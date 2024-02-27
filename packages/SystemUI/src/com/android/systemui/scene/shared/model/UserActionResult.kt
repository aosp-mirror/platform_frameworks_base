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

data class UserActionResult(

    /** The scene we should be transitioning due to the [UserAction]. */
    val toScene: SceneKey,

    /**
     * The key of the transition that should be used, if a specific one should be used.
     *
     * If `null`, the transition used will be the corresponding transition from the collection
     * passed into the UI layer.
     */
    val transitionKey: TransitionKey? = null,
)
