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

package com.android.systemui.volume.dialog.ui.binder

import android.view.View
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.setSnapshotBinding
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation

class VolumeDialogViewBinder
@Inject
constructor(private val volumeDialogViewModelFactory: VolumeDialogViewModel.Factory) {

    suspend fun bind(view: View) {
        view.repeatWhenAttached {
            view.viewModel(
                traceName = "VolumeDialogViewBinder",
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { volumeDialogViewModelFactory.create() },
            ) { viewModel ->
                view.setSnapshotBinding {}

                awaitCancellation()
            }
        }
    }
}
