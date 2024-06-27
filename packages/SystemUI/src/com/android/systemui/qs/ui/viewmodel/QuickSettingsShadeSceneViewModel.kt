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
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileGridViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeAlignment
import com.android.systemui.shade.ui.viewmodel.OverlayShadeViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Models UI state and handles user input for the Quick Settings Shade scene. */
@SysUISingleton
class QuickSettingsShadeSceneViewModel
@Inject
constructor(
    shadeInteractor: ShadeInteractor,
    val overlayShadeViewModel: OverlayShadeViewModel,
    val brightnessSliderViewModel: BrightnessSliderViewModel,
    val tileGridViewModel: TileGridViewModel,
    val editModeViewModel: EditModeViewModel,
    val qsSceneAdapter: QSSceneAdapter,
) {
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        MutableStateFlow(
                mapOf(
                    if (shadeInteractor.shadeAlignment == ShadeAlignment.Top) {
                        Swipe.Up
                    } else {
                        Swipe.Down
                    } to SceneFamilies.Home,
                    Back to SceneFamilies.Home,
                )
            )
            .asStateFlow()
}
