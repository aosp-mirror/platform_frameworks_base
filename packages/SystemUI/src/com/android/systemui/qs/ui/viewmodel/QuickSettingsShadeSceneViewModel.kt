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
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeAlignment
import com.android.systemui.shade.ui.viewmodel.OverlayShadeViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the Quick Settings Shade scene. */
@SysUISingleton
class QuickSettingsShadeSceneViewModel
@Inject
constructor(
    private val shadeInteractor: ShadeInteractor,
    val overlayShadeViewModel: OverlayShadeViewModel,
    val quickSettingsContainerViewModel: QuickSettingsContainerViewModel,
    @Application applicationScope: CoroutineScope,
) {

    val isEditing: StateFlow<Boolean> = quickSettingsContainerViewModel.editModeViewModel.isEditing

    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        isEditing
            .map { editing -> destinations(editing) }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                destinations(isEditing.value)
            )

    private fun destinations(editing: Boolean): Map<UserAction, UserActionResult> {
        return buildMap {
            put(
                if (shadeInteractor.shadeAlignment == ShadeAlignment.Top) {
                    Swipe.Up
                } else {
                    Swipe.Down
                },
                UserActionResult(SceneFamilies.Home)
            )
            if (!editing) {
                put(Back, UserActionResult(SceneFamilies.Home))
            }
        }
    }
}
