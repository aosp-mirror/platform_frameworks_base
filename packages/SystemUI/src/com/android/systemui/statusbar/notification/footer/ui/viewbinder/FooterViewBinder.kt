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
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.NotificationActivityStarter.SettingsIntent
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.notification.footer.shared.NotifRedesignFooter
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope

/** Binds a [FooterView] to its [view model][FooterViewModel]. */
object FooterViewBinder {
    fun bindWhileAttached(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
        launchNotificationSettings: View.OnClickListener,
        launchNotificationHistory: View.OnClickListener,
        notificationActivityStarter: NotificationActivityStarter,
    ): DisposableHandle {
        return footer.repeatWhenAttached {
            lifecycleScope.launch {
                bind(
                    footer,
                    viewModel,
                    clearAllNotifications,
                    launchNotificationSettings,
                    launchNotificationHistory,
                    notificationActivityStarter,
                )
            }
        }
    }

    suspend fun bind(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
        launchNotificationSettings: View.OnClickListener,
        launchNotificationHistory: View.OnClickListener,
        notificationActivityStarter: NotificationActivityStarter,
    ) = coroutineScope {
        launch { bindClearAllButton(footer, viewModel, clearAllNotifications) }
        if (!NotifRedesignFooter.isEnabled) {
            launch {
                bindManageOrHistoryButton(
                    footer,
                    viewModel,
                    launchNotificationSettings,
                    launchNotificationHistory,
                    notificationActivityStarter,
                )
            }
        } else {
            launch { bindSettingsButton(footer, viewModel, notificationActivityStarter) }
            launch { bindHistoryButton(footer, viewModel, notificationActivityStarter) }
        }
        launch { bindMessage(footer, viewModel) }
    }

    private suspend fun bindClearAllButton(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
    ) = coroutineScope {
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
                if (isVisible.value) {
                    footer.setClearAllButtonClickListener(clearAllNotifications)
                } else {
                    // When the button isn't visible, it also shouldn't react to clicks. This is
                    // necessary because when the clear all button is not visible, it's actually
                    // just the alpha that becomes 0 so it can still be tapped.
                    footer.setClearAllButtonClickListener(null)
                }

                if (isVisible.isAnimating) {
                    footer.setClearAllButtonVisible(isVisible.value, /* animate= */ true) { _ ->
                        isVisible.stopAnimating()
                    }
                } else {
                    footer.setClearAllButtonVisible(isVisible.value, /* animate= */ false)
                }
            }
        }
    }

    private suspend fun bindSettingsButton(
        footer: FooterView,
        viewModel: FooterViewModel,
        notificationActivityStarter: NotificationActivityStarter,
    ) = coroutineScope {
        val settingsIntent =
            SettingsIntent.forNotificationSettings(
                cujType = InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
            )
        val onClickListener = { view: View ->
            notificationActivityStarter.startSettingsIntent(view, settingsIntent)
        }
        footer.setSettingsButtonClickListener(onClickListener)

        launch {
            // NOTE: This visibility change is never animated. We also don't need to do anything
            // special about the onClickListener here, since we're changing the visibility to
            // GONE so it won't be clickable anyway.
            viewModel.settingsButtonVisible.collect { footer.setSettingsButtonVisible(it) }
        }
    }

    private suspend fun bindHistoryButton(
        footer: FooterView,
        viewModel: FooterViewModel,
        notificationActivityStarter: NotificationActivityStarter,
    ) = coroutineScope {
        val settingsIntent =
            SettingsIntent.forNotificationHistory(
                cujType = InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
            )
        val onClickListener = { view: View ->
            notificationActivityStarter.startSettingsIntent(view, settingsIntent)
        }
        footer.setHistoryButtonClickListener(onClickListener)

        launch {
            // NOTE: This visibility change is never animated. We also don't need to do anything
            // special about the onClickListener here, since we're changing the visibility to
            // GONE so it won't be clickable anyway.
            viewModel.historyButtonVisible.collect { footer.setHistoryButtonVisible(it) }
        }
    }

    private suspend fun bindManageOrHistoryButton(
        footer: FooterView,
        viewModel: FooterViewModel,
        launchNotificationSettings: View.OnClickListener,
        launchNotificationHistory: View.OnClickListener,
        notificationActivityStarter: NotificationActivityStarter,
    ) = coroutineScope {
        launch {
            if (ModesEmptyShadeFix.isEnabled) {
                viewModel.manageOrHistoryButtonClick.collect { settingsIntent ->
                    val onClickListener = { view: View ->
                        notificationActivityStarter.startSettingsIntent(view, settingsIntent)
                    }
                    footer.setManageButtonClickListener(onClickListener)
                }
            } else {
                viewModel.manageButtonShouldLaunchHistory.collect { shouldLaunchHistory ->
                    if (shouldLaunchHistory) {
                        footer.setManageButtonClickListener(launchNotificationHistory)
                    } else {
                        footer.setManageButtonClickListener(launchNotificationSettings)
                    }
                }
            }
        }

        launch {
            viewModel.manageOrHistoryButton.labelId.collect { textId ->
                footer.setManageOrHistoryButtonText(textId)
            }
        }

        launch {
            viewModel.manageOrHistoryButton.accessibilityDescriptionId.collect { textId ->
                footer.setManageOrHistoryButtonDescription(textId)
            }
        }

        launch {
            viewModel.manageOrHistoryButton.isVisible.collect { isVisible ->
                // NOTE: This visibility change is never animated. We also don't need to do anything
                // special about the onClickListener here, since we're changing the visibility to
                // GONE so it won't be clickable anyway.
                footer.setManageOrHistoryButtonVisible(isVisible.value)
            }
        }
    }

    private suspend fun bindMessage(footer: FooterView, viewModel: FooterViewModel) =
        coroutineScope {
            // Bind the resource IDs
            footer.setMessageString(viewModel.message.messageId)
            footer.setMessageIcon(viewModel.message.iconId)

            launch {
                viewModel.message.isVisible.collect { visible ->
                    footer.setFooterLabelVisible(visible)
                }
            }
        }
}
