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
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.VerboseMobileViewLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * A view model for an individual mobile icon that embeds the notion of a [StatusBarLocation]. This
 * allows the mobile icon to change some view parameters at different locations
 *
 * @param commonImpl for convenience, this class wraps a base interface that can provides all of the
 *   common implementations between locations. See [MobileIconViewModel]
 * @property location the [StatusBarLocation] of this VM.
 * @property verboseLogger an optional logger to log extremely verbose view updates.
 */
abstract class LocationBasedMobileViewModel(
    val commonImpl: MobileIconViewModelCommon,
    val location: StatusBarLocation,
    val verboseLogger: VerboseMobileViewLogger?,
) : MobileIconViewModelCommon by commonImpl {
    val defaultColor: Int = Color.WHITE

    companion object {
        fun viewModelForLocation(
            commonImpl: MobileIconViewModelCommon,
            interactor: MobileIconInteractor,
            verboseMobileViewLogger: VerboseMobileViewLogger,
            location: StatusBarLocation,
            scope: CoroutineScope,
        ): LocationBasedMobileViewModel =
            when (location) {
                StatusBarLocation.HOME ->
                    HomeMobileIconViewModel(
                        commonImpl,
                        verboseMobileViewLogger,
                    )
                StatusBarLocation.KEYGUARD -> KeyguardMobileIconViewModel(commonImpl)
                StatusBarLocation.QS -> QsMobileIconViewModel(commonImpl)
                StatusBarLocation.SHADE_CARRIER_GROUP ->
                    ShadeCarrierGroupMobileIconViewModel(
                        commonImpl,
                        interactor,
                        scope,
                    )
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
        location = StatusBarLocation.HOME,
        verboseMobileViewLogger,
    )

class QsMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(
        commonImpl,
        location = StatusBarLocation.QS,
        // Only do verbose logging for the Home location.
        verboseLogger = null,
    )

class ShadeCarrierGroupMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    interactor: MobileIconInteractor,
    scope: CoroutineScope,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(
        commonImpl,
        location = StatusBarLocation.SHADE_CARRIER_GROUP,
        // Only do verbose logging for the Home location.
        verboseLogger = null,
    ) {
    private val isSingleCarrier = interactor.isSingleCarrier
    val carrierName = interactor.carrierName

    override val isVisible: StateFlow<Boolean> =
        combine(super.isVisible, isSingleCarrier) { isVisible, isSingleCarrier ->
                if (isSingleCarrier) false else isVisible
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), super.isVisible.value)
}

class KeyguardMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
) :
    MobileIconViewModelCommon,
    LocationBasedMobileViewModel(
        commonImpl,
        location = StatusBarLocation.KEYGUARD,
        // Only do verbose logging for the Home location.
        verboseLogger = null,
    )
