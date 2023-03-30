/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.ui

import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * This class serves as a bridge between the old UI classes and the new data pipeline.
 *
 * Once the new pipeline notifies [wifiViewModel] that the wifi icon should be visible, this class
 * notifies [iconController] to inflate the wifi icon (if needed). After that, the [wifiViewModel]
 * has sole responsibility for updating the wifi icon drawable, visibility, etc. and the
 * [iconController] will not do any updates to the icon.
 */
@SysUISingleton
class WifiUiAdapter
@Inject
constructor(
    private val iconController: StatusBarIconController,
    private val wifiViewModel: WifiViewModel,
    private val statusBarPipelineFlags: StatusBarPipelineFlags,
) {
    /**
     * Binds the container for all the status bar icons to a view model, so that we inflate the wifi
     * view once we receive a valid icon from the data pipeline.
     *
     * NOTE: This should go away as we better integrate the data pipeline with the UI.
     *
     * @return the view model used for this particular group in the given [location].
     */
    fun bindGroup(
        statusBarIconGroup: ViewGroup,
        location: StatusBarLocation,
    ): LocationBasedWifiViewModel {
        val locationViewModel =
            when (location) {
                StatusBarLocation.HOME -> wifiViewModel.home
                StatusBarLocation.KEYGUARD -> wifiViewModel.keyguard
                StatusBarLocation.QS -> wifiViewModel.qs
            }

        statusBarIconGroup.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    locationViewModel.wifiIcon.collect { wifiIcon ->
                        // Only notify the icon controller if we want to *render* the new icon.
                        // Note that this flow may still run if
                        // [statusBarPipelineFlags.runNewWifiIconBackend] is true because we may
                        // want to get the logging data without rendering.
                        if (
                            wifiIcon is WifiIcon.Visible && statusBarPipelineFlags.useNewWifiIcon()
                        ) {
                            iconController.setNewWifiIcon()
                        }
                    }
                }
            }
        }

        return locationViewModel
    }
}
