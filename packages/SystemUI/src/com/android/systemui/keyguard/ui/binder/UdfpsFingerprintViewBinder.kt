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

package com.android.systemui.keyguard.ui.binder

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.android.systemui.keyguard.ui.viewmodel.FingerprintViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object UdfpsFingerprintViewBinder {
    private var udfpsIconColor = 0

    /**
     * Drives UI for the UDFPS fingerprint view when it's NOT on aod. See
     * [UdfpsAodFingerprintViewBinder] and [UdfpsBackgroundViewBinder].
     */
    @JvmStatic
    fun bind(
        view: LottieAnimationView,
        viewModel: FingerprintViewModel,
    ) {
        view.alpha = 0f
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.transition.collect {
                        view.alpha = it.alpha
                        view.scaleX = it.scale
                        view.scaleY = it.scale
                        if (udfpsIconColor != (it.color)) {
                            udfpsIconColor = it.color
                            view.invalidate()
                        }
                    }
                }

                launch {
                    viewModel.burnInOffsets.collect { burnInOffsets ->
                        view.translationX = burnInOffsets.x.toFloat()
                        view.translationY = burnInOffsets.y.toFloat()
                    }
                }

                launch {
                    viewModel.dozeAmount.collect { dozeAmount ->
                        // Lottie progress represents: aod=0 to lockscreen=1
                        view.progress = 1f - dozeAmount
                    }
                }

                launch {
                    viewModel.padding.collect { padding ->
                        view.setPadding(padding, padding, padding, padding)
                    }
                }
            }
        }

        // Add a callback that updates the color to `udfpsIconColor` whenever invalidate is called
        view.addValueCallback(KeyPath("**"), LottieProperty.COLOR_FILTER) {
            PorterDuffColorFilter(udfpsIconColor, PorterDuff.Mode.SRC_ATOP)
        }
    }
}
