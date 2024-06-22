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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.FixedColumnsSizeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val iconTilesViewModel: IconTilesViewModel,
    private val gridSizeViewModel: FixedColumnsSizeViewModel,
) : PaginatableGridLayout {

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
        val columns by gridSizeViewModel.columns.collectAsStateWithLifecycle()

        TileLazyGrid(modifier = modifier, columns = GridCells.Fixed(columns)) {
            items(tiles.size, span = { index -> GridItemSpan(tiles[index].spec.width()) }) { index
                ->
                Tile(
                    tile = tiles[index],
                    iconOnly = iconTilesViewModel.isIconTile(tiles[index].spec),
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
        onRemoveTile: (TileSpec) -> Unit,
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

    override fun splitIntoPages(
        tiles: List<TileViewModel>,
        rows: Int,
        columns: Int,
    ): List<List<TileViewModel>> {

        return PaginatableGridLayout.splitInRows(
                tiles.map { SizedTile(it, it.spec.width()) },
                columns,
            )
            .chunked(rows)
            .map { it.flatten().map { it.tile } }
    }

    private fun TileSpec.width(): Int {
        return if (iconTilesViewModel.isIconTile(this)) 1 else 2
    }
}
