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

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import com.android.systemui.R
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsPopupMenuViewModel

object KeyguardLongPressPopupViewBinder {
    @SuppressLint("InflateParams") // We don't care that the parent is null.
    fun createAndShow(
        container: View,
        viewModel: KeyguardSettingsPopupMenuViewModel,
        onDismissed: () -> Unit,
    ): () -> Unit {
        val contentView: View =
            LayoutInflater.from(container.context)
                .inflate(
                    R.layout.keyguard_settings_popup_menu,
                    null,
                )

        contentView.setOnClickListener { viewModel.onClicked() }
        IconViewBinder.bind(
            icon = viewModel.icon,
            view = contentView.requireViewById(R.id.icon),
        )
        TextViewBinder.bind(
            view = contentView.requireViewById(R.id.text),
            viewModel = viewModel.text,
        )

        val popupWindow =
            PopupWindow(container.context).apply {
                windowLayoutType = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
                setBackgroundDrawable(null)
                animationStyle = com.android.internal.R.style.Animation_Dialog
                isOutsideTouchable = true
                isFocusable = true
                setContentView(contentView)
                setOnDismissListener { onDismissed() }
                contentView.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        0,
                        View.MeasureSpec.UNSPECIFIED,
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        0,
                        View.MeasureSpec.UNSPECIFIED,
                    ),
                )
                showAtLocation(
                    container,
                    Gravity.NO_GRAVITY,
                    viewModel.position.x - contentView.measuredWidth / 2,
                    viewModel.position.y -
                        contentView.measuredHeight -
                        container.context.resources.getDimensionPixelSize(
                            R.dimen.keyguard_long_press_settings_popup_vertical_offset
                        ),
                )
            }

        return { popupWindow.dismiss() }
    }
}
