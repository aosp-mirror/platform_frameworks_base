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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.android.systemui.qs.pipeline.shared.TileSpec

/** Creates the state of the current selected tile that is remembered across compositions. */
@Composable
fun rememberSelectionState(): MutableSelectionState {
    return remember { MutableSelectionState() }
}

/** Holds the state of the current selection. */
class MutableSelectionState {
    private var _selectedTile = mutableStateOf<TileSpec?>(null)
    private var _resizingState = mutableStateOf<ResizingState?>(null)

    /** The [ResizingState] of the selected tile is currently being resized, null if not. */
    val resizingState by _resizingState

    fun isSelected(tileSpec: TileSpec): Boolean {
        return _selectedTile.value?.let { it == tileSpec } ?: false
    }

    fun select(tileSpec: TileSpec) {
        _selectedTile.value = tileSpec
    }

    fun unSelect() {
        _selectedTile.value = null
        onResizingDragEnd()
    }

    fun onResizingDrag(offset: Float) {
        _resizingState.value?.onDrag(offset)
    }

    fun onResizingDragStart(tileWidths: TileWidths, onResize: () -> Unit) {
        if (_selectedTile.value == null) return

        _resizingState.value = ResizingState(tileWidths, onResize)
    }

    fun onResizingDragEnd() {
        _resizingState.value = null
    }
}

/**
 * Listens for click events to select/unselect the given [TileSpec]. Use this on current tiles as
 * they can be selected.
 */
@Composable
fun Modifier.selectableTile(tileSpec: TileSpec, selectionState: MutableSelectionState): Modifier {
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                if (selectionState.isSelected(tileSpec)) {
                    selectionState.unSelect()
                } else {
                    selectionState.select(tileSpec)
                }
            }
        )
    }
}

/**
 * Listens for click events to unselect any tile. Use this on available tiles as they can't be
 * selected.
 */
@Composable
fun Modifier.clearSelectionTile(selectionState: MutableSelectionState): Modifier {
    return pointerInput(Unit) { detectTapGestures(onTap = { selectionState.unSelect() }) }
}
