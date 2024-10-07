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

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.UserActionResult.ReplaceByOverlay
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.map

/**
 * Models the UI state for the user actions that the user can perform to navigate to other scenes.
 *
 * Different from the [QuickSettingsShadeSceneContentViewModel] which models the _content_ of the
 * scene.
 */
class QuickSettingsShadeUserActionsViewModel
@AssistedInject
constructor(
    val quickSettingsContainerViewModel: QuickSettingsContainerViewModel,
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        quickSettingsContainerViewModel.editModeViewModel.isEditing
            .map { editing ->
                buildMap {
                    put(Swipe.Up, UserActionResult(SceneFamilies.Home))
                    put(
                        Swipe(
                            direction = SwipeDirection.Down,
                            fromSource = SceneContainerEdge.TopLeft
                        ),
                        ReplaceByOverlay(Overlays.NotificationsShade)
                    )
                    if (!editing) {
                        put(Back, UserActionResult(SceneFamilies.Home))
                    }
                }
            }
            .collect { actions -> setActions(actions) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickSettingsShadeUserActionsViewModel
    }
}
