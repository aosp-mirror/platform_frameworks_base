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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexScenePicker
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.TransitionState
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateElementFloatAsState
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.settingslib.Utils
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.LocalScreenCornerRadius
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.composable.ShadeHeader.Dimensions.CollapsedHeight
import com.android.systemui.shade.ui.composable.ShadeHeader.Values.ClockScale
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.policy.Clock
import kotlin.math.max

object ShadeHeader {
    object Elements {
        val ExpandedContent = ElementKey("ShadeHeaderExpandedContent")
        val CollapsedContentStart = ElementKey("ShadeHeaderCollapsedContentStart")
        val CollapsedContentEnd = ElementKey("ShadeHeaderCollapsedContentEnd")
        val PrivacyChip = ElementKey("PrivacyChip", scenePicker = LowestZIndexScenePicker)
        val Clock = ElementKey("ShadeHeaderClock", scenePicker = LowestZIndexScenePicker)
        val ShadeCarrierGroup = ElementKey("ShadeCarrierGroup")
    }

    object Values {
        val ClockScale = ValueKey("ShadeHeaderClockScale")
    }

    object Dimensions {
        val CollapsedHeight = 48.dp
        val ExpandedHeight = 120.dp
    }

    object Colors {
        val ColorScheme.shadeHeaderText: Color
            get() = Color.White
    }

    object TestTags {
        const val Root = "shade_header_root"
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
    val isDisabled by viewModel.isDisabled.collectAsStateWithLifecycle()
    if (isDisabled) {
        return
    }

    val cutoutWidth = LocalDisplayCutout.current.width()
    val cutoutHeight = LocalDisplayCutout.current.height()
    val cutoutTop = LocalDisplayCutout.current.top
    val cutoutLocation = LocalDisplayCutout.current.location
    val horizontalPadding =
        max(LocalScreenCornerRadius.current / 2f, Shade.Dimensions.HorizontalPadding)

    val useExpandedFormat by
        remember(cutoutLocation) {
            derivedStateOf {
                cutoutLocation != CutoutLocation.CENTER ||
                    shouldUseExpandedFormat(layoutState.transitionState)
            }
        }

    val isPrivacyChipVisible by viewModel.isPrivacyChipVisible.collectAsStateWithLifecycle()

