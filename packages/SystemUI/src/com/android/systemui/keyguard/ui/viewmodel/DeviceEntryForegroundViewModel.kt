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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import com.android.settingslib.Utils
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Models the UI state for the device entry icon foreground view (displayed icon). */
@ExperimentalCoroutinesApi
@SysUISingleton
class DeviceEntryForegroundViewModel
@Inject
constructor(
    val context: Context,
    configurationInteractor: ConfigurationInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    deviceEntryIconViewModel: DeviceEntryIconViewModel,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    private val isShowingAodOrDozing: Flow<Boolean> =
        combine(
            transitionInteractor.startedKeyguardState,
            transitionInteractor.transitionValue(KeyguardState.DOZING),
        ) { startedKeyguardState, dozingTransitionValue ->
            startedKeyguardState == KeyguardState.AOD || dozingTransitionValue == 1f
        }

    private fun getColor(usingBackgroundProtection: Boolean): Int {
        return if (usingBackgroundProtection) {
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
        } else {
            Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColorAccent)
        }
    }

    // While dozing, the display can show the AOD UI; show the AOD udfps when dozing
    private val useAodIconVariant: Flow<Boolean> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest { udfspEnrolled ->
            if (udfspEnrolled) {
                isShowingAodOrDozing.distinctUntilChanged()
            } else {
                flowOf(false)
            }
        }

    private val color: Flow<Int> =
        useAodIconVariant
            .flatMapLatest { useAodVariant ->
                if (useAodVariant) {
                    flowOf(android.graphics.Color.WHITE)
                } else {
                    deviceEntryIconViewModel.useBackgroundProtection.flatMapLatest { useBgProtection
                        ->
                        configurationInteractor.onAnyConfigurationChange
                            .map { getColor(useBgProtection) }
                            .onStart { emit(getColor(useBgProtection)) }
                    }
                }
            }
            .distinctUntilChanged()

    private val padding: Flow<Int> =
        deviceEntryUdfpsInteractor.isUdfpsSupported.flatMapLatest { udfpsSupported ->
            if (udfpsSupported) {
                udfpsOverlayInteractor.iconPadding
            } else {
                configurationInteractor.scaleForResolution.map { scale ->
                    (context.resources.getDimensionPixelSize(R.dimen.lock_icon_padding) * scale)
                        .roundToInt()
                }
            }
        }

    val viewModel: Flow<ForegroundIconViewModel> =
        combine(
            deviceEntryIconViewModel.iconType,
            useAodIconVariant,
            color,
            padding,
        ) { iconType, useAodVariant, color, padding ->
            ForegroundIconViewModel(
                type = iconType,
                useAodVariant = useAodVariant,
                tint = color,
                padding = padding,
            )
        }

    data class ForegroundIconViewModel(
        val type: DeviceEntryIconView.IconType,
        val useAodVariant: Boolean,
        val tint: Int,
        val padding: Int,
    )
}
