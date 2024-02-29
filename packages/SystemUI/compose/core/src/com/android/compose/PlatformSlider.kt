/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.padding
import com.android.compose.theme.LocalAndroidColorScheme

/**
 * Platform slider implementation that displays a slider with an [icon] and a [label] at the start.
 *
 * @param onValueChangeFinished is called when the slider settles on a [value]. This callback
 *   shouldn't be used to react to value changes. Use [onValueChange] instead
 * @param interactionSource - the [MutableInteractionSource] representing the stream of Interactions
 *   for this slider. You can create and pass in your own remembered instance to observe
 *   Interactions and customize the appearance / behavior of this slider in different states.
 * @param colors - slider color scheme.
 * @param draggingCornersRadius - radius of the slider indicator when the user drags it
 * @param icon - icon at the start of the slider. Icon is limited to a square space at the start of
 *   the slider
 * @param label - control shown next to the icon.
 */
@Composable
fun PlatformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    colors: PlatformSliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
    draggingCornersRadius: Dp = PlatformSliderDefaults.DefaultPlatformSliderDraggingCornerRadius,
    icon: (@Composable (isDragging: Boolean) -> Unit)? = null,
    label: (@Composable (isDragging: Boolean) -> Unit)? = null,
) {
    val sliderHeight: Dp = 64.dp
    val iconWidth: Dp = sliderHeight
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isDragging = true
                }
                is DragInteraction.Cancel,
                is DragInteraction.Stop -> {
                    isDragging = false
                }
            }
        }
    }
    val paddingStart by
        animateDpAsState(
            targetValue =
                if ((!isDragging && value == valueRange.start) || icon == null) {
                    16.dp
                } else {
                    0.dp
                },
            label = "LabelIconSpacingAnimation"
        )

    Box(modifier = modifier.height(sliderHeight)) {
        Slider(
            modifier = Modifier.fillMaxSize(),
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            enabled = enabled,
            track = {
                Track(
                    sliderState = it,
                    enabled = enabled,
                    colors = colors,
                    iconWidth = iconWidth,
                    draggingCornersRadius = draggingCornersRadius,
                    sliderHeight = sliderHeight,
                    isDragging = isDragging,
                    modifier = Modifier,
                )
            },
            thumb = { Spacer(Modifier.width(iconWidth).height(sliderHeight)) },
        )

        if (icon != null || label != null) {
            Row(modifier = Modifier.fillMaxSize()) {
                icon?.let { iconComposable ->
                    Box(
                        modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        iconComposable(isDragging)
                    }
                }

                label?.let { labelComposable ->
                    Box(
                        modifier =
                            Modifier.fillMaxHeight()
                                .weight(1f)
                                .padding(
                                    start = { paddingStart.roundToPx() },
                                    end = { sliderHeight.roundToPx() / 2 },
                                ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        labelComposable(isDragging)
                    }
                }
            }
        }
    }
}

