/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.compose.OngoingActivityChips
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.data.repository.DarkIconDispatcherStore
import com.android.systemui.statusbar.events.domain.interactor.SystemStatusEventAnimationInteractor
import com.android.systemui.statusbar.featurepods.popups.StatusBarPopupChips
import com.android.systemui.statusbar.featurepods.popups.ui.compose.StatusBarPopupChipsContainer
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ui.DarkIconManager
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel.HomeStatusBarViewModelFactory
import javax.inject.Inject

/** Factory to simplify the dependency management for [StatusBarRoot] */
class StatusBarRootFactory
@Inject
constructor(
    private val homeStatusBarViewModelFactory: HomeStatusBarViewModelFactory,
    private val homeStatusBarViewBinder: HomeStatusBarViewBinder,
    private val notificationIconsBinder: NotificationIconContainerStatusBarViewBinder,
    private val darkIconManagerFactory: DarkIconManager.Factory,
    private val iconController: StatusBarIconController,
    private val ongoingCallController: OngoingCallController,
    private val darkIconDispatcherStore: DarkIconDispatcherStore,
    private val eventAnimationInteractor: SystemStatusEventAnimationInteractor,
) {
    fun create(root: ViewGroup, andThen: (ViewGroup) -> Unit): ComposeView {
        val composeView = ComposeView(root.context)
        val displayId = root.context.displayId
        val darkIconDispatcher =
            darkIconDispatcherStore.forDisplay(root.context.displayId) ?: return composeView
        composeView.apply {
            setContent {
                StatusBarRoot(
                    parent = root,
                    statusBarViewModel = homeStatusBarViewModelFactory.create(displayId),
                    statusBarViewBinder = homeStatusBarViewBinder,
                    notificationIconsBinder = notificationIconsBinder,
                    darkIconManagerFactory = darkIconManagerFactory,
                    iconController = iconController,
                    ongoingCallController = ongoingCallController,
                    darkIconDispatcher = darkIconDispatcher,
                    eventAnimationInteractor = eventAnimationInteractor,
                    onViewCreated = andThen,
                )
            }
        }

        return composeView
    }
}

/**
 * For now, this class exists only to replace the former CollapsedStatusBarFragment. We simply stand
 * up the PhoneStatusBarView here (allowing the component to be initialized from the [init] block).
 * This is the place, for now, where we can manually set up lingering dependencies that came from
 * the fragment until we can move them to recommended-arch style repos.
 *
 * @param onViewCreated called immediately after the view is inflated, and takes as a parameter the
 *   newly-inflated PhoneStatusBarView. This lambda is useful for tying together old initialization
 *   logic until it can be replaced.
 */
