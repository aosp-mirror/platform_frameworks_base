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

package com.android.systemui.volume.panel

import android.content.res.mainResources
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.fakeConfigurationController
import com.android.systemui.util.mockito.mock
import com.android.systemui.volume.panel.dagger.factory.KosmosVolumePanelComponentFactory
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.domain.TestComponentAvailabilityCriteria
import com.android.systemui.volume.panel.domain.VolumePanelStartable
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractor
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractorImpl
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.shared.model.VolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.ComponentsFactory
import com.android.systemui.volume.panel.ui.layout.ComponentsLayoutManager
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import javax.inject.Provider

val Kosmos.mockVolumePanelUiComponent: VolumePanelUiComponent by Kosmos.Fixture { mock {} }
val Kosmos.mockVolumePanelUiComponentProvider: Provider<VolumePanelUiComponent> by
    Kosmos.Fixture { Provider { mockVolumePanelUiComponent } }
var Kosmos.componentByKey: Map<VolumePanelComponentKey, Provider<VolumePanelUiComponent>> by
    Kosmos.Fixture { emptyMap() }
val Kosmos.componentsFactory: ComponentsFactory by
    Kosmos.Fixture { ComponentsFactory(componentByKey) }

var Kosmos.componentsLayoutManager: ComponentsLayoutManager by Kosmos.Fixture()
var Kosmos.enabledComponents: Collection<VolumePanelComponentKey> by
    Kosmos.Fixture { componentByKey.keys }
var Kosmos.volumePanelStartables: Set<VolumePanelStartable> by
    Kosmos.Fixture { emptySet<VolumePanelStartable>() }
val Kosmos.unavailableCriteria: Provider<ComponentAvailabilityCriteria> by
    Kosmos.Fixture { Provider { TestComponentAvailabilityCriteria(false) } }
val Kosmos.availableCriteria: Provider<ComponentAvailabilityCriteria> by
    Kosmos.Fixture { Provider { TestComponentAvailabilityCriteria(true) } }
var Kosmos.defaultCriteria: Provider<ComponentAvailabilityCriteria> by
    Kosmos.Fixture { availableCriteria }
var Kosmos.criteriaByKey: Map<VolumePanelComponentKey, Provider<ComponentAvailabilityCriteria>> by
    Kosmos.Fixture { emptyMap() }
var Kosmos.componentsInteractor: ComponentsInteractor by
    Kosmos.Fixture {
        ComponentsInteractorImpl(
            enabledComponents,
            defaultCriteria,
            testScope.backgroundScope,
            criteriaByKey,
        )
    }

var Kosmos.volumePanelViewModel: VolumePanelViewModel by
    Kosmos.Fixture {
        VolumePanelViewModel(
            mainResources,
            testScope.backgroundScope,
            KosmosVolumePanelComponentFactory(this),
            fakeConfigurationController,
            broadcastDispatcher,
        )
    }
