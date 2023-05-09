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

package com.android.systemui.scene.ui.viewmodel

import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.StateFlow

/** Models UI state for a single scene container. */
class SceneContainerViewModel
@AssistedInject
constructor(
    private val interactor: SceneInteractor,
    @Assisted private val containerName: String,
) {
    /**
     * Keys of all scenes in the container.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    val allSceneKeys: List<SceneKey> = interactor.allSceneKeys(containerName)

    /** The current scene. */
    val currentScene: StateFlow<SceneModel> = interactor.currentScene(containerName)

    /** Whether the container is visible. */
    val isVisible: StateFlow<Boolean> = interactor.isVisible(containerName)

    /** Requests a transition to the scene with the given key. */
    fun setCurrentScene(scene: SceneModel) {
        interactor.setCurrentScene(containerName, scene)
    }

    /** Notifies of the progress of a scene transition. */
    fun setSceneTransitionProgress(progress: Float) {
        interactor.setSceneTransitionProgress(containerName, progress)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): SceneContainerViewModel
    }
}
