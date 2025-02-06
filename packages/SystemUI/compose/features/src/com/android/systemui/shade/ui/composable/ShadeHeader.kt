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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexContentPicker
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateElementFloatAsState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.thenIf
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
import com.android.systemui.shade.ui.composable.ShadeHeader.Colors.chipBackground
import com.android.systemui.shade.ui.composable.ShadeHeader.Colors.chipHighlighted
import com.android.systemui.shade.ui.composable.ShadeHeader.Colors.onScrimDim
import com.android.systemui.shade.ui.composable.ShadeHeader.Dimensions.CollapsedHeight
import com.android.systemui.shade.ui.composable.ShadeHeader.Values.ClockScale
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel.HeaderChipHighlight
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.policy.Clock
import kotlinx.coroutines.launch

object ShadeHeader {
    object Elements {
        val ExpandedContent = ElementKey("ShadeHeaderExpandedContent")
        val CollapsedContentStart = ElementKey("ShadeHeaderCollapsedContentStart")
        val CollapsedContentEnd = ElementKey("ShadeHeaderCollapsedContentEnd")
        val PrivacyChip = ElementKey("PrivacyChip", contentPicker = LowestZIndexContentPicker)
        val Clock = ElementKey("ShadeHeaderClock", contentPicker = LowestZIndexContentPicker)
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

        val ColorScheme.onScrimDim: Color
            get() = Color.DarkGray

        val ColorScheme.chipBackground: Color
            get() = Color.DarkGray

        val ColorScheme.chipHighlighted: Color
            get() = Color.LightGray
    }

    object TestTags {
        const val Root = "shade_header_root"
    }
}

