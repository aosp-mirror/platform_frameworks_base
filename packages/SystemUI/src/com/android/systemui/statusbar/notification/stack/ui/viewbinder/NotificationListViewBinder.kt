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
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.TraceUtils.traceAsync
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.view.setImportantForAccessibilityYesNo
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.repeatWhenAttachedToWindow
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController
import com.android.systemui.statusbar.notification.dagger.SilentHeader
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.notification.emptyshade.ui.view.EmptyShadeView
import com.android.systemui.statusbar.notification.emptyshade.ui.viewbinder.EmptyShadeViewBinder
import com.android.systemui.statusbar.notification.emptyshade.ui.viewmodel.EmptyShadeViewModel
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.shared.NotifRedesignFooter
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewbinder.FooterViewBinder
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerShelfViewBinder
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinder
import com.android.systemui.statusbar.notification.stack.DisplaySwitchNotificationsHiderTracker
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.HideNotificationsBinder.bindHideList
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.statusbar.notification.ui.viewbinder.HeadsUpNotificationViewBinder
import com.android.systemui.util.kotlin.awaitCancellationThenDispose
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import java.util.Optional
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Binds a [NotificationStackScrollLayout] to its [view model][NotificationListViewModel]. */
class NotificationListViewBinder
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val hiderTracker: DisplaySwitchNotificationsHiderTracker,
    @ShadeDisplayAware private val configuration: ConfigurationState,
    private val falsingManager: FalsingManager,
    private val hunBinder: HeadsUpNotificationViewBinder,
    private val loggerOptional: Optional<NotificationStatsLogger>,
    private val metricsLogger: MetricsLogger,
    private val nicBinder: NotificationIconContainerShelfViewBinder,
    // Using a provider to avoid a circular dependency.
    private val notificationActivityStarter: Provider<NotificationActivityStarter>,
    @SilentHeader private val silentHeaderController: SectionHeaderController,
    private val viewModel: NotificationListViewModel,
) {

    fun bindWhileAttached(
        view: NotificationStackScrollLayout,
        viewController: NotificationStackScrollLayoutController,
    ) {
        val shelf =
            LayoutInflater.from(view.context)
                .inflate(R.layout.status_bar_notification_shelf, view, false) as NotificationShelf
        view.setShelf(shelf)

        view.repeatWhenAttached {
            lifecycleScope.launch {
                if (SceneContainerFlag.isEnabled) {
                    launch { hunBinder.bindHeadsUpNotifications(view) }
                }
                launch { bindShelf(shelf) }
                bindHideList(viewController, viewModel, hiderTracker)

                if (FooterViewRefactor.isEnabled) {
                    val hasNonClearableSilentNotifications: StateFlow<Boolean> =
                        viewModel.hasNonClearableSilentNotifications.stateIn(this)
                    launch { reinflateAndBindFooter(view, hasNonClearableSilentNotifications) }
                    launch {
                        if (ModesEmptyShadeFix.isEnabled) {
                            reinflateAndBindEmptyShade(view)
                        } else {
                            bindEmptyShadeLegacy(viewModel.emptyShadeViewFactory.create(), view)
                        }
                    }
                    launch {
                        bindSilentHeaderClickListener(view, hasNonClearableSilentNotifications)
                    }
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
        NotificationShelfViewBinder.bind(shelf, viewModel.shelf, falsingManager, nicBinder)
    }

    private suspend fun reinflateAndBindFooter(
        parentView: NotificationStackScrollLayout,
        hasNonClearableSilentNotifications: StateFlow<Boolean>,
    ) {
        viewModel.footer.getOrNull()?.let { footerViewModel ->
            // The footer needs to be re-inflated every time the theme or the font size changes.
            configuration
                .inflateLayout<FooterView>(
                    if (NotifRedesignFooter.isEnabled) R.layout.notification_2025_footer
                    else R.layout.status_bar_notification_footer,
                    parentView,
                    attachToRoot = false,
                )
                .flowOn(backgroundDispatcher)
                .collectLatest { footerView: FooterView ->
                    traceAsync("bind FooterView") {
                        parentView.setFooterView(footerView)
                        bindFooter(
                            footerView,
                            footerViewModel,
                            parentView,
                            hasNonClearableSilentNotifications,
                        )
                    }
                }
        }
    }

    /**
     * Binds the footer (including its visibility) and dispose of the [DisposableHandle] when done.
     */
    private suspend fun bindFooter(
        footerView: FooterView,
        footerViewModel: FooterViewModel,
        parentView: NotificationStackScrollLayout,
        hasNonClearableSilentNotifications: StateFlow<Boolean>,
    ): Unit = coroutineScope {
        val disposableHandle =
            FooterViewBinder.bindWhileAttached(
                footerView,
                footerViewModel,
                {
                    clearAllNotifications(
                        parentView,
                        // Hide the silent section header (if present) if there will be
                        // no remaining silent notifications upon clearing.
                        hideSilentSection = !hasNonClearableSilentNotifications.value,
                    )
                },
                launchNotificationSettings,
                launchNotificationHistory,
                notificationActivityStarter.get(),
            )
        if (SceneContainerFlag.isEnabled) {
            launch {
                viewModel.shouldShowFooterView.collect { animatedVisibility ->
                    footerView.setVisible(
                        /* visible = */ animatedVisibility.value,
                        /* animate = */ animatedVisibility.isAnimating,
                    ) {
                        animatedVisibility.stopAnimating()
                    }
                }
            }
        } else {
            launch {
                viewModel.shouldIncludeFooterView.collect { animatedVisibility ->
                    footerView.setVisible(
                        /* visible = */ animatedVisibility.value,
                        /* animate = */ animatedVisibility.isAnimating,
                    )
                }
            }
            launch { viewModel.shouldHideFooterView.collect { footerView.setShouldBeHidden(it) } }
        }
        disposableHandle.awaitCancellationThenDispose()
    }

    private val launchNotificationSettings: (View) -> Unit = { view: View ->
        notificationActivityStarter.get().startHistoryIntent(view, /* showHistory= */ false)
    }

    private val launchNotificationHistory: (View) -> Unit = { view ->
        notificationActivityStarter.get().startHistoryIntent(view, /* showHistory= */ true)
    }

    private suspend fun reinflateAndBindEmptyShade(parentView: NotificationStackScrollLayout) {
        ModesEmptyShadeFix.assertInNewMode()
        // The empty shade needs to be re-inflated every time the theme or the font size
        // changes.
        configuration
            .inflateLayout<EmptyShadeView>(
                R.layout.status_bar_no_notifications,
                parentView,
                attachToRoot = false,
            )
            .flowOn(backgroundDispatcher)
            .collectLatest { emptyShadeView: EmptyShadeView ->
                traceAsync("bind EmptyShadeView") {
                    parentView.setEmptyShadeView(emptyShadeView)
                    bindEmptyShade(emptyShadeView, viewModel.emptyShadeViewFactory.create())
                }
            }
    }

    private suspend fun bindEmptyShadeLegacy(
        emptyShadeViewModel: EmptyShadeViewModel,
        parentView: NotificationStackScrollLayout,
    ) {
        ModesEmptyShadeFix.assertInLegacyMode()
        combine(
                viewModel.shouldShowEmptyShadeView,
                emptyShadeViewModel.areNotificationsHiddenInShade,
                emptyShadeViewModel.hasFilteredOutSeenNotifications,
                ::Triple,
            )
            .collect { (shouldShow, areNotifsHidden, hasFilteredNotifs) ->
                parentView.updateEmptyShadeView(shouldShow, areNotifsHidden, hasFilteredNotifs)
            }
    }

    private suspend fun bindEmptyShade(
        emptyShadeView: EmptyShadeView,
        emptyShadeViewModel: EmptyShadeViewModel,
    ): Unit = coroutineScope {
        ModesEmptyShadeFix.assertInNewMode()
        launch {
            emptyShadeView.repeatWhenAttachedToWindow {
                EmptyShadeViewBinder.bind(
                    emptyShadeView,
                    emptyShadeViewModel,
                    notificationActivityStarter.get(),
                )
            }
        }
        launch {
            viewModel.shouldShowEmptyShadeViewAnimated.collect { shouldShow ->
                emptyShadeView.setVisible(shouldShow.value, shouldShow.isAnimating) {
                    shouldShow.stopAnimating()
                }
            }
        }
    }

    private suspend fun bindSilentHeaderClickListener(
        parentView: NotificationStackScrollLayout,
        hasNonClearableSilentNotifications: StateFlow<Boolean>,
    ): Unit = coroutineScope {
        val hasClearableAlertingNotifications: StateFlow<Boolean> =
            viewModel.hasClearableAlertingNotifications.stateIn(this)
        silentHeaderController.setOnClearSectionClickListener {
            clearSilentNotifications(
                view = parentView,
                // Leave the shade open if there will be other notifs left over to clear.
                closeShade = !hasClearableAlertingNotifications.value,
                // Hide the silent section header itself, if there will be no remaining silent
                // notifications upon clearing.
                hideSilentSection = !hasNonClearableSilentNotifications.value,
            )
        }
        try {
            awaitCancellation()
        } finally {
            silentHeaderController.setOnClearSectionClickListener {}
        }
    }

    private fun clearAllNotifications(
        view: NotificationStackScrollLayout,
        hideSilentSection: Boolean,
    ) {
        metricsLogger.action(MetricsProto.MetricsEvent.ACTION_DISMISS_ALL_NOTES)
        view.clearAllNotifications(hideSilentSection)
    }

    private fun clearSilentNotifications(
        view: NotificationStackScrollLayout,
        closeShade: Boolean,
        hideSilentSection: Boolean,
    ) {
        view.clearSilentNotifications(closeShade, hideSilentSection)
    }

    private suspend fun bindLogger(view: NotificationStackScrollLayout) {
        if (NotificationsLiveDataStoreRefactor.isEnabled) {
            viewModel.logger.getOrNull()?.let { viewModel ->
                loggerOptional.getOrNull()?.let { logger ->
                    NotificationStatsLoggerBinder.bindLogger(view, logger, viewModel)
                }
            }
        }
    }
}
