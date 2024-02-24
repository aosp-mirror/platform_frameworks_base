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

package com.android.systemui.volume.panel.domain

import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractor
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractorImpl
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import dagger.Binds
import dagger.Module
import dagger.Provides

/** Domain layer bindings module. */
@Module
interface DomainModule {

    @Binds fun bindComponentsInteractor(impl: ComponentsInteractorImpl): ComponentsInteractor

    @Binds
    fun bindDefaultComponentAvailabilityCriteria(
        impl: AlwaysAvailableCriteria
    ): ComponentAvailabilityCriteria

    companion object {

        /**
         * Enabled components collection. These are the components processed by Volume Panel logic
         * and possibly shown in the UI.
         *
         * There should be a binding in [VolumePanelScope] for [ComponentAvailabilityCriteria] and
         * [com.android.systemui.volume.panel.ui.VolumePanelComponent] for each component from this
         * collection.
         */
        @Provides
        @VolumePanelScope
        fun provideEnabledComponents(): Collection<VolumePanelComponentKey> {
            return setOf(
                VolumePanelComponents.ANC,
                VolumePanelComponents.CAPTIONING,
                VolumePanelComponents.VOLUME_SLIDERS,
                VolumePanelComponents.MEDIA_OUTPUT,
                VolumePanelComponents.BOTTOM_BAR,
            )
        }
    }
}
