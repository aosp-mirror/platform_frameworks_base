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

package com.android.systemui.volume.panel.ui.composable

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.volume.panel.component.bottombar.ui.bottomBarComponent
import com.android.systemui.volume.panel.component.captioning.captioningComponent
import com.android.systemui.volume.panel.component.mediaoutput.mediaOutputComponent
import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.component.spatial.spatialAudioComponent
import com.android.systemui.volume.panel.component.volume.volumeSlidersComponent
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.shared.model.VolumePanelUiComponent
import javax.inject.Provider

var Kosmos.componentByKey: Map<VolumePanelComponentKey, Provider<VolumePanelUiComponent>> by
    Kosmos.Fixture { emptyMap() }
var Kosmos.prodComponentByKey: Map<VolumePanelComponentKey, Provider<VolumePanelUiComponent>> by
    Kosmos.Fixture {
        mapOf(
            VolumePanelComponents.MEDIA_OUTPUT to Provider { mediaOutputComponent },
            VolumePanelComponents.VOLUME_SLIDERS to Provider { volumeSlidersComponent },
            VolumePanelComponents.CAPTIONING to Provider { captioningComponent },
            VolumePanelComponents.SPATIAL_AUDIO to Provider { spatialAudioComponent },
            VolumePanelComponents.BOTTOM_BAR to Provider { bottomBarComponent },
        )
    }
var Kosmos.enabledComponents: Collection<VolumePanelComponentKey> by
    Kosmos.Fixture { componentByKey.keys }

val Kosmos.componentsFactory: ComponentsFactory by
    Kosmos.Fixture { ComponentsFactory(componentByKey) }
