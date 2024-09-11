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

package com.android.systemui.volume.panel.ui

import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.ui.layout.ComponentsLayoutManager
import com.android.systemui.volume.panel.ui.layout.DefaultComponentsLayoutManager
import dagger.Binds
import dagger.Module
import dagger.Provides

/** UI layer bindings module. */
@Module
interface UiModule {

    @Binds
    fun bindComponentsLayoutManager(impl: DefaultComponentsLayoutManager): ComponentsLayoutManager

    companion object {

        @Provides
        @VolumePanelScope
        @HeaderComponents
        fun provideHeaderComponents(): Collection<VolumePanelComponentKey> {
            return setOf(
                VolumePanelComponents.MEDIA_OUTPUT,
            )
        }

        @Provides
        @VolumePanelScope
        @FooterComponents
        fun provideFooterComponents(): Collection<VolumePanelComponentKey> {
            return listOf(
                VolumePanelComponents.ANC,
                VolumePanelComponents.SPATIAL_AUDIO,
                VolumePanelComponents.CAPTIONING,
            )
        }

        @Provides
        @VolumePanelScope
        @BottomBar
        fun provideBottomBarKey(): VolumePanelComponentKey = VolumePanelComponents.BOTTOM_BAR
    }
}
