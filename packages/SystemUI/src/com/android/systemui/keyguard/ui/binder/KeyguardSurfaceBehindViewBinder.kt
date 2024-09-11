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

import com.android.app.tracing.coroutines.launch
import com.android.systemui.keyguard.WindowManagerLockscreenVisibilityManager
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSurfaceBehindViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Binds the [WindowManagerLockscreenVisibilityManager] "view", which manages the visibility of the
 * surface behind the keyguard.
 */
object KeyguardSurfaceBehindViewBinder {
    @JvmStatic
    fun bind(
        viewModel: KeyguardSurfaceBehindViewModel,
        applier: KeyguardSurfaceBehindParamsApplier,
        scope: CoroutineScope
    ) {
        scope.launch("$TAG#viewModel.surfaceBehindViewParams") {
            viewModel.surfaceBehindViewParams.collect { applier.viewParams = it }
        }
    }

    private const val TAG = "KeyguardSurfaceBehindViewBinder"
}
