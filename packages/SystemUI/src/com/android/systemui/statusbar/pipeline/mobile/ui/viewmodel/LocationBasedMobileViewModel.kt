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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import android.graphics.Color
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.VerboseMobileViewLogger

/**
 * A view model for an individual mobile icon that embeds the notion of a [StatusBarLocation]. This
 * allows the mobile icon to change some view parameters at different locations
 *
 * @param commonImpl for convenience, this class wraps a base interface that can provides all of the
 *   common implementations between locations. See [MobileIconViewModel]
 * @property locationName the name of the location of this VM, used for logging.
 * @property verboseLogger an optional logger to log extremely verbose view updates.
 */
abstract class LocationBasedMobileViewModel(
    val commonImpl: MobileIconViewModelCommon,
    val locationName: String,
    val verboseLogger: VerboseMobileViewLogger?,
) : MobileIconViewModelCommon by commonImpl {
    val defaultColor: Int = Color.WHITE

    companion object {
        fun viewModelForLocation(
            commonImpl: MobileIconViewModelCommon,
            verboseMobileViewLogger: VerboseMobileViewLogger,
            loc: StatusBarLocation,
        ): LocationBasedMobileViewModel =
            when (loc) {
                StatusBarLocation.HOME ->
                    HomeMobileIconViewModel(
                        commonImpl,
                        verboseMobileViewLogger,
                    )
                StatusBarLocation.KEYGUARD -> KeyguardMobileIconViewModel(commonImpl)
                StatusBarLocation.QS -> QsMobileIconViewModel(commonImpl)
            }
    }
}

class HomeMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    verboseMobileViewLogger: VerboseMobileViewLogger,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(
        commonImpl,
        locationName = "Home",
        verboseMobileViewLogger,
    )

class QsMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(
        commonImpl,
        locationName = "QS",
        // Only do verbose logging for the Home location.
        verboseLogger = null,
    )

class KeyguardMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(
        commonImpl,
        locationName = "Keyguard",
        // Only do verbose logging for the Home location.
        verboseLogger = null,
    )
