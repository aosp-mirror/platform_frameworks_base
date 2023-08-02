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

import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.SceneTransitionModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Source of truth for scene framework application state. */
class SceneContainerRepository
@Inject
constructor(
    private val config: SceneContainerConfig,
) {

    private val _isVisible = MutableStateFlow(true)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    private val _currentScene = MutableStateFlow(SceneModel(config.initialSceneKey))
    val currentScene: StateFlow<SceneModel> = _currentScene.asStateFlow()

    private val transitionState = MutableStateFlow<Flow<ObservableTransitionState>?>(null)
    val transitionProgress: Flow<Float> =
        transitionState.flatMapLatest { observableTransitionStateFlow ->
            observableTransitionStateFlow?.flatMapLatest { observableTransitionState ->
                when (observableTransitionState) {
                    is ObservableTransitionState.Idle -> flowOf(1f)
                    is ObservableTransitionState.Transition -> observableTransitionState.progress
                }
            }
                ?: flowOf(1f)
        }

    private val _transitions = MutableStateFlow<SceneTransitionModel?>(null)
    val transitions: StateFlow<SceneTransitionModel?> = _transitions.asStateFlow()

    /**
     * Returns the keys to all scenes in the container with the given name.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    fun allSceneKeys(): List<SceneKey> {
        return config.sceneKeys
    }

    /** Sets the current scene in the container with the given name. */
    fun setCurrentScene(scene: SceneModel) {
        check(allSceneKeys().contains(scene.key)) {
            """
                Cannot set current scene key to "${scene.key}". The configuration does not contain a
                scene with that key.
            """
                .trimIndent()
        }

        _currentScene.value = scene
    }

    /** Sets the scene transition in the container with the given name. */
    fun setSceneTransition(from: SceneKey, to: SceneKey) {
        check(allSceneKeys().contains(from)) {
            """
                Cannot set current scene key to "$from". The configuration does not contain a scene
                with that key.
            """
                .trimIndent()
        }
        check(allSceneKeys().contains(to)) {
            """
                Cannot set current scene key to "$to". The configuration does not contain a scene
                with that key.
            """
                .trimIndent()
        }

        _transitions.value = SceneTransitionModel(from = from, to = to)
    }

    /** Sets whether the container with the given name is visible. */
    fun setVisible(isVisible: Boolean) {
        _isVisible.value = isVisible
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        this.transitionState.value = transitionState
    }
}
