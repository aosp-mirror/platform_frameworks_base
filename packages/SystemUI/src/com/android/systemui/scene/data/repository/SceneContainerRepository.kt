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

package com.android.systemui.scene.data.repository

import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Source of truth for scene framework application state. */
class SceneContainerRepository
@Inject
constructor(
    private val containerConfigByName: Map<String, SceneContainerConfig>,
) {

    private val containerVisibilityByName: Map<String, MutableStateFlow<Boolean>> =
        containerConfigByName
            .map { (containerName, _) -> containerName to MutableStateFlow(true) }
            .toMap()
    private val currentSceneByContainerName: Map<String, MutableStateFlow<SceneModel>> =
        containerConfigByName
            .map { (containerName, config) ->
                containerName to MutableStateFlow(SceneModel(config.initialSceneKey))
            }
            .toMap()
    private val sceneTransitionProgressByContainerName: Map<String, MutableStateFlow<Float>> =
        containerConfigByName
            .map { (containerName, _) -> containerName to MutableStateFlow(1f) }
            .toMap()

    /**
     * Returns the keys to all scenes in the container with the given name.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    fun allSceneKeys(containerName: String): List<SceneKey> {
        return containerConfigByName[containerName]?.sceneKeys
            ?: error(noSuchContainerErrorMessage(containerName))
    }

    /** Sets the current scene in the container with the given name. */
    fun setCurrentScene(containerName: String, scene: SceneModel) {
        check(allSceneKeys(containerName).contains(scene.key)) {
            """
                Cannot set current scene key to "${scene.key}". The container "$containerName" does
                not contain a scene with that key.
            """
                .trimIndent()
        }

        currentSceneByContainerName.setValue(containerName, scene)
    }

    /** The current scene in the container with the given name. */
    fun currentScene(containerName: String): StateFlow<SceneModel> {
        return currentSceneByContainerName.mutableOrError(containerName).asStateFlow()
    }

    /** Sets whether the container with the given name is visible. */
    fun setVisible(containerName: String, isVisible: Boolean) {
        containerVisibilityByName.setValue(containerName, isVisible)
    }

    /** Whether the container with the given name should be visible. */
    fun isVisible(containerName: String): StateFlow<Boolean> {
        return containerVisibilityByName.mutableOrError(containerName).asStateFlow()
    }

    /** Sets scene transition progress to the current scene in the container with the given name. */
    fun setSceneTransitionProgress(containerName: String, progress: Float) {
        sceneTransitionProgressByContainerName.setValue(containerName, progress)
    }

    /** Progress of the transition into the current scene in the container with the given name. */
    fun sceneTransitionProgress(containerName: String): StateFlow<Float> {
        return sceneTransitionProgressByContainerName.mutableOrError(containerName).asStateFlow()
    }

    private fun <T> Map<String, MutableStateFlow<T>>.mutableOrError(
        containerName: String,
    ): MutableStateFlow<T> {
        return this[containerName] ?: error(noSuchContainerErrorMessage(containerName))
    }

    private fun <T> Map<String, MutableStateFlow<T>>.setValue(
        containerName: String,
        value: T,
    ) {
        val mutable = mutableOrError(containerName)
        mutable.value = value
    }

    private fun noSuchContainerErrorMessage(containerName: String): String {
        return """
            No container named "$containerName". Existing containers:
            ${containerConfigByName.values.joinToString(", ") { it.name }}
        """
            .trimIndent()
    }
}