    // This layout assumes it is globally positioned at (0, 0) and is the
    // same size as the screen.
    Layout(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        contents =
            listOf(
                {
                    Row(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                        Clock(
                            scale = 1f,
                            viewModel = viewModel,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        VariableDayDate(
                            viewModel = viewModel,
                            modifier =
                                Modifier.element(ShadeHeader.Elements.CollapsedContentStart)
                                    .align(Alignment.CenterVertically),
                        )
                    }
                },
                {
                    if (isPrivacyChipVisible) {
                        Box(
                            modifier =
                                Modifier.height(CollapsedHeight)
                                    .fillMaxWidth()
                                    .padding(horizontal = horizontalPadding)
                        ) {
                            PrivacyChip(
                                viewModel = viewModel,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier =
                                Modifier.element(ShadeHeader.Elements.CollapsedContentEnd)
                                    .padding(horizontal = horizontalPadding)
                        ) {
                            SystemIconContainer(
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
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
                                    createBatteryMeterViewController =
                                        createBatteryMeterViewController,
                                    useExpandedFormat = useExpandedFormat,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
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
        val height = max(cutoutHeight + (cutoutTop * 2), CollapsedHeight).roundToPx()
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
    val isDisabled by viewModel.isDisabled.collectAsStateWithLifecycle()
    if (isDisabled) {
        return
    }

    val useExpandedFormat by remember {
        derivedStateOf { shouldUseExpandedFormat(layoutState.transitionState) }
    }

    val isPrivacyChipVisible by viewModel.isPrivacyChipVisible.collectAsStateWithLifecycle()

    Box(modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root)) {
        if (isPrivacyChipVisible) {
            Box(modifier = Modifier.height(CollapsedHeight).fillMaxWidth()) {
                PrivacyChip(
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier =
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = ShadeHeader.Dimensions.ExpandedHeight)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box {
                    Clock(
                        scale = 2.57f,
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                Box(
                    modifier =
                        Modifier.element(ShadeHeader.Elements.ShadeCarrierGroup).fillMaxWidth()
                ) {
                    ShadeCarrierGroup(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            Spacer(modifier = Modifier.width(5.dp))
            Row(modifier = Modifier.element(ShadeHeader.Elements.ExpandedContent)) {
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
}

@Composable
private fun SceneScope.Clock(
    scale: Float,
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current

    Element(key = ShadeHeader.Elements.Clock, modifier = modifier) {
        val animatedScale by animateElementFloatAsState(scale, ClockScale, canOverflow = false)
        AndroidView(
            factory = { context ->
                Clock(
                    ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings_Header),
                    null,
                )
            },
            modifier =
                modifier
                    // use graphicsLayer instead of Modifier.scale to anchor transform
                    // to the (start, top) corner
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        transformOrigin =
                            TransformOrigin(
                                when (layoutDirection) {
                                    LayoutDirection.Ltr -> 0f
                                    LayoutDirection.Rtl -> 1f
                                },
                                0.5f
                            )
                    }
                    .clickable { viewModel.onClockClicked() }
        )
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

            val themedContext =
                ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings_Header)
            val fg = Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimary)
            val bg =
                Utils.getColorAttrDefaultColor(
                    themedContext,
                    android.R.attr.textColorPrimaryInverse,
                )

            // [BatteryMeterView.updateColors] is an old method that was built to distinguish
            // between dual-tone colors and single-tone. The current icon is only single-tone, so
            // the final [fg] is the only one we actually need
            batteryIcon.updateColors(fg, bg, fg)

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
        val subIds by viewModel.mobileSubIds.collectAsStateWithLifecycle()

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
                        .also { it.setOnClickListener { viewModel.onShadeCarrierGroupClicked() } }
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
    val cameraSlot = stringResource(id = com.android.internal.R.string.status_bar_camera)
    val micSlot = stringResource(id = com.android.internal.R.string.status_bar_microphone)
    val locationSlot = stringResource(id = com.android.internal.R.string.status_bar_location)

    val isSingleCarrier by viewModel.isSingleCarrier.collectAsStateWithLifecycle()
    val isPrivacyChipEnabled by viewModel.isPrivacyChipEnabled.collectAsStateWithLifecycle()
    val isMicCameraIndicationEnabled by
        viewModel.isMicCameraIndicationEnabled.collectAsStateWithLifecycle()
    val isLocationIndicationEnabled by
        viewModel.isLocationIndicationEnabled.collectAsStateWithLifecycle()

    AndroidView(
        factory = { context ->
            val themedContext =
                ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings_Header)
            val iconContainer = StatusIconContainer(themedContext, null)
            val iconManager = createTintedIconManager(iconContainer, StatusBarLocation.QS)
            iconManager.setTint(
                Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimary),
                Utils.getColorAttrDefaultColor(
                    themedContext,
                    android.R.attr.textColorPrimaryInverse
                ),
            )
            statusBarIconController.addIconGroup(iconManager)

            iconContainer
        },
        update = { iconContainer ->
            iconContainer.setQsExpansionTransitioning(
                layoutState.isTransitioningBetween(Scenes.Shade, Scenes.QuickSettings)
            )
            if (isSingleCarrier || !useExpandedFormat) {
                iconContainer.removeIgnoredSlots(carrierIconSlots)
            } else {
                iconContainer.addIgnoredSlots(carrierIconSlots)
            }

            if (isPrivacyChipEnabled) {
                if (isMicCameraIndicationEnabled) {
                    iconContainer.addIgnoredSlot(cameraSlot)
                    iconContainer.addIgnoredSlot(micSlot)
                } else {
                    iconContainer.removeIgnoredSlot(cameraSlot)
                    iconContainer.removeIgnoredSlot(micSlot)
                }
                if (isLocationIndicationEnabled) {
                    iconContainer.addIgnoredSlot(locationSlot)
                } else {
                    iconContainer.removeIgnoredSlot(locationSlot)
                }
            } else {
                iconContainer.removeIgnoredSlot(cameraSlot)
                iconContainer.removeIgnoredSlot(micSlot)
                iconContainer.removeIgnoredSlot(locationSlot)
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
        modifier = modifier.height(CollapsedHeight),
        content = content,
    )
}

@Composable
private fun SceneScope.PrivacyChip(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    val privacyList by viewModel.privacyItems.collectAsStateWithLifecycle()

    AndroidView(
        factory = { context ->
            val view =
                OngoingPrivacyChip(context, null).also { privacyChip ->
                    privacyChip.privacyList = privacyList
                    privacyChip.setOnClickListener { viewModel.onPrivacyChipClicked(privacyChip) }
                }
            view
        },
        update = { it.privacyList = privacyList },
        modifier = modifier.element(ShadeHeader.Elements.PrivacyChip),
    )
}

private fun shouldUseExpandedFormat(state: TransitionState): Boolean {
    return when (state) {
        is TransitionState.Idle -> {
            state.currentScene == Scenes.QuickSettings
        }
        is TransitionState.Transition -> {
            ((state.isTransitioning(Scenes.Shade, Scenes.QuickSettings) ||
                state.isTransitioning(Scenes.Gone, Scenes.QuickSettings) ||
                state.isTransitioning(Scenes.Lockscreen, Scenes.QuickSettings)) &&
                state.progress >= 0.5) ||
                ((state.isTransitioning(Scenes.QuickSettings, Scenes.Shade) ||
                    state.isTransitioning(Scenes.QuickSettings, Scenes.Gone) ||
                    state.isTransitioning(Scenes.QuickSettings, Scenes.Lockscreen)) &&
                    state.progress <= 0.5)
        }
    }
}
