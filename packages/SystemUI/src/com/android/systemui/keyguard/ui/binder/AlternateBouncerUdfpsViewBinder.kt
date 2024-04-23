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

package com.android.systemui.keyguard.ui.binder

import android.content.res.ColorStateList
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerUdfpsIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
object AlternateBouncerUdfpsViewBinder {

    /** Updates UI for the UDFPS icon on the alternate bouncer. */
    @JvmStatic
    fun bind(
        view: DeviceEntryIconView,
        viewModel: AlternateBouncerUdfpsIconViewModel,
    ) {
        if (DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()) {
            return
        }
        val fgIconView = view.iconView
        val bgView = view.bgView

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                view.alpha = 0f
                launch("$TAG#viewModel.accessibilityDelegateHint") {
                    viewModel.accessibilityDelegateHint.collect { hint ->
                        view.accessibilityHintType = hint
                    }
                }

                launch("$TAG#viewModel.alpha") { viewModel.alpha.collect { view.alpha = it } }
            }
        }

        fgIconView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fgViewModel.collect { fgViewModel ->
                    fgIconView.setImageState(
                        view.getIconState(fgViewModel.type, fgViewModel.useAodVariant),
                        /* merge */ false
                    )
                    fgIconView.imageTintList = ColorStateList.valueOf(fgViewModel.tint)
                    fgIconView.setPadding(
                        fgViewModel.padding,
                        fgViewModel.padding,
                        fgViewModel.padding,
                        fgViewModel.padding,
                    )
                }
            }
        }

        bgView.visibility = View.VISIBLE
        bgView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.bgColor") {
                    viewModel.bgColor.collect { color ->
                        bgView.imageTintList = ColorStateList.valueOf(color)
                    }
                }
                launch("$TAG#viewModel.bgAlpha") {
                    viewModel.bgAlpha.collect { alpha -> bgView.alpha = alpha }
                }
            }
        }
    }

    private const val TAG = "AlternateBouncerUdfpsViewBinder"
}
