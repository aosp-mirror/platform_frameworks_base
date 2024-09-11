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

package com.android.systemui.volume.panel.domain.interactor

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.volume.panel.component.bottombar.ui.bottomBarAvailabilityCriteria
import com.android.systemui.volume.panel.component.captioning.captioningAvailabilityCriteria
import com.android.systemui.volume.panel.component.mediaoutput.mediaOutputAvailabilityCriteria
import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.component.spatial.spatialAudioAvailabilityCriteria
import com.android.systemui.volume.panel.component.volume.volumeSlidersAvailabilityCriteria
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.domain.defaultCriteria
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.ui.composable.enabledComponents
import javax.inject.Provider

var Kosmos.criteriaByKey: Map<VolumePanelComponentKey, Provider<ComponentAvailabilityCriteria>> by
    Kosmos.Fixture { emptyMap() }
var Kosmos.prodCriteriaByKey:
    Map<VolumePanelComponentKey, Provider<ComponentAvailabilityCriteria>> by
    Kosmos.Fixture {
        mapOf(
            VolumePanelComponents.MEDIA_OUTPUT to Provider { mediaOutputAvailabilityCriteria },
            VolumePanelComponents.VOLUME_SLIDERS to Provider { volumeSlidersAvailabilityCriteria },
            VolumePanelComponents.CAPTIONING to Provider { captioningAvailabilityCriteria },
            VolumePanelComponents.SPATIAL_AUDIO to Provider { spatialAudioAvailabilityCriteria },
            VolumePanelComponents.BOTTOM_BAR to Provider { bottomBarAvailabilityCriteria },
        )
    }

var Kosmos.componentsInteractor: ComponentsInteractor by
    Kosmos.Fixture {
        ComponentsInteractorImpl(
            enabledComponents,
            { defaultCriteria },
            testScope.backgroundScope,
            criteriaByKey,
        )
    }
