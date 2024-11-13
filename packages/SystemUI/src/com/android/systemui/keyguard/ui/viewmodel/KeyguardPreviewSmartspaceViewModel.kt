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
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** View model for the smartspace. */
class KeyguardPreviewSmartspaceViewModel
@Inject
constructor(
    interactor: KeyguardClockInteractor,
    val smartspaceViewModel: KeyguardSmartspaceViewModel,
    val clockViewModel: KeyguardClockViewModel,
) {

    val selectedClockSize: StateFlow<ClockSizeSetting> = interactor.selectedClockSize

    val shouldHideSmartspace: Flow<Boolean> =
        combine(
                interactor.selectedClockSize,
                interactor.currentClockId,
                ::Pair,
            )
            .map { (size, currentClockId) ->
                when (size) {
                    // TODO (b/284122375) This is temporary. We should use clockController
                    //      .largeClock.config.hasCustomWeatherDataDisplay instead, but
                    //      ClockRegistry.createCurrentClock is not reliable.
                    ClockSizeSetting.DYNAMIC -> currentClockId == "DIGITAL_CLOCK_WEATHER"
                    ClockSizeSetting.SMALL -> false
                }
            }

    fun getSmartspaceStartPadding(context: Context): Int {
        return KeyguardSmartspaceViewModel.getSmartspaceStartMargin(context)
    }

    fun getSmartspaceEndPadding(context: Context): Int {
        return KeyguardSmartspaceViewModel.getSmartspaceEndMargin(context)
    }

    fun getSmallClockSmartspaceTopPadding(splitShadePreview: Boolean, context: Context): Int {
        return getSmallClockTopPadding(splitShadePreview, context) +
            context.resources.getDimensionPixelSize(
                com.android.systemui.customization.R.dimen.small_clock_height
            )
    }

    fun getLargeClockSmartspaceTopPadding(splitShadePreview: Boolean, context: Context): Int {
        return getSmallClockTopPadding(splitShadePreview, context)
    }

    /*
     * SmallClockTopPadding decides the top position of smartspace
     */
    private fun getSmallClockTopPadding(splitShadePreview: Boolean, context: Context): Int {
        return with(context.resources) {
            if (splitShadePreview) {
                getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
            } else {
                getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                    SystemBarUtils.getStatusBarHeight(context) +
                    getDimensionPixelSize(R.dimen.keyguard_smartspace_top_offset)
            }
        }
    }
}
