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
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneContainerNames
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
    private val authenticationInteractor: AuthenticationInteractor,
    private val featureFlags: FeatureFlags,
) : CoreStartable {

    override fun start() {
        if (featureFlags.isEnabled(Flags.SCENE_CONTAINER)) {
            hydrateVisibility()
            automaticallySwitchScenes()
        }
    }

    /** Updates the visibility of the scene container based on the current scene. */
    private fun hydrateVisibility() {
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

    /** Switches between scenes based on ever-changing application state. */
    private fun automaticallySwitchScenes() {
        applicationScope.launch {
            authenticationInteractor.isUnlocked
                .map { isUnlocked ->
                    val currentSceneKey = sceneInteractor.currentScene(CONTAINER_NAME).value.key
                    val isBypassEnabled = authenticationInteractor.isBypassEnabled.value
                    when {
                        isUnlocked ->
                            when (currentSceneKey) {
                                // When the device becomes unlocked in Bouncer, go to the Gone.
                                is SceneKey.Bouncer -> SceneKey.Gone
                                // When the device becomes unlocked in Lockscreen, go to Gone if
                                // bypass is enabled.
                                is SceneKey.Lockscreen -> SceneKey.Gone.takeIf { isBypassEnabled }
                                // We got unlocked while on a scene that's not Lockscreen or
                                // Bouncer, no need to change scenes.
                                else -> null
                            }
                        // When the device becomes locked, to Lockscreen.
                        !isUnlocked ->
                            when (currentSceneKey) {
                                // Already on lockscreen or bouncer, no need to change scenes.
                                is SceneKey.Lockscreen,
                                is SceneKey.Bouncer -> null
                                // We got locked while on a scene that's not Lockscreen or Bouncer,
                                // go to Lockscreen.
                                else -> SceneKey.Lockscreen
                            }
                        else -> null
                    }
                }
                .filterNotNull()
                .collect { targetSceneKey ->
                    sceneInteractor.setCurrentScene(
                        containerName = CONTAINER_NAME,
                        scene = SceneModel(targetSceneKey),
                    )
                }
        }
    }

    companion object {
        private const val CONTAINER_NAME = SceneContainerNames.SYSTEM_UI_DEFAULT
    }
}
