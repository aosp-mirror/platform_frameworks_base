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

package com.android.systemui.dreams.homecontrols.domain.interactor

import android.annotation.SuppressLint
import android.app.DreamManager
import android.content.ComponentName
import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.domain.interactor.PackageChangeInteractor
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.kotlin.pairwiseBy
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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
    private val packageChangeInteractor: PackageChangeInteractor,
    private val systemClock: SystemClock,
    private val dreamManager: DreamManager,
    @Background private val bgScope: CoroutineScope
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
        combine(
            allAvailableServices(),
            allAuthorizedPanels,
        ) { serviceInfos, authorizedPanels ->
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
        combine(
                allAvailableAndAuthorizedPanels,
                selectedPanel,
            ) { panels, selected ->
                val item =
                    panels.firstOrNull { it.componentName == selected?.componentName }
                        ?: panels.firstOrNull()
                item?.panelActivity
            }
            .stateIn(bgScope, SharingStarted.WhileSubscribed(), null)

    private val taskFragmentFinished =
        MutableSharedFlow<Long>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun onTaskFragmentEmpty() {
        taskFragmentFinished.tryEmit(systemClock.currentTimeMillis())
    }

    /**
     * Monitors if the current home panel package is updated and causes the dream to finish, and
     * attempts to restart the dream in this case.
     */
    @SuppressLint("MissingPermission")
    suspend fun monitorUpdatesAndRestart() {
        taskFragmentFinished.resetReplayCache()
        panelComponent
            .flatMapLatest { component ->
                if (component == null) return@flatMapLatest emptyFlow()
                packageChangeInteractor.packageChanged(UserHandle.CURRENT, component.packageName)
            }
            .filter { it.isUpdate() }
            // Wait for an UpdatedStarted - UpdateFinished pair to ensure the update has finished.
            .pairwiseBy(::validateUpdatePair)
            .filterNotNull()
            .sample(taskFragmentFinished, ::Pair)
            .filter { (updateStarted, lastFinishedTimestamp) ->
                abs(updateStarted.timeMillis - lastFinishedTimestamp) <=
                    MAX_UPDATE_CORRELATION_DELAY.inWholeMilliseconds
            }
            .collect { dreamManager.startDream() }
    }

    private data class PanelComponent(
        val componentName: ComponentName,
        val panelActivity: ComponentName,
    )

    companion object {
        /**
         * The maximum delay between a package update **starting** and the task fragment finishing
         * which causes us to correlate the package update as the cause of the task fragment
         * finishing.
         */
        val MAX_UPDATE_CORRELATION_DELAY = 500.milliseconds
    }
}

private fun PackageChangeModel.isUpdate() =
    this is PackageChangeModel.UpdateStarted || this is PackageChangeModel.UpdateFinished

private fun validateUpdatePair(
    updateStarted: PackageChangeModel,
    updateFinished: PackageChangeModel
): PackageChangeModel.UpdateStarted? =
    when {
        !updateStarted.isSamePackage(updateFinished) -> null
        updateStarted !is PackageChangeModel.UpdateStarted -> null
        updateFinished !is PackageChangeModel.UpdateFinished -> null
        else -> updateStarted
    }
