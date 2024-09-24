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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.ResizingDotSize

/**
 * Dot handling resizing drag events. Use this on the selected tile to resize it
 *
 * @param enabled whether resizing drag events should be handled
 * @param selectionState the [MutableSelectionState] on the grid
 * @param transition the animated value for the dot, used for its alpha and scale
 * @param tileWidths the [TileWidths] of the selected tile
 * @param onResize the callback when the drag passes the resizing threshold
 */
@Composable
fun ResizingHandle(
    enabled: Boolean,
    selectionState: MutableSelectionState,
    transition: () -> Float,
    tileWidths: () -> TileWidths? = { null },
) {
    if (enabled) {
        // Manually creating the touch target around the resizing dot to ensure that the next tile
        // does
        // not receive the touch input accidentally.
        val minTouchTargetSize = LocalMinimumInteractiveComponentSize.current
        Box(
            Modifier.size(minTouchTargetSize).pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, offset -> selectionState.onResizingDrag(offset) },
                    onDragStart = { tileWidths()?.let { selectionState.onResizingDragStart(it) } },
                    onDragEnd = selectionState::onResizingDragEnd,
                    onDragCancel = selectionState::onResizingDragEnd,
                )
            }
        ) {
            ResizingDot(transition = transition, modifier = Modifier.align(Alignment.Center))
        }
    } else {
        ResizingDot(transition = transition)
    }
}

@Composable
private fun ResizingDot(
    transition: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier.size(ResizingDotSize)) {
        val v = transition()
        drawCircle(color = color, radius = (ResizingDotSize / 2).toPx() * v, alpha = v)
    }
}

private object SelectionDefaults {
    val ResizingDotSize = 16.dp
}
