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

import com.android.app.tracing.traceSection
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerShelfViewBinder
import com.android.systemui.statusbar.notification.row.ui.viewbinder.ActivatableNotificationViewBinder
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Binds a [NotificationShelf] to its [view model][NotificationShelfViewModel]. */
object NotificationShelfViewBinder {
    suspend fun bind(
        shelf: NotificationShelf,
        viewModel: NotificationShelfViewModel,
        falsingManager: FalsingManager,
        nicBinder: NotificationIconContainerShelfViewBinder,
    ): Unit = coroutineScope {
        ActivatableNotificationViewBinder.bind(viewModel, shelf, falsingManager)
        shelf.apply {
            traceSection("NotifShelf#bindShelfIcons") { launch { nicBinder.bind(shelfIcons) } }
            launch {
                viewModel.canModifyColorOfNotifications.collect(::setCanModifyColorOfNotifications)
            }
            launch { viewModel.isClickable.collect(::setCanInteract) }
            registerViewListenersWhileAttached(shelf, viewModel)
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
