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

package com.android.systemui.communal.domain.interactor

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.communal.data.repository.CommunalSceneRepository
import com.android.systemui.communal.domain.model.CommunalTransitionProgressModel
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalSceneInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val communalSceneRepository: CommunalSceneRepository,
) {
    /**
     * Asks for an asynchronous scene witch to [newScene], which will use the corresponding
     * installed transition or the one specified by [transitionKey], if provided.
     */
    fun changeScene(newScene: SceneKey, transitionKey: TransitionKey? = null) {
        communalSceneRepository.changeScene(newScene, transitionKey)
    }

    /** Immediately snaps to the new scene. */
    fun snapToScene(newScene: SceneKey, delayMillis: Long = 0) {
        communalSceneRepository.snapToScene(newScene, delayMillis)
    }

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through [changeScene].
     */
    val currentScene: Flow<SceneKey> = communalSceneRepository.currentScene

    /** Transition state of the hub mode. */
    val transitionState: StateFlow<ObservableTransitionState> =
        communalSceneRepository.transitionState

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        communalSceneRepository.setTransitionState(transitionState)
    }

    /** Returns a flow that tracks the progress of transitions to the given scene from 0-1. */
    fun transitionProgressToScene(targetScene: SceneKey) =
        transitionState
            .flatMapLatest { state ->
                when (state) {
                    is ObservableTransitionState.Idle ->
                        flowOf(CommunalTransitionProgressModel.Idle(state.currentScene))
                    is ObservableTransitionState.Transition ->
                        if (state.toScene == targetScene) {
                            state.progress.map {
                                CommunalTransitionProgressModel.Transition(
                                    // Clamp the progress values between 0 and 1 as actual progress
                                    // values can be higher than 0 or lower than 1 due to a fling.
                                    progress = it.coerceIn(0.0f, 1.0f)
                                )
                            }
                        } else {
                            flowOf(CommunalTransitionProgressModel.OtherTransition)
                        }
                }
            }
            .distinctUntilChanged()

    /**
     * Flow that emits a boolean if the communal UI is fully visible and not in transition.
     *
     * This will not be true while transitioning to the hub and will turn false immediately when a
     * swipe to exit the hub starts.
     */
    val isIdleOnCommunal: StateFlow<Boolean> =
        transitionState
            .map {
                it is ObservableTransitionState.Idle && it.currentScene == CommunalScenes.Communal
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Flow that emits a boolean if any portion of the communal UI is visible at all.
     *
     * This flow will be true during any transition and when idle on the communal scene.
     */
    val isCommunalVisible: Flow<Boolean> =
        transitionState.map {
            !(it is ObservableTransitionState.Idle && it.currentScene == CommunalScenes.Blank)
        }
}
