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

package com.android.systemui.scene.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.RemoteUserInput
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.SceneTransitionModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generic business logic and app state accessors for the scene framework.
 *
 * Note that this class should not depend on state or logic of other modules or features. Instead,
 * other feature modules should depend on and call into this class when their parts of the
 * application state change.
 */
@SysUISingleton
class SceneInteractor
@Inject
constructor(
    private val repository: SceneContainerRepository,
    private val logger: SceneLogger,
) {

    /**
     * Returns the keys of all scenes in the container with the given name.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    fun allSceneKeys(): List<SceneKey> {
        return repository.allSceneKeys()
    }

    /** Sets the scene in the container with the given name. */
    fun setCurrentScene(scene: SceneModel, loggingReason: String) {
        val currentSceneKey = repository.currentScene.value.key
        if (currentSceneKey == scene.key) {
            return
        }

        logger.logSceneChange(
            from = currentSceneKey,
            to = scene.key,
            reason = loggingReason,
        )
        repository.setCurrentScene(scene)
        repository.setSceneTransition(from = currentSceneKey, to = scene.key)
    }

    /** The current scene in the container with the given name. */
    val currentScene: StateFlow<SceneModel> = repository.currentScene

    /** Sets the visibility of the container with the given name. */
    fun setVisible(isVisible: Boolean, loggingReason: String) {
        val wasVisible = repository.isVisible.value
        if (wasVisible == isVisible) {
            return
        }

        logger.logVisibilityChange(
            from = wasVisible,
            to = isVisible,
            reason = loggingReason,
        )
        return repository.setVisible(isVisible)
    }

    /** Whether the container with the given name is visible. */
    val isVisible: StateFlow<Boolean> = repository.isVisible

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        repository.setTransitionState(transitionState)
    }

    /** Progress of the transition into the current scene in the container with the given name. */
    val transitionProgress: Flow<Float> = repository.transitionProgress

    /**
     * Scene transitions as pairs of keys. A new value is emitted exactly once, each time a scene
     * transition occurs. The flow begins with a `null` value at first, because the initial scene is
     * not something that we transition to from another scene.
     */
    val transitions: StateFlow<SceneTransitionModel?> = repository.transitions

    private val _remoteUserInput: MutableStateFlow<RemoteUserInput?> = MutableStateFlow(null)

    /** A flow of motion events originating from outside of the scene framework. */
    val remoteUserInput: StateFlow<RemoteUserInput?> = _remoteUserInput.asStateFlow()

    /** Handles a remote user input. */
    fun onRemoteUserInput(input: RemoteUserInput) {
        _remoteUserInput.value = input
    }
}
