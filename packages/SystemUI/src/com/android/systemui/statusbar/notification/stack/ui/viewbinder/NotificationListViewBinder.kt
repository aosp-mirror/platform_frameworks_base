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
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.traceSection
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.reinflateAndBindLatest
import com.android.systemui.common.ui.view.setImportantForAccessibilityYesNo
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewbinder.FooterViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.ShelfNotificationIconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.StatusBarIconViewBindingFailureTracker
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinder
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.HideNotificationsBinder.bindHideList
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Binds a [NotificationStackScrollLayout] to its [view model][NotificationListViewModel]. */
class NotificationListViewBinder
@Inject
constructor(
    private val viewModel: NotificationListViewModel,
    private val configuration: ConfigurationState,
    private val configurationController: ConfigurationController,
    private val falsingManager: FalsingManager,
    private val iconAreaController: NotificationIconAreaController,
    private val iconViewBindingFailureTracker: StatusBarIconViewBindingFailureTracker,
    private val shelfIconViewStore: ShelfNotificationIconViewStore,
) {

    fun bind(
        view: NotificationStackScrollLayout,
        viewController: NotificationStackScrollLayoutController
    ) {
        bindShelf(view)
        bindFooter(view)
        bindEmptyShade(view)
        bindHideList(viewController, viewModel)

        view.repeatWhenAttached {
            lifecycleScope.launch {
                viewModel.isImportantForAccessibility.collect { isImportantForAccessibility ->
                    view.setImportantForAccessibilityYesNo(isImportantForAccessibility)
                }
            }
        }
    }

    private fun bindShelf(parentView: NotificationStackScrollLayout) {
        val shelf =
            LayoutInflater.from(parentView.context)
                .inflate(R.layout.status_bar_notification_shelf, parentView, false)
                as NotificationShelf
        NotificationShelfViewBinder.bind(
            shelf,
            viewModel.shelf,
            configuration,
            configurationController,
            falsingManager,
            iconViewBindingFailureTracker,
            iconAreaController,
            shelfIconViewStore,
        )
        parentView.setShelf(shelf)
    }

    private fun bindFooter(parentView: NotificationStackScrollLayout) {
        viewModel.footer.ifPresent { footerViewModel ->
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

    private fun bindEmptyShade(
        parentView: NotificationStackScrollLayout,
    ) {
        parentView.repeatWhenAttached {
            lifecycleScope.launch {
                combine(
                        viewModel.shouldShowEmptyShadeView,
                        viewModel.areNotificationsHiddenInShade,
                        viewModel.hasFilteredOutSeenNotifications,
                        ::Triple
                    )
                    .collect { (shouldShow, areNotifsHidden, hasFilteredNotifs) ->
                        parentView.updateEmptyShadeView(
                            shouldShow,
                            areNotifsHidden,
                            hasFilteredNotifs,
                        )
                    }
            }
        }
    }
}
