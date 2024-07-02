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

@file:OptIn(ExperimentalComposeUiApi::class)

package com.android.systemui.scene.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.observableTransitionState
import com.android.systemui.ribbon.ui.composable.BottomRightCornerRibbon
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel

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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SceneContainer(
    viewModel: SceneContainerViewModel,
    sceneByKey: Map<SceneKey, ComposableScene>,
    dataSourceDelegator: SceneDataSourceDelegator,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val currentSceneKey: SceneKey by viewModel.currentScene.collectAsStateWithLifecycle()
    val currentDestinations by
        viewModel.currentDestinationScenes(coroutineScope).collectAsStateWithLifecycle()
    val state: MutableSceneTransitionLayoutState = remember {
        MutableSceneTransitionLayoutState(
            initialScene = currentSceneKey,
            canChangeScene = { toScene -> viewModel.canChangeScene(toScene) },
            transitions = SceneContainerTransitions,
            enableInterruptions = false,
        )
    }

    DisposableEffect(state) {
        val dataSource = SceneTransitionLayoutDataSource(state, coroutineScope)
        dataSourceDelegator.setDelegate(dataSource)
        onDispose { dataSourceDelegator.setDelegate(null) }
    }

    DisposableEffect(viewModel, state) {
        viewModel.setTransitionState(state.observableTransitionState())
        onDispose { viewModel.setTransitionState(null) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        SceneTransitionLayout(state = state, modifier = modifier.fillMaxSize()) {
            sceneByKey.forEach { (sceneKey, composableScene) ->
                scene(
                    key = sceneKey,
                    userActions =
                        if (sceneKey == currentSceneKey) {
                            currentDestinations
                        } else {
                            viewModel.resolveSceneFamilies(composableScene.destinationScenes.value)
                        },
                ) {
                    with(composableScene) {
                        this@scene.Content(
                            modifier = Modifier.element(sceneKey.rootElementKey).fillMaxSize(),
                        )
                    }
                }
            }
        }

        BottomRightCornerRibbon(
            content = {
                Text(
                    text = "flexi\uD83E\uDD43",
                    color = Color.White,
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
