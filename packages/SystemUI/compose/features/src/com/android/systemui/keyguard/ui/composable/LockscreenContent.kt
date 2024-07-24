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

package com.android.systemui.keyguard.ui.composable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.transitions
import com.android.systemui.keyguard.ui.composable.blueprint.ComposableLockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import javax.inject.Inject

/**
 * Renders the content of the lockscreen.
 *
 * This is separate from the [LockscreenScene] because it's meant to support usage of this UI from
 * outside the scene container framework.
 */
class LockscreenContent
@Inject
constructor(
    private val viewModel: LockscreenContentViewModel,
    private val blueprints: Set<@JvmSuppressWildcards ComposableLockscreenSceneBlueprint>,
) {

    private val sceneKeyByBlueprint: Map<ComposableLockscreenSceneBlueprint, SceneKey> by lazy {
        blueprints.associateWith { blueprint -> SceneKey(blueprint.id) }
    }
    private val sceneKeyByBlueprintId: Map<String, SceneKey> by lazy {
        sceneKeyByBlueprint.entries.associate { (blueprint, sceneKey) -> blueprint.id to sceneKey }
    }

    @Composable
    fun Content(
        modifier: Modifier = Modifier,
    ) {
        val coroutineScope = rememberCoroutineScope()
        val blueprintId by viewModel.blueprintId(coroutineScope).collectAsState()

        // Switch smoothly between blueprints, any composable tagged with element() will be
        // transition-animated between any two blueprints, if they both display the same element.
        SceneTransitionLayout(
            currentScene = checkNotNull(sceneKeyByBlueprintId[blueprintId]),
            onChangeScene = {},
            transitions =
                transitions { sceneKeyByBlueprintId.values.forEach { sceneKey -> to(sceneKey) } },
            modifier = modifier,
        ) {
            sceneKeyByBlueprint.entries.forEach { (blueprint, sceneKey) ->
                scene(sceneKey) { with(blueprint) { Content(Modifier.fillMaxSize()) } }
            }
        }
    }
}