/** The status bar that appears above the Shade scene on small screens */
@Composable
fun ContentScope.CollapsedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    val cutoutLocation = LocalDisplayCutout.current.location
    val horizontalPadding =
        max(LocalScreenCornerRadius.current / 2f, Shade.Dimensions.HorizontalPadding)

    val useExpandedTextFormat by
        remember(cutoutLocation) {
            derivedStateOf {
                cutoutLocation != CutoutLocation.CENTER ||
                    shouldUseExpandedFormat(layoutState.transitionState)
            }
        }

    val longerDateText by viewModel.longerDateText.collectAsStateWithLifecycle()
    val shorterDateText by viewModel.shorterDateText.collectAsStateWithLifecycle()

    val isShadeLayoutWide = viewModel.isShadeLayoutWide

    val isPrivacyChipVisible by viewModel.isPrivacyChipVisible.collectAsStateWithLifecycle()

    // This layout assumes it is globally positioned at (0, 0) and is the same size as the screen.
    CutoutAwareShadeHeader(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        startContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = horizontalPadding),
            ) {
                Clock(scale = 1f, onClick = viewModel::onClockClicked)
                VariableDayDate(
                    longerDateText = longerDateText,
                    shorterDateText = shorterDateText,
                    chipHighlight = viewModel.notificationsChipHighlight,
                    modifier = Modifier.element(ShadeHeader.Elements.CollapsedContentStart),
                )
            }
        },
        endContent = {
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
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.element(ShadeHeader.Elements.CollapsedContentEnd)
                            .padding(horizontal = horizontalPadding),
                ) {
                    if (isShadeLayoutWide) {
                        ShadeCarrierGroup(viewModel = viewModel)
                    }
                    SystemIconChip(
                        onClick = viewModel::onSystemIconChipClicked.takeIf { isShadeLayoutWide }
                    ) {
                        StatusIcons(
                            viewModel = viewModel,
                            useExpandedFormat = useExpandedTextFormat,
                            modifier = Modifier.padding(end = 6.dp).weight(1f, fill = false),
                        )
                        BatteryIcon(
                            createBatteryMeterViewController =
                                viewModel.createBatteryMeterViewController,
                            useExpandedFormat = useExpandedTextFormat,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
    )
}

/** The status bar that appears above the Quick Settings scene on small screens */
@Composable
fun ContentScope.ExpandedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    val useExpandedFormat by remember {
        derivedStateOf { shouldUseExpandedFormat(layoutState.transitionState) }
    }

    val longerDateText by viewModel.longerDateText.collectAsStateWithLifecycle()
    val shorterDateText by viewModel.shorterDateText.collectAsStateWithLifecycle()
    val isPrivacyChipVisible by viewModel.isPrivacyChipVisible.collectAsStateWithLifecycle()

    Box(modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root)) {
        if (isPrivacyChipVisible) {
            Box(modifier = Modifier.height(CollapsedHeight).fillMaxWidth()) {
                PrivacyChip(viewModel = viewModel, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier =
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = ShadeHeader.Dimensions.ExpandedHeight),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box {
                    Clock(
                        scale = 2.57f,
                        onClick = viewModel::onClockClicked,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.element(ShadeHeader.Elements.ExpandedContent),
            ) {
                VariableDayDate(
                    longerDateText = longerDateText,
                    shorterDateText = shorterDateText,
                    chipHighlight = viewModel.notificationsChipHighlight,
                    modifier = Modifier.widthIn(max = 90.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                SystemIconChip {
                    StatusIcons(
                        viewModel = viewModel,
                        useExpandedFormat = useExpandedFormat,
                        modifier = Modifier.padding(end = 6.dp).weight(1f, fill = false),
                    )
                    BatteryIcon(
                        useExpandedFormat = useExpandedFormat,
                        createBatteryMeterViewController =
                            viewModel.createBatteryMeterViewController,
                    )
                }
            }
        }
    }
}

/**
 * The status bar that appears above both the Notifications and Quick Settings shade overlays when
 * overlay shade is enabled.
 */
@Composable
fun ContentScope.OverlayShadeHeader(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding =
        max(LocalScreenCornerRadius.current / 2f, Shade.Dimensions.HorizontalPadding)

    val isShadeLayoutWide = viewModel.isShadeLayoutWide

    val isPrivacyChipVisible by viewModel.isPrivacyChipVisible.collectAsStateWithLifecycle()

    // This layout assumes it is globally positioned at (0, 0) and is the same size as the screen.
    CutoutAwareShadeHeader(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        startContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            ) {
                if (isShadeLayoutWide) {
                    Clock(
                        scale = 1f,
                        onClick = viewModel::onClockClicked,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                val chipHighlight = viewModel.notificationsChipHighlight
                NotificationIconChip(
                    chipHighlight = chipHighlight,
                    onClick = viewModel::onNotificationIconChipClicked,
                ) {
                    if (isShadeLayoutWide) {
                        NotificationIcons(
                            chipHighlight = chipHighlight,
                            notificationIconContainerStatusBarViewBinder =
                                viewModel.notificationIconContainerStatusBarViewBinder,
                            modifier = Modifier.width(IntrinsicSize.Min).height(20.dp),
                        )
                    } else {
                        val longerDateText by viewModel.longerDateText.collectAsStateWithLifecycle()
                        val shorterDateText by
                            viewModel.shorterDateText.collectAsStateWithLifecycle()
                        VariableDayDate(
                            longerDateText = longerDateText,
                            shorterDateText = shorterDateText,
                            chipHighlight = viewModel.notificationsChipHighlight,
                        )
                    }
                }
            }
        },
        endContent = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            ) {
                val chipHighlight = viewModel.quickSettingsChipHighlight
                SystemIconChip(
                    chipHighlight = chipHighlight,
                    onClick = viewModel::onSystemIconChipClicked,
                ) {
                    StatusIcons(
                        viewModel = viewModel,
                        useExpandedFormat = false,
                        modifier = Modifier.padding(end = 6.dp).weight(1f, fill = false),
                        chipHighlight = chipHighlight,
                    )
                    BatteryIcon(
                        createBatteryMeterViewController =
                            viewModel.createBatteryMeterViewController,
                        useExpandedFormat = false,
                        chipHighlight = chipHighlight,
                    )
                }
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
                }
            }
        },
    )
}

/** The header that appears at the top of the Quick Settings shade overlay. */
@Composable
fun QuickSettingsOverlayHeader(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        ShadeCarrierGroup(viewModel = viewModel)
        BatteryIcon(
            createBatteryMeterViewController = viewModel.createBatteryMeterViewController,
            useExpandedFormat = true,
        )
    }
}

/*
 * Places startContent and endContent according to the location of the display cutout.
 * Assumes it is globally positioned at (0, 0) and the same size as the screen.
 */
@Composable
private fun CutoutAwareShadeHeader(
    modifier: Modifier = Modifier,
    startContent: @Composable () -> Unit,
    endContent: @Composable () -> Unit,
) {
    val cutoutWidth = LocalDisplayCutout.current.width()
    val cutoutHeight = LocalDisplayCutout.current.height()
    val cutoutTop = LocalDisplayCutout.current.top
    val cutoutLocation = LocalDisplayCutout.current.location

    Layout(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        contents = listOf(startContent, endContent),
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
                    endPlaceable.placeRelative(x = startPlaceable.width, y = 0)
                }
                CutoutLocation.CENTER -> {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width + cutoutWidthPx, y = 0)
                }
                CutoutLocation.LEFT -> {
                    startPlaceable.placeRelative(x = cutoutWidthPx, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width + cutoutWidthPx, y = 0)
                }
            }
        }
    }
}

@Composable
private fun ContentScope.Clock(scale: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                                0.5f,
                            )
                    }
                    .clickable { onClick() },
        )
    }
}

