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
import java.lang.IllegalArgumentException

/**
 * A view model for a wifi icon in a specific location. This allows us to control parameters that
 * are location-specific (for example, different tints of the icon in different locations).
 *
 * Must be subclassed for each distinct location.
 */
abstract class LocationBasedWifiViewModel(
    private val commonImpl: WifiViewModelCommon,
) : WifiViewModelCommon by commonImpl {
    val defaultColor: Int = Color.WHITE

    companion object {
        /**
         * Returns a new instance of [LocationBasedWifiViewModel] that's specific to the given
         * [location].
         */
        fun viewModelForLocation(
            commonImpl: WifiViewModelCommon,
            location: StatusBarLocation,
        ): LocationBasedWifiViewModel =
            when (location) {
                StatusBarLocation.HOME -> HomeWifiViewModel(commonImpl)
                StatusBarLocation.KEYGUARD -> KeyguardWifiViewModel(commonImpl)
                StatusBarLocation.QS -> QsWifiViewModel(commonImpl)
                StatusBarLocation.SHADE_CARRIER_GROUP ->
                    throw IllegalArgumentException("invalid location for WifiViewModel: $location")
            }
    }
}

/**
 * A view model for the wifi icon shown on the "home" page (aka, when the device is unlocked and not
 * showing the shade, so the user is on the home-screen, or in an app).
 */
class HomeWifiViewModel(
    commonImpl: WifiViewModelCommon,
) : WifiViewModelCommon, LocationBasedWifiViewModel(commonImpl)

/** A view model for the wifi icon shown on keyguard (lockscreen). */
class KeyguardWifiViewModel(
    commonImpl: WifiViewModelCommon,
) : WifiViewModelCommon, LocationBasedWifiViewModel(commonImpl)

/** A view model for the wifi icon shown in quick settings (when the shade is pulled down). */
class QsWifiViewModel(
    commonImpl: WifiViewModelCommon,
) : WifiViewModelCommon, LocationBasedWifiViewModel(commonImpl)

/**
 * A view model for the wifi icon in the shade carrier group (visible when quick settings is fully
 * expanded, and in large screen shade). Currently unused.
 */
class ShadeCarrierGroupWifiViewModel(
    commonImpl: WifiViewModelCommon,
) : WifiViewModelCommon, LocationBasedWifiViewModel(commonImpl)
