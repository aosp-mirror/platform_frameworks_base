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

package com.android.systemui.biometrics.ui.viewmodel

import android.annotation.RawRes
import android.content.res.Configuration
import com.android.systemui.R
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Models UI of [BiometricPromptLayout.iconView] */
class PromptFingerprintIconViewModel
@Inject
constructor(
    private val displayStateInteractor: DisplayStateInteractor,
    promptSelectorInteractor: PromptSelectorInteractor,
) {
    /** Current BiometricPromptLayout.iconView asset. */
    val iconAsset: Flow<Int> =
        combine(
            displayStateInteractor.currentRotation,
            displayStateInteractor.isFolded,
            displayStateInteractor.isInRearDisplayMode,
            promptSelectorInteractor.sensorType,
        ) {
            rotation: DisplayRotation,
            isFolded: Boolean,
            isInRearDisplayMode: Boolean,
            sensorType: FingerprintSensorType ->
            when (sensorType) {
                FingerprintSensorType.POWER_BUTTON ->
                    getSideFpsAnimationAsset(rotation, isFolded, isInRearDisplayMode)
                // Replace below when non-SFPS iconAsset logic is migrated to this ViewModel
                else -> -1
            }
        }

    @RawRes
    private fun getSideFpsAnimationAsset(
        rotation: DisplayRotation,
        isDeviceFolded: Boolean,
        isInRearDisplayMode: Boolean,
    ): Int =
        when (rotation) {
            DisplayRotation.ROTATION_90 ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_portrait_reverse_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_topleft
                } else {
                    R.raw.biometricprompt_portrait_base_topleft
                }
            DisplayRotation.ROTATION_270 ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_portrait_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_bottomright
                } else {
                    R.raw.biometricprompt_portrait_base_bottomright
                }
            else ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_landscape_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_default
                } else {
                    R.raw.biometricprompt_landscape_base
                }
        }

    /** Called on configuration changes */
    fun onConfigurationChanged(newConfig: Configuration) {
        displayStateInteractor.onConfigurationChanged(newConfig)
    }
}
