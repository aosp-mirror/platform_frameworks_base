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
 */

package com.android.systemui.statusbar.pipeline.satellite.ui.binder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.DeviceBasedSatelliteViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.view.SingleBindableStatusBarIconView
import kotlinx.coroutines.launch

object DeviceBasedSatelliteIconBinder {
    fun bind(
        view: SingleBindableStatusBarIconView,
        viewModel: DeviceBasedSatelliteViewModel,
    ): ModernStatusBarViewBinding {
        return SingleBindableStatusBarIconView.withDefaultBinding(
            view = view,
            shouldBeVisible = { viewModel.icon.value != null }
        ) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.icon.collect { newIcon ->
                        if (newIcon == null) {
                            view.iconView.setImageDrawable(null)
                        } else {
                            IconViewBinder.bind(newIcon, view.iconView)
                        }
                    }
                }
            }
        }
    }
}
