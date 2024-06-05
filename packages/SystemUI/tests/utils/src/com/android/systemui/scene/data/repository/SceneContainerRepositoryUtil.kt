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

package com.android.systemui.scene.data.repository

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.model.Scenes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

private val mutableTransitionState =
    MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(Scenes.Lockscreen))

fun Kosmos.setSceneTransition(
    transition: ObservableTransitionState,
    scope: TestScope = testScope,
    repository: SceneContainerRepository = sceneContainerRepository
) {
    repository.setTransitionState(mutableTransitionState)
    mutableTransitionState.value = transition
    scope.runCurrent()
}

fun Transition(
    from: SceneKey,
    to: SceneKey,
    currentScene: Flow<SceneKey> = flowOf(to),
    progress: Flow<Float> = flowOf(0f),
    isInitiatedByUserInput: Boolean = false,
    isUserInputOngoing: Flow<Boolean> = flowOf(false),
): ObservableTransitionState.Transition {
    return ObservableTransitionState.Transition(
        fromScene = from,
        toScene = to,
        currentScene = currentScene,
        progress = progress,
        isInitiatedByUserInput = isInitiatedByUserInput,
        isUserInputOngoing = isUserInputOngoing
    )
}

fun Idle(currentScene: SceneKey): ObservableTransitionState.Idle {
    return ObservableTransitionState.Idle(currentScene)
}
