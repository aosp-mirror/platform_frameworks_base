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

package com.android.systemui.shade.ui.viewmodel

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Models UI state and handles user input for the overlay shade UI, which shows a shade as an
 * overlay on top of another scene UI.
 */
@SysUISingleton
class OverlayShadeViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val sceneInteractor: SceneInteractor,
) {
    /** The scene to show in the background when the overlay shade is open. */
    val backgroundScene: StateFlow<SceneKey> =
        sceneInteractor
            .resolveSceneFamily(SceneFamilies.Home)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = Scenes.Lockscreen,
            )

    /** Notifies that the user has clicked the semi-transparent background scrim. */
    fun onScrimClicked() {
        sceneInteractor.changeScene(
            toScene = SceneFamilies.Home,
            loggingReason = "Shade scrim clicked",
        )
    }
}
