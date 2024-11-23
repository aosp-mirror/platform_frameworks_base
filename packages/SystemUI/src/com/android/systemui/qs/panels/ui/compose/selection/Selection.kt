/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose.selection

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.ResizingDotSize
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.SelectedBorderWidth
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Places a dot to handle resizing drag events. Use this on tiles to resize.
 *
 * The dot is placed vertically centered on the right border. The [content] will have a border when
 * selected.
 *
 * @param selected whether resizing drag events should be handled
 * @param state the [ResizingState] for the tile
 * @param selectionAlpha the animated value for the dot and border alpha
 * @param selectionColor the [Color] of the dot and border
 */
@Composable
fun ResizableTileContainer(
    selected: Boolean,
    state: ResizingState,
    selectionAlpha: () -> Float,
    selectionColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.resizable(selected, state).selectionBorder(selectionColor, selectionAlpha)) {
        content()
        ResizingHandle(
            enabled = selected,
            state = state,
            modifier =
                // Higher zIndex to make sure the handle is drawn above the content
                Modifier.zIndex(2f),
        )
    }
}

@Composable
private fun ResizingHandle(enabled: Boolean, state: ResizingState, modifier: Modifier = Modifier) {
    // Manually creating the touch target around the resizing dot to ensure that the next tile
    // does not receive the touch input accidentally.
    val minTouchTargetSize = LocalMinimumInteractiveComponentSize.current
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .layout { measurable, constraints ->
                val size = minTouchTargetSize.roundToPx()
                val placeable = measurable.measure(Constraints(size, size, size, size))
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        x = constraints.maxWidth - placeable.width / 2,
                        y = constraints.maxHeight / 2 - placeable.height / 2,
                    )
                }
            }
            .systemGestureExclusion { Rect(Offset.Zero, it.size.toSize()) }
            .anchoredDraggable(
                enabled = enabled,
                state = state.anchoredDraggableState,
                orientation = Orientation.Horizontal,
            )
            .clickable(enabled = enabled, interactionSource = null, indication = null) {
                scope.launch { state.toggleCurrentValue() }
            }
    ) {
        ResizingDot(enabled = enabled, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ResizingDot(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0f)
    val radius by
        animateDpAsState(
            if (enabled) ResizingDotSize / 2 else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        )
    Canvas(modifier = modifier.size(ResizingDotSize)) {
        drawCircle(color = color, radius = radius.toPx(), alpha = alpha)
    }
}

private fun Modifier.selectionBorder(
    selectionColor: Color,
    selectionAlpha: () -> Float = { 0f },
): Modifier {
    return drawWithContent {
        drawContent()
        drawRoundRect(
            SolidColor(selectionColor),
            cornerRadius = CornerRadius(InactiveCornerRadius.toPx()),
            style = Stroke(SelectedBorderWidth.toPx()),
            alpha = selectionAlpha(),
        )
    }
}

@Composable
private fun Modifier.resizable(selected: Boolean, state: ResizingState): Modifier {
    if (!selected) return zIndex(1f)

    return zIndex(2f).layout { measurable, constraints ->
        val isIdle by derivedStateOf { state.progress().let { it == 0f || it == 1f } }
        // Grab the width from the resizing state if a resize is in progress
        val width =
            state.anchoredDraggableState.requireOffset().roundToInt().takeIf { !isIdle }
                ?: constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
}

private object SelectionDefaults {
    val ResizingDotSize = 16.dp
    val SelectedBorderWidth = 2.dp
}
