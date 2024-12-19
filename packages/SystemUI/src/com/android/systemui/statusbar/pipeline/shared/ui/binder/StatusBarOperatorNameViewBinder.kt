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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.StatusBarOperatorNameViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.StatusBarTintColor
import com.android.systemui.util.view.viewBoundsOnScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object StatusBarOperatorNameViewBinder {
    fun bind(
        operatorFrameView: View,
        viewModel: StatusBarOperatorNameViewModel,
        areaTint: (Int) -> Flow<StatusBarTintColor>,
    ) {
        operatorFrameView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val displayId = operatorFrameView.display.displayId

                val operatorNameText =
                    operatorFrameView.requireViewById<TextView>(R.id.operator_name)
                launch { viewModel.operatorName.collect { operatorNameText.text = it } }

                launch {
                    val tint = areaTint(displayId)
                    tint.collect { statusBarTintColors ->
                        operatorNameText.setTextColor(
                            statusBarTintColors.tint(operatorNameText.viewBoundsOnScreen())
                        )
                    }
                }
            }
        }
    }
}
