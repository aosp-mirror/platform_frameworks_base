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

package com.android.systemui.statusbar.notification.shelf.ui.viewbinder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.ShelfNotificationIconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.StatusBarIconViewBindingFailureTracker
import com.android.systemui.statusbar.notification.row.ui.viewbinder.ActivatableNotificationViewBinder
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** Binds a [NotificationShelf] to its [view model][NotificationShelfViewModel]. */
object NotificationShelfViewBinder {
    fun bind(
        shelf: NotificationShelf,
        viewModel: NotificationShelfViewModel,
        configuration: ConfigurationState,
        systemBarUtilsState: SystemBarUtilsState,
        falsingManager: FalsingManager,
        iconViewBindingFailureTracker: StatusBarIconViewBindingFailureTracker,
        notificationIconAreaController: NotificationIconAreaController,
        shelfIconViewStore: ShelfNotificationIconViewStore,
    ) {
        ActivatableNotificationViewBinder.bind(viewModel, shelf, falsingManager)
        shelf.apply {
            if (NotificationIconContainerRefactor.isEnabled) {
                NotificationIconContainerViewBinder.bindWhileAttached(
                    shelfIcons,
                    viewModel.icons,
                    configuration,
                    systemBarUtilsState,
                    iconViewBindingFailureTracker,
                    shelfIconViewStore,
                )
            } else {
                notificationIconAreaController.setShelfIcons(shelfIcons)
            }
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.canModifyColorOfNotifications.collect(
                            ::setCanModifyColorOfNotifications
                        )
                    }
                    launch { viewModel.isClickable.collect(::setCanInteract) }
                    registerViewListenersWhileAttached(shelf, viewModel)
                }
            }
        }
    }

    private suspend fun registerViewListenersWhileAttached(
        shelf: NotificationShelf,
        viewModel: NotificationShelfViewModel,
    ) {
        try {
            shelf.setOnClickListener { viewModel.onShelfClicked() }
            awaitCancellation()
        } finally {
            shelf.setOnClickListener(null)
        }
    }
}
