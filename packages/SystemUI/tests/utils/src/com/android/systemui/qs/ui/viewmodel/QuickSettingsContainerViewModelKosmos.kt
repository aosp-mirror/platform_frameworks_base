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

import com.android.systemui.brightness.ui.viewmodel.brightnessSliderViewModelFactory
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.panels.ui.viewmodel.editModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.quickQuickSettingsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.tileGridViewModel

val Kosmos.quickSettingsContainerViewModelFactory by
    Kosmos.Fixture {
        object : QuickSettingsContainerViewModel.Factory {
            override fun create(
                supportsBrightnessMirroring: Boolean
            ): QuickSettingsContainerViewModel {
                return QuickSettingsContainerViewModel(
                    brightnessSliderViewModelFactory,
                    supportsBrightnessMirroring,
                    tileGridViewModel,
                    editModeViewModel,
                    quickQuickSettingsViewModel,
                )
            }
        }
    }