@Composable
fun StatusBarRoot(
    parent: ViewGroup,
    statusBarViewModel: HomeStatusBarViewModel,
    statusBarViewBinder: HomeStatusBarViewBinder,
    notificationIconsBinder: NotificationIconContainerStatusBarViewBinder,
    darkIconManagerFactory: DarkIconManager.Factory,
    iconController: StatusBarIconController,
    ongoingCallController: OngoingCallController,
    darkIconDispatcher: DarkIconDispatcher,
    eventAnimationInteractor: SystemStatusEventAnimationInteractor,
    onViewCreated: (ViewGroup) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // TODO(b/364360986): remove this before rolling the flag forward
        if (StatusBarRootModernization.SHOW_DISAMBIGUATION) {
            Disambiguation(viewModel = statusBarViewModel)
        }

        Row(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    val inflater = LayoutInflater.from(context)
                    val phoneStatusBarView =
                        inflater.inflate(R.layout.status_bar, parent, false) as PhoneStatusBarView

                    // For now, just set up the system icons the same way we used to
                    val statusIconContainer =
                        phoneStatusBarView.requireViewById<StatusIconContainer>(R.id.statusIcons)
                    // TODO(b/364360986): turn this into a repo/intr/viewmodel
                    val darkIconManager =
                        darkIconManagerFactory.create(
                            statusIconContainer,
                            StatusBarLocation.HOME,
                            darkIconDispatcher,
                        )
                    iconController.addIconGroup(darkIconManager)

                    if (StatusBarChipsModernization.isEnabled) {
                        val startSideExceptHeadsUp =
                            phoneStatusBarView.requireViewById<LinearLayout>(
                                R.id.status_bar_start_side_except_heads_up
                            )

                        val composeView =
                            ComposeView(context).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                    )

                                setViewCompositionStrategy(
                                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                                )

                                setContent {
                                    PlatformTheme {
                                        val chips by
                                            statusBarViewModel.ongoingActivityChips
                                                .collectAsStateWithLifecycle()
                                        OngoingActivityChips(chips = chips)
                                    }
                                }
                            }

                        // Add the composable container for ongoingActivityChips before the
                        // notification_icon_area to maintain the same ordering for ongoing activity
                        // chips in the status bar layout.
                        val notificationIconAreaIndex =
                            startSideExceptHeadsUp.indexOfChild(
                                startSideExceptHeadsUp.findViewById(R.id.notification_icon_area)
                            )
                        startSideExceptHeadsUp.addView(composeView, notificationIconAreaIndex)
                    }

                    HomeStatusBarIconBlockListBinder.bind(
                        statusIconContainer,
                        darkIconManager,
                        statusBarViewModel.iconBlockList,
                    )

                    if (StatusBarChipsModernization.isEnabled) {
                        // Make sure the primary chip is hidden when StatusBarChipsModernization is
                        // enabled. OngoingActivityChips will be shown in a composable container
                        // when this flag is enabled.
                        phoneStatusBarView
                            .requireViewById<View>(R.id.ongoing_activity_chip_primary)
                            .visibility = View.GONE
                    } else {
                        ongoingCallController.setChipView(
                            phoneStatusBarView.requireViewById(R.id.ongoing_activity_chip_primary)
                        )
                    }

                    // For notifications, first inflate the [NotificationIconContainer]
                    val notificationIconArea =
                        phoneStatusBarView.requireViewById<ViewGroup>(R.id.notification_icon_area)
                    inflater.inflate(R.layout.notification_icon_area, notificationIconArea, true)
                    // Then bind it using the icons binder
                    val notificationIconContainer =
                        phoneStatusBarView.requireViewById<NotificationIconContainer>(
                            R.id.notificationIcons
                        )

                    // Add a composable container for `StatusBarPopupChip`s
                    if (StatusBarPopupChips.isEnabled) {
                        val endSideContent =
                            phoneStatusBarView.requireViewById<AlphaOptimizedLinearLayout>(
                                R.id.status_bar_end_side_content
                            )

                        val composeView =
                            ComposeView(context).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                    )

                                setViewCompositionStrategy(
                                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                                )

                                setContent {
                                    val chips =
                                        statusBarViewModel.statusBarPopupChips
                                            .collectAsStateWithLifecycle()
                                    StatusBarPopupChipsContainer(chips = chips.value)
                                }
                            }
                        endSideContent.addView(composeView, 0)
                    }

                    notificationIconsBinder.bindWhileAttached(
                        notificationIconContainer,
                        context.displayId,
                    )

                    // This binder handles everything else
                    statusBarViewBinder.bind(
                        context.displayId,
                        phoneStatusBarView,
                        statusBarViewModel,
                        eventAnimationInteractor::animateStatusBarContentForChipEnter,
                        eventAnimationInteractor::animateStatusBarContentForChipExit,
                        listener = null,
                    )
                    onViewCreated(phoneStatusBarView)
                    phoneStatusBarView
                }
            )
        }
    }
}

/**
 * This is our analog of the flexi "ribbon", which just shows some text so we know if the flag is on
 */
@Composable
fun Disambiguation(viewModel: HomeStatusBarViewModel) {
    val clockVisibilityModel =
        viewModel.isClockVisible.collectAsStateWithLifecycle(
            initialValue = VisibilityModel(visibility = View.GONE, shouldAnimateChange = false)
        )
    if (clockVisibilityModel.value.visibility == View.VISIBLE) {
        Box(modifier = Modifier.fillMaxSize().alpha(0.5f), contentAlignment = Alignment.Center) {
            RetroText(text = "COMPOSE->BAR")
        }
    }
}
