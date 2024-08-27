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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.composable.LockscreenContent
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.ui.composable.QuickSettingsShade.Transitions.QuickSettingsLayoutEnter
import com.android.systemui.qs.ui.composable.QuickSettingsShade.Transitions.QuickSettingsLayoutExit
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeSceneActionsViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeSceneContentViewModel
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QuickSettingsShadeScene
@Inject
constructor(
    private val actionsViewModelFactory: QuickSettingsShadeSceneActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsShadeSceneContentViewModel.Factory,
    private val lockscreenContent: Lazy<Optional<LockscreenContent>>,
    private val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
) : ExclusiveActivatable(), ComposableScene {

    override val key = Scenes.QuickSettingsShade

    private val actionsViewModel: QuickSettingsShadeSceneActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val destinationScenes: Flow<Map<UserAction, UserActionResult>> =
        actionsViewModel.actions

    override suspend fun onActivated(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        val viewModel = rememberViewModel { contentViewModelFactory.create() }
        OverlayShade(
            viewModelFactory = viewModel.overlayShadeViewModelFactory,
            lockscreenContent = lockscreenContent,
            modifier = modifier,
        ) {
            Column {
                ExpandedShadeHeader(
                    viewModelFactory = shadeHeaderViewModelFactory,
                    createTintedIconManager = tintedIconManagerFactory::create,
                    createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
                    statusBarIconController = statusBarIconController,
                    modifier = Modifier.padding(QuickSettingsShade.Dimensions.Padding),
                )

                ShadeBody(
                    viewModel = viewModel.quickSettingsContainerViewModel,
                )
            }
        }
    }
}

@Composable
fun ShadeBody(
    viewModel: QuickSettingsContainerViewModel,
) {
    val isEditing by viewModel.editModeViewModel.isEditing.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = isEditing,
        transitionSpec = { QuickSettingsLayoutEnter togetherWith QuickSettingsLayoutExit }
    ) { editing ->
        if (editing) {
            EditMode(
                viewModel = viewModel.editModeViewModel,
                modifier = Modifier.fillMaxWidth().padding(QuickSettingsShade.Dimensions.Padding)
            )
        } else {
            QuickSettingsLayout(
                viewModel = viewModel,
                modifier = Modifier.sysuiResTag("quick_settings_panel")
            )
        }
    }
}

@Composable
private fun QuickSettingsLayout(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(QuickSettingsShade.Dimensions.Padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(QuickSettingsShade.Dimensions.Padding),
    ) {
        BrightnessSliderContainer(
            viewModel = viewModel.brightnessSliderViewModel,
            modifier =
                Modifier.fillMaxWidth()
                    .height(QuickSettingsShade.Dimensions.BrightnessSliderHeight),
        )
        TileGrid(
            viewModel = viewModel.tileGridViewModel,
            modifier =
                Modifier.fillMaxWidth().heightIn(max = QuickSettingsShade.Dimensions.GridMaxHeight),
            viewModel.editModeViewModel::startEditing,
        )
    }
}

object QuickSettingsShade {

    object Dimensions {
        val Padding = 16.dp
        val BrightnessSliderHeight = 64.dp
        val GridMaxHeight = 800.dp
    }

    object Transitions {
        val QuickSettingsLayoutEnter: EnterTransition = fadeIn(tween(500))
        val QuickSettingsLayoutExit: ExitTransition = fadeOut(tween(500))
        val QuickSettingsEditorEnter: EnterTransition = fadeIn(tween(500))
        val QuickSettingsEditorExit: ExitTransition = fadeOut(tween(500))
    }
}
