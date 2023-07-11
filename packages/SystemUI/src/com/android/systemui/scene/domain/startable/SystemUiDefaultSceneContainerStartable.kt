/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.domain.startable

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneContainerNames
import com.android.systemui.scene.shared.model.SceneKey
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hooks up business logic that manipulates the state of the [SceneInteractor] for the default
 * system UI scene container (the one named [SceneContainerNames.SYSTEM_UI_DEFAULT]) based on state
 * from other systems.
 */
@SysUISingleton
class SystemUiDefaultSceneContainerStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val sceneInteractor: SceneInteractor,
    private val featureFlags: FeatureFlags,
) : CoreStartable {

    override fun start() {
        if (featureFlags.isEnabled(Flags.SCENE_CONTAINER)) {
            keepVisibilityUpdated()
        }
    }

    /** Drives visibility of the scene container. */
    private fun keepVisibilityUpdated() {
        applicationScope.launch {
            sceneInteractor
                .currentScene(CONTAINER_NAME)
                .map { it.key }
                .distinctUntilChanged()
                .collect { sceneKey ->
                    sceneInteractor.setVisible(CONTAINER_NAME, sceneKey != SceneKey.Gone)
                }
        }
    }

    companion object {
        private const val CONTAINER_NAME = SceneContainerNames.SYSTEM_UI_DEFAULT
    }
}
