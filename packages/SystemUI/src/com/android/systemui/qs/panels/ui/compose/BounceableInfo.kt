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

import com.android.compose.animation.Bounceable
import com.android.systemui.grid.ui.compose.SpannedGridState
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel

data class BounceableInfo(
    val bounceable: BounceableTileViewModel,
    val previousTile: Bounceable?,
    val nextTile: Bounceable?,
    val bounceEnd: Boolean,
)

fun List<Pair<GridCell, BounceableTileViewModel>>.bounceableInfo(
    index: Int,
    columns: Int,
): BounceableInfo {
    val cell = this[index].first as TileGridCell
    // Only look for neighbor bounceables if they are on the same row
    val onLastColumn = cell.onLastColumn(cell.column, columns)
    val previousTile = getOrNull(index - 1)?.takeIf { it.first.row == cell.row }
    val nextTile = getOrNull(index + 1)?.takeIf { it.first.row == cell.row }
    return BounceableInfo(this[index].second, previousTile?.second, nextTile?.second, !onLastColumn)
}

inline fun List<SizedTile<TileViewModel>>.forEachWithBounceables(
    positions: List<SpannedGridState.Position>,
    bounceables: List<BounceableTileViewModel>,
    columns: Int,
    action: (index: Int, tile: SizedTile<TileViewModel>, bounceableInfo: BounceableInfo) -> Unit,
) {
    this.forEachIndexed { index, tile ->
        val position = positions.getOrNull(index)
        val onLastColumn = position?.column == columns - tile.width
        val previousBounceable =
            bounceables.getOrNull(index - 1)?.takeIf {
                position != null && positions.getOrNull(index - 1)?.row == position.row
            }
        val nextBounceable =
            bounceables.getOrNull(index + 1)?.takeIf {
                position != null && positions.getOrNull(index + 1)?.row == position.row
            }
        val bounceableInfo =
            BounceableInfo(
                bounceable = bounceables[index],
                previousTile = previousBounceable,
                nextTile = nextBounceable,
                bounceEnd = !onLastColumn,
            )
        action(index, tile, bounceableInfo)
    }
}

private fun <T> SizedTile<T>.onLastColumn(column: Int, columns: Int): Boolean {
    return column == columns - width
}
