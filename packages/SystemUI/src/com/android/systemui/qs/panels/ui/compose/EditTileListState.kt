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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec

@Composable
fun rememberEditListState(
    tiles: List<EditTileViewModel>,
): EditTileListState {
    return remember(tiles) { EditTileListState(tiles) }
}

/** Holds the temporary state of the tile list during a drag movement where we move tiles around. */
class EditTileListState(tiles: List<EditTileViewModel>) {
    val tiles: SnapshotStateList<EditTileViewModel> = tiles.toMutableStateList()

    fun move(tileSpec: TileSpec, target: TileSpec) {
        val fromIndex = indexOf(tileSpec)
        val toIndex = indexOf(target)

        if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) {
            return
        }

        val isMovingToCurrent = tiles[toIndex].isCurrent
        tiles.apply { add(toIndex, removeAt(fromIndex).copy(isCurrent = isMovingToCurrent)) }
    }

    fun indexOf(tileSpec: TileSpec): Int {
        return tiles.indexOfFirst { it.tileSpec == tileSpec }
    }
}
