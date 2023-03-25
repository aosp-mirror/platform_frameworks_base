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

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.graphics.Color
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags

/**
 * A view model for a wifi icon in a specific location. This allows us to control parameters that
 * are location-specific (for example, different tints of the icon in different locations).
 *
 * Must be subclassed for each distinct location.
 */
abstract class LocationBasedWifiViewModel(
    val commonImpl: WifiViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
    debugTint: Int,
) : WifiViewModelCommon by commonImpl {
    val useDebugColoring: Boolean = statusBarPipelineFlags.useDebugColoring()

    val defaultColor: Int =
        if (useDebugColoring) {
            debugTint
        } else {
            Color.WHITE
        }

    companion object {
        /**
         * Returns a new instance of [LocationBasedWifiViewModel] that's specific to the given
         * [location].
         */
        fun viewModelForLocation(
            commonImpl: WifiViewModelCommon,
            flags: StatusBarPipelineFlags,
            location: StatusBarLocation,
        ): LocationBasedWifiViewModel =
            when (location) {
                StatusBarLocation.HOME -> HomeWifiViewModel(commonImpl, flags)
                StatusBarLocation.KEYGUARD -> KeyguardWifiViewModel(commonImpl, flags)
                StatusBarLocation.QS -> QsWifiViewModel(commonImpl, flags)
            }
    }
}

/**
 * A view model for the wifi icon shown on the "home" page (aka, when the device is unlocked and not
 * showing the shade, so the user is on the home-screen, or in an app).
 */
class HomeWifiViewModel(
    commonImpl: WifiViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
) :
    WifiViewModelCommon,
    LocationBasedWifiViewModel(commonImpl, statusBarPipelineFlags, debugTint = Color.CYAN)

/** A view model for the wifi icon shown on keyguard (lockscreen). */
class KeyguardWifiViewModel(
    commonImpl: WifiViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
) :
    WifiViewModelCommon,
    LocationBasedWifiViewModel(commonImpl, statusBarPipelineFlags, debugTint = Color.MAGENTA)

/** A view model for the wifi icon shown in quick settings (when the shade is pulled down). */
class QsWifiViewModel(
    commonImpl: WifiViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
) :
    WifiViewModelCommon,
    LocationBasedWifiViewModel(commonImpl, statusBarPipelineFlags, debugTint = Color.GREEN)
