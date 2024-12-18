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

package com.android.systemui.volume.panel.component.volume

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlidersComponent
import com.android.systemui.volume.panel.component.volume.ui.viewmodel.audioVolumeComponentViewModel
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.domain.availableCriteria

var Kosmos.volumeSlidersComponent: VolumeSlidersComponent by
    Kosmos.Fixture { VolumeSlidersComponent(audioVolumeComponentViewModel) }
var Kosmos.volumeSlidersAvailabilityCriteria: ComponentAvailabilityCriteria by
    Kosmos.Fixture { availableCriteria }
