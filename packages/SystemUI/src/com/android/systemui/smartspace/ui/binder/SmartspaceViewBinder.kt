/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.smartspace.ui.binder

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.smartspace.ui.viewmodel.SmartspaceViewModel
import kotlinx.coroutines.launch

/** Binds the view and view-model for the smartspace. */
object SmartspaceViewBinder {

    /** Binds the view and view-model for the smartspace. */
    fun bind(
        smartspaceView: SmartspaceView,
        viewModel: SmartspaceViewModel,
    ) {
        val view = smartspaceView as View
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    // Observe screen on/off changes
                    viewModel.isAwake.collect { isAwake -> smartspaceView.setScreenOn(isAwake) }
                }
            }
        }
    }
}
