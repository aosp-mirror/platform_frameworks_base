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

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.TileRow
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.FixedColumnsSizeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class StretchedGridLayout
@Inject
constructor(
    private val iconTilesViewModel: IconTilesViewModel,
    private val gridSizeViewModel: FixedColumnsSizeViewModel,
) : GridLayout {

    @Composable
    override fun TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        editModeStart: () -> Unit,
    ) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }

        // Tile widths [normal|stretched]
        // Icon [3 | 4]
        // Large [6 | 8]
        val columns = 12
        val stretchedTiles =
            remember(tiles) {
                val sizedTiles =
                    tiles.map {
                        SizedTile(
                            it,
                            if (iconTilesViewModel.isIconTile(it.spec)) {
                                3
                            } else {
                                6
                            }
                        )
                    }
                splitInRows(sizedTiles, columns)
            }

        TileLazyGrid(columns = GridCells.Fixed(columns), modifier = modifier) {
            items(stretchedTiles.size, span = { GridItemSpan(stretchedTiles[it].width) }) { index ->
                Tile(
                    tile = stretchedTiles[index].tile,
                    iconOnly = iconTilesViewModel.isIconTile(stretchedTiles[index].tile.spec),
                    modifier = Modifier.height(dimensionResource(id = R.dimen.qs_tile_height))
                )
            }
        }
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit
    ) {
        val columns by gridSizeViewModel.columns.collectAsStateWithLifecycle()

        DefaultEditTileGrid(
            tiles = tiles,
            isIconOnly = iconTilesViewModel::isIconTile,
            columns = GridCells.Fixed(columns),
            modifier = modifier,
            onAddTile = onAddTile,
            onRemoveTile = onRemoveTile,
        )
    }

    private fun splitInRows(
        tiles: List<SizedTile<TileViewModel>>,
        columns: Int
    ): List<SizedTile<TileViewModel>> {
        val row = TileRow<TileViewModel>(columns)

        return buildList {
            for (tile in tiles) {
                if (row.maybeAddTile(tile)) {
                    if (row.isFull()) {
                        // Row is full, no need to stretch tiles
                        addAll(row.tiles)
                        row.clear()
                    }
                } else {
                    if (row.isFull()) {
                        addAll(row.tiles)
                    } else {
                        // Stretching tiles when row isn't full
                        addAll(row.tiles.map { it.copy(width = it.width + (it.width / 3)) })
                    }
                    row.clear()
                    row.maybeAddTile(tile)
                }
            }
            addAll(row.tiles)
        }
    }
}
