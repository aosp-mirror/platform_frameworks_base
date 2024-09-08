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
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope

private val mutableTransitionState =
    MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(Scenes.Lockscreen))

suspend fun Kosmos.setTransition(
    sceneTransition: ObservableTransitionState,
    stateTransition: TransitionStep? = null,
    fillInStateSteps: Boolean = true,
    scope: TestScope = testScope,
    repository: SceneContainerRepository = sceneContainerRepository
) {
    var state: TransitionStep? = stateTransition
    if (SceneContainerFlag.isEnabled) {
        setSceneTransition(sceneTransition, scope, repository)

        if (state != null) {
            state = getStateWithUndefined(sceneTransition, state)
        }
    }

    if (state == null) return
    fakeKeyguardTransitionRepository.sendTransitionSteps(
        step = state,
        testScope = scope,
        fillInSteps = fillInStateSteps,
    )
    scope.testScheduler.runCurrent()
}

fun Kosmos.setSceneTransition(
    transition: ObservableTransitionState,
    scope: TestScope = testScope,
    repository: SceneContainerRepository = sceneContainerRepository
) {
    repository.setTransitionState(mutableTransitionState)
    mutableTransitionState.value = transition
    scope.testScheduler.runCurrent()
}

fun Transition(
    from: SceneKey,
    to: SceneKey,
    currentScene: Flow<SceneKey> = flowOf(to),
    progress: Flow<Float> = flowOf(0f),
    isInitiatedByUserInput: Boolean = false,
    isUserInputOngoing: Flow<Boolean> = flowOf(false),
    previewProgress: Flow<Float> = flowOf(0f),
    isInPreviewStage: Flow<Boolean> = flowOf(false)
): ObservableTransitionState.Transition {
    return ObservableTransitionState.Transition(
        fromScene = from,
        toScene = to,
        currentScene = currentScene,
        progress = progress,
        isInitiatedByUserInput = isInitiatedByUserInput,
        isUserInputOngoing = isUserInputOngoing,
        previewProgress = previewProgress,
        isInPreviewStage = isInPreviewStage
    )
}

fun Idle(currentScene: SceneKey): ObservableTransitionState.Idle {
    return ObservableTransitionState.Idle(currentScene)
}

private fun getStateWithUndefined(
    sceneTransition: ObservableTransitionState,
    state: TransitionStep
): TransitionStep {
    return when (sceneTransition) {
        is ObservableTransitionState.Idle -> {
            TransitionStep(
                from = state.from,
                to =
                    if (sceneTransition.currentScene != Scenes.Lockscreen) {
                        KeyguardState.UNDEFINED
                    } else {
                        state.to
                    },
                value = state.value,
                transitionState = state.transitionState
            )
        }
        is ObservableTransitionState.Transition -> {
            TransitionStep(
                from =
                    if (sceneTransition.fromContent != Scenes.Lockscreen) {
                        KeyguardState.UNDEFINED
                    } else {
                        state.from
                    },
                to =
                    if (sceneTransition.toContent != Scenes.Lockscreen) {
                        KeyguardState.UNDEFINED
                    } else {
                        state.from
                    },
                value = state.value,
                transitionState = state.transitionState
            )
        }
        else -> state
    }
}
