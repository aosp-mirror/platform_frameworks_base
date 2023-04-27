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
import android.view.Surface
import com.android.systemui.R
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Models UI of AuthBiometricFingerprintView to support rear display state changes. */
class AuthBiometricFingerprintViewModel
@Inject
constructor(private val interactor: DisplayStateInteractor) {
    /** Current device rotation. */
    private var rotation: Int = Surface.ROTATION_0

    /** Current AuthBiometricFingerprintView asset. */
    val iconAsset: Flow<Int> =
        combine(interactor.isFolded, interactor.isInRearDisplayMode) {
            isFolded: Boolean,
            isInRearDisplayMode: Boolean ->
            getSideFpsAnimationAsset(isFolded, isInRearDisplayMode)
        }

    @RawRes
    private fun getSideFpsAnimationAsset(
        isDeviceFolded: Boolean,
        isInRearDisplayMode: Boolean,
    ): Int =
        when (rotation) {
            Surface.ROTATION_90 ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_portrait_reverse_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_topleft
                } else {
                    R.raw.biometricprompt_portrait_base_topleft
                }
            Surface.ROTATION_270 ->
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
        interactor.onConfigurationChanged(newConfig)
    }

    fun setRotation(newRotation: Int) {
        rotation = newRotation
    }
}
