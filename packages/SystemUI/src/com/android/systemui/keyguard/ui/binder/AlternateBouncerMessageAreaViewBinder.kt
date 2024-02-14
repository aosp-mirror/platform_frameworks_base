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

package com.android.systemui.keyguard.ui.binder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.AuthKeyguardMessageArea
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerMessageAreaViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Binds the alternate bouncer message view to its view-model. */
@ExperimentalCoroutinesApi
object AlternateBouncerMessageAreaViewBinder {

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: AuthKeyguardMessageArea,
        viewModel: AlternateBouncerMessageAreaViewModel,
    ) {
        view.setIsVisible(true)
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.message.collect { biometricMsg ->
                    if (biometricMsg == null) {
                        view.setMessage("", true)
                    } else {
                        view.setMessage(biometricMsg.message, true)
                    }
                }
            }
        }
    }
}
