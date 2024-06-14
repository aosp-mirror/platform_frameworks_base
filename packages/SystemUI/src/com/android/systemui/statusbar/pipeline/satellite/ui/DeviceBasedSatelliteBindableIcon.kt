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

package com.android.systemui.statusbar.pipeline.satellite.ui

import android.content.Context
import com.android.internal.telephony.flags.Flags.oemEnabledSatelliteFlag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.icons.shared.model.BindableIcon
import com.android.systemui.statusbar.pipeline.icons.shared.model.ModernStatusBarViewCreator
import com.android.systemui.statusbar.pipeline.satellite.ui.binder.DeviceBasedSatelliteIconBinder
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.DeviceBasedSatelliteViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.view.SingleBindableStatusBarIconView
import javax.inject.Inject

@SysUISingleton
class DeviceBasedSatelliteBindableIcon
@Inject
constructor(
    context: Context,
    viewModel: DeviceBasedSatelliteViewModel,
) : BindableIcon {
    override val slot: String =
        context.getString(com.android.internal.R.string.status_bar_oem_satellite)

    override val initializer = ModernStatusBarViewCreator { context ->
        SingleBindableStatusBarIconView.createView(context).also { view ->
            view.initView(slot) { DeviceBasedSatelliteIconBinder.bind(view, viewModel) }
        }
    }

    override val shouldBindIcon: Boolean = oemEnabledSatelliteFlag()
}
