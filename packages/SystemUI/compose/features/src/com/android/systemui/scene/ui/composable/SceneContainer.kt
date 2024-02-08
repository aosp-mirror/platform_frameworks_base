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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Edge as SceneTransitionEdge
import com.android.compose.animation.scene.ObservableTransitionState as SceneTransitionObservableTransitionState
import com.android.compose.animation.scene.SceneKey as SceneTransitionSceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction as SceneTransitionUserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.observableTransitionState
import com.android.compose.animation.scene.updateSceneTransitionLayoutState
import com.android.systemui.ribbon.ui.composable.BottomRightCornerRibbon
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.Edge
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import kotlinx.coroutines.flow.map

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
    modifier: Modifier = Modifier,
) {
    val currentSceneModel: SceneModel by viewModel.currentScene.collectAsState()
    val currentSceneKey = currentSceneModel.key
    val currentScene = checkNotNull(sceneByKey[currentSceneKey])
    val currentDestinations: Map<UserAction, SceneModel> by
        currentScene.destinationScenes.collectAsState()
    val state =
        updateSceneTransitionLayoutState(
            currentSceneKey.toTransitionSceneKey(),
            onChangeScene = viewModel::onSceneChanged,
            transitions = SceneContainerTransitions,
        )

    DisposableEffect(viewModel, state) {
        viewModel.setTransitionState(state.observableTransitionState().map { it.toModel() })
        onDispose { viewModel.setTransitionState(null) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        SceneTransitionLayout(
            state = state,
            modifier =
                modifier
                    .fillMaxSize()
                    .motionEventSpy { event -> viewModel.onMotionEvent(event) }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Final)
                                viewModel.onMotionEventComplete()
                            }
                        }
                    }
        ) {
            sceneByKey.forEach { (sceneKey, composableScene) ->
                scene(
                    key = sceneKey.toTransitionSceneKey(),
                    userActions =
                        if (sceneKey == currentSceneKey) {
                                currentDestinations
                            } else {
                                composableScene.destinationScenes.value
                            }
                            .map { (userAction, destinationSceneModel) ->
                                toTransitionModels(userAction, destinationSceneModel)
                            }
                            .toMap(),
                ) {
                    with(composableScene) {
                        this@scene.Content(
                            modifier =
                                Modifier.element(sceneKey.toTransitionSceneKey().rootElementKey)
                                    .fillMaxSize(),
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

// TODO(b/293899074): remove this once we can use the one from SceneTransitionLayout.
private fun SceneTransitionObservableTransitionState.toModel(): ObservableTransitionState {
    return when (this) {
        is SceneTransitionObservableTransitionState.Idle ->
            ObservableTransitionState.Idle(scene.toModel().key)
        is SceneTransitionObservableTransitionState.Transition ->
            ObservableTransitionState.Transition(
                fromScene = fromScene.toModel().key,
                toScene = toScene.toModel().key,
                progress = progress,
                isInitiatedByUserInput = isInitiatedByUserInput,
                isUserInputOngoing = isUserInputOngoing,
            )
    }
}

// TODO(b/293899074): remove this once we can use the one from SceneTransitionLayout.
private fun toTransitionModels(
    userAction: UserAction,
    sceneModel: SceneModel,
): Pair<SceneTransitionUserAction, UserActionResult> {
    return userAction.toTransitionUserAction() to sceneModel.key.toTransitionSceneKey()
}

// TODO(b/293899074): remove this once we can use the one from SceneTransitionLayout.
private fun SceneTransitionSceneKey.toModel(): SceneModel {
    return SceneModel(key = identity as SceneKey)
}

// TODO(b/293899074): remove this once we can use the one from SceneTransitionLayout.
private fun UserAction.toTransitionUserAction(): SceneTransitionUserAction {
    return when (this) {
        is UserAction.Swipe ->
            Swipe(
                pointerCount = pointerCount,
                fromSource =
                    when (this.fromEdge) {
                        null -> null
                        Edge.LEFT -> SceneTransitionEdge.Left
                        Edge.TOP -> SceneTransitionEdge.Top
                        Edge.RIGHT -> SceneTransitionEdge.Right
                        Edge.BOTTOM -> SceneTransitionEdge.Bottom
                    },
                direction =
                    when (this.direction) {
                        Direction.LEFT -> SwipeDirection.Left
                        Direction.UP -> SwipeDirection.Up
                        Direction.RIGHT -> SwipeDirection.Right
                        Direction.DOWN -> SwipeDirection.Down
                    }
            )
        is UserAction.Back -> Back
    }
}

private fun SceneContainerViewModel.onSceneChanged(sceneKey: SceneTransitionSceneKey) {
    onSceneChanged(sceneKey.toModel())
}
