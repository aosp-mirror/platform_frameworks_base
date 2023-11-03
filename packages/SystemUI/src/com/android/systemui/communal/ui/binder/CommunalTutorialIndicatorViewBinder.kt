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

package com.android.systemui.communal.ui.binder

import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.communal.ui.viewmodel.CommunalTutorialIndicatorViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** View binder for communal tutorial indicator shown on keyguard. */
object CommunalTutorialIndicatorViewBinder {
    fun bind(
        view: TextView,
        viewModel: CommunalTutorialIndicatorViewModel,
    ): DisposableHandle {
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.showIndicator.collect { isVisible ->
                            updateView(
                                view = view,
                                isIndicatorVisible = isVisible,
                            )
                        }
                    }

                    launch { viewModel.alpha.collect { view.alpha = it } }
                }
            }

        return disposableHandle
    }

    private fun updateView(
        isIndicatorVisible: Boolean,
        view: TextView,
    ) {
        if (!isIndicatorVisible) {
            view.isGone = true
            return
        }

        if (!view.isVisible) {
            view.isVisible = true
        }
    }
}
