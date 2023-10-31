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
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.reinflateAndBindLatest
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewbinder.FooterViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.ShelfNotificationIconViewStore
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinder
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.traceSection

/** Binds a [NotificationStackScrollLayout] to its [view model][NotificationListViewModel]. */
object NotificationListViewBinder {
    @JvmStatic
    fun bind(
        view: NotificationStackScrollLayout,
        viewModel: NotificationListViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        falsingManager: FalsingManager,
        iconAreaController: NotificationIconAreaController,
        shelfIconViewStore: ShelfNotificationIconViewStore,
    ) {
        bindShelf(
            view,
            viewModel,
            configuration,
            configurationController,
            falsingManager,
            iconAreaController,
            shelfIconViewStore
        )

        bindFooter(view, viewModel, configuration)
    }

    private fun bindShelf(
        parentView: NotificationStackScrollLayout,
        parentViewModel: NotificationListViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        falsingManager: FalsingManager,
        iconAreaController: NotificationIconAreaController,
        shelfIconViewStore: ShelfNotificationIconViewStore
    ) {
        val shelf =
            LayoutInflater.from(parentView.context)
                .inflate(R.layout.status_bar_notification_shelf, parentView, false)
                as NotificationShelf
        NotificationShelfViewBinder.bind(
            shelf,
            parentViewModel.shelf,
            configuration,
            configurationController,
            falsingManager,
            iconAreaController,
            shelfIconViewStore,
        )
        parentView.setShelf(shelf)
    }

    private fun bindFooter(
        parentView: NotificationStackScrollLayout,
        parentViewModel: NotificationListViewModel,
        configuration: ConfigurationState
    ) {
        parentViewModel.footer.ifPresent { footerViewModel ->
            // The footer needs to be re-inflated every time the theme or the font size changes.
            parentView.repeatWhenAttached {
                configuration.reinflateAndBindLatest(
                    R.layout.status_bar_notification_footer,
                    parentView,
                    attachToRoot = false,
                ) { footerView: FooterView ->
                    traceSection("bind FooterView") {
                        val disposableHandle = FooterViewBinder.bind(footerView, footerViewModel)
                        parentView.setFooterView(footerView)
                        return@reinflateAndBindLatest disposableHandle
                    }
                }
            }
        }
    }
}
