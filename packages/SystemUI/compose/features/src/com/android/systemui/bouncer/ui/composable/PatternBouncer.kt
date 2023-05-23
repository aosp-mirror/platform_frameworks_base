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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.internal.R
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PatternDotViewModel
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * UI for the input part of a pattern-requiring version of the bouncer.
 *
 * The user can press, hold, and drag their pointer to select dots along a grid of dots.
 */
@Composable
internal fun PatternBouncer(
    viewModel: PatternBouncerViewModel,
    modifier: Modifier = Modifier,
) {
    // Report that the UI is shown to let the view-model run some logic.
    LaunchedEffect(Unit) { viewModel.onShown() }

    val colCount = viewModel.columnCount
    val rowCount = viewModel.rowCount

    val dotColor = MaterialTheme.colorScheme.secondary
    val dotRadius = with(LocalDensity.current) { 8.dp.toPx() }
    val lineColor = MaterialTheme.colorScheme.primary
    val lineStrokeWidth = dotRadius * 2 + with(LocalDensity.current) { 4.dp.toPx() }

    var containerSize: IntSize by remember { mutableStateOf(IntSize(0, 0)) }
    val horizontalSpacing = containerSize.width / colCount
    val verticalSpacing = containerSize.height / rowCount
    val spacing = min(horizontalSpacing, verticalSpacing).toFloat()
    val verticalOffset = containerSize.height - spacing * rowCount

    // All dots that should be rendered on the grid.
    val dots: List<PatternDotViewModel> by viewModel.dots.collectAsState()
    // The most recently selected dot, if the user is currently dragging.
    val currentDot: PatternDotViewModel? by viewModel.currentDot.collectAsState()
    // The dots selected so far, if the user is currently dragging.
    val selectedDots: List<PatternDotViewModel> by viewModel.selectedDots.collectAsState()

    // Map of animatables for the scale of each dot, keyed by dot.
    val dotScalingAnimatables = remember(dots) { dots.associateWith { Animatable(1f) } }
    // Map of animatables for the lines that connect between selected dots, keyed by the destination
    // dot of the line.
    val lineFadeOutAnimatables = remember(dots) { dots.associateWith { Animatable(1f) } }
    val lineFadeOutAnimationDurationMs =
        integerResource(R.integer.lock_pattern_line_fade_out_duration)
    val lineFadeOutAnimationDelayMs = integerResource(R.integer.lock_pattern_line_fade_out_delay)

    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // When the current dot is changed, we need to update our animations.
    LaunchedEffect(currentDot) {
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )

        // Make sure that the current dot is scaled up while the other dots are scaled back down.
        dotScalingAnimatables.entries.forEach { (dot, animatable) ->
            val isSelected = dot == currentDot
            launch {
                animatable.animateTo(if (isSelected) 2f else 1f)
                if (isSelected) {
                    animatable.animateTo(1f)
                }
            }
        }

        selectedDots.forEach { dot ->
            lineFadeOutAnimatables[dot]?.let { line ->
                if (!line.isRunning) {
                    scope.launch {
                        if (dot == currentDot) {
                            // Reset the fade-out animation for the current dot. When the current
                            // dot is switched, this entire code block runs again for the newly
                            // selected dot.
                            line.snapTo(1f)
                        } else {
                            // For all non-current dots, make sure that the lines are fading out.
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

    // This is the position of the input pointer.
    var inputPosition: Offset? by remember { mutableStateOf(null) }

    Canvas(
        modifier
            // Need to clip to bounds to make sure that the lines don't follow the input pointer
            // when it leaves the bounds of the dot grid.
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        inputPosition = start
                        viewModel.onDragStart()
                    },
                    onDragEnd = {
                        inputPosition = null
                        lineFadeOutAnimatables.values.forEach { animatable ->
                            scope.launch { animatable.animateTo(1f) }
                        }
                        viewModel.onDragEnd()
                    },
                ) { change, _ ->
                    inputPosition = change.position
                    viewModel.onDrag(
                        xPx = change.position.x,
                        yPx = change.position.y,
                        containerSizePx = containerSize.width,
                        verticalOffsetPx = verticalOffset,
                    )
                }
            }
    ) {
        // Draw lines between dots.
        selectedDots.forEachIndexed { index, dot ->
            if (index > 0) {
                val previousDot = selectedDots[index - 1]
                val lineFadeOutAnimationProgress = lineFadeOutAnimatables[previousDot]!!.value
                val startLerp = 1 - lineFadeOutAnimationProgress
                val from = pixelOffset(previousDot, spacing, verticalOffset)
                val to = pixelOffset(dot, spacing, verticalOffset)
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

        // Draw the line between the most recently-selected dot and the input pointer position.
        inputPosition?.let { lineEnd ->
            currentDot?.let { dot ->
                val from = pixelOffset(dot, spacing, verticalOffset)
                val lineLength = sqrt((from.y - lineEnd.y).pow(2) + (from.x - lineEnd.x).pow(2))
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

        // Draw each dot on the grid.
        dots.forEach { dot ->
            drawCircle(
                center = pixelOffset(dot, spacing, verticalOffset),
                color = dotColor,
                radius = dotRadius * (dotScalingAnimatables[dot]?.value ?: 1f),
            )
        }
    }
}

/** Returns an [Offset] representation of the given [dot], in pixel coordinates. */
private fun pixelOffset(
    dot: PatternDotViewModel,
    spacing: Float,
    verticalOffset: Float,
): Offset {
    return Offset(
        x = dot.x * spacing + spacing / 2,
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
