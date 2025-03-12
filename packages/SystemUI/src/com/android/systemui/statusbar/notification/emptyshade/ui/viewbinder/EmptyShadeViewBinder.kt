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

package com.android.systemui.statusbar.notification.emptyshade.ui.viewbinder

import android.view.View
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.emptyshade.ui.view.EmptyShadeView
import com.android.systemui.statusbar.notification.emptyshade.ui.viewmodel.EmptyShadeViewModel
import kotlinx.coroutines.coroutineScope
import com.android.app.tracing.coroutines.launchTraced as launch

object EmptyShadeViewBinder {
    suspend fun bind(
        view: EmptyShadeView,
        viewModel: EmptyShadeViewModel,
        notificationActivityStarter: NotificationActivityStarter,
    ) = coroutineScope {
        launch { viewModel.text.collect { view.setText(it) } }

        launch {
            viewModel.onClick.collect { settingsIntent ->
                val onClickListener = { view: View ->
                    notificationActivityStarter.startSettingsIntent(view, settingsIntent)
                }
                view.setOnClickListener(onClickListener)
            }
        }

        launch { bindFooter(view, viewModel) }
    }

    private suspend fun bindFooter(view: EmptyShadeView, viewModel: EmptyShadeViewModel) =
        coroutineScope {
            // Bind the resource IDs
            view.setFooterText(viewModel.footer.messageId)
            view.setFooterIcon(viewModel.footer.iconId)

            launch {
                viewModel.footer.isVisible.collect { visible ->
                    view.setFooterVisibility(if (visible) View.VISIBLE else View.GONE)
                }
            }
        }
}
