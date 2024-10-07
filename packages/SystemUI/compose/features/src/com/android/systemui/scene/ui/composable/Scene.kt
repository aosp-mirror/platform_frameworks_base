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

package com.android.systemui.scene.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.lifecycle.Activatable

/**
 * Defines interface for classes that can describe a "scene".
 *
 * In the scene framework, there can be multiple scenes in a single scene "container". The container
 * takes care of rendering the current scene and allowing scenes to be switched from one to another
 * based on either user action (for example, swiping down while on the lock screen scene may switch
 * to the shade scene).
 */
interface Scene : Activatable, ActionableContent {

    /** Uniquely-identifying key for this scene. The key must be unique within its container. */
    val key: SceneKey

    @Composable fun SceneScope.Content(modifier: Modifier)
}
