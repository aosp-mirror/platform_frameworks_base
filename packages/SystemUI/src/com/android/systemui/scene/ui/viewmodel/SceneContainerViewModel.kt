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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Models UI state for the scene container. */
@SysUISingleton
class SceneContainerViewModel
@Inject
constructor(
    private val interactor: SceneInteractor,
) {
    /**
     * Keys of all scenes in the container.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    val allSceneKeys: List<SceneKey> = interactor.allSceneKeys()

    /** The scene that should be rendered. */
    val currentScene: StateFlow<SceneModel> = interactor.desiredScene

    /** Whether the container is visible. */
    val isVisible: StateFlow<Boolean> = interactor.isVisible

    /** Notifies that the UI has transitioned sufficiently to the given scene. */
    fun onSceneChanged(scene: SceneModel) {
        interactor.onSceneChanged(
            scene = scene,
            loggingReason = SCENE_TRANSITION_LOGGING_REASON,
        )
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        interactor.setTransitionState(transitionState)
    }

    companion object {
        private const val SCENE_TRANSITION_LOGGING_REASON = "user input"
    }
}
