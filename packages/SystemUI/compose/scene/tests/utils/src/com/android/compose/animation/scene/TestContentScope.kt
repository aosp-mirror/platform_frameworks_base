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

package com.android.compose.animation.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * [ContentScope] for tests, which allows a single scene to be drawn in a [SceneTransitionLayout].
 */
@Composable
fun TestContentScope(
    modifier: Modifier = Modifier,
    currentScene: SceneKey = remember { SceneKey("current") },
    content: @Composable ContentScope.() -> Unit,
) {
    val state = remember { MutableSceneTransitionLayoutState(currentScene) }
    SceneTransitionLayout(state, modifier) { scene(currentScene, content = content) }
}
