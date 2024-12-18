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

package com.android.systemui.statusbar.notification.row.ui.viewbinder

import android.widget.ViewFlipper
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.row.ui.viewmodel.NotificationViewFlipperViewModel
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Binds a [NotificationViewFlipper] to its [view model][NotificationViewFlipperViewModel]. */
object NotificationViewFlipperBinder {
    fun bindWhileAttached(
        viewFlipper: ViewFlipper,
        viewModel: NotificationViewFlipperViewModel,
    ): DisposableHandle {
        if (!viewFlipper.isAutoStart) {
            // If the ViewFlipper is not set to AutoStart, the pause binding is meaningless
            return DisposableHandle {}
        }
        return viewFlipper.repeatWhenAttached {
            lifecycleScope.launch { bind(viewFlipper, viewModel) }
        }
    }

    suspend fun bind(
        viewFlipper: ViewFlipper,
        viewModel: NotificationViewFlipperViewModel,
    ) = coroutineScope { launch { viewModel.isPaused.collect { viewFlipper.setPaused(it) } } }

    private fun ViewFlipper.setPaused(paused: Boolean) {
        if (paused) {
            stopFlipping()
        } else if (isAutoStart) {
            startFlipping()
        }
    }
}
