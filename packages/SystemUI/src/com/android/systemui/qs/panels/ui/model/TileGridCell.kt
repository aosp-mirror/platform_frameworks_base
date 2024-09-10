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

package com.android.systemui.qs.panels.ui.model

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Immutable
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.shared.model.CategoryAndName

/** Represents an item from a grid associated with a row and a span */
interface GridCell {
    val row: Int
    val span: GridItemSpan
}

/**
 * Represents a [EditTileViewModel] from a grid associated with a tile format and the row it's
 * positioned at
 */
@Immutable
data class TileGridCell(
    override val tile: EditTileViewModel,
    override val row: Int,
    override val width: Int,
    override val span: GridItemSpan = GridItemSpan(width)
) : GridCell, SizedTile<EditTileViewModel>, CategoryAndName by tile {
    val key: String = "${tile.tileSpec.spec}-$row"

    constructor(
        sizedTile: SizedTile<EditTileViewModel>,
        row: Int
    ) : this(
        tile = sizedTile.tile,
        row = row,
        width = sizedTile.width,
    )
}

/** Represents an empty space used to fill incomplete rows. Will always display as a 1x1 tile */
@Immutable
data class SpacerGridCell(
    override val row: Int,
    override val span: GridItemSpan = GridItemSpan(1)
) : GridCell

fun List<SizedTile<EditTileViewModel>>.toGridCells(
    columns: Int,
    includeSpacers: Boolean = false
): List<GridCell> {
    return splitInRowsSequence(this, columns)
        .flatMapIndexed { rowIndex, sizedTiles ->
            val row: List<GridCell> = sizedTiles.map { TileGridCell(it, rowIndex) }

            if (includeSpacers) {
                // Fill the incomplete rows with spacers
                val numSpacers = columns - sizedTiles.sumOf { it.width }
                row.toMutableList().apply { repeat(numSpacers) { add(SpacerGridCell(rowIndex)) } }
            } else {
                row
            }
        }
        .toList()
}
