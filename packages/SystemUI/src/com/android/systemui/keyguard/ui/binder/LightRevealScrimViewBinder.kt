/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.app.tracing.coroutines.launch
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.LightRevealScrim
import kotlinx.coroutines.launch

object LightRevealScrimViewBinder {
    @JvmStatic
    fun bind(revealScrim: LightRevealScrim, viewModel: LightRevealScrimViewModel) {
        revealScrim.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#viewModel.revealAmount") {
                    viewModel.revealAmount.collect { amount -> revealScrim.revealAmount = amount }
                }

                launch("$TAG#viewModel.lightRevealEffect") {
                    viewModel.lightRevealEffect.collect { effect ->
                        revealScrim.revealEffect = effect
                    }
                }
            }
        }
    }

    private const val TAG = "LightRevealScrimViewBinder"
}
