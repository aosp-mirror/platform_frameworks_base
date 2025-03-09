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

package com.android.systemui.volume.dialog.ui.viewmodel

import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogSafetyWarningInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.VolumeDialogLogger
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogPluginScope
class VolumeDialogPluginViewModel
@Inject
constructor(
    @VolumeDialogPlugin private val coroutineScope: CoroutineScope,
    private val dialogVisibilityInteractor: VolumeDialogVisibilityInteractor,
    private val dialogSafetyWarningInteractor: VolumeDialogSafetyWarningInteractor,
    private val volumeDialogProvider: Provider<VolumeDialog>,
    private val logger: VolumeDialogLogger,
) {

    fun launchVolumeDialog() {
        dialogVisibilityInteractor.dialogVisibility
            .mapLatest { visibilityModel ->
                with(visibilityModel) {
                    if (this is VolumeDialogVisibilityModel.Visible) {
                        showDialog()
                        Events.writeEvent(Events.EVENT_SHOW_DIALOG, reason, keyguardLocked)
                        logger.onShow(reason)
                    }
                    if (this is VolumeDialogVisibilityModel.Dismissed) {
                        Events.writeEvent(Events.EVENT_DISMISS_DIALOG, reason)
                        logger.onDismiss(reason)
                    }
                }
            }
            .launchIn(coroutineScope)
    }

    val isShowingSafetyWarning: Flow<Boolean> = dialogSafetyWarningInteractor.isShowingSafetyWarning

    fun onSafetyWarningDismissed() {
        dialogSafetyWarningInteractor.onSafetyWarningDismissed()
    }

    private fun showDialog() {
        volumeDialogProvider
            .get()
            .apply {
                setOnDismissListener {
                    dialogVisibilityInteractor.dismissDialog(Events.DISMISS_REASON_UNKNOWN)
                }
            }
            .show()
    }
}
