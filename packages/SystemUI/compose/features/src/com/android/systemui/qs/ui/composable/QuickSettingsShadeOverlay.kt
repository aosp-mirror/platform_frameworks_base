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

package com.android.systemui.qs.ui.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.composable.NotificationsShade
import com.android.systemui.notifications.ui.composable.SnoozeableHeadsUpNotificationSpace
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.TileDetails
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.panels.ui.compose.toolbar.Toolbar
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeOverlayActionsViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeOverlayContentViewModel
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.composable.OverlayShadeHeader
import com.android.systemui.shade.ui.composable.QuickSettingsOverlayHeader
import com.android.systemui.shade.ui.composable.SingleShadeMeasurePolicy
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QuickSettingsShadeOverlay
@Inject
constructor(
    private val actionsViewModelFactory: QuickSettingsShadeOverlayActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsShadeOverlayContentViewModel.Factory,
    private val quickSettingsContainerViewModelFactory: QuickSettingsContainerViewModel.Factory,
    private val notificationStackScrollView: Lazy<NotificationScrollView>,
    private val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
) : Overlay {

    override val key = Overlays.QuickSettingsShade

    private val actionsViewModel: QuickSettingsShadeOverlayActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override suspend fun activate(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun ContentScope.Content(modifier: Modifier) {
        val contentViewModel =
            rememberViewModel("QuickSettingsShadeOverlayContent") {
                contentViewModelFactory.create()
            }
        val quickSettingsContainerViewModel =
            rememberViewModel("QuickSettingsShadeOverlayContainer") {
                // TODO(b/393054014): Add support for brightness mirroring.
                quickSettingsContainerViewModelFactory.create(supportsBrightnessMirroring = false)
            }
        val panelCornerRadius =
            with(LocalDensity.current) { OverlayShade.Dimensions.PanelCornerRadius.toPx().toInt() }

        // Set the bounds to null when the QuickSettings overlay disappears.
        DisposableEffect(Unit) { onDispose { contentViewModel.onPanelShapeChanged(null) } }

        Box(modifier = modifier) {
            SnoozeableHeadsUpNotificationSpace(
                stackScrollView = notificationStackScrollView.get(),
                viewModel =
                    rememberViewModel("QuickSettingsShadeOverlayPlaceholder") {
                        notificationsPlaceholderViewModelFactory.create()
                    },
            )
            OverlayShade(
                panelAlignment = Alignment.TopEnd,
                onScrimClicked = contentViewModel::onScrimClicked,
                header = {
                    OverlayShadeHeader(
                        viewModel = quickSettingsContainerViewModel.shadeHeaderViewModel,
                        modifier =
                            Modifier.element(NotificationsShade.Elements.StatusBar)
                                .layoutId(SingleShadeMeasurePolicy.LayoutId.ShadeHeader),
                    )
                },
            ) {
                ShadeBody(
                    viewModel = quickSettingsContainerViewModel,
                    modifier =
                        Modifier.onPlaced { coordinates ->
                            val boundsInWindow = coordinates.boundsInWindow()
                            val shadeScrimBounds =
                                ShadeScrimBounds(
                                    left = boundsInWindow.left,
                                    top = boundsInWindow.top,
                                    right = boundsInWindow.right,
                                    bottom = boundsInWindow.bottom,
                                )
                            val shape =
                                ShadeScrimShape(
                                    bounds = shadeScrimBounds,
                                    topRadius = 0,
                                    bottomRadius = panelCornerRadius,
                                )
                            contentViewModel.onPanelShapeChanged(shape)
                        },
                    header = {
                        if (quickSettingsContainerViewModel.showHeader) {
                            QuickSettingsOverlayHeader(
                                viewModel = quickSettingsContainerViewModel.shadeHeaderViewModel,
                                modifier =
                                    Modifier.padding(top = QuickSettingsShade.Dimensions.Padding),
                            )
                        }
                    },
                )
            }
        }
    }
}

// The possible states of the `ShadeBody`.
sealed interface ShadeBodyState {
    data object Editing : ShadeBodyState

    data object TileDetails : ShadeBodyState

    data object Default : ShadeBodyState
}

@Composable
fun ContentScope.ShadeBody(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    val isEditing by viewModel.editModeViewModel.isEditing.collectAsStateWithLifecycle()
    val tileDetails =
        if (QsDetailedView.isEnabled) viewModel.detailsViewModel.activeTileDetails else null

    AnimatedContent(
        targetState =
            when {
                isEditing -> ShadeBodyState.Editing
                tileDetails != null -> ShadeBodyState.TileDetails
                else -> ShadeBodyState.Default
            },
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
    ) { state ->
        when (state) {
            ShadeBodyState.Editing -> {
                EditMode(
                    viewModel = viewModel.editModeViewModel,
                    modifier =
                        modifier.fillMaxWidth().padding(QuickSettingsShade.Dimensions.Padding),
                )
            }

            ShadeBodyState.TileDetails -> {
                TileDetails(modifier = modifier, viewModel.detailsViewModel)
            }

            else -> {
                QuickSettingsLayout(
                    viewModel = viewModel,
                    modifier = modifier.sysuiResTag("quick_settings_panel"),
                    header = header,
                )
            }
        }
    }
}

/** Column containing Brightness and QS tiles. */
@Composable
fun ContentScope.QuickSettingsLayout(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(QuickSettingsShade.Dimensions.Padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier.padding(
                start = QuickSettingsShade.Dimensions.Padding,
                end = QuickSettingsShade.Dimensions.Padding,
                bottom = QuickSettingsShade.Dimensions.Padding,
            ),
    ) {
        header()
        Toolbar(
            modifier =
                Modifier.fillMaxWidth().requiredHeight(QuickSettingsShade.Dimensions.ToolbarHeight),
            toolbarViewModelFactory = viewModel.toolbarViewModelFactory,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(QuickSettingsShade.Dimensions.Padding),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            BrightnessSliderContainer(
                viewModel = viewModel.brightnessSliderViewModel,
                containerColor = Color.Transparent,
                modifier =
                    Modifier.fillMaxWidth()
                        .height(QuickSettingsShade.Dimensions.BrightnessSliderHeight),
            )
            Box {
                GridAnchor()
                TileGrid(
                    viewModel = viewModel.tileGridViewModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

object QuickSettingsShade {

    object Dimensions {
        val Padding = 16.dp
        val ToolbarHeight = 48.dp
        val BrightnessSliderHeight = 64.dp
    }
}
