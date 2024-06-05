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

import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.ui.BottomBar
import com.android.systemui.volume.panel.ui.FooterComponents
import com.android.systemui.volume.panel.ui.HeaderComponents
import com.android.systemui.volume.panel.ui.viewmodel.ComponentState
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelState
import javax.inject.Inject

@VolumePanelScope
class DefaultComponentsLayoutManager
@Inject
constructor(
    @BottomBar private val bottomBar: VolumePanelComponentKey,
    @HeaderComponents
    private val headerComponents: Collection<VolumePanelComponentKey> = emptyList(),
    @FooterComponents
    private val footerComponents: Collection<VolumePanelComponentKey> = emptyList(),
) : ComponentsLayoutManager {

    override fun layout(
        volumePanelState: VolumePanelState,
        components: Collection<ComponentState>
    ): ComponentsLayout {
        val contentComponents =
            components.filter {
                !headerComponents.contains(it.key) &&
                    !footerComponents.contains(it.key) &&
                    it.key != bottomBar
            }
        val headerComponents =
            components
                .filter { it.key in headerComponents }
                .sortedBy { headerComponents.indexOf(it.key) }
        val footerComponents =
            components
                .filter { it.key in footerComponents }
                .sortedBy { footerComponents.indexOf(it.key) }
        return ComponentsLayout(
            headerComponents = headerComponents,
            contentComponents = contentComponents.sortedBy { it.key },
            footerComponents = footerComponents,
            bottomBarComponent = components.find { it.key == bottomBar }
                    ?: error(
                        "VolumePanelComponents.BOTTOM_BAR must be present in the default " +
                            "components layout."
                    )
        )
    }
}
