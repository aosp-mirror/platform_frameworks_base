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
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.composable.SnoozeableHeadsUpNotificationSpace
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.TileDetails
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.panels.ui.compose.toolbar.Toolbar
import com.android.systemui.qs.ui.composable.QuickSettingsShade.Dimensions.GridMaxHeight
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeOverlayActionsViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeOverlayContentViewModel
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QuickSettingsShadeOverlay
@Inject
constructor(
    private val actionsViewModelFactory: QuickSettingsShadeOverlayActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsShadeOverlayContentViewModel.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
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
        val viewModel =
            rememberViewModel("QuickSettingsShadeOverlay") { contentViewModelFactory.create() }

        OverlayShade(modifier = modifier, onScrimClicked = viewModel::onScrimClicked) {
            Column {
                ExpandedShadeHeader(
                    viewModelFactory = viewModel.shadeHeaderViewModelFactory,
                    createTintedIconManager = tintedIconManagerFactory::create,
                    createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
                    statusBarIconController = statusBarIconController,
                    modifier = Modifier.padding(QuickSettingsShade.Dimensions.Padding),
                )

                ShadeBody(viewModel = viewModel.quickSettingsContainerViewModel)
            }

            SnoozeableHeadsUpNotificationSpace(
                stackScrollView = notificationStackScrollView.get(),
                viewModel =
                    rememberViewModel("QuickSettingsShadeOverlay") {
                        notificationsPlaceholderViewModelFactory.create()
                    },
            )
        }
    }
}

// A sealed interface to represent the possible states of the `ShadeBody`
sealed interface ShadeBodyState {
    data object Editing : ShadeBodyState

    data object TileDetails : ShadeBodyState

    data object Default : ShadeBodyState
}

// Function to map the current state of the `ShadeBody`
fun checkQsState(isEditing: Boolean, tileDetails: TileDetailsViewModel?): ShadeBodyState {
    if (isEditing) {
        return ShadeBodyState.Editing
    } else if (tileDetails != null && QsDetailedView.isEnabled) {
        return ShadeBodyState.TileDetails
    }
    return ShadeBodyState.Default
}

@Composable
fun SceneScope.ShadeBody(viewModel: QuickSettingsContainerViewModel) {
    val isEditing by viewModel.editModeViewModel.isEditing.collectAsStateWithLifecycle()
    val tileDetails = viewModel.detailsViewModel.activeTileDetails

    AnimatedContent(
        targetState = checkQsState(isEditing, tileDetails),
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
    ) { state ->
        when (state) {
            ShadeBodyState.Editing -> {
                EditMode(
                    viewModel = viewModel.editModeViewModel,
                    modifier =
                        Modifier.fillMaxWidth().padding(QuickSettingsShade.Dimensions.Padding),
                )
            }
            ShadeBodyState.TileDetails -> {
                TileDetails(viewModel.detailsViewModel)
            }
            else -> {
                QuickSettingsLayout(
                    viewModel = viewModel,
                    modifier = Modifier.sysuiResTag("quick_settings_panel"),
                )
            }
        }
    }
}

/** Column containing Brightness and QS tiles. */
@Composable
fun SceneScope.QuickSettingsLayout(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(QuickSettingsShade.Dimensions.Padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = QuickSettingsShade.Dimensions.Padding,
                    end = QuickSettingsShade.Dimensions.Padding,
                    bottom = QuickSettingsShade.Dimensions.Padding / 2,
                ),
    ) {
        Toolbar(viewModel.toolbarViewModelFactory)
        BrightnessSliderContainer(
            viewModel = viewModel.brightnessSliderViewModel,
            modifier =
                Modifier.fillMaxWidth().height(QuickSettingsShade.Dimensions.BrightnessSliderHeight),
        )
        Box(
            modifier =
                Modifier.requiredHeightIn(max = GridMaxHeight)
                    .verticalNestedScrollToScene()
                    .verticalScroll(rememberScrollState())
        ) {
            GridAnchor()
            TileGrid(viewModel = viewModel.tileGridViewModel, modifier = Modifier.fillMaxWidth())
        }
    }
}

object QuickSettingsShade {

    object Dimensions {
        val Padding = 16.dp
        val BrightnessSliderHeight = 64.dp
        val GridMaxHeight = 420.dp
    }
}
