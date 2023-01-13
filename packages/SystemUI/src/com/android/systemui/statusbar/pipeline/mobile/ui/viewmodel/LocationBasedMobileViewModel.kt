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
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags

/**
 * A view model for an individual mobile icon that embeds the notion of a [StatusBarLocation]. This
 * allows the mobile icon to change some view parameters at different locations
 *
 * @param commonImpl for convenience, this class wraps a base interface that can provides all of the
 * common implementations between locations. See [MobileIconViewModel]
 */
abstract class LocationBasedMobileViewModel(
    val commonImpl: MobileIconViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
    debugTint: Int,
) : MobileIconViewModelCommon by commonImpl {
    val useDebugColoring: Boolean = statusBarPipelineFlags.useDebugColoring()

    val defaultColor: Int =
        if (useDebugColoring) {
            debugTint
        } else {
            Color.WHITE
        }

    companion object {
        fun viewModelForLocation(
            commonImpl: MobileIconViewModelCommon,
            statusBarPipelineFlags: StatusBarPipelineFlags,
            loc: StatusBarLocation,
        ): LocationBasedMobileViewModel =
            when (loc) {
                StatusBarLocation.HOME ->
                    HomeMobileIconViewModel(commonImpl, statusBarPipelineFlags)
                StatusBarLocation.KEYGUARD ->
                    KeyguardMobileIconViewModel(commonImpl, statusBarPipelineFlags)
                StatusBarLocation.QS -> QsMobileIconViewModel(commonImpl, statusBarPipelineFlags)
            }
    }
}

class HomeMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(commonImpl, statusBarPipelineFlags, debugTint = Color.CYAN)

class QsMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(commonImpl, statusBarPipelineFlags, debugTint = Color.GREEN)

class KeyguardMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    statusBarPipelineFlags: StatusBarPipelineFlags,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(commonImpl, statusBarPipelineFlags, debugTint = Color.MAGENTA)
