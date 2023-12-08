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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.biometrics.ui.viewmodel.PromptFingerprintIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.launch

/** Sub-binder for [BiometricPromptLayout.iconView]. */
object PromptFingerprintIconViewBinder {

    /** Binds [BiometricPromptLayout.iconView] to [PromptFingerprintIconViewModel]. */
    @JvmStatic
    fun bind(view: LottieAnimationView, viewModel: PromptFingerprintIconViewModel) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onConfigurationChanged(view.context.resources.configuration)
                launch {
                    viewModel.iconAsset.collect { iconAsset ->
                        if (iconAsset != -1) {
                            view.setAnimation(iconAsset)
                            // TODO: must replace call below once non-sfps asset logic and
                            // shouldAnimateIconView logic is migrated to this ViewModel.
                            view.playAnimation()
                        }
                    }
                }
            }
        }
    }
}
