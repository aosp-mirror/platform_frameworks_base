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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.data.repository

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSource
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Source of truth for scene framework application state. */
class SceneContainerRepository
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val config: SceneContainerConfig,
    private val dataSource: SceneDataSource,
) {
    val currentScene: StateFlow<SceneKey> = dataSource.currentScene

    private val _isVisible = MutableStateFlow(true)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /**
     * Whether there's an ongoing remotely-initiated user interaction.
     *
     * For more information see the logic in `SceneInteractor` that mutates this.
     */
    val isRemoteUserInteractionOngoing = MutableStateFlow(false)

    private val defaultTransitionState = ObservableTransitionState.Idle(config.initialSceneKey)
    private val _transitionState = MutableStateFlow<Flow<ObservableTransitionState>?>(null)
    val transitionState: StateFlow<ObservableTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = defaultTransitionState,
            )

    /**
     * Returns the keys to all scenes in the container.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    fun allSceneKeys(): List<SceneKey> {
        return config.sceneKeys
    }

    fun changeScene(
        toScene: SceneKey,
        transitionKey: TransitionKey? = null,
    ) {
        check(allSceneKeys().contains(toScene)) {
            """
                Cannot set the desired scene key to "$toScene". The configuration does not
                contain a scene with that key.
            """
                .trimIndent()
        }

        dataSource.changeScene(
            toScene = toScene,
            transitionKey = transitionKey,
        )
    }

    /** Sets whether the container is visible. */
    fun setVisible(isVisible: Boolean) {
        _isVisible.value = isVisible
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        _transitionState.value = transitionState
    }
}
