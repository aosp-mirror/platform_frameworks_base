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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeSceneContentViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeUserActionsViewModel
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QuickSettingsShadeScene
@Inject
constructor(
    private val actionsViewModelFactory: QuickSettingsShadeUserActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsShadeSceneContentViewModel.Factory,
    private val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
) : ExclusiveActivatable(), Scene {

    override val key = Scenes.QuickSettingsShade

    private val actionsViewModel: QuickSettingsShadeUserActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override suspend fun onActivated(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        val viewModel =
            rememberViewModel("QuickSettingsShadeScene") { contentViewModelFactory.create() }

        OverlayShade(
            modifier = modifier,
            onScrimClicked = {},
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
