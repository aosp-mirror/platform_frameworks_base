/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.domain.model.ComponentModel
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

interface ComponentsInteractor {

    /**
     * Components collection for the UI layer. Uses [ComponentAvailabilityCriteria] to dynamically
     * determine each component availability.
     */
    val components: Flow<Collection<ComponentModel>>
}

@VolumePanelScope
class ComponentsInteractorImpl
@Inject
constructor(
    enabledComponents: Collection<VolumePanelComponentKey>,
    defaultCriteria: Provider<ComponentAvailabilityCriteria>,
    @VolumePanelScope coroutineScope: CoroutineScope,
    private val criteriaByKey:
        Map<
            VolumePanelComponentKey,
            @JvmSuppressWildcards
            Provider<@JvmSuppressWildcards ComponentAvailabilityCriteria>
        >,
) : ComponentsInteractor {

    override val components: Flow<Collection<ComponentModel>> =
        combine(
                enabledComponents.map { componentKey ->
                    val componentCriteria = (criteriaByKey[componentKey] ?: defaultCriteria).get()
                    componentCriteria.isAvailable().map { isAvailable ->
                        ComponentModel(componentKey, isAvailable = isAvailable)
                    }
                }
            ) {
                it.asList()
            }
            .shareIn(coroutineScope, SharingStarted.Eagerly, replay = 1)
}
