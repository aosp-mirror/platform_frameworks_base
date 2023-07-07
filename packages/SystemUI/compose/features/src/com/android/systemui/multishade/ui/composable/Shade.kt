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

package com.android.systemui.multishade.ui.composable

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.height
import com.android.compose.modifiers.padding
import com.android.compose.swipeable.FixedThreshold
import com.android.compose.swipeable.SwipeableState
import com.android.compose.swipeable.ThresholdConfig
import com.android.compose.swipeable.rememberSwipeableState
import com.android.compose.swipeable.swipeable
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.ui.viewmodel.ShadeViewModel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Renders a shade (container and content).
 *
 * This should be allowed to grow to fill the width and height of its container.
 *
 * @param viewModel The view-model for this shade.
 * @param currentTimeMillis A provider for the current time, in milliseconds.
 * @param containerHeightPx The height of the container that this shade is being shown in, in
 *   pixels.
 * @param modifier The Modifier.
 * @param content The content of the shade.
 */
@Composable
fun Shade(
    viewModel: ShadeViewModel,
    currentTimeMillis: () -> Long,
    containerHeightPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val isVisible: Boolean by viewModel.isVisible.collectAsState()
    if (!isVisible) {
        return
    }

    val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
    ReportNonProxiedInput(viewModel, interactionSource)

    val swipeableState = rememberSwipeableState(initialValue = ShadeState.FullyCollapsed)
    HandleForcedCollapse(viewModel, swipeableState)
    HandleProxiedInput(viewModel, swipeableState, currentTimeMillis)
    ReportShadeExpansion(viewModel, swipeableState, containerHeightPx)

    val isSwipingEnabled: Boolean by viewModel.isSwipingEnabled.collectAsState()
    val collapseThreshold: Float by viewModel.swipeCollapseThreshold.collectAsState()
    val expandThreshold: Float by viewModel.swipeExpandThreshold.collectAsState()

    val width: ShadeViewModel.Size by viewModel.width.collectAsState()
    val density = LocalDensity.current

    val anchors: Map<Float, ShadeState> =
        remember(containerHeightPx) { swipeableAnchors(containerHeightPx) }

    ShadeContent(
        shadeHeightPx = { swipeableState.offset.value },
        overstretch = { swipeableState.overflow.value / containerHeightPx },
        isSwipingEnabled = isSwipingEnabled,
        swipeableState = swipeableState,
        interactionSource = interactionSource,
        anchors = anchors,
        thresholds = { _, to ->
            swipeableThresholds(
                to = to,
                swipeCollapseThreshold = collapseThreshold.fractionToDp(density, containerHeightPx),
                swipeExpandThreshold = expandThreshold.fractionToDp(density, containerHeightPx),
            )
        },
        modifier = modifier.shadeWidth(width, density),
        content = content,
    )
}

/**
 * Draws the content of the shade.
 *
 * @param shadeHeightPx Provider for the current expansion of the shade, in pixels, where `0` is
 *   fully collapsed.
 * @param overstretch Provider for the current amount of vertical "overstretch" that the shade
 *   should be rendered with. This is `0` or a positive number that is a percentage of the total
 *   height of the shade when fully expanded. A value of `0` means that the shade is not stretched
 *   at all.
 * @param isSwipingEnabled Whether swiping inside the shade is enabled or not.
 * @param swipeableState The state to use for the [swipeable] modifier, allowing external control in
 *   addition to direct control (proxied user input in addition to non-proxied/direct user input).
 * @param anchors A map of [ShadeState] keyed by the vertical position, in pixels, where that state
 *   occurs; this is used to configure the [swipeable] modifier.
 * @param thresholds Function that returns the [ThresholdConfig] for going from one [ShadeState] to
 *   another. This controls how the [swipeable] decides which [ShadeState] to animate to once the
 *   user lets go of the shade; e.g. does it animate to fully collapsed or fully expanded.
 * @param content The content to render inside the shade.
 * @param modifier The [Modifier].
 */
