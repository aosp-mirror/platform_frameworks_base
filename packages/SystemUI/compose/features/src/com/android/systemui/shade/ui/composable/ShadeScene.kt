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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexScenePicker
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.ui.MediaCarouselController
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.media.controls.ui.MediaHostState
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL
import com.android.systemui.notifications.ui.composable.NotificationScrollingStack
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.util.animation.MeasurementInput
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object Shade {
    object Elements {
        val QuickSettings = ElementKey("ShadeQuickSettings")
        val MediaCarousel = ElementKey("ShadeMediaCarousel")
        val BackgroundScrim =
            ElementKey("ShadeBackgroundScrim", scenePicker = LowestZIndexScenePicker)
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
    private val mediaCarouselController: MediaCarouselController,
    @Named(QUICK_QS_PANEL) private val mediaHost: MediaHost,
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
            mediaCarouselController = mediaCarouselController,
            mediaHost = mediaHost,
            modifier = modifier,
        )

    init {
        mediaHost.expansion = MediaHostState.EXPANDED
        mediaHost.showsOnlyActiveMedia = true
        mediaHost.init(MediaHierarchyManager.LOCATION_QQS)
    }

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
    mediaCarouselController: MediaCarouselController,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutWidth = remember { mutableStateOf(0) }
    val maxNotifScrimTop = remember { mutableStateOf(0f) }

    Box(
        modifier =
            modifier
                .element(Shade.Elements.BackgroundScrim)
                .background(MaterialTheme.colorScheme.scrim),
    )
    Box {
        Layout(
            contents =
                listOf(
                    {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable(onClick = { viewModel.onContentClicked() })
                        ) {
                            CollapsedShadeHeader(
                                viewModel = viewModel.shadeHeaderViewModel,
                                createTintedIconManager = createTintedIconManager,
                                createBatteryMeterViewController = createBatteryMeterViewController,
                                statusBarIconController = statusBarIconController,
                                modifier =
                                    Modifier.padding(
                                        horizontal = Shade.Dimensions.HorizontalPadding
                                    )
                            )
                            QuickSettings(
                                viewModel.qsSceneAdapter,
                                { viewModel.qsSceneAdapter.qqsHeight },
                            )

                            if (viewModel.isMediaVisible()) {
                                val mediaHeight =
                                    dimensionResource(R.dimen.qs_media_session_height_expanded)
                                MediaCarousel(
                                    modifier =
                                        Modifier.height(mediaHeight).fillMaxWidth().layout {
                                            measurable,
                                            constraints ->
                                            val placeable = measurable.measure(constraints)

                                            // Notify controller to size the carousel for the
                                            // current space
                                            mediaHost.measurementInput =
                                                MeasurementInput(placeable.width, placeable.height)
                                            mediaCarouselController.setSceneContainerSize(
                                                placeable.width,
                                                placeable.height
                                            )

                                            layout(placeable.width, placeable.height) {
                                                placeable.placeRelative(0, 0)
                                            }
                                        },
                                    mediaHost = mediaHost,
                                    layoutWidth = layoutWidth.value,
                                    layoutHeight = with(density) { mediaHeight.toPx() }.toInt(),
                                    carouselController = mediaCarouselController,
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    },
                    {
                        NotificationScrollingStack(
                            viewModel = viewModel.notifications,
                            maxScrimTop = { maxNotifScrimTop.value },
                        )
                    },
                )
        ) { measurables, constraints ->
            check(measurables.size == 2)
            check(measurables[0].size == 1)
            check(measurables[1].size == 1)

            val quickSettingsPlaceable = measurables[0][0].measure(constraints)
            val notificationsPlaceable = measurables[1][0].measure(constraints)

            maxNotifScrimTop.value = quickSettingsPlaceable.height.toFloat()

            layout(constraints.maxWidth, constraints.maxHeight) {
                quickSettingsPlaceable.placeRelative(x = 0, y = 0)
                notificationsPlaceable.placeRelative(x = 0, y = maxNotifScrimTop.value.roundToInt())
            }
        }
    }
}
