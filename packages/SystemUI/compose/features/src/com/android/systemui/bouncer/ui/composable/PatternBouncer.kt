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

package com.android.systemui.bouncer.ui.composable

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Easings
import com.android.compose.modifiers.thenIf
import com.android.internal.R
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PatternDotViewModel
import com.android.systemui.compose.modifiers.sysuiResTag
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * UI for the input part of a pattern-requiring version of the bouncer.
 *
 * The user can press, hold, and drag their pointer to select dots along a grid of dots.
 *
 * If [centerDotsVertically] is `true`, the dots should be centered along the axis of interest; if
 * `false`, the dots will be pushed towards the end/bottom of the axis.
 */
@Composable
internal fun PatternBouncer(
    viewModel: PatternBouncerViewModel,
    centerDotsVertically: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    DisposableEffect(Unit) { onDispose { viewModel.onHidden() } }

    val colCount = viewModel.columnCount
    val rowCount = viewModel.rowCount

    val dotColor = MaterialTheme.colorScheme.secondary
    val dotRadius = with(density) { (DOT_DIAMETER_DP / 2).dp.toPx() }
    val lineColor = MaterialTheme.colorScheme.primary
    val lineStrokeWidth = with(density) { LINE_STROKE_WIDTH_DP.dp.toPx() }

    // All dots that should be rendered on the grid.
    val dots: List<PatternDotViewModel> by viewModel.dots.collectAsStateWithLifecycle()
    // The most recently selected dot, if the user is currently dragging.
    val currentDot: PatternDotViewModel? by viewModel.currentDot.collectAsStateWithLifecycle()
    // The dots selected so far, if the user is currently dragging.
    val selectedDots: List<PatternDotViewModel> by
        viewModel.selectedDots.collectAsStateWithLifecycle()
    val isInputEnabled: Boolean by viewModel.isInputEnabled.collectAsStateWithLifecycle()
    val isAnimationEnabled: Boolean by viewModel.isPatternVisible.collectAsStateWithLifecycle()
    val animateFailure: Boolean by viewModel.animateFailure.collectAsStateWithLifecycle()

    // Map of animatables for the scale of each dot, keyed by dot.
    val dotScalingAnimatables = remember(dots) { dots.associateWith { Animatable(1f) } }
    // Map of animatables for the lines that connect between selected dots, keyed by the destination
    // dot of the line.
    val lineFadeOutAnimatables = remember(dots) { dots.associateWith { Animatable(1f) } }
    val lineFadeOutAnimationDurationMs =
        integerResource(R.integer.lock_pattern_line_fade_out_duration)
    val lineFadeOutAnimationDelayMs = integerResource(R.integer.lock_pattern_line_fade_out_delay)

    val dotAppearFadeInAnimatables = remember(dots) { dots.associateWith { Animatable(0f) } }
    val dotAppearMoveUpAnimatables = remember(dots) { dots.associateWith { Animatable(0f) } }
    val dotAppearMaxOffsetPixels =
        remember(dots) {
            dots.associateWith { dot -> with(density) { (80 + (20 * dot.y)).dp.toPx() } }
        }
    LaunchedEffect(Unit) {
        dotAppearFadeInAnimatables.forEach { (dot, animatable) ->
            scope.launch {
                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            delayMillis = 33 * dot.y,
                            durationMillis = 450,
                            easing = Easings.LegacyDecelerate,
                        )
                )
            }
        }
        dotAppearMoveUpAnimatables.forEach { (dot, animatable) ->
            scope.launch {
                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            delayMillis = 0,
                            durationMillis = 450 + (33 * dot.y),
                            easing = Easings.StandardDecelerate,
                        )
                )
            }
        }
    }

    val view = LocalView.current

    // When the current dot is changed, we need to update our animations.
    LaunchedEffect(currentDot, isAnimationEnabled) {
        // Perform haptic feedback, but only if the current dot is not null, so we don't perform it
        // when the UI first shows up or when the user lifts their pointer/finger.
        if (currentDot != null) {
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }

        if (!isAnimationEnabled) {
            return@LaunchedEffect
        }

        // Make sure that the current dot is scaled up while the other dots are scaled back
        // down.
        dotScalingAnimatables.entries.forEach { (dot, animatable) ->
            val isSelected = dot == currentDot
            // Launch using the longer-lived scope because we want these animations to proceed to
            // completion even if the LaunchedEffect is canceled because its key objects have
            // changed.
            scope.launch {
                if (isSelected) {
                    animatable.animateTo(
                        targetValue = (SELECTED_DOT_DIAMETER_DP / DOT_DIAMETER_DP.toFloat()),
                        animationSpec =
                            tween(
                                durationMillis = SELECTED_DOT_REACTION_ANIMATION_DURATION_MS,
                                easing = Easings.StandardAccelerate,
                            ),
                    )
                } else {
                    animatable.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = SELECTED_DOT_RETRACT_ANIMATION_DURATION_MS,
                                easing = Easings.StandardDecelerate,
                            ),
                    )
                }
            }
        }

        selectedDots.forEach { dot ->
            lineFadeOutAnimatables[dot]?.let { line ->
                if (!line.isRunning) {
                    // Launch using the longer-lived scope because we want these animations to
                    // proceed to completion even if the LaunchedEffect is canceled because its key
                    // objects have changed.
                    scope.launch {
                        if (dot == currentDot) {
                            // Reset the fade-out animation for the current dot. When the
                            // current dot is switched, this entire code block runs again for
                            // the newly selected dot.
                            line.snapTo(1f)
                        } else {
                            // For all non-current dots, make sure that the lines are fading
                            // out.
                            line.animateTo(
                                targetValue = 0f,
                                animationSpec =
                                    tween(
                                        durationMillis = lineFadeOutAnimationDurationMs,
                                        delayMillis = lineFadeOutAnimationDelayMs,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    // Show the failure animation if the user entered the wrong input.
    LaunchedEffect(animateFailure) {
        if (animateFailure) {
            showFailureAnimation(
                dots = dots,
                scalingAnimatables = dotScalingAnimatables,
            )
            viewModel.onFailureAnimationShown()
        }
    }

    // This is the position of the input pointer.
    var inputPosition: Offset? by remember { mutableStateOf(null) }
    var gridCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    var offset: Offset by remember { mutableStateOf(Offset.Zero) }
    var scale: Float by remember { mutableStateOf(1f) }

    Canvas(
        modifier
            .sysuiResTag("bouncer_pattern_root")
            // Because the width also includes spacing to the left and right of the leftmost and
            // rightmost dots in the grid and because UX mocks specify the width without that
            // spacing, the actual width needs to be defined slightly bigger than the UX mock width.
            .width((262 * colCount / 2).dp)
            // Because the height also includes spacing above and below the topmost and bottommost
            // dots in the grid and because UX mocks specify the height without that spacing, the
            // actual height needs to be defined slightly bigger than the UX mock height.
            .height((262 * rowCount / 2).dp)
            // Need to clip to bounds to make sure that the lines don't follow the input pointer
            // when it leaves the bounds of the dot grid.
            .clipToBounds()
            .onGloballyPositioned { coordinates -> gridCoordinates = coordinates }
            .thenIf(isInputEnabled) {
                Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            viewModel.onDown()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                inputPosition = start
                                viewModel.onDragStart()
                            },
                            onDragEnd = {
                                inputPosition = null
                                if (isAnimationEnabled) {
                                    lineFadeOutAnimatables.values.forEach { animatable ->
                                        // Launch using the longer-lived scope because we want these
                                        // animations to proceed to completion even if the
                                        // surrounding scope is canceled.
                                        scope.launch { animatable.animateTo(1f) }
                                    }
                                }
                                viewModel.onDragEnd()
                            },
                        ) { change, _ ->
                            inputPosition = change.position
                            change.position.minus(offset).div(scale).let {
                                viewModel.onDrag(
                                    xPx = it.x,
                                    yPx = it.y,
                                    containerSizePx = size.width,
                                )
                            }
                        }
                    }
            }
    ) {
        gridCoordinates?.let { nonNullCoordinates ->
            val containerSize = nonNullCoordinates.size
            if (containerSize.width <= 0 || containerSize.height <= 0) {
                return@let
            }

            val horizontalSpacing = containerSize.width.toFloat() / colCount
            val verticalSpacing = containerSize.height.toFloat() / rowCount
            val spacing = min(horizontalSpacing, verticalSpacing)
            val horizontalOffset =
                offset(
                    availableSize = containerSize.width,
                    spacingPerDot = spacing,
                    dotCount = colCount,
                    isCentered = true,
                )
            val verticalOffset =
                offset(
                    availableSize = containerSize.height,
                    spacingPerDot = spacing,
                    dotCount = rowCount,
                    isCentered = centerDotsVertically,
                )
            offset = Offset(horizontalOffset, verticalOffset)
            scale = (colCount * spacing) / containerSize.width

            if (isAnimationEnabled) {
                // Draw lines between dots.
                selectedDots.forEachIndexed { index, dot ->
                    if (index > 0) {
                        val previousDot = selectedDots[index - 1]
                        val lineFadeOutAnimationProgress =
                            lineFadeOutAnimatables[previousDot]!!.value
                        val startLerp = 1 - lineFadeOutAnimationProgress
                        val from =
                            pixelOffset(previousDot, spacing, horizontalOffset, verticalOffset)
                        val to = pixelOffset(dot, spacing, horizontalOffset, verticalOffset)
                        val lerpedFrom =
                            Offset(
                                x = from.x + (to.x - from.x) * startLerp,
                                y = from.y + (to.y - from.y) * startLerp,
                            )
                        drawLine(
                            start = lerpedFrom,
                            end = to,
                            cap = StrokeCap.Round,
                            alpha = lineFadeOutAnimationProgress * lineAlpha(spacing),
                            color = lineColor,
                            strokeWidth = lineStrokeWidth,
                        )
                    }
                }

                // Draw the line between the most recently-selected dot and the input pointer
                // position.
                inputPosition?.let { lineEnd ->
                    currentDot?.let { dot ->
                        val from = pixelOffset(dot, spacing, horizontalOffset, verticalOffset)
                        val lineLength =
                            sqrt((from.y - lineEnd.y).pow(2) + (from.x - lineEnd.x).pow(2))
                        drawLine(
                            start = from,
                            end = lineEnd,
                            cap = StrokeCap.Round,
                            alpha = lineAlpha(spacing, lineLength),
                            color = lineColor,
                            strokeWidth = lineStrokeWidth,
                        )
                    }
                }
            }

            // Draw each dot on the grid.
            dots.forEach { dot ->
                val initialOffset = checkNotNull(dotAppearMaxOffsetPixels[dot])
                val appearOffset =
                    (1 - checkNotNull(dotAppearMoveUpAnimatables[dot]).value) * initialOffset
                drawCircle(
                    center =
                        pixelOffset(
                            dot,
                            spacing,
                            horizontalOffset,
                            verticalOffset + appearOffset,
                        ),
                    color =
                        dotColor.copy(alpha = checkNotNull(dotAppearFadeInAnimatables[dot]).value),
                    radius = dotRadius * checkNotNull(dotScalingAnimatables[dot]).value
                )
            }
        }
    }
}

/** Returns an [Offset] representation of the given [dot], in pixel coordinates. */
private fun pixelOffset(
    dot: PatternDotViewModel,
    spacing: Float,
    horizontalOffset: Float,
    verticalOffset: Float,
): Offset {
    return Offset(
        x = dot.x * spacing + spacing / 2 + horizontalOffset,
        y = dot.y * spacing + spacing / 2 + verticalOffset,
    )
}

/**
 * Returns the alpha for a line between dots where dots are normally [gridSpacing] apart from each
 * other on the dot grid and the line ends [lineLength] away from the origin dot.
 *
 * The reason [lineLength] can be different from [gridSpacing] is that all lines originate in dots
 * but one line might end where the user input pointer is, which isn't always a dot position.
 */
private fun lineAlpha(gridSpacing: Float, lineLength: Float = gridSpacing): Float {
    // Custom curve for the alpha of a line as a function of its distance from its source dot. The
    // farther the user input pointer goes from the line, the more opaque the line gets.
    return ((lineLength / gridSpacing - 0.3f) * 4f).coerceIn(0f, 1f)
}

private suspend fun showFailureAnimation(
    dots: List<PatternDotViewModel>,
    scalingAnimatables: Map<PatternDotViewModel, Animatable<Float, AnimationVector1D>>,
) {
    val dotsByRow =
        buildList<MutableList<PatternDotViewModel>> {
            dots.forEach { dot ->
                val rowIndex = dot.y
                while (size <= rowIndex) {
                    add(mutableListOf())
                }
                get(rowIndex).add(dot)
            }
        }

    coroutineScope {
        dotsByRow.forEachIndexed { rowIndex, rowDots ->
            rowDots.forEach { dot ->
                scalingAnimatables[dot]?.let { dotScaleAnimatable ->
                    launch {
                        dotScaleAnimatable.animateTo(
                            targetValue =
                                FAILURE_ANIMATION_DOT_DIAMETER_DP / DOT_DIAMETER_DP.toFloat(),
                            animationSpec =
                                tween(
                                    durationMillis =
                                        FAILURE_ANIMATION_DOT_SHRINK_ANIMATION_DURATION_MS,
                                    delayMillis =
                                        rowIndex * FAILURE_ANIMATION_DOT_SHRINK_STAGGER_DELAY_MS,
                                    easing = Easings.Linear,
                                ),
                        )

                        dotScaleAnimatable.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                tween(
                                    durationMillis =
                                        FAILURE_ANIMATION_DOT_REVERT_ANIMATION_DURATION,
                                    easing = Easings.Standard,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Returns the amount of offset along the axis, in pixels, that should be applied to all dots.
 *
 * @param availableSize The size of the container, along the axis of interest.
 * @param spacingPerDot The amount of pixels that each dot should take (including the area around
 *   that dot).
 * @param dotCount The number of dots along the axis (e.g. if the axis of interest is the
 *   horizontal/x axis, this is the number of columns in the dot grid).
 * @param isCentered Whether the dots should be centered along the axis of interest; if `false`, the
 *   dots will be pushed towards to end/bottom of the axis.
 */
private fun offset(
    availableSize: Int,
    spacingPerDot: Float,
    dotCount: Int,
    isCentered: Boolean = false,
): Float {
    val default = availableSize - spacingPerDot * dotCount
    return if (isCentered) {
        default / 2
    } else {
        default
    }
}

private const val DOT_DIAMETER_DP = 14
private const val SELECTED_DOT_DIAMETER_DP = (DOT_DIAMETER_DP * 1.5).toInt()
private const val SELECTED_DOT_REACTION_ANIMATION_DURATION_MS = 83
private const val SELECTED_DOT_RETRACT_ANIMATION_DURATION_MS = 750
private const val LINE_STROKE_WIDTH_DP = DOT_DIAMETER_DP
private const val FAILURE_ANIMATION_DOT_DIAMETER_DP = (DOT_DIAMETER_DP * 0.81f).toInt()
private const val FAILURE_ANIMATION_DOT_SHRINK_ANIMATION_DURATION_MS = 50
private const val FAILURE_ANIMATION_DOT_SHRINK_STAGGER_DELAY_MS = 33
private const val FAILURE_ANIMATION_DOT_REVERT_ANIMATION_DURATION = 617
