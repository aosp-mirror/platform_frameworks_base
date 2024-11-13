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

import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.row.ui.view.EnRouteView
import com.android.systemui.statusbar.notification.row.ui.viewmodel.EnRouteViewModel
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Binds a [EnRouteView] to its [view model][EnRouteViewModel]. */
object EnRouteViewBinder {
    fun bindWhileAttached(
        view: EnRouteView,
        viewModel: EnRouteViewModel,
    ): DisposableHandle {
        return view.repeatWhenAttached { lifecycleScope.launch { bind(view, viewModel) } }
    }

    suspend fun bind(
        view: EnRouteView,
        viewModel: EnRouteViewModel,
    ) = coroutineScope {
        launch { viewModel.icon.collect { view.setIcon(it) } }
        launch { viewModel.title.collect { view.setTitle(it) } }
        launch { viewModel.text.collect { view.setText(it) } }
    }
}