@Composable
private fun BatteryIcon(
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
    chipHighlight: HeaderChipHighlight = HeaderChipHighlight.None,
) {
    val localContext = LocalContext.current
    val themedContext =
        ContextThemeWrapper(localContext, R.style.Theme_SystemUI_QuickSettings_Header)
    val primaryColor =
        Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimary)
    val inverseColor =
        Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimaryInverse)

    AndroidView(
        factory = { context ->
            val batteryIcon = BatteryMeterView(context, null)
            batteryIcon.setPercentShowMode(BatteryMeterView.MODE_ON)

            // [BatteryMeterView.updateColors] is an old method that was built to distinguish
            // between dual-tone colors and single-tone. The current icon is only single-tone, so
            // the final [fg] is the only one we actually need
            batteryIcon.updateColors(primaryColor, inverseColor, primaryColor)

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
                if (useExpandedFormat) BatteryMeterView.MODE_ESTIMATE else BatteryMeterView.MODE_ON
            )
            if (chipHighlight is HeaderChipHighlight.Strong) {
                batteryIcon.updateColors(primaryColor, inverseColor, inverseColor)
            } else if (chipHighlight is HeaderChipHighlight.Weak) {
                batteryIcon.updateColors(primaryColor, inverseColor, primaryColor)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun ShadeCarrierGroup(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
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
                                    StatusBarLocation.SHADE_CARRIER_GROUP,
                                ) as ShadeCarrierGroupMobileIconViewModel),
                        )
                        .also { it.setOnClickListener { viewModel.onShadeCarrierGroupClicked() } }
                }
            )
        }
    }
}

@Composable
private fun NotificationIcons(
    chipHighlight: HeaderChipHighlight,
    notificationIconContainerStatusBarViewBinder: NotificationIconContainerStatusBarViewBinder,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    AndroidView(
        factory = { context ->
            NotificationIconContainer(context, null).also { view ->
                view.setOverrideIconColor(true)
                scope.launch {
                    notificationIconContainerStatusBarViewBinder.bindWhileAttached(
                        view = view,
                        displayId = context.displayId,
                    )
                }
            }
        },
        update = { it.setUseInverseOverrideIconColor(chipHighlight is HeaderChipHighlight.Strong) },
        modifier = modifier,
    )
}

@Composable
private fun ContentScope.StatusIcons(
    viewModel: ShadeHeaderViewModel,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
    chipHighlight: HeaderChipHighlight = HeaderChipHighlight.None,
) {
    val localContext = LocalContext.current
    val themedContext =
        ContextThemeWrapper(localContext, R.style.Theme_SystemUI_QuickSettings_Header)
    val primaryColor =
        Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimary)
    val inverseColor =
        Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimaryInverse)

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

    val iconContainer = remember { StatusIconContainer(themedContext, null) }
    val iconManager = remember {
        viewModel.createTintedIconManager(iconContainer, StatusBarLocation.QS)
    }

    AndroidView(
        factory = { context ->
            iconManager.setTint(primaryColor, inverseColor)
            viewModel.statusBarIconController.addIconGroup(iconManager)

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

            if (chipHighlight is HeaderChipHighlight.Strong) {
                iconManager.setTint(inverseColor, primaryColor)
            } else if (chipHighlight is HeaderChipHighlight.Weak) {
                iconManager.setTint(primaryColor, inverseColor)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun NotificationIconChip(
    chipHighlight: HeaderChipHighlight,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onClick() },
                    )
                    .clip(RoundedCornerShape(25.dp))
                    .background(
                        if (chipHighlight is HeaderChipHighlight.Strong)
                            MaterialTheme.colorScheme.chipHighlighted
                        else MaterialTheme.colorScheme.chipBackground
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SystemIconChip(
    modifier: Modifier = Modifier,
    chipHighlight: HeaderChipHighlight = HeaderChipHighlight.None,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverModifier =
        Modifier.clip(RoundedCornerShape(CollapsedHeight / 4))
            .background(MaterialTheme.colorScheme.onScrimDim)
    val backgroundColor =
        if (chipHighlight is HeaderChipHighlight.Strong) MaterialTheme.colorScheme.chipHighlighted
        else MaterialTheme.colorScheme.chipBackground

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .thenIf(chipHighlight !is HeaderChipHighlight.None) {
                    Modifier.graphicsLayer {
                            shape = RoundedCornerShape(25.dp)
                            clip = true
                        }
                        .background(backgroundColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                }
                .thenIf(onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onClick?.invoke() },
                    )
                }
                .thenIf(isHovered) { hoverModifier },
        content = content,
    )
}

@Composable
private fun ContentScope.PrivacyChip(
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
