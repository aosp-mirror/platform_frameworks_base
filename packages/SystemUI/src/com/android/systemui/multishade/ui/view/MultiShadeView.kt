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

package com.android.systemui.multishade.ui.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.multishade.domain.interactor.MultiShadeInteractor
import com.android.systemui.multishade.ui.viewmodel.MultiShadeViewModel
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.launch

/**
 * View that hosts the multi-shade system and acts as glue between legacy code and the
 * implementation.
 */
class MultiShadeView(
    context: Context,
    attrs: AttributeSet?,
) :
    FrameLayout(
        context,
        attrs,
    ) {

    fun init(
        interactor: MultiShadeInteractor,
        clock: SystemClock,
    ) {
        repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    addView(
                        ComposeFacade.createMultiShadeView(
                            context = context,
                            viewModel =
                                MultiShadeViewModel(
                                    viewModelScope = this,
                                    interactor = interactor,
                                ),
                            clock = clock,
                        )
                    )
                }

                // Here when destroyed.
                removeAllViews()
            }
        }
    }
}
