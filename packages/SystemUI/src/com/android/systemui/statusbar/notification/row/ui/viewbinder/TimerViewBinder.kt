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

import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.view.View
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.row.ui.view.TimerButtonView
import com.android.systemui.statusbar.notification.row.ui.view.TimerView
import com.android.systemui.statusbar.notification.row.ui.viewmodel.TimerViewModel
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Binds a [TimerView] to its [view model][TimerViewModel]. */
object TimerViewBinder {
    fun bindWhileAttached(
        view: TimerView,
        viewModel: TimerViewModel,
    ): DisposableHandle {
        return view.repeatWhenAttached { lifecycleScope.launch { bind(view, viewModel) } }
    }

    suspend fun bind(
        view: TimerView,
        viewModel: TimerViewModel,
    ) = coroutineScope {
        launch { viewModel.icon.collect { view.setIcon(it) } }
        launch { viewModel.label.collect { view.setLabel(it) } }
        launch { viewModel.pausedTime.collect { view.setPausedTime(it) } }
        launch { viewModel.countdownTime.collect { view.setCountdownTime(it) } }
        launch { viewModel.mainButtonModel.collect { bind(view.mainButton, it) } }
        launch { viewModel.altButtonModel.collect { bind(view.altButton, it) } }
        launch { viewModel.resetButtonModel.collect { bind(view.resetButton, it) } }
    }

    fun bind(buttonView: TimerButtonView, model: TimerViewModel.ButtonViewModel?) {
        if (model != null) {
            buttonView.setButtonBackground(
                ColorStateList.valueOf(
                    buttonView.context.getColor(com.android.internal.R.color.system_accent2_100)
                )
            )
            buttonView.setTextColor(
                buttonView.context.getColor(
                    com.android.internal.R.color.notification_primary_text_color_light
                )
            )

            when (model) {
                is TimerViewModel.ButtonViewModel.WithSystemAttrs -> {
                    buttonView.setIcon(model.iconRes)
                    buttonView.setText(model.labelRes)
                }
                is TimerViewModel.ButtonViewModel.WithCustomAttrs -> {
                    // TODO: b/352142761 - is there a better way to deal with TYPE_RESOURCE icons
                    // with empty resPackage? RemoteViews handles this by using a  different
                    // `contextForResources` for inflation.
                    val icon =
                        if (model.icon.type == Icon.TYPE_RESOURCE && model.icon.resPackage == "")
                            Icon.createWithResource(
                                "com.google.android.deskclock",
                                model.icon.resId
                            )
                        else model.icon
                    buttonView.setImageIcon(icon)
                    buttonView.text = model.label
                }
            }

            buttonView.setOnClickListener(
                model.pendingIntent?.let { pendingIntent ->
                    View.OnClickListener { pendingIntent.send() }
                }
            )
            buttonView.isEnabled = model.pendingIntent != null
        }
        buttonView.isGone = model == null
    }
}
