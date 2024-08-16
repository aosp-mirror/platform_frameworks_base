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

/**
 * Represents a [EditTileViewModel] from a grid associated with a tile format and the row it's
 * positioned at
 */
@Immutable
data class TileGridCell(
    override val tile: EditTileViewModel,
    val row: Int,
    val key: String = "${tile.tileSpec.spec}-$row",
    override val width: Int,
) : SizedTile<EditTileViewModel> {
    constructor(
        sizedTile: SizedTile<EditTileViewModel>,
        row: Int
    ) : this(
        tile = sizedTile.tile,
        row = row,
        width = sizedTile.width,
    )

    val span = GridItemSpan(width)
}

fun List<SizedTile<EditTileViewModel>>.toTileGridCells(columns: Int): List<TileGridCell> {
    return splitInRowsSequence(this, columns)
        .flatMapIndexed { index, sizedTiles -> sizedTiles.map { TileGridCell(it, index) } }
        .toList()
}
