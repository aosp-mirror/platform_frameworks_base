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
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val shadeInteractor: ShadeInteractor,
    @Application private val applicationScope: CoroutineScope,
    unfoldTransitionInteractor: UnfoldTransitionInteractor,
) {
    @VisibleForTesting val clockSize = clockInteractor.clockSize

    val isUdfpsVisible: Boolean
        get() = authController.isUdfpsSupported

    val shouldUseSplitNotificationShade: StateFlow<Boolean> =
        shadeInteractor.shadeMode
            .map { it == ShadeMode.Split }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    val areNotificationsVisible: StateFlow<Boolean> =
        combine(
                clockSize,
                shouldUseSplitNotificationShade,
            ) { clockSize, shouldUseSplitNotificationShade ->
                clockSize == ClockSize.SMALL || shouldUseSplitNotificationShade
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Amount of horizontal translation that should be applied to elements in the scene. */
    val unfoldTranslations: StateFlow<UnfoldTranslations> =
        combine(
                unfoldTransitionInteractor.unfoldTranslationX(isOnStartSide = true),
                unfoldTransitionInteractor.unfoldTranslationX(isOnStartSide = false),
            ) { start, end ->
                UnfoldTranslations(
                    start = start,
                    end = end,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = UnfoldTranslations(),
            )

    fun getSmartSpacePaddingTop(resources: Resources): Int {
        return if (clockSize.value == ClockSize.LARGE) {
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

    data class UnfoldTranslations(

        /**
         * Amount of horizontal translation to apply to elements that are aligned to the start side
         * (left in left-to-right layouts). Can also be used as horizontal padding for elements that
         * need horizontal padding on both side. In pixels.
         */
        val start: Float = 0f,

        /**
         * Amount of horizontal translation to apply to elements that are aligned to the end side
         * (right in left-to-right layouts). In pixels.
         */
        val end: Float = 0f,
    )
}
