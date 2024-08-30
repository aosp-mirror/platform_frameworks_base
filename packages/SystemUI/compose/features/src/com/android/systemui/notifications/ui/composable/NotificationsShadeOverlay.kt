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

package com.android.systemui.notifications.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.viewmodel.NotificationsShadeOverlayActionsViewModel
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.viewmodel.OverlayShadeViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject

@SysUISingleton
class NotificationsShadeOverlay
@Inject
constructor(
    private val actionsViewModelFactory: NotificationsShadeOverlayActionsViewModel.Factory,
    private val overlayShadeViewModelFactory: OverlayShadeViewModel.Factory,
    private val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    private val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
    private val shadeSession: SaveableSession,
    private val stackScrollView: Lazy<NotificationScrollView>,
) : Overlay {

    override val key = Overlays.NotificationsShade

    private val actionsViewModel: NotificationsShadeOverlayActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override suspend fun activate(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun ContentScope.Content(
        modifier: Modifier,
    ) {
        OverlayShade(
            modifier = modifier,
            viewModelFactory = overlayShadeViewModelFactory,
            lockscreenContent = { Optional.empty() },
        ) {
            Column {
                val placeholderViewModel =
                    rememberViewModel("NotificationsShadeOverlay") {
                        notificationsPlaceholderViewModelFactory.create()
                    }

                ExpandedShadeHeader(
                    viewModelFactory = shadeHeaderViewModelFactory,
                    createTintedIconManager = tintedIconManagerFactory::create,
                    createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
                    statusBarIconController = statusBarIconController,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                NotificationScrollingStack(
                    shadeSession = shadeSession,
                    stackScrollView = stackScrollView.get(),
                    viewModel = placeholderViewModel,
                    maxScrimTop = { 0f },
                    shouldPunchHoleBehindScrim = false,
                    shouldFillMaxSize = false,
                    shouldReserveSpaceForNavBar = false,
                    shadeMode = ShadeMode.Dual,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Communicates the bottom position of the drawable area within the shade to NSSL.
                NotificationStackCutoffGuideline(
                    stackScrollView = stackScrollView.get(),
                    viewModel = placeholderViewModel,
                )
            }
        }
    }
}
