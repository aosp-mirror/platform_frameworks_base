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

package com.android.systemui.deviceentry.ui.binder

import android.annotation.SuppressLint
import androidx.core.view.isInvisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlay
import com.android.systemui.deviceentry.ui.viewmodel.UdfpsAccessibilityOverlayViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
object UdfpsAccessibilityOverlayBinder {

    /** Forwards hover events to the view model to make guided announcements for accessibility. */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        view: UdfpsAccessibilityOverlay,
        viewModel: UdfpsAccessibilityOverlayViewModel,
    ) {
        view.setOnHoverListener { v, event -> viewModel.onHoverEvent(v, event) }
        view.repeatWhenAttached {
            // Repeat on CREATED because we update the visibility of the view
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.visible.collect { visible -> view.isInvisible = !visible }
            }
        }
    }
}
