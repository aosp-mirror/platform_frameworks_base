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

package com.android.systemui.volume.panel.dagger.factory

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.volume.panel.componentsFactory
import com.android.systemui.volume.panel.componentsInteractor
import com.android.systemui.volume.panel.componentsLayoutManager
import com.android.systemui.volume.panel.dagger.VolumePanelComponent
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractor
import com.android.systemui.volume.panel.ui.composable.ComponentsFactory
import com.android.systemui.volume.panel.ui.layout.ComponentsLayoutManager
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import kotlinx.coroutines.CoroutineScope

class KosmosVolumePanelComponentFactory(private val kosmos: Kosmos) : VolumePanelComponentFactory {

    override fun create(viewModel: VolumePanelViewModel): VolumePanelComponent =
        object : VolumePanelComponent {

            override fun coroutineScope(): CoroutineScope = kosmos.testScope.backgroundScope

            override fun componentsInteractor(): ComponentsInteractor = kosmos.componentsInteractor

            override fun componentsFactory(): ComponentsFactory = kosmos.componentsFactory

            override fun componentsLayoutManager(): ComponentsLayoutManager =
                kosmos.componentsLayoutManager
        }
}
