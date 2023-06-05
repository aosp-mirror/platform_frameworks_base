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

@file:OptIn(ExperimentalAnimationApi::class)

package com.android.systemui.scene.ui.composable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import java.util.Locale

/**
 * Renders a container of a collection of "scenes" that the user can switch between using certain
 * user actions (for instance, swiping up and down) or that can be switched automatically based on
 * application business logic in response to certain events (for example, the device unlocking).
 *
 * It's possible for the application to host several such scene containers, the configuration system
 * allows configuring each container with its own set of scenes. Scenes can be present in multiple
 * containers.
 *
 * @param viewModel The UI state holder for this container.
 * @param sceneByKey Mapping of [ComposableScene] by [SceneKey], ordered by z-order such that the
 *   last scene is rendered on top of all other scenes. It's critical that this map contains exactly
 *   and only the scenes on this container. In other words: (a) there should be no scene in this map
 *   that is not in the configuration for this container and (b) all scenes in the configuration
 *   must have entries in this map.
 * @param modifier A modifier.
 */
@Composable
fun SceneContainer(
    viewModel: SceneContainerViewModel,
    sceneByKey: Map<SceneKey, ComposableScene>,
    modifier: Modifier = Modifier,
) {
    val currentScene: SceneModel by viewModel.currentScene.collectAsState()

    AnimatedContent(
        targetState = currentScene.key,
        label = "scene container",
        modifier = modifier,
    ) { currentSceneKey ->
        sceneByKey.forEach { (key, composableScene) ->
            if (key == currentSceneKey) {
                Scene(
                    scene = composableScene,
                    containerName = viewModel.containerName,
                    onSceneChanged = viewModel::setCurrentScene,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Renders the given [ComposableScene]. */
@Composable
private fun Scene(
    scene: ComposableScene,
    containerName: String,
    onSceneChanged: (SceneModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // TODO(b/280880714): replace with the real UI and make sure to call onTransitionProgress.
    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center),
        ) {
            scene.Content(
                containerName = containerName,
                modifier = Modifier,
            )

            val destinationScenes: Map<UserAction, SceneModel> by
                scene.destinationScenes(containerName).collectAsState()
            val swipeLeftDestinationScene = destinationScenes[UserAction.Swipe(Direction.LEFT)]
            val swipeUpDestinationScene = destinationScenes[UserAction.Swipe(Direction.UP)]
            val swipeRightDestinationScene = destinationScenes[UserAction.Swipe(Direction.RIGHT)]
            val swipeDownDestinationScene = destinationScenes[UserAction.Swipe(Direction.DOWN)]
            val backDestinationScene = destinationScenes[UserAction.Back]

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DirectionalButton(Direction.LEFT, swipeLeftDestinationScene, onSceneChanged)
                DirectionalButton(Direction.UP, swipeUpDestinationScene, onSceneChanged)
                DirectionalButton(Direction.RIGHT, swipeRightDestinationScene, onSceneChanged)
                DirectionalButton(Direction.DOWN, swipeDownDestinationScene, onSceneChanged)
            }

            if (backDestinationScene != null) {
                BackHandler { onSceneChanged.invoke(backDestinationScene) }
            }
        }
    }
}

@Composable
private fun DirectionalButton(
    direction: Direction,
    destinationScene: SceneModel?,
    onSceneChanged: (SceneModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { destinationScene?.let { onSceneChanged.invoke(it) } },
        enabled = destinationScene != null,
        modifier = modifier,
    ) {
        Text(direction.name.lowercase(Locale.getDefault()))
    }
}
