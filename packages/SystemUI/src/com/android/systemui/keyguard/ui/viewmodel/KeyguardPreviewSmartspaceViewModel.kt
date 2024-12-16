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
 * limitations under the License
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import com.android.systemui.customization.R as customR
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.plugins.clocks.ClockPreviewConfig
import com.android.systemui.plugins.clocks.DefaultClockFaceLayout.Companion.getSmallClockTopPadding
import com.android.systemui.statusbar.ui.SystemBarUtilsProxy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** View model for the smartspace. */
class KeyguardPreviewSmartspaceViewModel
@Inject
constructor(
    interactor: KeyguardClockInteractor,
    val smartspaceViewModel: KeyguardSmartspaceViewModel,
    val clockViewModel: KeyguardClockViewModel,
    private val systemBarUtils: SystemBarUtilsProxy,
) {
    // overrideClockSize will override the clock size that is currently set to the system.
    private val overrideClockSize: MutableStateFlow<ClockSizeSetting?> = MutableStateFlow(null)
    val previewingClockSize =
        combine(overrideClockSize, interactor.selectedClockSize) {
            overrideClockSize,
            selectedClockSize ->
            overrideClockSize ?: selectedClockSize
        }

    val shouldHideSmartspace: Flow<Boolean> =
        combine(previewingClockSize, interactor.currentClockId, ::Pair).map { (size, clockId) ->
            when (size) {
                // TODO (b/284122375) This is temporary. We should use clockController
                //      .largeClock.config.hasCustomWeatherDataDisplay instead, but
                //      ClockRegistry.createCurrentClock is not reliable.
                ClockSizeSetting.DYNAMIC -> clockId == "DIGITAL_CLOCK_WEATHER"
                ClockSizeSetting.SMALL -> false
            }
        }

    fun setOverrideClockSize(clockSize: ClockSizeSetting) {
        overrideClockSize.value = clockSize
    }

    fun getDateWeatherStartPadding(context: Context): Int {
        return KeyguardSmartspaceViewModel.getDateWeatherStartMargin(context)
    }

    fun getDateWeatherEndPadding(context: Context): Int {
        return KeyguardSmartspaceViewModel.getDateWeatherEndMargin(context)
    }

    /*
     * SmallClockTopPadding decides the top position of smartspace
     */
    fun getSmallClockSmartspaceTopPadding(config: ClockPreviewConfig): Int {
        return getSmallClockTopPadding(config, systemBarUtils.getStatusBarHeaderHeightKeyguard()) +
            config.previewContext.resources.getDimensionPixelSize(customR.dimen.small_clock_height)
    }

    fun getLargeClockSmartspaceTopPadding(clockPreviewConfig: ClockPreviewConfig): Int {
        return getSmallClockTopPadding(
            clockPreviewConfig,
            systemBarUtils.getStatusBarHeaderHeightKeyguard(),
        )
    }
}
