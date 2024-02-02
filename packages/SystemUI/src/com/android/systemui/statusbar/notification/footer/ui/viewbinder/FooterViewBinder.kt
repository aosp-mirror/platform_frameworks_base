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

package com.android.systemui.statusbar.notification.footer.ui.viewbinder

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Binds a [FooterView] to its [view model][FooterViewModel]. */
object FooterViewBinder {
    fun bindWhileAttached(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
        launchNotificationSettings: View.OnClickListener,
        launchNotificationHistory: View.OnClickListener,
    ): DisposableHandle {
        return footer.repeatWhenAttached {
            lifecycleScope.launch {
                bind(
                    footer,
                    viewModel,
                    clearAllNotifications,
                    launchNotificationSettings,
                    launchNotificationHistory
                )
            }
        }
    }

    suspend fun bind(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
        launchNotificationSettings: View.OnClickListener,
        launchNotificationHistory: View.OnClickListener
    ) = coroutineScope {
        launch {
            bindClearAllButton(
                footer,
                viewModel,
                clearAllNotifications,
            )
        }
        launch {
            bindManageOrHistoryButton(
                footer,
                viewModel,
                launchNotificationSettings,
                launchNotificationHistory
            )
        }
        launch { bindMessage(footer, viewModel) }
    }

    private suspend fun bindClearAllButton(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
    ) = coroutineScope {
        footer.setClearAllButtonClickListener(clearAllNotifications)

        launch {
            viewModel.clearAllButton.labelId.collect { textId ->
                footer.setClearAllButtonText(textId)
            }
        }

        launch {
            viewModel.clearAllButton.accessibilityDescriptionId.collect { textId ->
                footer.setClearAllButtonDescription(textId)
            }
        }

        launch {
            viewModel.clearAllButton.isVisible.collect { isVisible ->
                if (isVisible.isAnimating) {
                    footer.setClearAllButtonVisible(
                        isVisible.value,
                        /* animate = */ true,
                    ) { _ ->
                        isVisible.stopAnimating()
                    }
                } else {
                    footer.setClearAllButtonVisible(
                        isVisible.value,
                        /* animate = */ false,
                    )
                }
            }
        }
    }

    private suspend fun bindManageOrHistoryButton(
        footer: FooterView,
        viewModel: FooterViewModel,
        launchNotificationSettings: View.OnClickListener,
        launchNotificationHistory: View.OnClickListener,
    ) = coroutineScope {
        launch {
            viewModel.manageButtonShouldLaunchHistory.collect { shouldLaunchHistory ->
                if (shouldLaunchHistory) {
                    footer.setManageButtonClickListener(launchNotificationHistory)
                } else {
                    footer.setManageButtonClickListener(launchNotificationSettings)
                }
            }
        }

        launch {
            viewModel.manageOrHistoryButton.labelId.collect { textId ->
                footer.setManageOrHistoryButtonText(textId)
            }
        }

        launch {
            viewModel.clearAllButton.accessibilityDescriptionId.collect { textId ->
                footer.setManageOrHistoryButtonDescription(textId)
            }
        }

        // NOTE: The manage/history button is always visible as long as the footer is visible, no
        //  need to update the visibility here.
    }

    private suspend fun bindMessage(
        footer: FooterView,
        viewModel: FooterViewModel,
    ) = coroutineScope {
        // Bind the resource IDs
        footer.setMessageString(viewModel.message.messageId)
        footer.setMessageIcon(viewModel.message.iconId)

        launch {
            viewModel.message.isVisible.collect { visible -> footer.setFooterLabelVisible(visible) }
        }
    }
}
