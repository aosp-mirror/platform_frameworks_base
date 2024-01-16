/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardSmartspaceInteractor
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardSmartspaceViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    smartspaceController: LockscreenSmartspaceController,
    keyguardClockViewModel: KeyguardClockViewModel,
    smartspaceInteractor: KeyguardSmartspaceInteractor,
) {
    /** Whether the smartspace section is available in the build. */
    val isSmartspaceEnabled: Boolean = smartspaceController.isEnabled()
    /** Whether the weather area is available in the build. */
    // TODO(b/317891876): this should be a Flow as the value can change over time.
    val isWeatherEnabled: Boolean = smartspaceController.isWeatherEnabled()
    /** Whether the data and weather areas are decoupled in the build. */
    val isDateWeatherDecoupled: Boolean = smartspaceController.isDateWeatherDecoupled()

    /** Whether the date area should be visible. */
    val isDateVisible: StateFlow<Boolean> =
        keyguardClockViewModel.hasCustomWeatherDataDisplay
            .map { !it }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = !keyguardClockViewModel.hasCustomWeatherDataDisplay.value,
            )

    /** Whether the weather area should be visible. */
    val isWeatherVisible: StateFlow<Boolean> =
        keyguardClockViewModel.hasCustomWeatherDataDisplay
            .map { clockIncludesCustomWeatherDisplay ->
                isWeatherVisible(
                    clockIncludesCustomWeatherDisplay = clockIncludesCustomWeatherDisplay,
                    isWeatherEnabled = isWeatherEnabled,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    isWeatherVisible(
                        clockIncludesCustomWeatherDisplay =
                            keyguardClockViewModel.hasCustomWeatherDataDisplay.value,
                        isWeatherEnabled = isWeatherEnabled,
                    )
            )

    private fun isWeatherVisible(
        clockIncludesCustomWeatherDisplay: Boolean,
        isWeatherEnabled: Boolean,
    ): Boolean {
        return !clockIncludesCustomWeatherDisplay && isWeatherEnabled
    }

    /* trigger clock and smartspace constraints change when smartspace appears */
    var bcSmartspaceVisibility: StateFlow<Int> = smartspaceInteractor.bcSmartspaceVisibility
}
