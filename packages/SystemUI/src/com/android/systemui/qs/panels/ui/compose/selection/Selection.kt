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
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.android.compose.modifiers.thenIf
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.ResizingDotSize
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.SelectedBorderWidth

/**
 * Places a dot to handle resizing drag events. Use this on tiles to resize.
 *
 * The dot is placed vertically centered on the right border. The [content] will have a border when
 * selected.
 *
 * @param selected whether resizing drag events should be handled
 * @param selectionState the [MutableSelectionState] on the grid
 * @param selectionAlpha the animated value for the dot and border alpha
 * @param selectionColor the [Color] of the dot and border
 * @param tileWidths the [TileWidths] of the selected tile
 */
@Composable
fun ResizableTileContainer(
    selected: Boolean,
    selectionState: MutableSelectionState,
    selectionAlpha: () -> Float,
    selectionColor: Color,
    tileWidths: () -> TileWidths?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .resizable(selected, selectionState, tileWidths)
            .selectionBorder(selectionColor, selectionAlpha)
    ) {
        content()
        ResizingHandle(
            enabled = selected,
            selectionState = selectionState,
            tileWidths = tileWidths,
            modifier =
                // Higher zIndex to make sure the handle is drawn above the content
                Modifier.zIndex(2f),
        )
    }
}

@Composable
private fun ResizingHandle(
    enabled: Boolean,
    selectionState: MutableSelectionState,
    tileWidths: () -> TileWidths?,
    modifier: Modifier = Modifier,
) {
    // Manually creating the touch target around the resizing dot to ensure that the next tile
    // does not receive the touch input accidentally.
    val minTouchTargetSize = LocalMinimumInteractiveComponentSize.current
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
            .thenIf(enabled) {
                Modifier.systemGestureExclusion { Rect(Offset.Zero, it.size.toSize()) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, offset ->
                                selectionState.onResizingDrag(offset)
                            },
                            onDragStart = {
                                tileWidths()?.let { selectionState.onResizingDragStart(it) }
                            },
                            onDragEnd = selectionState::onResizingDragEnd,
                            onDragCancel = selectionState::onResizingDragEnd,
                        )
                    }
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
private fun Modifier.resizable(
    selected: Boolean,
    selectionState: MutableSelectionState,
    tileWidths: () -> TileWidths?,
): Modifier {
    if (!selected) return zIndex(1f)

    // Animated diff between the current width and the resized width of the tile. We can't use
    // animateContentSize here as the tile is sometimes unbounded.
    val remainingOffset by
        animateIntAsState(
            selectionState.resizingState?.let { tileWidths()?.base?.minus(it.width) ?: 0 } ?: 0,
            label = "QSEditTileWidthOffset",
        )
    return zIndex(2f).layout { measurable, constraints ->
        // Grab the width from the resizing state if a resize is in progress
        val width = selectionState.resizingState?.width ?: (constraints.maxWidth - remainingOffset)
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
}

private object SelectionDefaults {
    val ResizingDotSize = 16.dp
    val SelectedBorderWidth = 2.dp
}
