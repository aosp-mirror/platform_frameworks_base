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

package com.android.systemui.volume.panel.component.spatial

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.volume.panel.component.button.ui.composable.ButtonComponent
import com.android.systemui.volume.panel.component.spatial.domain.SpatialAudioAvailabilityCriteria
import com.android.systemui.volume.panel.component.spatial.domain.interactor.spatialAudioComponentInteractor
import com.android.systemui.volume.panel.component.spatial.ui.viewmodel.spatialAudioViewModel

val Kosmos.spatialAudioComponent by
    Kosmos.Fixture { ButtonComponent(spatialAudioViewModel.spatialAudioButton) { _, _ -> } }
val Kosmos.spatialAudioAvailabilityCriteria by
    Kosmos.Fixture { SpatialAudioAvailabilityCriteria(spatialAudioComponentInteractor) }
