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
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.reinflateAndBindLatest
import com.android.systemui.common.ui.view.setImportantForAccessibilityYesNo
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewbinder.FooterViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerShelfViewBinder
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinder
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.HideNotificationsBinder.bindHideList
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Binds a [NotificationStackScrollLayout] to its [view model][NotificationListViewModel]. */
class NotificationListViewBinder
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val configuration: ConfigurationState,
    private val falsingManager: FalsingManager,
    private val iconAreaController: NotificationIconAreaController,
    private val metricsLogger: MetricsLogger,
    private val nicBinder: NotificationIconContainerShelfViewBinder,
    private val loggerOptional: Optional<NotificationStatsLogger>,
    private val viewModel: NotificationListViewModel,
) {

    fun bindWhileAttached(
        view: NotificationStackScrollLayout,
        viewController: NotificationStackScrollLayoutController
    ) {
        val shelf =
            LayoutInflater.from(view.context)
                .inflate(R.layout.status_bar_notification_shelf, view, false) as NotificationShelf
        view.setShelf(shelf)

        view.repeatWhenAttached {
            lifecycleScope.launch {
                launch { bindShelf(shelf) }
                launch { bindHideList(viewController, viewModel) }

                if (FooterViewRefactor.isEnabled) {
                    launch { bindFooter(view) }
                    launch { bindEmptyShade(view) }
                    launch {
                        viewModel.isImportantForAccessibility.collect { isImportantForAccessibility
                            ->
                            view.setImportantForAccessibilityYesNo(isImportantForAccessibility)
                        }
                    }
                }

                launch { bindLogger(view) }
            }
        }
    }

    private suspend fun bindShelf(shelf: NotificationShelf) {
        NotificationShelfViewBinder.bind(
            shelf,
            viewModel.shelf,
            falsingManager,
            nicBinder,
            iconAreaController,
        )
    }

    private suspend fun bindFooter(parentView: NotificationStackScrollLayout) {
        viewModel.footer.getOrNull()?.let { footerViewModel ->
            // The footer needs to be re-inflated every time the theme or the font size changes.
            configuration.reinflateAndBindLatest(
                R.layout.status_bar_notification_footer,
                parentView,
                attachToRoot = false,
                backgroundDispatcher,
            ) { footerView: FooterView ->
                traceSection("bind FooterView") {
                    val disposableHandle =
                        FooterViewBinder.bind(
                            footerView,
                            footerViewModel,
                            clearAllNotifications = {
                                metricsLogger.action(
                                    MetricsProto.MetricsEvent.ACTION_DISMISS_ALL_NOTES
                                )
                                parentView.clearAllNotifications()
                            },
                        )
                    parentView.setFooterView(footerView)
                    return@reinflateAndBindLatest disposableHandle
                }
            }
        }
    }

    private suspend fun bindEmptyShade(parentView: NotificationStackScrollLayout) {
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

    private suspend fun bindLogger(view: NotificationStackScrollLayout) {
        if (NotificationsLiveDataStoreRefactor.isEnabled) {
            viewModel.logger.getOrNull()?.let { viewModel ->
                loggerOptional.getOrNull()?.let { logger ->
                    NotificationStatsLoggerBinder.bindLogger(
                        view,
                        logger,
                        viewModel,
                    )
                }
            }
        }
    }
}
