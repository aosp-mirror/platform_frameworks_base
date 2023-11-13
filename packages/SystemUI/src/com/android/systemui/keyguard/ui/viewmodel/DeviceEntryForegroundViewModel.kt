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
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Models the UI state for the device entry icon foreground view (displayed icon). */
@ExperimentalCoroutinesApi
class DeviceEntryForegroundViewModel
@Inject
constructor(
    val context: Context,
    configurationRepository: ConfigurationRepository, // TODO (b/309655554): create & use interactor
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    deviceEntryIconViewModel: DeviceEntryIconViewModel,
) {
    private val isShowingAod: Flow<Boolean> =
        transitionInteractor.startedKeyguardState.map { keyguardState ->
            keyguardState == KeyguardState.AOD
        }
    private val color: Flow<Int> =
        configurationRepository.onAnyConfigurationChange
            .map { Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary) }
            .onStart {
                emit(Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary))
            }
    private val useAodIconVariant: Flow<Boolean> =
        combine(isShowingAod, deviceEntryUdfpsInteractor.isUdfpsSupported) {
                isTransitionToAod,
                isUdfps ->
                isTransitionToAod && isUdfps
            }
            .distinctUntilChanged()
    private val padding: Flow<Int> =
        configurationRepository.scaleForResolution.map { scale ->
            (context.resources.getDimensionPixelSize(R.dimen.lock_icon_padding) * scale)
                .roundToInt()
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
