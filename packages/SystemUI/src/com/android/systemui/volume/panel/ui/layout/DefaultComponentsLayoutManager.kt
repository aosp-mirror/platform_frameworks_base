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

import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.ui.viewmodel.ComponentState
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelState
import javax.inject.Inject

@VolumePanelScope
class DefaultComponentsLayoutManager @Inject constructor() : ComponentsLayoutManager {

    override fun layout(
        volumePanelState: VolumePanelState,
        components: Collection<ComponentState>
    ): ComponentsLayout {
        val bottomBarKey = VolumePanelComponents.BOTTOM_BAR
        return ComponentsLayout(
            components.filter { it.key != bottomBarKey }.sortedBy { it.key },
            components.find { it.key == bottomBarKey }
                ?: error(
                    "VolumePanelComponents.BOTTOM_BAR must be present in the default " +
                        "components layout."
                )
        )
    }
}
