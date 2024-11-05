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

package com.android.systemui.dreams.homecontrols.system.domain.interactor

import android.content.ComponentName
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class HomeControlsComponentInteractor
@Inject
constructor(
    private val selectedComponentRepository: SelectedComponentRepository,
    controlsComponent: ControlsComponent,
    authorizedPanelsRepository: AuthorizedPanelsRepository,
    userRepository: UserRepository,
    @Background private val bgScope: CoroutineScope,
) {
    private val controlsListingController: ControlsListingController? =
        controlsComponent.getControlsListingController().getOrNull()

    /** Gets the current user's selected panel, or null if there isn't one */
    private val selectedPanel: Flow<SelectedComponentRepository.SelectedComponent?> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                selectedComponentRepository.selectedComponentFlow(user.userHandle)
            }
            .map { if (it?.isPanel == true) it else null }

    /** Gets the current user's authorized panels */
    private val allAuthorizedPanels: Flow<Set<String>> =
        userRepository.selectedUserInfo.flatMapLatest { user ->
            authorizedPanelsRepository.observeAuthorizedPanels(user.userHandle)
        }

    /** Gets all the available services from [ControlsListingController] */
    private fun allAvailableServices(): Flow<List<ControlsServiceInfo>> {
        if (controlsListingController == null) {
            return emptyFlow()
        }
        return conflatedCallbackFlow {
                val listener =
                    object : ControlsListingController.ControlsListingCallback {
                        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                            trySend(serviceInfos)
                        }
                    }
                controlsListingController.addCallback(listener)
                awaitClose { controlsListingController.removeCallback(listener) }
            }
            .onStart { emit(controlsListingController.getCurrentServices()) }
    }

    /** Gets all panels which are available and authorized by the user */
    private val allAvailableAndAuthorizedPanels: Flow<List<PanelComponent>> =
        combine(allAvailableServices(), allAuthorizedPanels) { serviceInfos, authorizedPanels ->
            serviceInfos.mapNotNull {
                val panelActivity = it.panelActivity
                if (it.componentName.packageName in authorizedPanels && panelActivity != null) {
                    PanelComponent(it.componentName, panelActivity)
                } else {
                    null
                }
            }
        }

    val panelComponent: StateFlow<ComponentName?> =
        combine(allAvailableAndAuthorizedPanels, selectedPanel) { panels, selected ->
                val item =
                    panels.firstOrNull { it.componentName == selected?.componentName }
                        ?: panels.firstOrNull()
                item?.panelActivity
            }
            .stateIn(bgScope, SharingStarted.Eagerly, null)

    private data class PanelComponent(
        val componentName: ComponentName,
        val panelActivity: ComponentName,
    )
}