@Composable
private fun Track(
    sliderState: SliderState,
    enabled: Boolean,
    colors: PlatformSliderColors,
    iconWidth: Dp,
    draggingCornersRadius: Dp,
    sliderHeight: Dp,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val iconWidthPx: Float
    val halfIconWidthPx: Float
    val targetIndicatorRadiusPx: Float
    val halfSliderHeightPx: Float
    with(LocalDensity.current) {
        halfSliderHeightPx = sliderHeight.toPx() / 2
        iconWidthPx = iconWidth.toPx()
        halfIconWidthPx = iconWidthPx / 2
        targetIndicatorRadiusPx =
            if (isDragging) draggingCornersRadius.toPx() else halfSliderHeightPx
    }

    val indicatorRadiusPx: Float by
        animateFloatAsState(
            targetValue = targetIndicatorRadiusPx,
            label = "PlatformSliderCornersAnimation",
        )

    val trackColor = colors.getTrackColor(enabled)
    val indicatorColor = colors.getIndicatorColor(enabled)
    val trackCornerRadius = CornerRadius(halfSliderHeightPx, halfSliderHeightPx)
    val indicatorCornerRadius = CornerRadius(indicatorRadiusPx, indicatorRadiusPx)
    Canvas(modifier.fillMaxSize()) {
        val trackPath = Path()
        trackPath.addRoundRect(
            RoundRect(
                left = -halfIconWidthPx,
                top = 0f,
                right = size.width + halfIconWidthPx,
                bottom = size.height,
                cornerRadius = trackCornerRadius,
            )
        )
        drawPath(path = trackPath, color = trackColor)

        clipPath(trackPath) {
            val indicatorPath = Path()
            if (isRtl) {
                indicatorPath.addRoundRect(
                    RoundRect(
                        left =
                            size.width -
                                size.width * sliderState.coercedNormalizedValue -
                                halfIconWidthPx,
                        top = 0f,
                        right = size.width + iconWidthPx,
                        bottom = size.height,
                        topLeftCornerRadius = indicatorCornerRadius,
                        topRightCornerRadius = trackCornerRadius,
                        bottomRightCornerRadius = trackCornerRadius,
                        bottomLeftCornerRadius = indicatorCornerRadius,
                    )
                )
            } else {
                indicatorPath.addRoundRect(
                    RoundRect(
                        left = -halfIconWidthPx,
                        top = 0f,
                        right = size.width * sliderState.coercedNormalizedValue + halfIconWidthPx,
                        bottom = size.height,
                        topLeftCornerRadius = trackCornerRadius,
                        topRightCornerRadius = indicatorCornerRadius,
                        bottomRightCornerRadius = indicatorCornerRadius,
                        bottomLeftCornerRadius = trackCornerRadius,
                    )
                )
            }
            drawPath(path = indicatorPath, color = indicatorColor)
        }
    }
}

/** [SliderState.value] normalized using [SliderState.valueRange]. The result belongs to [0, 1] */
private val SliderState.coercedNormalizedValue: Float
    get() {
        val dif = valueRange.endInclusive - valueRange.start
        return if (dif == 0f) {
            0f
        } else {
            val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
            (coercedValue - valueRange.start) / dif
        }
    }

/**
 * [PlatformSlider] color scheme.
 *
 * @param trackColor fills the track of the slider. This is a "background" of the slider
 * @param indicatorColor fills the slider from the start to the value
 * @param iconColor is the default icon color
 * @param labelColor is the default icon color
 * @param disabledTrackColor is the [trackColor] when the PlatformSlider#enabled == false
 * @param disabledIndicatorColor is the [indicatorColor] when the PlatformSlider#enabled == false
 * @param disabledIconColor is the [iconColor] when the PlatformSlider#enabled == false
 * @param disabledLabelColor is the [labelColor] when the PlatformSlider#enabled == false
 */
data class PlatformSliderColors(
    val trackColor: Color,
    val indicatorColor: Color,
    val iconColor: Color,
    val labelColor: Color,
    val disabledTrackColor: Color,
    val disabledIndicatorColor: Color,
    val disabledIconColor: Color,
    val disabledLabelColor: Color,
)

object PlatformSliderDefaults {

    /** Indicator corner radius used when the user drags the [PlatformSlider]. */
    val DefaultPlatformSliderDraggingCornerRadius = 8.dp

    @Composable
    fun defaultPlatformSliderColors(): PlatformSliderColors =
        if (isSystemInDarkTheme()) darkThemePlatformSliderColors()
        else lightThemePlatformSliderColors()
}

/** [PlatformSliderColors] for the light theme */
@Composable
private fun lightThemePlatformSliderColors() =
    PlatformSliderColors(
        trackColor = MaterialTheme.colorScheme.tertiaryContainer,
        indicatorColor = LocalAndroidColorScheme.current.tertiaryFixedDim,
        iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
        disabledTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledIconColor = MaterialTheme.colorScheme.outline,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** [PlatformSliderColors] for the dark theme */
@Composable
private fun darkThemePlatformSliderColors() =
    PlatformSliderColors(
        trackColor = MaterialTheme.colorScheme.onTertiary,
        indicatorColor = LocalAndroidColorScheme.current.onTertiaryFixedVariant,
        iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
        disabledTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledIconColor = MaterialTheme.colorScheme.outline,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

private fun PlatformSliderColors.getTrackColor(isEnabled: Boolean): Color =
    if (isEnabled) trackColor else disabledTrackColor

private fun PlatformSliderColors.getIndicatorColor(isEnabled: Boolean): Color =
    if (isEnabled) indicatorColor else disabledIndicatorColor
