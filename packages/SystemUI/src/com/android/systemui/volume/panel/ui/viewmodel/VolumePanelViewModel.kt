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

package com.android.systemui.volume.panel.ui.viewmodel

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.volume.panel.dagger.VolumePanelComponent
import com.android.systemui.volume.panel.dagger.factory.VolumePanelComponentFactory
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractor
import com.android.systemui.volume.panel.ui.composable.ComponentsFactory
import com.android.systemui.volume.panel.ui.layout.ComponentsLayout
import com.android.systemui.volume.panel.ui.layout.ComponentsLayoutManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class VolumePanelViewModel(
    resources: Resources,
    daggerComponentFactory: VolumePanelComponentFactory,
    configurationController: ConfigurationController,
) : ViewModel() {

    private val volumePanelComponent: VolumePanelComponent = daggerComponentFactory.create(this)

    private val scope: CoroutineScope
        get() = volumePanelComponent.coroutineScope()

    private val componentsInteractor: ComponentsInteractor
        get() = volumePanelComponent.componentsInteractor()

    private val componentsFactory: ComponentsFactory
        get() = volumePanelComponent.componentsFactory()

    private val componentsLayoutManager: ComponentsLayoutManager
        get() = volumePanelComponent.componentsLayoutManager()

    private val mutablePanelVisibility = MutableStateFlow(true)

    val volumePanelState: StateFlow<VolumePanelState> =
        combine(
                configurationController.onConfigChanged
                    .onStart { emit(resources.configuration) }
                    .distinctUntilChanged(),
                mutablePanelVisibility,
            ) { configuration, isVisible ->
                VolumePanelState(
                    orientation = configuration.orientation,
                    isVisible = isVisible,
                    isLargeScreen = resources.getBoolean(R.bool.volume_panel_is_large_screen),
                )
            }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                VolumePanelState(
                    orientation = resources.configuration.orientation,
                    isVisible = mutablePanelVisibility.value,
                    isLargeScreen = resources.getBoolean(R.bool.volume_panel_is_large_screen)
                ),
            )
    val componentsLayout: Flow<ComponentsLayout> =
        combine(
                componentsInteractor.components,
                volumePanelState,
            ) { components, scope ->
                val componentStates =
                    components.map { model ->
                        ComponentState(
                            model.key,
                            componentsFactory.createComponent(model.key),
                            model.isAvailable,
                        )
                    }
                componentsLayoutManager.layout(scope, componentStates)
            }
            .shareIn(
                scope,
                SharingStarted.Eagerly,
                replay = 1,
            )

    fun dismissPanel() {
        mutablePanelVisibility.update { false }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        private val daggerComponentFactory: VolumePanelComponentFactory,
        private val configurationController: ConfigurationController,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass == VolumePanelViewModel::class.java)
            return VolumePanelViewModel(
                context.resources,
                daggerComponentFactory,
                configurationController,
            )
                as T
        }
    }
}
