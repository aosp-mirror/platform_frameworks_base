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

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.viewmodel.BackgroundViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object UdfpsBackgroundViewBinder {

    /**
     * Drives UI for the udfps background view. See [UdfpsAodFingerprintViewBinder] and
     * [UdfpsFingerprintViewBinder].
     */
    @JvmStatic
    fun bind(
        view: ImageView,
        viewModel: BackgroundViewModel,
    ) {
        view.alpha = 0f
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.transition.collect {
                        view.alpha = it.alpha
                        view.scaleX = it.scale
                        view.scaleY = it.scale
                        view.imageTintList = ColorStateList.valueOf(it.color)
                    }
                }
            }
        }
    }
}
