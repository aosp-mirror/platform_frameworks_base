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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.keyguard.ui.viewmodel.UdfpsAodViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object UdfpsAodFingerprintViewBinder {

    /**
     * Drives UI for the UDFPS aod fingerprint view. See [UdfpsFingerprintViewBinder] and
     * [UdfpsBackgroundViewBinder].
     */
    @JvmStatic
    fun bind(
        view: LottieAnimationView,
        viewModel: UdfpsAodViewModel,
    ) {
        view.alpha = 0f
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.burnInOffsets.collect { burnInOffsets ->
                        view.progress = burnInOffsets.progress
                        view.translationX = burnInOffsets.x.toFloat()
                        view.translationY = burnInOffsets.y.toFloat()
                    }
                }

                launch { viewModel.alpha.collect { alpha -> view.alpha = alpha } }

                launch {
                    viewModel.padding.collect { padding ->
                        view.setPadding(padding, padding, padding, padding)
                    }
                }
            }
        }
    }
}
