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

import com.android.systemui.volume.panel.dagger.VolumePanelComponent
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.shared.model.VolumePanelUiComponent
import javax.inject.Inject
import javax.inject.Provider

/** Provides [VolumePanelComponent] implementation for each [VolumePanelComponentKey]. */
@VolumePanelScope
class ComponentsFactory
@Inject
constructor(
    private val componentByKey:
        Map<
            VolumePanelComponentKey,
            @JvmSuppressWildcards
            Provider<@JvmSuppressWildcards VolumePanelUiComponent>
        >
) {

    fun createComponent(key: VolumePanelComponentKey): VolumePanelUiComponent {
        require(componentByKey.containsKey(key)) { "Component for key=$key is not bound." }
        return componentByKey.getValue(key).get()
    }
}
