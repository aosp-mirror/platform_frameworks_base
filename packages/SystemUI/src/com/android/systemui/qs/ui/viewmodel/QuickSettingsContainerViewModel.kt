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

import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickSettingsContainerViewModel
@AssistedInject
constructor(
    brightnessSliderViewModelFactory: BrightnessSliderViewModel.Factory,
    quickQuickSettingsViewModelFactory: QuickQuickSettingsViewModel.Factory,
    @Assisted supportsBrightnessMirroring: Boolean,
    val tileGridViewModel: TileGridViewModel,
    val editModeViewModel: EditModeViewModel,
    val detailsViewModel: DetailsViewModel,
    val toolbarViewModelFactory: ToolbarViewModel.Factory,
) : ExclusiveActivatable() {

    val brightnessSliderViewModel =
        brightnessSliderViewModelFactory.create(supportsBrightnessMirroring)

    val quickQuickSettingsViewModel = quickQuickSettingsViewModelFactory.create()

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { brightnessSliderViewModel.activate() }
            launch { quickQuickSettingsViewModel.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(supportsBrightnessMirroring: Boolean): QuickSettingsContainerViewModel
    }
}
