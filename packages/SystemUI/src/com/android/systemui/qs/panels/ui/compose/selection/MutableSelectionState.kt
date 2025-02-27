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

/**
 * Holds the selected [TileSpec] and whether the selection was manual, i.e. caused by a tap from the
 * user.
 */
data class Selection(val tileSpec: TileSpec, val manual: Boolean)

/** Holds the state of the current selection. */
class MutableSelectionState {
    private var _selection = mutableStateOf<Selection?>(null)

    /** The [Selection] if a tile is selected, null if not. */
    val selection by _selection

    fun select(tileSpec: TileSpec, manual: Boolean) {
        _selection.value = Selection(tileSpec, manual)
    }

    fun unSelect() {
        _selection.value = null
    }
}

/**
 * Listens for click events to select/unselect the given [TileSpec]. Use this on current tiles as
 * they can be selected.
 */
fun Modifier.selectableTile(
    tileSpec: TileSpec,
    selectionState: MutableSelectionState,
    onClick: () -> Unit = {},
): Modifier {
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                if (selectionState.selection?.tileSpec == tileSpec) {
                    selectionState.unSelect()
                } else {
                    selectionState.select(tileSpec, manual = true)
                }
                onClick()
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
