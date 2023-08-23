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

package com.android.systemui.communal.ui.binder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.res.R
import com.android.systemui.communal.ui.adapter.CommunalWidgetViewAdapter
import com.android.systemui.communal.ui.view.CommunalWidgetWrapper
import com.android.systemui.communal.ui.viewmodel.CommunalWidgetViewModel
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.launch

/** Binds [CommunalWidgetViewModel] to the keyguard root view. */
object CommunalWidgetViewBinder {

    @JvmStatic
    fun bind(
        rootView: KeyguardRootView,
        viewModel: CommunalWidgetViewModel,
        adapter: CommunalWidgetViewAdapter,
        keyguardBlueprintInteractor: KeyguardBlueprintInteractor,
    ) {
        rootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    adapter.adapt(viewModel.appWidgetInfo).collect {
                        val oldView =
                            rootView.findViewById<CommunalWidgetWrapper>(
                                R.id.communal_widget_wrapper
                            )
                        var dirty = false

                        if (oldView != null) {
                            rootView.removeView(oldView)
                            dirty = true
                        }

                        if (it != null) {
                            rootView.addView(it)
                            dirty = true
                        }

                        if (dirty) {
                            keyguardBlueprintInteractor.refreshBlueprint()
                        }
                    }
                }

                launch { viewModel.alpha.collect { rootView.alpha = it } }
            }
        }
    }
}
