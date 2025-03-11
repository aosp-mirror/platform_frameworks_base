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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.model.toGridCells
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec

/**
 * Creates the edit tile list state that is remembered across compositions.
 *
 * Changes to the tiles or columns will recreate the state.
 */
@Composable
fun rememberEditListState(
    tiles: List<SizedTile<EditTileViewModel>>,
    columns: Int,
    largeTilesSpan: Int,
): EditTileListState {
    return remember(tiles, columns) { EditTileListState(tiles, columns, largeTilesSpan) }
}

/** Holds the temporary state of the tile list during a drag movement where we move tiles around. */
class EditTileListState(
    tiles: List<SizedTile<EditTileViewModel>>,
    private val columns: Int,
    private val largeTilesSpan: Int,
) : DragAndDropState {
    private val _draggedCell = mutableStateOf<SizedTile<EditTileViewModel>?>(null)
    override val draggedCell
        get() = _draggedCell.value

    override val dragInProgress: Boolean
        get() = _draggedCell.value != null

    private val _tiles: SnapshotStateList<GridCell> =
        tiles.toGridCells(columns).toMutableStateList()
    val tiles: List<GridCell>
        get() = _tiles.toList()

    fun tileSpecs(): List<TileSpec> {
        return _tiles.filterIsInstance<TileGridCell>().map { it.tile.tileSpec }
    }

    private fun indexOf(tileSpec: TileSpec): Int {
        return _tiles.indexOfFirst { it is TileGridCell && it.tile.tileSpec == tileSpec }
    }

    /** Resize the tile corresponding to the [TileSpec] to [toIcon] */
    fun resizeTile(tileSpec: TileSpec, toIcon: Boolean) {
        val fromIndex = indexOf(tileSpec)
        if (fromIndex != -1) {
            val cell = _tiles[fromIndex] as TileGridCell

            if (cell.isIcon == toIcon) return

            _tiles.removeAt(fromIndex)
            _tiles.add(fromIndex, cell.copy(width = if (toIcon) 1 else largeTilesSpan))
            regenerateGrid(fromIndex)
        }
    }

    override fun isMoving(tileSpec: TileSpec): Boolean {
        return _draggedCell.value?.let { it.tile.tileSpec == tileSpec } ?: false
    }

    override fun onStarted(cell: SizedTile<EditTileViewModel>) {
        _draggedCell.value = cell

        // Add spacers to the grid to indicate where the user can move a tile
        regenerateGrid()
    }

    override fun onMoved(target: Int, insertAfter: Boolean) {
        val draggedTile = _draggedCell.value ?: return

        val fromIndex = indexOf(draggedTile.tile.tileSpec)
        if (fromIndex == target) {
            return
        }

        val insertionIndex = if (insertAfter) target + 1 else target
        if (fromIndex != -1) {
            val cell = _tiles.removeAt(fromIndex)
            regenerateGrid()
            _tiles.add(insertionIndex.coerceIn(0, _tiles.size), cell)
        } else {
            // Add the tile with a temporary row/col which will get reassigned when
            // regenerating spacers
            _tiles.add(insertionIndex.coerceIn(0, _tiles.size), TileGridCell(draggedTile, 0, 0))
        }

        regenerateGrid()
    }

    override fun movedOutOfBounds() {
        val draggedTile = _draggedCell.value ?: return

        _tiles.removeIf { cell ->
            cell is TileGridCell && cell.tile.tileSpec == draggedTile.tile.tileSpec
        }
    }

    override fun onDrop() {
        _draggedCell.value = null

        // Remove the spacers
        regenerateGrid()
    }

    /** Regenerate the list of [GridCell] with their new potential rows */
    private fun regenerateGrid() {
        _tiles.filterIsInstance<TileGridCell>().toGridCells(columns).let {
            _tiles.clear()
            _tiles.addAll(it)
        }
    }

    /**
     * Regenerate the list of [GridCell] with their new potential rows from [fromIndex], leaving
     * cells before that untouched.
     */
    private fun regenerateGrid(fromIndex: Int) {
        val fromRow = _tiles[fromIndex].row
        val (pre, post) = _tiles.partition { it.row < fromRow }
        post.filterIsInstance<TileGridCell>().toGridCells(columns, startingRow = fromRow).let {
            _tiles.clear()
            _tiles.addAll(pre)
            _tiles.addAll(it)
        }
    }
}
