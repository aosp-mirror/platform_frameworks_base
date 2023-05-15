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
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Business logic and app state accessors for the scene framework. */
@SysUISingleton
class SceneInteractor
@Inject
constructor(
    private val repository: SceneContainerRepository,
) {

    /**
     * Returns the keys of all scenes in the container with the given name.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    fun allSceneKeys(containerName: String): List<SceneKey> {
        return repository.allSceneKeys(containerName)
    }

    /** Sets the scene in the container with the given name. */
    fun setCurrentScene(containerName: String, scene: SceneModel) {
        repository.setCurrentScene(containerName, scene)
    }

    /** The current scene in the container with the given name. */
    fun currentScene(containerName: String): StateFlow<SceneModel> {
        return repository.currentScene(containerName)
    }

    /** Sets the visibility of the container with the given name. */
    fun setVisible(containerName: String, isVisible: Boolean) {
        return repository.setVisible(containerName, isVisible)
    }

    /** Whether the container with the given name is visible. */
    fun isVisible(containerName: String): StateFlow<Boolean> {
        return repository.isVisible(containerName)
    }

    /** Sets scene transition progress to the current scene in the container with the given name. */
    fun setSceneTransitionProgress(containerName: String, progress: Float) {
        repository.setSceneTransitionProgress(containerName, progress)
    }

    /** Progress of the transition into the current scene in the container with the given name. */
    fun sceneTransitionProgress(containerName: String): StateFlow<Float> {
        return repository.sceneTransitionProgress(containerName)
    }
}
