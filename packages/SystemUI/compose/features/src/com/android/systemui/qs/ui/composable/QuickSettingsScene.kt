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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.notifications.ui.composable.HeadsUpNotificationSpace
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneViewModel
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.composable.CollapsedShadeHeader
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.Shade
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager
import com.android.systemui.statusbar.phone.StatusBarLocation
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** The Quick Settings (AKA "QS") scene shows the quick setting tiles. */
@SysUISingleton
class QuickSettingsScene
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: QuickSettingsSceneViewModel,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
) : ComposableScene {
    override val key = SceneKey.QuickSettings

    override val destinationScenes =
        viewModel.destinationScenes.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = emptyMap(),
        )

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
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            val isCustomizing by viewModel.qsSceneAdapter.isCustomizing.collectAsState()
            val collapsedHeaderHeight =
                with(LocalDensity.current) { ShadeHeader.Dimensions.CollapsedHeight.roundToPx() }
            Spacer(
                modifier =
                    Modifier.element(Shade.Elements.ScrimBackground)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim, shape = Shade.Shapes.Scrim)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 48.dp)
            ) {
                when (LocalWindowSizeClass.current.widthSizeClass) {
                    WindowWidthSizeClass.Compact ->
                        AnimatedVisibility(
                            visible = !isCustomizing,
                            enter =
                                expandVertically(
                                    animationSpec = tween(1000),
                                    initialHeight = { collapsedHeaderHeight },
                                ) + fadeIn(tween(1000)),
                            exit =
                                shrinkVertically(
                                    animationSpec = tween(1000),
                                    targetHeight = { collapsedHeaderHeight },
                                    shrinkTowards = Alignment.Top,
                                ) + fadeOut(tween(1000)),
                        ) {
                            ExpandedShadeHeader(
                                viewModel = viewModel.shadeHeaderViewModel,
                                createTintedIconManager = createTintedIconManager,
                                createBatteryMeterViewController = createBatteryMeterViewController,
                                statusBarIconController = statusBarIconController,
                            )
                        }
                    else ->
                        CollapsedShadeHeader(
                            viewModel = viewModel.shadeHeaderViewModel,
                            createTintedIconManager = createTintedIconManager,
                            createBatteryMeterViewController = createBatteryMeterViewController,
                            statusBarIconController = statusBarIconController,
                        )
                }
                Spacer(modifier = Modifier.height(16.dp))
                QuickSettings(
                    modifier = Modifier.fillMaxHeight(),
                    viewModel.qsSceneAdapter,
                )
            }
        }
        HeadsUpNotificationSpace(
            viewModel = viewModel.notifications,
            isPeekFromBottom = true,
            modifier = Modifier.padding(16.dp).fillMaxSize(),
        )
    }
}
