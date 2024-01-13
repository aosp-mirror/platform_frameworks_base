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

import android.content.Context
import com.android.settingslib.Utils
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Models the UI state for the UDFPS icon view in the alternate bouncer view. */
@ExperimentalCoroutinesApi
class AlternateBouncerUdfpsIconViewModel
@Inject
constructor(
    val context: Context,
    configurationInteractor: ConfigurationInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    deviceEntryBackgroundViewModel: DeviceEntryBackgroundViewModel,
    fingerprintPropertyInteractor: FingerprintPropertyInteractor,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    private val isSupported: Flow<Boolean> = deviceEntryUdfpsInteractor.isUdfpsSupported

    /**
     * UDFPS icon location in pixels for the current display and screen resolution, in natural
     * orientation.
     */
    val iconLocation: Flow<IconLocation> =
        isSupported.flatMapLatest { supportsUI ->
            if (supportsUI) {
                fingerprintPropertyInteractor.sensorLocation.map { sensorLocation ->
                    IconLocation(
                        left = (sensorLocation.centerX - sensorLocation.radius).toInt(),
                        top = (sensorLocation.centerY - sensorLocation.radius).toInt(),
                        right = (sensorLocation.centerX + sensorLocation.radius).toInt(),
                        bottom = (sensorLocation.centerY + sensorLocation.radius).toInt(),
                    )
                }
            } else {
                emptyFlow()
            }
        }
    val accessibilityDelegateHint: Flow<DeviceEntryIconView.AccessibilityHintType> =
        flowOf(DeviceEntryIconView.AccessibilityHintType.ENTER)

    private val fgIconColor: Flow<Int> =
        configurationInteractor.onAnyConfigurationChange
            .map { Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary) }
            .onStart {
                emit(Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary))
            }
    private val fgIconPadding: Flow<Int> = udfpsOverlayInteractor.iconPadding
    val fgViewModel: Flow<DeviceEntryForegroundViewModel.ForegroundIconViewModel> =
        combine(
            fgIconColor,
            fgIconPadding,
        ) { color, padding ->
            DeviceEntryForegroundViewModel.ForegroundIconViewModel(
                type = DeviceEntryIconView.IconType.FINGERPRINT,
                useAodVariant = false,
                tint = color,
                padding = padding,
            )
        }

    val bgColor: Flow<Int> = deviceEntryBackgroundViewModel.color
    val bgAlpha: Flow<Float> = flowOf(1f)

    data class IconLocation(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width = right - left
        val height = bottom - top
    }
}
