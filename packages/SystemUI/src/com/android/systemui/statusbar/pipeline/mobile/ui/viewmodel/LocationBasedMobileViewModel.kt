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
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * A view model for an individual mobile icon that embeds the notion of a [StatusBarLocation]. This
 * allows the mobile icon to change some view parameters at different locations
 *
 * @param commonImpl for convenience, this class wraps a base interface that can provides all of the
 * common implementations between locations. See [MobileIconViewModel]
 */
abstract class LocationBasedMobileViewModel(
    val commonImpl: MobileIconViewModelCommon,
    val logger: ConnectivityPipelineLogger,
) : MobileIconViewModelCommon by commonImpl {
    abstract val tint: Flow<Int>

    companion object {
        fun viewModelForLocation(
            commonImpl: MobileIconViewModelCommon,
            logger: ConnectivityPipelineLogger,
            loc: StatusBarLocation,
        ): LocationBasedMobileViewModel =
            when (loc) {
                StatusBarLocation.HOME -> HomeMobileIconViewModel(commonImpl, logger)
                StatusBarLocation.KEYGUARD -> KeyguardMobileIconViewModel(commonImpl, logger)
                StatusBarLocation.QS -> QsMobileIconViewModel(commonImpl, logger)
            }
    }
}

class HomeMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    logger: ConnectivityPipelineLogger,
) : MobileIconViewModelCommon, LocationBasedMobileViewModel(commonImpl, logger) {
    override val tint: Flow<Int> =
        flowOf(Color.CYAN)
            .distinctUntilChanged()
            .logOutputChange(logger, "HOME tint(${commonImpl.subscriptionId})")
}

class QsMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    logger: ConnectivityPipelineLogger,
) : MobileIconViewModelCommon, LocationBasedMobileViewModel(commonImpl, logger) {
    override val tint: Flow<Int> =
        flowOf(Color.GREEN)
            .distinctUntilChanged()
            .logOutputChange(logger, "QS tint(${commonImpl.subscriptionId})")
}

class KeyguardMobileIconViewModel(
    commonImpl: MobileIconViewModelCommon,
    logger: ConnectivityPipelineLogger,
) : MobileIconViewModelCommon, LocationBasedMobileViewModel(commonImpl, logger) {
    override val tint: Flow<Int> =
        flowOf(Color.MAGENTA)
            .distinctUntilChanged()
            .logOutputChange(logger, "KEYGUARD tint(${commonImpl.subscriptionId})")
}
