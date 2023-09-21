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

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.LegacyNotificationShelfControllerImpl
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.notification.row.ui.viewbinder.ActivatableNotificationViewBinder
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.phone.NotificationIconContainer
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Controller class for [NotificationShelf]. This implementation serves as a temporary wrapper
 * around a [NotificationShelfViewBinder], so that external code can continue to depend on the
 * [NotificationShelfController] interface. Once the [LegacyNotificationShelfControllerImpl] is
 * removed, this class can go away and the ViewBinder can be used directly.
 */
@SysUISingleton
class NotificationShelfViewBinderWrapperControllerImpl @Inject constructor() :
    NotificationShelfController {

    override val view: NotificationShelf
        get() = unsupported

    override val intrinsicHeight: Int
        get() = unsupported

    override val shelfIcons: NotificationIconContainer
        get() = unsupported

    override fun canModifyColorOfNotifications(): Boolean = unsupported

    override fun bind(
        ambientState: AmbientState,
        notificationStackScrollLayoutController: NotificationStackScrollLayoutController,
    ) = unsupported

    override fun setOnClickListener(listener: View.OnClickListener) = unsupported

    companion object {
        val unsupported: Nothing
            get() = error("Code path not supported when NOTIFICATION_SHELF_REFACTOR is disabled")
    }
}

/** Binds a [NotificationShelf] to its [view model][NotificationShelfViewModel]. */
object NotificationShelfViewBinder {
    fun bind(
        shelf: NotificationShelf,
        viewModel: NotificationShelfViewModel,
        falsingManager: FalsingManager,
        featureFlags: FeatureFlags,
        notificationIconAreaController: NotificationIconAreaController,
    ) {
        ActivatableNotificationViewBinder.bind(viewModel, shelf, falsingManager)
        shelf.apply {
            // TODO(278765923): Replace with eventual NotificationIconContainerViewBinder#bind()
            notificationIconAreaController.setShelfIcons(shelfIcons)
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
