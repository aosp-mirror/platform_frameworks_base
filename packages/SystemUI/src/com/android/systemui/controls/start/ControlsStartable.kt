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
 *
 */

package com.android.systemui.controls.start

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.WorkerThread
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.common.domain.interactor.PackageChangeInteractor
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Started with SystemUI to perform early operations for device controls subsystem (only if enabled)
 *
 * In particular, it will perform the following:
 * * If there is no preferred selection for provider and at least one of the preferred packages
 * provides a panel, it will select the first one that does.
 * * If the preferred selection provides a panel, it will bind to that service (to reduce latency on
 * displaying the panel).
 *
 * It will also perform those operations on user change.
 */
@SysUISingleton
class ControlsStartable
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Background private val executor: Executor,
    private val controlsComponent: ControlsComponent,
    private val userTracker: UserTracker,
    private val authorizedPanelsRepository: AuthorizedPanelsRepository,
    private val selectedComponentRepository: SelectedComponentRepository,
    private val packageChangeInteractor: PackageChangeInteractor,
    private val userManager: UserManager,
    private val broadcastDispatcher: BroadcastDispatcher,
) : CoreStartable {

    // These two controllers can only be accessed after `start` method once we've checked if the
    // feature is enabled
    private val controlsController: ControlsController
        get() = controlsComponent.getControlsController().get()

    private val controlsListingController: ControlsListingController
        get() = controlsComponent.getControlsListingController().get()

    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                controlsController.changeUser(UserHandle.of(newUser))
                startForUser()
            }
        }

    private var packageJob: Job? = null

    override fun start() {}

    override fun onBootCompleted() {
        if (!controlsComponent.isEnabled()) {
            // Controls is disabled, we don't need this anymore
            return
        }
        executor.execute(this::startForUser)
        userTracker.addCallback(userTrackerCallback, executor)
    }

    @WorkerThread
    private fun startForUser() {
        controlsListingController.forceReload()
        selectDefaultPanelIfNecessary()
        bindToPanel()
        monitorPackageUninstall()
    }

    private fun monitorPackageUninstall() {
        packageJob?.cancel()
        packageJob = packageChangeInteractor.packageChanged(userTracker.userHandle)
            .filterIsInstance<PackageChangeModel.Uninstalled>()
            .filter {
                val selectedPackage =
                    selectedComponentRepository.getSelectedComponent()?.componentName?.packageName
                // Selected package was uninstalled
                it.packageName == selectedPackage
            }
            .onEach { selectedComponentRepository.removeSelectedComponent() }
            .flowOn(bgDispatcher)
            .launchIn(scope)
    }

    private fun selectDefaultPanelIfNecessary() {
        if (!selectedComponentRepository.shouldAddDefaultComponent()) {
            return
        }
        val currentSelection = controlsController.getPreferredSelection()
        if (currentSelection == SelectedItem.EMPTY_SELECTION) {
            val availableServices = controlsListingController.getCurrentServices()
            val panels = availableServices.filter { it.panelActivity != null }
            authorizedPanelsRepository
                .getPreferredPackages()
                // Looking for the first element in the string array such that there is one package
                // that has a panel. It will return null if there are no packages in the array,
                // or if no packages in the array have a panel associated with it.
                .firstNotNullOfOrNull { name ->
                    panels.firstOrNull { it.componentName.packageName == name }
                }
                ?.let { info ->
                    controlsController.setPreferredSelection(
                        SelectedItem.PanelItem(info.loadLabel(), info.componentName)
                    )
                }
        }
    }

    private fun bindToPanel() {
        if (userManager.isUserUnlocked(userTracker.userId)) {
            bindToPanelInternal()
        } else {
            broadcastDispatcher.registerReceiver(
                    receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (userManager.isUserUnlocked(userTracker.userId)) {
                                bindToPanelInternal()
                                broadcastDispatcher.unregisterReceiver(this)
                            }
                        }
                    },
                    filter = IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    executor = executor,
                    user = userTracker.userHandle,
            )
        }
    }

    private fun bindToPanelInternal() {
        val currentSelection = controlsController.getPreferredSelection()
        val panels =
                controlsListingController.getCurrentServices().filter { it.panelActivity != null }
        if (currentSelection is SelectedItem.PanelItem &&
                panels.firstOrNull { it.componentName == currentSelection.componentName } != null
        ) {
            controlsController.bindComponentForPanel(currentSelection.componentName)
        }
    }
}
