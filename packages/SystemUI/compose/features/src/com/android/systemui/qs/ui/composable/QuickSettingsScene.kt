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

package com.android.systemui.qs.ui.composable

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.footer.ui.compose.QuickSettings
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneViewModel
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager
import com.android.systemui.statusbar.phone.StatusBarLocation
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Quick Settings (AKA "QS") scene shows the quick setting tiles. */
@SysUISingleton
class QuickSettingsScene
@Inject
constructor(
    private val viewModel: QuickSettingsSceneViewModel,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
) : ComposableScene {
    override val key = SceneKey.QuickSettings

    override fun destinationScenes(): StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
                mapOf(
                    UserAction.Swipe(Direction.UP) to SceneModel(SceneKey.Shade),
                )
            )
            .asStateFlow()

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        QuickSettingsScene(
            viewModel = viewModel,
            createTintedIconManager = tintedIconManagerFactory::create,
            createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
            statusBarIconController = statusBarIconController,
            modifier = modifier,
        )
    }
}

@Composable
private fun SceneScope.QuickSettingsScene(
    viewModel: QuickSettingsSceneViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    modifier: Modifier = Modifier,
) {
    // TODO(b/280887232): implement the real UI.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .clickable(onClick = { viewModel.onContentClicked() })
                .padding(start = 16.dp, end = 16.dp, bottom = 48.dp)
    ) {
        ExpandedShadeHeader(
            viewModel = viewModel.shadeHeaderViewModel,
            createTintedIconManager = createTintedIconManager,
            createBatteryMeterViewController = createBatteryMeterViewController,
            statusBarIconController = statusBarIconController,
        )
        Spacer(modifier = Modifier.height(16.dp))
        QuickSettings()
    }
}
