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
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.res.Resources
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.SplitShadeStateController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class LockscreenContentViewModel
@Inject
constructor(
    clockInteractor: KeyguardClockInteractor,
    private val interactor: KeyguardBlueprintInteractor,
    private val authController: AuthController,
    val longPress: KeyguardLongPressViewModel,
    val splitShadeStateController: SplitShadeStateController,
) {
    private val clockSize = clockInteractor.clockSize

    val isUdfpsVisible: Boolean
        get() = authController.isUdfpsSupported
    val isLargeClockVisible: Boolean
        get() = clockSize.value == KeyguardClockSwitch.LARGE
    fun areNotificationsVisible(resources: Resources): Boolean {
        return !isLargeClockVisible ||
            splitShadeStateController.shouldUseSplitNotificationShade(resources)
    }

    fun getSmartSpacePaddingTop(resources: Resources): Int {
        return if (isLargeClockVisible) {
            resources.getDimensionPixelSize(R.dimen.keyguard_smartspace_top_offset) +
                resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin)
        } else {
            0
        }
    }

    fun blueprintId(scope: CoroutineScope): StateFlow<String> {
        return interactor.blueprint
            .map { it.id }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = interactor.getCurrentBlueprint().id,
            )
    }
}
