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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.rememberEditListState
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.FixedColumnsSizeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.ElementKeys.toElementKey
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
    override fun SceneScope.TileGrid(
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
        val sizedTiles = tiles.map { SizedTileImpl(it, it.spec.width()) }

        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = sizedTiles.fastMap { it.width },
        ) { spanIndex ->
            val it = sizedTiles[spanIndex]
            Tile(
                tile = it.tile,
                iconOnly = iconTilesViewModel.isIconTile(it.tile.spec),
                modifier = Modifier.element(it.tile.spec.toElementKey(spanIndex)),
            )
        }
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
    ) {
        val columns by gridSizeViewModel.columns.collectAsStateWithLifecycle()
        val largeTiles by iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()

        // Non-current tiles should always be displayed as icon tiles.
        val sizedTiles =
            remember(tiles, largeTiles) {
                tiles.map {
                    SizedTileImpl(
                        it,
                        if (!it.isCurrent || !largeTiles.contains(it.tileSpec)) 1 else 2,
                    )
                }
            }

        val (currentTiles, otherTiles) = sizedTiles.partition { it.tile.isCurrent }
        val currentListState = rememberEditListState(currentTiles, columns)
        DefaultEditTileGrid(
            listState = currentListState,
            otherTiles = otherTiles,
            columns = columns,
            modifier = modifier,
            onRemoveTile = onRemoveTile,
            onSetTiles = onSetTiles,
            onResize = iconTilesViewModel::resize,
        )
    }

    override fun splitIntoPages(
        tiles: List<TileViewModel>,
        rows: Int,
        columns: Int,
    ): List<List<TileViewModel>> {

        return PaginatableGridLayout.splitInRows(
                tiles.map { SizedTileImpl(it, it.spec.width()) },
                columns,
            )
            .chunked(rows)
            .map { it.flatten().map { it.tile } }
    }

    private fun TileSpec.width(): Int {
        return if (iconTilesViewModel.isIconTile(this)) 1 else 2
    }
}
