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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.view.View
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarVisibilityChangeListener

/**
 * A fake view binder that can be used from Java tests.
 *
 * Since Java tests can't run tests within test scopes, we need to bypass the flows from
 * [HomeStatusBarViewModel] and just trigger the listener directly.
 */
class FakeHomeStatusBarViewBinder : HomeStatusBarViewBinder {
    var listener: StatusBarVisibilityChangeListener? = null

    override fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        listener: StatusBarVisibilityChangeListener,
    ) {
        this.listener = listener
    }
}
