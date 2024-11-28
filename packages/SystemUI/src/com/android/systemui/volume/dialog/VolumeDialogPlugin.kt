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

import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.volume.dialog.dagger.VolumeDialogPluginComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class VolumeDialogPlugin
@Inject
constructor(
    @Application private val applicationCoroutineScope: CoroutineScope,
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
                            it.viewModel().launchVolumeDialog()
                        }
                }
            }
    }

    override fun destroy() {
        job?.cancel()
        pluginComponent = null
    }
}
