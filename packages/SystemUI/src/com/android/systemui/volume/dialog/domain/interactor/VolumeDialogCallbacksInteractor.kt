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

package com.android.systemui.volume.dialog.domain.interactor

import android.annotation.SuppressLint
import android.os.Handler
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

private const val BUFFER_CAPACITY = 16

/**
 * Exposes [VolumeDialogController] callback events in the [event].
 *
 * @see VolumeDialogController.Callbacks
 */
@VolumeDialogPluginScope
class VolumeDialogCallbacksInteractor
@Inject
constructor(
    private val volumeDialogController: VolumeDialogController,
    @VolumeDialogPlugin private val coroutineScope: CoroutineScope,
    @Background private val bgHandler: Handler,
) {

    @SuppressLint("SharedFlowCreation") // event-bus needed
    val event: Flow<VolumeDialogEventModel> =
        callbackFlow {
                val producer = VolumeDialogEventModelProducer(this)
                volumeDialogController.addCallback(producer, bgHandler)
                awaitClose { volumeDialogController.removeCallback(producer) }
            }
            .buffer(BUFFER_CAPACITY)
            .shareIn(replay = 0, scope = coroutineScope, started = SharingStarted.WhileSubscribed())

    private class VolumeDialogEventModelProducer(
        private val scope: ProducerScope<VolumeDialogEventModel>
    ) : VolumeDialogController.Callbacks {
        override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
            scope.trySend(
                VolumeDialogEventModel.ShowRequested(
                    reason = reason,
                    keyguardLocked = keyguardLocked,
                    lockTaskModeState = lockTaskModeState,
                )
            )
        }

        override fun onDismissRequested(reason: Int) {
            scope.trySend(VolumeDialogEventModel.DismissRequested(reason))
        }

        override fun onStateChanged(state: VolumeDialogController.State?) {
            if (state != null) {
                scope.trySend(VolumeDialogEventModel.StateChanged(state))
            }
        }

        override fun onLayoutDirectionChanged(layoutDirection: Int) {
            scope.trySend(VolumeDialogEventModel.LayoutDirectionChanged(layoutDirection))
        }

        // Configuration change is never emitted by the VolumeDialogControllerImpl now.
        override fun onConfigurationChanged() = Unit

        override fun onShowVibrateHint() {
            scope.trySend(VolumeDialogEventModel.ShowVibrateHint)
        }

        override fun onShowSilentHint() {
            scope.trySend(VolumeDialogEventModel.ShowSilentHint)
        }

        override fun onScreenOff() {
            scope.trySend(VolumeDialogEventModel.ScreenOff)
        }

        override fun onShowSafetyWarning(flags: Int) {
            scope.trySend(VolumeDialogEventModel.ShowSafetyWarning(flags))
        }

        override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {
            scope.trySend(VolumeDialogEventModel.AccessibilityModeChanged(showA11yStream == true))
        }

        // Captions button is remove from the Volume Dialog
        override fun onCaptionComponentStateChanged(
            isComponentEnabled: Boolean,
            fromTooltip: Boolean,
        ) = Unit

        // Captions button is remove from the Volume Dialog
        override fun onCaptionEnabledStateChanged(isEnabled: Boolean, checkBeforeSwitch: Boolean) =
            Unit

        override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {
            scope.trySend(
                VolumeDialogEventModel.ShowCsdWarning(
                    csdWarning = csdWarning,
                    durationMs = durationMs,
                )
            )
        }

        override fun onVolumeChangedFromKey() {
            scope.trySend(VolumeDialogEventModel.VolumeChangedFromKey)
        }
    }
}
