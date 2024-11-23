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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.compose.rememberEditListState
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.ElementKeys.toElementKey
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    private val viewModelFactory: InfiniteGridViewModel.Factory,
    private val tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
) : PaginatableGridLayout {

    @Composable
    override fun SceneScope.TileGrid(tiles: List<TileViewModel>, modifier: Modifier) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }
        val iconTilesViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModel.dynamicIconTilesViewModelFactory.create()
            }
        val columnsWithMediaViewModel =
            rememberViewModel(traceName = "InfiniteGridLAyout.TileGrid") {
                viewModel.columnsWithMediaViewModelFactory.create(LOCATION_QS)
            }

        val columns = columnsWithMediaViewModel.columns
        val largeTilesSpan by iconTilesViewModel.largeTilesSpanState
        val sizedTiles = tiles.map { SizedTileImpl(it, it.spec.width(largeTilesSpan)) }
        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var cellIndex = 0

        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = sizedTiles.fastMap { it.width },
        ) { spanIndex ->
            val it = sizedTiles[spanIndex]
            val column = cellIndex % columns
            cellIndex += it.width
            Tile(
                tile = it.tile,
                iconOnly = iconTilesViewModel.isIconTile(it.tile.spec),
                modifier = Modifier.element(it.tile.spec.toElementKey(spanIndex)),
                squishiness = { squishiness },
                tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                coroutineScope = scope,
                bounceableInfo = bounceables.bounceableInfo(it, spanIndex, column, columns),
                detailsViewModel = detailsViewModel,
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
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val iconTilesViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.dynamicIconTilesViewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val columns = columnsViewModel.columns
        val largeTilesSpan by iconTilesViewModel.largeTilesSpanState
        val largeTiles by iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()

        // Non-current tiles should always be displayed as icon tiles.
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan) {
                tiles.map {
                    SizedTileImpl(
                        it,
                        if (!it.isCurrent || !largeTiles.contains(it.tileSpec)) 1
                        else largeTilesSpan,
                    )
                }
            }

        val (currentTiles, otherTiles) = sizedTiles.partition { it.tile.isCurrent }
        val currentListState = rememberEditListState(currentTiles, columns, largeTilesSpan)
        DefaultEditTileGrid(
            listState = currentListState,
            otherTiles = otherTiles,
            columns = columns,
            modifier = modifier,
            onRemoveTile = onRemoveTile,
            onSetTiles = onSetTiles,
            onResize = iconTilesViewModel::resize,
            onStopEditing = onStopEditing,
            onReset = viewModel::showResetDialog,
            largeTilesSpan = largeTilesSpan,
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

    private fun TileSpec.width(largeSize: Int = iconTilesViewModel.largeTilesSpan.value): Int {
        return if (iconTilesViewModel.isIconTile(this)) 1 else largeSize
    }
}
