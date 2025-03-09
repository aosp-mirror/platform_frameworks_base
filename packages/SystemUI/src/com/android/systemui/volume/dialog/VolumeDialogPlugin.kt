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

package com.android.systemui.volume.dialog

import android.content.Context
import android.media.AudioManager
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.volume.SafetyWarningDialog
import com.android.systemui.volume.dialog.dagger.VolumeDialogPluginComponent
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogPluginViewModel
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeDialogPlugin
@Inject
constructor(
    @Application private val applicationCoroutineScope: CoroutineScope,
    private val context: Context,
    private val audioManager: AudioManager,
    private val volumeDialogPluginComponentFactory: VolumeDialogPluginComponent.Factory,
) : VolumeDialog {

    private var job: Job? = null
    private var pluginComponent: VolumeDialogPluginComponent? = null

    override fun init(windowType: Int, callback: VolumeDialog.Callback?) {
        job =
            applicationCoroutineScope.launch {
                coroutineScopeTraced("[Volume]plugin") {
                    pluginComponent =
                        volumeDialogPluginComponentFactory.create(this).also {
                            bindPlugin(it.viewModel())
                        }
                }
            }
    }

    private fun CoroutineScope.bindPlugin(viewModel: VolumeDialogPluginViewModel) {
        viewModel.launchVolumeDialog()

        viewModel.isShowingSafetyWarning
            .mapLatest { isShowingSafetyWarning ->
                if (isShowingSafetyWarning) {
                    showSafetyWarningVisibility { viewModel.onSafetyWarningDismissed() }
                }
            }
            .launchIn(this)
    }

    override fun destroy() {
        job?.cancel()
        pluginComponent = null
    }

    private suspend fun showSafetyWarningVisibility(onDismissed: () -> Unit) =
        suspendCancellableCoroutine { continuation ->
            val dialog =
                object : SafetyWarningDialog(context, audioManager) {
                    override fun cleanUp() {
                        onDismissed()
                        continuation.resume(Unit)
                    }
                }
            dialog.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }
}
