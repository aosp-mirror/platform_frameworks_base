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

package com.android.systemui.qs.ui.viewmodel

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Models UI state used to render the content of the quick settings shade overlay.
 *
 * Different from [QuickSettingsShadeOverlayActionsViewModel], which only models user actions that
 * can be performed to navigate to other scenes.
 */
class QuickSettingsShadeOverlayContentViewModel
@AssistedInject
constructor(
    val shadeInteractor: ShadeInteractor,
    val sceneInteractor: SceneInteractor,
    val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    quickSettingsContainerViewModelFactory: QuickSettingsContainerViewModel.Factory,
) : ExclusiveActivatable() {

    val quickSettingsContainerViewModel = quickSettingsContainerViewModelFactory.create(false)

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                sceneInteractor.currentScene.collect { currentScene ->
                    when (currentScene) {
                        // TODO(b/369513770): The ShadeSession should be preserved in this scenario.
                        Scenes.Bouncer ->
                            shadeInteractor.collapseQuickSettingsShade(
                                loggingReason = "bouncer shown while shade is open"
                            )
                    }
                }
            }

            launch {
                shadeInteractor.isShadeTouchable
                    .distinctUntilChanged()
                    .filter { !it }
                    .collect {
                        shadeInteractor.collapseQuickSettingsShade(
                            loggingReason = "device became non-interactive"
                        )
                    }
            }

            launch { quickSettingsContainerViewModel.activate() }
        }

        awaitCancellation()
    }

    fun onScrimClicked() {
        shadeInteractor.collapseQuickSettingsShade(loggingReason = "shade scrim clicked")
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickSettingsShadeOverlayContentViewModel
    }
}
