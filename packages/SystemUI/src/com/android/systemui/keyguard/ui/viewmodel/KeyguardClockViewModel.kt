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

import android.content.res.Resources
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.helper.widget.Layer
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.customization.R as customR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.ComposeLockscreen
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.ui.SystemBarUtilsProxy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardClockViewModel
@Inject
constructor(
    keyguardClockInteractor: KeyguardClockInteractor,
    @Application private val applicationScope: CoroutineScope,
    notifsKeyguardInteractor: NotificationsKeyguardInteractor,
    @get:VisibleForTesting val shadeInteractor: ShadeInteractor,
    private val systemBarUtils: SystemBarUtilsProxy,
    configurationInteractor: ConfigurationInteractor,
    @Main private val resources: Resources,
) {
    var burnInLayer: Layer? = null

    val clockSize: StateFlow<ClockSize> =
        combine(
                keyguardClockInteractor.selectedClockSize,
                keyguardClockInteractor.clockSize,
            ) { selectedSize, clockSize ->
                if (selectedSize == ClockSizeSetting.SMALL) ClockSize.SMALL else clockSize
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ClockSize.LARGE,
            )

    val isLargeClockVisible: StateFlow<Boolean> =
        clockSize
            .map { it == ClockSize.LARGE }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = true,
            )

    val currentClock = keyguardClockInteractor.currentClock

    val hasCustomWeatherDataDisplay =
        combine(
                isLargeClockVisible,
                currentClock,
            ) { isLargeClock, clock ->
                clock?.let { clock ->
                    val face = if (isLargeClock) clock.largeClock else clock.smallClock
                    face.config.hasCustomWeatherDataDisplay
                }
                    ?: false
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = currentClock.value?.largeClock?.config?.hasCustomWeatherDataDisplay
                        ?: false
            )

    val clockShouldBeCentered: StateFlow<Boolean> =
        keyguardClockInteractor.clockShouldBeCentered.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    val isAodIconsVisible: StateFlow<Boolean> =
        notifsKeyguardInteractor.areNotificationsFullyHidden.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    val currentClockLayout: StateFlow<ClockLayout> =
        combine(
                isLargeClockVisible,
                clockShouldBeCentered,
                shadeInteractor.shadeMode,
                currentClock,
            ) { isLargeClockVisible, clockShouldBeCentered, shadeMode, currentClock ->
                val shouldUseSplitShade = shadeMode == ShadeMode.Split
                if (currentClock?.config?.useCustomClockScene == true) {
                    when {
                        shouldUseSplitShade && clockShouldBeCentered ->
                            ClockLayout.WEATHER_LARGE_CLOCK
                        shouldUseSplitShade && isLargeClockVisible ->
                            ClockLayout.SPLIT_SHADE_WEATHER_LARGE_CLOCK
                        shouldUseSplitShade -> ClockLayout.SPLIT_SHADE_SMALL_CLOCK
                        isLargeClockVisible -> ClockLayout.WEATHER_LARGE_CLOCK
                        else -> ClockLayout.SMALL_CLOCK
                    }
                } else {
                    when {
                        shouldUseSplitShade && clockShouldBeCentered -> ClockLayout.LARGE_CLOCK
                        shouldUseSplitShade && isLargeClockVisible ->
                            ClockLayout.SPLIT_SHADE_LARGE_CLOCK
                        shouldUseSplitShade -> ClockLayout.SPLIT_SHADE_SMALL_CLOCK
                        isLargeClockVisible -> ClockLayout.LARGE_CLOCK
                        else -> ClockLayout.SMALL_CLOCK
                    }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ClockLayout.SMALL_CLOCK
            )

    val hasCustomPositionUpdatedAnimation: StateFlow<Boolean> =
        combine(currentClock, isLargeClockVisible) { currentClock, isLargeClockVisible ->
                isLargeClockVisible &&
                    currentClock?.largeClock?.config?.hasCustomPositionUpdatedAnimation == true
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false
            )

    /** Calculates the top margin for the small clock. */
    fun getSmallClockTopMargin(): Int {
        val statusBarHeight = systemBarUtils.getStatusBarHeaderHeightKeyguard()
        return if (shadeInteractor.shadeMode.value == ShadeMode.Split) {
            resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin) -
                if (ComposeLockscreen.isEnabled) statusBarHeight else 0
        } else {
            resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                if (!ComposeLockscreen.isEnabled) statusBarHeight else 0
        }
    }

    val smallClockTopMargin =
        combine(
            configurationInteractor.onAnyConfigurationChange,
            shadeInteractor.shadeMode,
        ) { _, _ ->
            getSmallClockTopMargin()
        }

    /** Calculates the top margin for the large clock. */
    fun getLargeClockTopMargin(): Int {
        return systemBarUtils.getStatusBarHeight() +
            resources.getDimensionPixelSize(customR.dimen.small_clock_padding_top) +
            resources.getDimensionPixelSize(R.dimen.keyguard_smartspace_top_offset)
    }

    val largeClockTopMargin: Flow<Int> =
        configurationInteractor.onAnyConfigurationChange.map { getLargeClockTopMargin() }

    enum class ClockLayout {
        LARGE_CLOCK,
        SMALL_CLOCK,
        SPLIT_SHADE_LARGE_CLOCK,
        SPLIT_SHADE_SMALL_CLOCK,
        WEATHER_LARGE_CLOCK,
        SPLIT_SHADE_WEATHER_LARGE_CLOCK,
    }
}
