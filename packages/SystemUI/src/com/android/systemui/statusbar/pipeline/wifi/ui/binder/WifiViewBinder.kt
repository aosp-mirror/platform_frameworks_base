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

package com.android.systemui.statusbar.pipeline.wifi.ui.binder

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.R
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Binds a wifi icon in the status bar to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
@OptIn(InternalCoroutinesApi::class)
object WifiViewBinder {
    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: WifiViewModel,
    ) {
        val iconView = view.requireViewById<ImageView>(R.id.wifi_signal)

        view.isVisible = true
        iconView.isVisible = true
        iconView.setImageDrawable(view.context.getDrawable(WIFI_FULL_ICONS[2]))

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.tint.collect { tint ->
                        iconView.imageTintList = ColorStateList.valueOf(tint)
                    }
                }
            }
        }

        // TODO(b/238425913): Hook up to [viewModel] to render actual changes to the wifi icon.
    }
}
