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
 *
 */

package com.android.systemui.shade.ui.composable

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateSceneFloatAsState
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.settingslib.Utils
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.res.R
import com.android.systemui.scene.ui.composable.QuickSettings
import com.android.systemui.scene.ui.composable.Shade as ShadeKey
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.policy.Clock

object ShadeHeader {
    object Elements {
        val ExpandedContent = ElementKey("ShadeHeaderExpandedContent")
        val CollapsedContent = ElementKey("ShadeHeaderCollapsedContent")
    }

    object Keys {
        val transitionProgress = ValueKey("ShadeHeaderTransitionProgress")
    }

    object Dimensions {
        val CollapsedHeight = 48.dp
        val ExpandedHeight = 120.dp
    }
}

@Composable
fun SceneScope.CollapsedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    modifier: Modifier = Modifier,
) {
    val formatProgress =
        animateSceneFloatAsState(0f, ShadeHeader.Keys.transitionProgress)
            .unsafeCompositionState(initialValue = 0f)

    val cutoutWidth = LocalDisplayCutout.current.width()
    val cutoutLocation = LocalDisplayCutout.current.location

    val useExpandedFormat by
        remember(formatProgress) {
            derivedStateOf {
                cutoutLocation != CutoutLocation.CENTER || formatProgress.value > 0.5f
            }
        }

    // This layout assumes it is globally positioned at (0, 0) and is the
    // same size as the screen.
    Layout(
        modifier = modifier.element(ShadeHeader.Elements.CollapsedContent),
        contents =
            listOf(
                {
                    Row {
                        AndroidView(
                            factory = { context ->
                                Clock(
                                    ContextThemeWrapper(context, R.style.TextAppearance_QS_Status),
                                    null
                                )
                            },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        VariableDayDate(
                            viewModel = viewModel,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                },
                {
                    Row(horizontalArrangement = Arrangement.End) {
                        SystemIconContainer {
                            when (LocalWindowSizeClass.current.widthSizeClass) {
                                WindowWidthSizeClass.Medium,
                                WindowWidthSizeClass.Expanded ->
                                    ShadeCarrierGroup(
                                        viewModel = viewModel,
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    )
                            }
                            StatusIcons(
                                viewModel = viewModel,
                                createTintedIconManager = createTintedIconManager,
                                statusBarIconController = statusBarIconController,
                                useExpandedFormat = useExpandedFormat,
                                modifier =
                                    Modifier.align(Alignment.CenterVertically)
                                        .padding(end = 6.dp)
                                        .weight(1f, fill = false)
                            )
                            BatteryIcon(
                                createBatteryMeterViewController = createBatteryMeterViewController,
                                useExpandedFormat = useExpandedFormat,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                },
            ),
    ) { measurables, constraints ->
        check(constraints.hasBoundedWidth)
        check(measurables.size == 2)
        check(measurables[0].size == 1)
        check(measurables[1].size == 1)

        val screenWidth = constraints.maxWidth
        val cutoutWidthPx = cutoutWidth.roundToPx()
        val height = ShadeHeader.Dimensions.CollapsedHeight.roundToPx()
        val childConstraints = Constraints.fixed((screenWidth - cutoutWidthPx) / 2, height)

        val startMeasurable = measurables[0][0]
        val endMeasurable = measurables[1][0]

        val startPlaceable = startMeasurable.measure(childConstraints)
        val endPlaceable = endMeasurable.measure(childConstraints)

        layout(screenWidth, height) {
            when (cutoutLocation) {
                CutoutLocation.NONE,
                CutoutLocation.RIGHT -> {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(
                        x = startPlaceable.width,
                        y = 0,
                    )
                }
                CutoutLocation.CENTER -> {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(
                        x = startPlaceable.width + cutoutWidthPx,
                        y = 0,
                    )
                }
                CutoutLocation.LEFT -> {
                    startPlaceable.placeRelative(
                        x = cutoutWidthPx,
                        y = 0,
                    )
                    endPlaceable.placeRelative(
                        x = startPlaceable.width + cutoutWidthPx,
                        y = 0,
                    )
                }
            }
        }
    }
}

@Composable
fun SceneScope.ExpandedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    modifier: Modifier = Modifier,
) {
    val formatProgress =
        animateSceneFloatAsState(1f, ShadeHeader.Keys.transitionProgress)
            .unsafeCompositionState(initialValue = 1f)
    val useExpandedFormat by
        remember(formatProgress) { derivedStateOf { formatProgress.value > 0.5f } }

    Column(
        verticalArrangement = Arrangement.Bottom,
        modifier =
            modifier
                .element(ShadeHeader.Elements.ExpandedContent)
                .fillMaxWidth()
                .defaultMinSize(minHeight = ShadeHeader.Dimensions.ExpandedHeight)
    ) {
        Row {
            AndroidView(
                factory = { context ->
                    Clock(ContextThemeWrapper(context, R.style.TextAppearance_QS_Status), null)
                },
                modifier =
                    Modifier.align(Alignment.CenterVertically)
                        // use graphicsLayer instead of Modifier.scale to anchor transform to
                        // the (start, top) corner
                        .graphicsLayer(
                            scaleX = 2.57f,
                            scaleY = 2.57f,
                            transformOrigin =
                                TransformOrigin(
                                    when (LocalLayoutDirection.current) {
                                        LayoutDirection.Ltr -> 0f
                                        LayoutDirection.Rtl -> 1f
                                    },
                                    0.5f
                                )
                        ),
            )
            Spacer(modifier = Modifier.weight(1f))
            ShadeCarrierGroup(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Row {
            VariableDayDate(
                viewModel = viewModel,
                modifier = Modifier.widthIn(max = 90.dp).align(Alignment.CenterVertically),
            )
            Spacer(modifier = Modifier.weight(1f))
            SystemIconContainer {
                StatusIcons(
                    viewModel = viewModel,
                    createTintedIconManager = createTintedIconManager,
                    statusBarIconController = statusBarIconController,
                    useExpandedFormat = useExpandedFormat,
                    modifier =
                        Modifier.align(Alignment.CenterVertically)
                            .padding(end = 6.dp)
                            .weight(1f, fill = false),
                )
                BatteryIcon(
                    useExpandedFormat = useExpandedFormat,
                    createBatteryMeterViewController = createBatteryMeterViewController,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun BatteryIcon(
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            val batteryIcon = BatteryMeterView(context, null)
            batteryIcon.setPercentShowMode(BatteryMeterView.MODE_ON)

            val batteryMaterViewController =
                createBatteryMeterViewController(batteryIcon, StatusBarLocation.QS)
            batteryMaterViewController.init()
            batteryMaterViewController.ignoreTunerUpdates()

            batteryIcon
        },
        update = { batteryIcon ->
            // TODO(b/298525212): use MODE_ESTIMATE in collapsed view when the screen
            //  has no center cutout. See [QsBatteryModeController.getBatteryMode]
            batteryIcon.setPercentShowMode(
                if (useExpandedFormat) {
                    BatteryMeterView.MODE_ESTIMATE
                } else {
                    BatteryMeterView.MODE_ON
                }
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun ShadeCarrierGroup(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        val subIds by viewModel.mobileSubIds.collectAsState()

        for (subId in subIds) {
            Spacer(modifier = Modifier.width(5.dp))
            AndroidView(
                factory = { context ->
                    ModernShadeCarrierGroupMobileView.constructAndBind(
                        context = context,
                        logger = viewModel.mobileIconsViewModel.logger,
                        slot = "mobile_carrier_shade_group",
                        viewModel =
                            (viewModel.mobileIconsViewModel.viewModelForSub(
                                subId,
                                StatusBarLocation.SHADE_CARRIER_GROUP
                            ) as ShadeCarrierGroupMobileIconViewModel),
                    )
                },
            )
        }
    }
}

@Composable
private fun SceneScope.StatusIcons(
    viewModel: ShadeHeaderViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    statusBarIconController: StatusBarIconController,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
) {
    val carrierIconSlots =
        listOf(stringResource(id = com.android.internal.R.string.status_bar_mobile))
    val isSingleCarrier by viewModel.isSingleCarrier.collectAsState()

    AndroidView(
        factory = { context ->
            val iconContainer = StatusIconContainer(context, null)
            val iconManager = createTintedIconManager(iconContainer, StatusBarLocation.QS)
            iconManager.setTint(
                Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary),
                Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimaryInverse),
            )
            statusBarIconController.addIconGroup(iconManager)

            iconContainer
        },
        update = { iconContainer ->
            iconContainer.setQsExpansionTransitioning(
                layoutState.isTransitioningBetween(ShadeKey, QuickSettings)
            )
            if (isSingleCarrier || !useExpandedFormat) {
                iconContainer.removeIgnoredSlots(carrierIconSlots)
            } else {
                iconContainer.addIgnoredSlots(carrierIconSlots)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun SystemIconContainer(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    // TODO(b/298524053): add hover state for this container
    Row(
        modifier = modifier.height(ShadeHeader.Dimensions.CollapsedHeight),
        content = content,
    )
}
