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

package com.android.systemui.shade.ui.composable

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager
import com.android.systemui.statusbar.phone.StatusBarLocation
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object Shade {
    object Elements {
        val QuickSettings = ElementKey("ShadeQuickSettings")
        val Scrim = ElementKey("ShadeScrim")
        val ScrimBackground = ElementKey("ShadeScrimBackground")
    }

    object Dimensions {
        val ScrimCornerSize = 32.dp
        val HorizontalPadding = 16.dp
    }

    object Shapes {
        val Scrim =
            RoundedCornerShape(
                topStart = Dimensions.ScrimCornerSize,
                topEnd = Dimensions.ScrimCornerSize,
            )
    }
}

/** The shade scene shows scrolling list of notifications and some of the quick setting tiles. */
@SysUISingleton
class ShadeScene
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: ShadeSceneViewModel,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
) : ComposableScene {
    override val key = SceneKey.Shade

    override val destinationScenes: StateFlow<Map<UserAction, SceneModel>> =
        viewModel.upDestinationSceneKey
            .map { sceneKey -> destinationScenes(up = sceneKey) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = destinationScenes(up = viewModel.upDestinationSceneKey.value),
            )

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) =
        ShadeScene(
            viewModel = viewModel,
            createTintedIconManager = tintedIconManagerFactory::create,
            createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
            statusBarIconController = statusBarIconController,
            modifier = modifier,
        )

    private fun destinationScenes(
        up: SceneKey,
    ): Map<UserAction, SceneModel> {
        return mapOf(
            UserAction.Swipe(Direction.UP) to SceneModel(up),
            UserAction.Swipe(Direction.DOWN) to SceneModel(SceneKey.QuickSettings),
        )
    }
}

@Composable
private fun SceneScope.ShadeScene(
    viewModel: ShadeSceneViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    modifier: Modifier = Modifier,
) {
    Box(modifier.element(Shade.Elements.Scrim)) {
        Spacer(
            modifier =
                Modifier.element(Shade.Elements.ScrimBackground)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim, shape = Shade.Shapes.Scrim)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.fillMaxSize()
                    .clickable(onClick = { viewModel.onContentClicked() })
                    .padding(
                        start = Shade.Dimensions.HorizontalPadding,
                        end = Shade.Dimensions.HorizontalPadding,
                        bottom = 48.dp
                    )
        ) {
            CollapsedShadeHeader(
                viewModel = viewModel.shadeHeaderViewModel,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
            )
            Spacer(modifier = Modifier.height(16.dp))
            QuickSettings(
                modifier = Modifier.wrapContentHeight(),
                viewModel.qsSceneAdapter,
                QSSceneAdapter.State.QQS
            )
            Spacer(modifier = Modifier.height(16.dp))
            Notifications(modifier = Modifier.weight(1f))
        }
    }
}
