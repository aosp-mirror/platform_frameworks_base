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

import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.VolumeDialogComponent
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.VolumeDialogLogger
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach

@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogPluginScope
class VolumeDialogPluginViewModel
@Inject
constructor(
    private val componentFactory: VolumeDialogComponent.Factory,
    private val dialogVisibilityInteractor: VolumeDialogVisibilityInteractor,
    private val controller: VolumeDialogController,
    private val logger: VolumeDialogLogger,
) : ExclusiveActivatable() {

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            dialogVisibilityInteractor.dialogVisibility
                .onEach { controller.notifyVisible(it is VolumeDialogVisibilityModel.Visible) }
                .mapLatest { visibilityModel ->
                    with(visibilityModel) {
                        if (this is VolumeDialogVisibilityModel.Visible) {
                            showDialog(reason, keyguardLocked)
                        }
                        if (this is VolumeDialogVisibilityModel.Dismissed) {
                            Events.writeEvent(Events.EVENT_DISMISS_DIALOG, reason)
                            logger.onDismiss(reason)
                        }
                    }
                }
                .launchIn(this)
        }
        awaitCancellation()
    }

    suspend fun showDialog(reason: Int, keyguardLocked: Boolean): Unit = coroutineScope {
        logger.onShow(reason)

        controller.notifyVisible(true)

        val volumeDialogComponent: VolumeDialogComponent = componentFactory.create(this)
        val dialog =
            volumeDialogComponent.volumeDialog().apply {
                setOnDismissListener {
                    volumeDialogComponent.coroutineScope().cancel()
                    dialogVisibilityInteractor.dismissDialog(Events.DISMISS_REASON_UNKNOWN)
                }
            }
        dialog.show()

        Events.writeEvent(Events.EVENT_SHOW_DIALOG, reason, keyguardLocked)
    }
}
