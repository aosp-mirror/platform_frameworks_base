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

package com.android.systemui.volume.dialog.settings.domain

import android.app.ActivityManager
import com.android.app.tracing.coroutines.flow.flowName
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@VolumeDialogScope
class VolumeDialogSettingsButtonInteractor
@Inject
constructor(
    @VolumeDialog private val coroutineScope: CoroutineScope,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val volumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
) {

    val isVisible: StateFlow<Boolean> =
        visibilityInteractor.dialogVisibility
            .filterIsInstance(VolumeDialogVisibilityModel.Visible::class)
            .map { model ->
                deviceProvisionedController.isCurrentUserSetup() &&
                    model.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            }
            .flowName("VDSBI#isVisible")
            .stateIn(coroutineScope, SharingStarted.Eagerly, false)

    fun onButtonClicked() {
        volumePanelGlobalStateInteractor.setVisible(true)
        visibilityInteractor.dismissDialog(Events.DISMISS_REASON_SETTINGS_CLICKED)
    }
}