@Composable
private fun ShadeContent(
    shadeHeightPx: () -> Float,
    overstretch: () -> Float,
    isSwipingEnabled: Boolean,
    swipeableState: SwipeableState<ShadeState>,
    interactionSource: MutableInteractionSource,
    anchors: Map<Float, ShadeState>,
    thresholds: (from: ShadeState, to: ShadeState) -> ThresholdConfig,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    /**
     * Returns a function that takes in [Density] and returns the current padding around the shade
     * content.
     */
    fun padding(
        shadeHeightPx: () -> Float,
    ): Density.() -> Int {
        return {
            min(
                12.dp.toPx().roundToInt(),
                shadeHeightPx().roundToInt(),
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(32.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height { shadeHeightPx().roundToInt() }
                .padding(
                    horizontal = padding(shadeHeightPx),
                    vertical = padding(shadeHeightPx),
                )
                .graphicsLayer {
                    // Applies the vertical over-stretching of the shade content that may happen if
                    // the user keep dragging down when the shade is already fully-expanded.
                    transformOrigin = transformOrigin.copy(pivotFractionY = 0f)
                    this.scaleY = 1 + overstretch().coerceAtLeast(0f)
                }
                .swipeable(
                    enabled = isSwipingEnabled,
                    state = swipeableState,
                    interactionSource = interactionSource,
                    anchors = anchors,
                    thresholds = thresholds,
                    orientation = Orientation.Vertical,
                ),
        content = content,
    )
}

/** Funnels current shade expansion values into the view-model. */
@Composable
private fun ReportShadeExpansion(
    viewModel: ShadeViewModel,
    swipeableState: SwipeableState<ShadeState>,
    containerHeightPx: Float,
) {
    LaunchedEffect(swipeableState.offset, containerHeightPx) {
        snapshotFlow { swipeableState.offset.value / containerHeightPx }
            .collect { expansion -> viewModel.onExpansionChanged(expansion) }
    }
}

/** Funnels drag gesture start and end events into the view-model. */
@Composable
private fun ReportNonProxiedInput(
    viewModel: ShadeViewModel,
    interactionSource: InteractionSource,
) {
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> {
                    viewModel.onDragStarted()
                }
                is DragInteraction.Stop -> {
                    viewModel.onDragEnded()
                }
            }
        }
    }
}

/** When told to force collapse, collapses the shade. */
@Composable
private fun HandleForcedCollapse(
    viewModel: ShadeViewModel,
    swipeableState: SwipeableState<ShadeState>,
) {
    LaunchedEffect(viewModel) {
        viewModel.isForceCollapsed.collect {
            launch { swipeableState.animateTo(ShadeState.FullyCollapsed) }
        }
    }
}

/**
 * Handles proxied input (input originating outside of the UI of the shade) by driving the
 * [SwipeableState] accordingly.
 */
@Composable
private fun HandleProxiedInput(
    viewModel: ShadeViewModel,
    swipeableState: SwipeableState<ShadeState>,
    currentTimeMillis: () -> Long,
) {
    val velocityTracker: VelocityTracker = remember { VelocityTracker() }
    LaunchedEffect(viewModel) {
        viewModel.proxiedInput.collect {
            when (it) {
                is ProxiedInputModel.OnDrag -> {
                    velocityTracker.addPosition(
                        timeMillis = currentTimeMillis.invoke(),
                        position = Offset(0f, it.yDragAmountPx),
                    )
                    swipeableState.performDrag(it.yDragAmountPx)
                }
                is ProxiedInputModel.OnDragEnd -> {
                    launch {
                        val velocity = velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        // We use a VelocityTracker to keep a record of how fast the pointer was
                        // moving such that we know how far to fling the shade when the gesture
                        // ends. Flinging the SwipeableState using performFling is required after
                        // one or more calls to performDrag such that the swipeable settles into one
                        // of the states. Without doing that, the shade would remain unmoving in an
                        // in-between state on the screen.
                        swipeableState.performFling(velocity)
                    }
                }
                is ProxiedInputModel.OnDragCancel -> {
                    launch {
                        velocityTracker.resetTracking()
                        swipeableState.animateTo(swipeableState.progress.from)
                    }
                }
                else -> Unit
            }
        }
    }
}

/**
 * Converts the [Float] (which is assumed to be a fraction between `0` and `1`) to a value in dp.
 *
 * @param density The [Density] of the display.
 * @param wholePx The whole amount that the given [Float] is a fraction of.
 * @return The dp size that's a fraction of the whole amount.
 */
private fun Float.fractionToDp(density: Density, wholePx: Float): Dp {
    return with(density) { (this@fractionToDp * wholePx).toDp() }
}

private fun Modifier.shadeWidth(
    size: ShadeViewModel.Size,
    density: Density,
): Modifier {
    return then(
        when (size) {
            is ShadeViewModel.Size.Fraction -> Modifier.fillMaxWidth(size.fraction)
            is ShadeViewModel.Size.Pixels -> Modifier.width(with(density) { size.pixels.toDp() })
        }
    )
}

/** Returns the pixel positions for each of the supported shade states. */
private fun swipeableAnchors(containerHeightPx: Float): Map<Float, ShadeState> {
    return mapOf(
        0f to ShadeState.FullyCollapsed,
        containerHeightPx to ShadeState.FullyExpanded,
    )
}

/**
 * Returns the [ThresholdConfig] for how far the shade should be expanded or collapsed such that it
 * actually completes the expansion or collapse after the user lifts their pointer.
 */
private fun swipeableThresholds(
    to: ShadeState,
    swipeExpandThreshold: Dp,
    swipeCollapseThreshold: Dp,
): ThresholdConfig {
    return FixedThreshold(
        when (to) {
            ShadeState.FullyExpanded -> swipeExpandThreshold
            ShadeState.FullyCollapsed -> swipeCollapseThreshold
        }
    )
}

/** Enumerates the shade UI states for [SwipeableState]. */
private enum class ShadeState {
    FullyCollapsed,
    FullyExpanded,
}
