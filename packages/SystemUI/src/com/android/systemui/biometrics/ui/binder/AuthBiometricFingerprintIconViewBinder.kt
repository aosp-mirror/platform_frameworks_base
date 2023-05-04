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

package com.android.systemui.biometrics.ui.binder

import android.view.DisplayInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.biometrics.AuthBiometricFingerprintView
import com.android.systemui.biometrics.ui.viewmodel.AuthBiometricFingerprintViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.launch

/** Sub-binder for [AuthBiometricFingerprintView.mIconView]. */
object AuthBiometricFingerprintIconViewBinder {

    /**
     * Binds a [AuthBiometricFingerprintView.mIconView] to a [AuthBiometricFingerprintViewModel].
     */
    @JvmStatic
    fun bind(view: LottieAnimationView, viewModel: AuthBiometricFingerprintViewModel) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val displayInfo = DisplayInfo()
                view.context.display?.getDisplayInfo(displayInfo)
                viewModel.setRotation(displayInfo.rotation)
                viewModel.onConfigurationChanged(view.context.resources.configuration)
                launch { viewModel.iconAsset.collect { iconAsset -> view.setAnimation(iconAsset) } }
            }
        }
    }
}
