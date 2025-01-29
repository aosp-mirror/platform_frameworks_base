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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.dimensionResource
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.rememberBurnIn
import com.android.systemui.keyguard.ui.composable.section.DefaultClockSection
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.viewmodel.NotificationsShadeOverlayActionsViewModel
import com.android.systemui.notifications.ui.viewmodel.NotificationsShadeOverlayContentViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.composable.OverlayShadeHeader
import com.android.systemui.shade.ui.composable.SingleShadeMeasurePolicy
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class NotificationsShadeOverlay
@Inject
constructor(
    private val actionsViewModelFactory: NotificationsShadeOverlayActionsViewModel.Factory,
    private val contentViewModelFactory: NotificationsShadeOverlayContentViewModel.Factory,
    private val shadeSession: SaveableSession,
    private val stackScrollView: Lazy<NotificationScrollView>,
    private val clockSection: DefaultClockSection,
    private val clockInteractor: KeyguardClockInteractor,
) : Overlay {
    override val key = Overlays.NotificationsShade

    private val actionsViewModel: NotificationsShadeOverlayActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override suspend fun activate(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun ContentScope.Content(modifier: Modifier) {
        val notificationStackPadding = dimensionResource(id = R.dimen.notification_side_paddings)

        val viewModel =
            rememberViewModel("NotificationsShadeOverlay-viewModel") {
                contentViewModelFactory.create()
            }
        val placeholderViewModel =
            rememberViewModel("NotificationsShadeOverlay-notifPlaceholderViewModel") {
                viewModel.notificationsPlaceholderViewModelFactory.create()
            }

        OverlayShade(
            panelAlignment = Alignment.TopStart,
            modifier = modifier,
            onScrimClicked = viewModel::onScrimClicked,
            header = {
                val headerViewModel =
                    rememberViewModel("NotificationsShadeOverlayHeader") {
                        viewModel.shadeHeaderViewModelFactory.create()
                    }
                OverlayShadeHeader(
                    viewModel = headerViewModel,
                    modifier =
                        Modifier.element(NotificationsShade.Elements.StatusBar)
                            .layoutId(SingleShadeMeasurePolicy.LayoutId.ShadeHeader),
                )
            },
        ) {
            Box {
                Column {
                    if (viewModel.showClock) {
                        val burnIn = rememberBurnIn(clockInteractor)

                        with(clockSection) {
                            SmallClock(
                                burnInParams = burnIn.parameters,
                                onTopChanged = burnIn.onSmallClockTopChanged,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    NotificationScrollingStack(
                        shadeSession = shadeSession,
                        stackScrollView = stackScrollView.get(),
                        viewModel = placeholderViewModel,
                        maxScrimTop = { 0f },
                        shouldPunchHoleBehindScrim = false,
                        stackTopPadding = notificationStackPadding,
                        stackBottomPadding = notificationStackPadding,
                        shouldFillMaxSize = false,
                        shouldShowScrim = false,
                        supportNestedScrolling = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Communicates the bottom position of the drawable area within the shade to NSSL.
                NotificationStackCutoffGuideline(
                    stackScrollView = stackScrollView.get(),
                    viewModel = placeholderViewModel,
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = notificationStackPadding),
                )
            }
        }
    }
}

object NotificationsShade {
    object Elements {
        val StatusBar = ElementKey("NotificationsShadeStatusBar")
    }
}
