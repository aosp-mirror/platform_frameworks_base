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
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewbinder.FooterViewBinder
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinder
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onDensityOrFontScaleChanged
import com.android.systemui.statusbar.policy.onThemeChanged
import com.android.systemui.util.traceSection
import com.android.systemui.util.view.reinflateAndBindLatest
import kotlinx.coroutines.flow.merge

/** Binds a [NotificationStackScrollLayout] to its [view model][NotificationListViewModel]. */
object NotificationListViewBinder {
    @JvmStatic
    fun bind(
        view: NotificationStackScrollLayout,
        viewModel: NotificationListViewModel,
        falsingManager: FalsingManager,
        featureFlags: FeatureFlagsClassic,
        iconAreaController: NotificationIconAreaController,
        configurationController: ConfigurationController,
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

        viewModel.footer.ifPresent { footerViewModel ->
            // The footer needs to be re-inflated every time the theme or the font size changes.
            view.repeatWhenAttached {
                LayoutInflater.from(view.context).reinflateAndBindLatest(
                    R.layout.status_bar_notification_footer,
                    view,
                    attachToRoot = false,
                    // TODO(b/305930747): This may lead to duplicate invocations if both flows emit,
                    // find a solution to only emit one event.
                    merge(
                        configurationController.onThemeChanged,
                        configurationController.onDensityOrFontScaleChanged,
                    ),
                ) { view ->
                    traceSection("bind FooterView") {
                        FooterViewBinder.bind(view as FooterView, footerViewModel)
                    }
                }
            }
        }
    }
}
