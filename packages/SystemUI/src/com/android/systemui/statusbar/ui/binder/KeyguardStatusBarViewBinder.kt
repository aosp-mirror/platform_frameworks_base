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
 * limitations under the License
 */

package com.android.systemui.statusbar.ui.binder

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.statusbar.ui.viewmodel.KeyguardStatusBarViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Binds [KeyguardStatusBarViewModel] to [KeyguardStatusBarView]. */
object KeyguardStatusBarViewBinder {
    @JvmStatic
    fun bind(
        view: KeyguardStatusBarView,
        viewModel: KeyguardStatusBarViewModel,
    ) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isVisible.collect { isVisible ->
                        view.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                    }
                }

                launch { viewModel.isBatteryCharging.collect { view.onBatteryChargingChanged(it) } }

                launch {
                    viewModel.isKeyguardUserSwitcherEnabled.distinctUntilChanged().collect {
                        view.setKeyguardUserSwitcherEnabled(it)
                    }
                }
            }
        }
    }
}
