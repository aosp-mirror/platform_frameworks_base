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

package com.android.systemui.volume.dialog.settings.ui.binder

import android.view.View
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.setSnapshotBinding
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.settings.ui.viewmodel.VolumeDialogSettingsButtonViewModel
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogSettingsButtonViewBinder
@Inject
constructor(private val viewModelFactory: VolumeDialogSettingsButtonViewModel.Factory) {

    fun bind(view: View) {
        with(view) {
            val button = requireViewById<View>(R.id.volume_dialog_settings)
            repeatWhenAttached {
                viewModel(
                    traceName = "VolumeDialogViewBinder",
                    minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                    factory = { viewModelFactory.create() },
                ) { viewModel ->
                    setSnapshotBinding {
                        viewModel.isVisible
                            .onEach { isVisible ->
                                visibility = if (isVisible) View.VISIBLE else View.GONE
                            }
                            .launchIn(this)

                        button.setOnClickListener { viewModel.onButtonClicked() }
                    }

                    awaitCancellation()
                }
            }
        }
    }
}
