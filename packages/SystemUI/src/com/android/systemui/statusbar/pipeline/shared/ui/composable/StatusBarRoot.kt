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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.data.repository.DarkIconDispatcherStore
import com.android.systemui.statusbar.events.domain.interactor.SystemStatusEventAnimationInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ui.DarkIconManager
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarVisibilityChangeListener
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import javax.inject.Inject

/** Factory to simplify the dependency management for [StatusBarRoot] */
class StatusBarRootFactory
@Inject
constructor(
    private val homeStatusBarViewModel: HomeStatusBarViewModel,
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
        composeView.apply {
            setContent {
                StatusBarRoot(
                    parent = root,
                    statusBarViewModel = homeStatusBarViewModel,
                    statusBarViewBinder = homeStatusBarViewBinder,
                    notificationIconsBinder = notificationIconsBinder,
                    darkIconManagerFactory = darkIconManagerFactory,
                    iconController = iconController,
                    ongoingCallController = ongoingCallController,
                    darkIconDispatcher = darkIconDispatcherStore.forDisplay(root.context.displayId),
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
    // None of these methods are used when [StatusBarRootModernization] is on.
    // This can be deleted once the fragment is gone
    val nopVisibilityChangeListener =
        object : StatusBarVisibilityChangeListener {
            override fun onStatusBarVisibilityMaybeChanged() {}

            override fun onTransitionFromLockscreenToDreamStarted() {}

            override fun onOngoingActivityStatusChanged(
                hasPrimaryOngoingActivity: Boolean,
                hasSecondaryOngoingActivity: Boolean,
                shouldAnimate: Boolean,
            ) {}

            override fun onIsHomeStatusBarAllowedBySceneChanged(
                isHomeStatusBarAllowedByScene: Boolean
            ) {}
        }

    Box(Modifier.fillMaxSize()) {
        // TODO(b/364360986): remove this before rolling the flag forward
        Disambiguation(viewModel = statusBarViewModel)

        Row(Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
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

                    if (!StatusBarChipsModernization.isEnabled) {
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

                    scope.launch {
                        notificationIconsBinder.bindWhileAttached(
                            notificationIconContainer,
                            context.displayId,
                        )
                    }

                    // This binder handles everything else
                    scope.launch {
                        statusBarViewBinder.bind(
                            context.displayId,
                            phoneStatusBarView,
                            statusBarViewModel,
                            eventAnimationInteractor::animateStatusBarContentForChipEnter,
                            eventAnimationInteractor::animateStatusBarContentForChipExit,
                            nopVisibilityChangeListener,
                        )
                    }
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
            initialValue =
                HomeStatusBarViewModel.VisibilityModel(
                    visibility = View.GONE,
                    shouldAnimateChange = false,
                )
        )
    if (clockVisibilityModel.value.visibility == View.VISIBLE) {
        Box(modifier = Modifier.fillMaxSize().alpha(0.5f), contentAlignment = Alignment.Center) {
            RetroText(text = "COMPOSE->BAR")
        }
    }
}
