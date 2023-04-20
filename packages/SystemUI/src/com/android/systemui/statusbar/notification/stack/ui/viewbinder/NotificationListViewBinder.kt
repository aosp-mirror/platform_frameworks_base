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

package com.android.systemui.statusbar.notification.stack.ui.viewbinder

import android.view.LayoutInflater
import com.android.systemui.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinder
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.statusbar.phone.NotificationIconAreaController

/** Binds a [NotificationStackScrollLayout] to its [view model][NotificationListViewModel]. */
object NotificationListViewBinder {
    @JvmStatic
    fun bind(
        view: NotificationStackScrollLayout,
        viewModel: NotificationListViewModel,
        falsingManager: FalsingManager,
        featureFlags: FeatureFlags,
        iconAreaController: NotificationIconAreaController,
    ) {
        val shelf =
            LayoutInflater.from(view.context)
                .inflate(R.layout.status_bar_notification_shelf, view, false) as NotificationShelf
        NotificationShelfViewBinder.bind(
            shelf,
            viewModel.shelf,
            falsingManager,
            featureFlags,
            iconAreaController
        )
        view.setShelf(shelf)
    }
}
