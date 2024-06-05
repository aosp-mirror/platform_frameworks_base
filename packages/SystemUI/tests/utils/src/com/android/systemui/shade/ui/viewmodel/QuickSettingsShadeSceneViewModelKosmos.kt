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

import com.android.systemui.brightness.ui.viewmodel.brightnessSliderViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.qs.panels.ui.viewmodel.editModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.tileGridViewModel
import com.android.systemui.qs.ui.adapter.qsSceneAdapter
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeSceneViewModel

val Kosmos.quickSettingsShadeSceneViewModel: QuickSettingsShadeSceneViewModel by
    Kosmos.Fixture {
        QuickSettingsShadeSceneViewModel(
            applicationScope = applicationCoroutineScope,
            overlayShadeViewModel = overlayShadeViewModel,
            brightnessSliderViewModel = brightnessSliderViewModel,
            tileGridViewModel = tileGridViewModel,
            editModeViewModel = editModeViewModel,
            qsSceneAdapter = qsSceneAdapter,
        )
    }
