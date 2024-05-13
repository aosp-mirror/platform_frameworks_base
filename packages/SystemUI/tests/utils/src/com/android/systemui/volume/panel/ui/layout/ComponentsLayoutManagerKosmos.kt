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

package com.android.systemui.volume.panel.ui.layout

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey

var Kosmos.volumePanelBottomBarComponentKey: VolumePanelComponentKey by
    Kosmos.Fixture { VolumePanelComponents.BOTTOM_BAR }
var Kosmos.volumePanelHeaderComponentKeys: Collection<VolumePanelComponentKey> by
    Kosmos.Fixture { listOf(VolumePanelComponents.MEDIA_OUTPUT) }
var Kosmos.volumePanelFooterComponentKeys: Collection<VolumePanelComponentKey> by
    Kosmos.Fixture {
        listOf(
            VolumePanelComponents.ANC,
            VolumePanelComponents.SPATIAL_AUDIO,
            VolumePanelComponents.CAPTIONING,
        )
    }

var Kosmos.componentsLayoutManager: ComponentsLayoutManager by
    Kosmos.Fixture {
        DefaultComponentsLayoutManager(
            bottomBar = volumePanelBottomBarComponentKey,
            headerComponents = volumePanelHeaderComponentKeys,
            footerComponents = volumePanelFooterComponentKeys,
        )
    }
