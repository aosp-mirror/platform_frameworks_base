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
import android.content.IntentFilter
import android.content.res.Resources
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.util.kotlin.launchAndDispose
import com.android.systemui.volume.VolumePanelDialogReceiver
import com.android.systemui.volume.panel.dagger.VolumePanelComponent
import com.android.systemui.volume.panel.dagger.factory.VolumePanelComponentFactory
import com.android.systemui.volume.panel.domain.VolumePanelStartable
import com.android.systemui.volume.panel.domain.interactor.ComponentsInteractor
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.shared.VolumePanelLogger
import com.android.systemui.volume.panel.ui.composable.ComponentsFactory
import com.android.systemui.volume.panel.ui.layout.ComponentsLayout
import com.android.systemui.volume.panel.ui.layout.ComponentsLayoutManager
import com.android.systemui.volume.panel.ui.layout.toLogString
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

private const val TAG = "VolumePanelViewModel"

// Can't inject a constructor here because VolumePanelComponent provides this view model for its
// components.
@OptIn(ExperimentalCoroutinesApi::class)
class VolumePanelViewModel(
    resources: Resources,
    coroutineScope: CoroutineScope,
    daggerComponentFactory: VolumePanelComponentFactory,
    configurationController: ConfigurationController,
    broadcastDispatcher: BroadcastDispatcher,
    private val dumpManager: DumpManager,
    private val logger: VolumePanelLogger,
    private val volumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
) : Dumpable {

    private val volumePanelComponent: VolumePanelComponent =
        daggerComponentFactory.create(this, coroutineScope)

    private val scope: CoroutineScope
        get() = volumePanelComponent.coroutineScope()

    private val componentsInteractor: ComponentsInteractor
        get() = volumePanelComponent.componentsInteractor()

    private val componentsFactory: ComponentsFactory
        get() = volumePanelComponent.componentsFactory()

    private val componentsLayoutManager: ComponentsLayoutManager
        get() = volumePanelComponent.componentsLayoutManager()

    val volumePanelState: StateFlow<VolumePanelState> =
        configurationController.onConfigChanged
            .onStart { emit(resources.configuration) }
            .map { configuration ->
                VolumePanelState(
                        orientation = configuration.orientation,
                        isLargeScreen = resources.getBoolean(R.bool.volume_panel_is_large_screen),
                    )
                    .also { logger.onVolumePanelStateChanged(it) }
            }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                VolumePanelState(
                    orientation = resources.configuration.orientation,
                    isLargeScreen = resources.getBoolean(R.bool.volume_panel_is_large_screen)
                ),
            )
    val componentsLayout: StateFlow<ComponentsLayout?> =
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
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                null,
            )

    init {
        scope.launchAndDispose {
            dumpManager.registerNormalDumpable(TAG, this)
            DisposableHandle { dumpManager.unregisterDumpable(TAG) }
        }

        volumePanelComponent.volumePanelStartables().onEach(VolumePanelStartable::start)
        broadcastDispatcher
            .broadcastFlow(IntentFilter(VolumePanelDialogReceiver.DISMISS_ACTION))
            .onEach { dismissPanel() }
            .launchIn(scope)
    }

    fun dismissPanel() {
        volumePanelGlobalStateInteractor.setVisible(false)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        with(pw) {
            println("volumePanelState=${volumePanelState.value}")
            println("componentsLayout=${componentsLayout.value?.toLogString()}")
        }
    }

    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        private val daggerComponentFactory: VolumePanelComponentFactory,
        private val configurationController: ConfigurationController,
        private val broadcastDispatcher: BroadcastDispatcher,
        private val dumpManager: DumpManager,
        private val logger: VolumePanelLogger,
        private val volumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
    ) {

        fun create(coroutineScope: CoroutineScope): VolumePanelViewModel {
            return VolumePanelViewModel(
                context.resources,
                coroutineScope,
                daggerComponentFactory,
                configurationController,
                broadcastDispatcher,
                dumpManager,
                logger,
                volumePanelGlobalStateInteractor,
            )
        }
    }
}
