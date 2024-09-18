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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMap
import com.android.compose.modifiers.background
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.DragAndDropState
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.dragAndDropRemoveZone
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileList
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileSource
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.res.R

object TileType

@Composable
fun DefaultEditTileGrid(
    currentListState: EditTileListState,
    otherTiles: List<SizedTile<EditTileViewModel>>,
    columns: Int,
    modifier: Modifier,
    onAddTile: (TileSpec, Int) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
    onResize: (TileSpec) -> Unit,
) {
    val addTileToEnd: (TileSpec) -> Unit by rememberUpdatedState {
        onAddTile(it, CurrentTilesInteractor.POSITION_AT_END)
    }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Column(
            verticalArrangement =
                spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            AnimatedContent(
                targetState = currentListState.dragInProgress,
                modifier = Modifier.wrapContentSize(),
                label = "",
            ) { dragIsInProgress ->
                EditGridHeader(Modifier.dragAndDropRemoveZone(currentListState, onRemoveTile)) {
                    if (dragIsInProgress) {
                        RemoveTileTarget()
                    } else {
                        Text(text = "Hold and drag to rearrange tiles.")
                    }
                }
            }

            CurrentTilesGrid(currentListState, columns, onRemoveTile, onResize, onSetTiles)

            // Hide available tiles when dragging
            AnimatedVisibility(
                visible = !currentListState.dragInProgress,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    verticalArrangement =
                        spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
                    modifier = modifier.fillMaxSize(),
                ) {
                    EditGridHeader { Text(text = "Hold and drag to add tiles.") }

                    AvailableTileGrid(otherTiles, columns, addTileToEnd, currentListState)
                }
            }

            // Drop zone to remove tiles dragged out of the tile grid
            Spacer(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .dragAndDropRemoveZone(currentListState, onRemoveTile)
            )
        }
    }
}

@Composable
private fun EditGridHeader(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onBackground.copy(alpha = .5f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.fillMaxWidth().height(EditModeTileDefaults.EditGridHeaderHeight),
        ) {
            content()
        }
    }
}

@Composable
private fun RemoveTileTarget() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement(),
        modifier =
            Modifier.fillMaxHeight()
                .border(1.dp, LocalContentColor.current, shape = CircleShape)
                .padding(10.dp),
    ) {
        Icon(imageVector = Icons.Default.Clear, contentDescription = null)
        Text(text = "Remove")
    }
}

@Composable
private fun CurrentTilesContainer(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f),
                shape = RoundedCornerShape(48.dp),
            )
            .padding(dimensionResource(R.dimen.qs_tile_margin_vertical))
    ) {
        content()
    }
}

@Composable
private fun CurrentTilesGrid(
    listState: EditTileListState,
    columns: Int,
    onClick: (TileSpec) -> Unit,
    onResize: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
) {
    val currentListState by rememberUpdatedState(listState)
    val tilePadding = CommonTileDefaults.TileArrangementPadding

    CurrentTilesContainer {
        val tileHeight = CommonTileDefaults.TileHeight
        val totalRows = listState.tiles.lastOrNull()?.row ?: 0
        val totalHeight = gridHeight(totalRows + 1, tileHeight, tilePadding)
        val gridState = rememberLazyGridState()
        var gridContentOffset by remember { mutableStateOf(Offset(0f, 0f)) }

        TileLazyGrid(
            state = gridState,
            modifier =
                Modifier.height(totalHeight)
                    .dragAndDropTileList(gridState, gridContentOffset, listState) {
                        onSetTiles(currentListState.tileSpecs())
                    }
                    .onGloballyPositioned { coordinates ->
                        gridContentOffset = coordinates.positionInRoot()
                    }
                    .testTag(CURRENT_TILES_GRID_TEST_TAG),
            columns = GridCells.Fixed(columns),
        ) {
            EditTiles(listState.tiles, onClick, listState, onResize = onResize)
        }
    }
}

@Composable
private fun AvailableTileGrid(
    tiles: List<SizedTile<EditTileViewModel>>,
    columns: Int,
    onClick: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
) {
    // Available tiles aren't visible during drag and drop, so the row isn't needed
    val groupedTiles =
        remember(tiles.fastMap { it.tile.category }, tiles.fastMap { it.tile.label }) {
            groupAndSort(tiles.fastMap { TileGridCell(it, 0) })
        }
    val labelColors = EditModeTileDefaults.editTileColors()

    // Available tiles
    Column(
        verticalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        horizontalAlignment = Alignment.Start,
        modifier =
            Modifier.fillMaxWidth().wrapContentHeight().testTag(AVAILABLE_TILES_GRID_TEST_TAG),
    ) {
        groupedTiles.forEach { (category, tiles) ->
            Text(
                text = category.label.load() ?: "",
                fontSize = 20.sp,
                color = labelColors.label,
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Color.Black)
                        .padding(start = 16.dp, bottom = 8.dp, top = 8.dp),
            )
            tiles.chunked(columns).forEach { row ->
                Row(
                    horizontalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                ) {
                    row.forEachIndexed { index, tileGridCell ->
                        AvailableTileGridCell(
                            cell = tileGridCell,
                            index = index,
                            dragAndDropState = dragAndDropState,
                            onClick = onClick,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    // Spacers for incomplete rows
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

fun gridHeight(rows: Int, tileHeight: Dp, padding: Dp): Dp {
    return ((tileHeight + padding) * rows) - padding
}

private fun GridCell.key(index: Int, dragAndDropState: DragAndDropState): Any {
    return when (this) {
        is TileGridCell -> {
            if (dragAndDropState.isMoving(tile.tileSpec)) index else key
        }
        is SpacerGridCell -> index
    }
}

fun LazyGridScope.EditTiles(
    cells: List<GridCell>,
    onClick: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
    onResize: (TileSpec) -> Unit = {},
) {
    items(
        count = cells.size,
        key = { cells[it].key(it, dragAndDropState) },
        span = { cells[it].span },
        contentType = { TileType },
    ) { index ->
        when (val cell = cells[index]) {
            is TileGridCell ->
                if (dragAndDropState.isMoving(cell.tile.tileSpec)) {
                    // If the tile is being moved, replace it with a visible spacer
                    SpacerGridCell(
                        Modifier.background(
                                color = MaterialTheme.colorScheme.secondary,
                                alpha = { EditModeTileDefaults.PLACEHOLDER_ALPHA },
                                shape = RoundedCornerShape(InactiveCornerRadius),
                            )
                            .animateItem()
                    )
                } else {
                    TileGridCell(
                        cell = cell,
                        index = index,
                        dragAndDropState = dragAndDropState,
                        onClick = onClick,
                        onResize = onResize,
                    )
                }
            is SpacerGridCell -> SpacerGridCell()
        }
    }
}

@Composable
private fun LazyGridItemScope.TileGridCell(
    cell: TileGridCell,
    index: Int,
    dragAndDropState: DragAndDropState,
    onClick: (TileSpec) -> Unit,
    onResize: (TileSpec) -> Unit = {},
) {
    val onClickActionName = stringResource(id = R.string.accessibility_qs_edit_remove_tile_action)
    val stateDescription = stringResource(id = R.string.accessibility_qs_edit_position, index + 1)

    EditTile(
        tileViewModel = cell.tile,
        iconOnly = cell.isIcon,
        modifier =
            Modifier.animateItem()
                .semantics(mergeDescendants = true) {
                    onClick(onClickActionName) { false }
                    this.stateDescription = stateDescription
                }
                .dragAndDropTileSource(
                    SizedTileImpl(cell.tile, cell.width),
                    dragAndDropState,
                    onClick,
                    onResize,
                ),
    )
}

@Composable
private fun AvailableTileGridCell(
    cell: TileGridCell,
    index: Int,
    dragAndDropState: DragAndDropState,
    modifier: Modifier = Modifier,
    onClick: (TileSpec) -> Unit,
) {
    val onClickActionName = stringResource(id = R.string.accessibility_qs_edit_tile_add_action)
    val stateDescription = stringResource(id = R.string.accessibility_qs_edit_position, index + 1)
    val colors = EditModeTileDefaults.editTileColors()

    // Displays the tile as an icon tile with the label underneath
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(CommonTileDefaults.TilePadding, Alignment.Top),
        modifier = modifier,
    ) {
        EditTile(
            tileViewModel = cell.tile,
            iconOnly = true,
            colors = colors,
            modifier =
                Modifier.semantics(mergeDescendants = true) {
                        onClick(onClickActionName) { false }
                        this.stateDescription = stateDescription
                    }
                    .dragAndDropTileSource(
                        SizedTileImpl(cell.tile, cell.width),
                        dragAndDropState,
                        onTap = onClick,
                    ),
        )
        Box(Modifier.fillMaxSize()) {
            Text(
                cell.tile.label.text,
                maxLines = 2,
                color = colors.label,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun SpacerGridCell(modifier: Modifier = Modifier) {
    // By default, spacers are invisible and exist purely to catch drag movements
    Box(modifier.height(CommonTileDefaults.TileHeight).fillMaxWidth().tilePadding())
}

@Composable
fun EditTile(
    tileViewModel: EditTileViewModel,
    iconOnly: Boolean,
    modifier: Modifier = Modifier,
    colors: TileColors = EditModeTileDefaults.editTileColors(),
) {
    EditTileContainer(colors = colors, modifier = modifier) {
        if (iconOnly) {
            SmallTileContent(
                icon = tileViewModel.icon,
                color = colors.icon,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LargeTileContent(
                label = tileViewModel.label.text,
                secondaryLabel = tileViewModel.appName?.text,
                icon = tileViewModel.icon,
                colors = colors,
            )
        }
    }
}

@Composable
private fun EditTileContainer(
    colors: TileColors,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(CommonTileDefaults.TileHeight)
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        SolidColor(colors.background),
                        cornerRadius = CornerRadius(InactiveCornerRadius.toPx()),
                    )
                }
                .tilePadding(),
        content = content,
    )
}

private object EditModeTileDefaults {
    const val PLACEHOLDER_ALPHA = .3f
    val EditGridHeaderHeight = 60.dp

    @Composable
    fun editTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconBackground = MaterialTheme.colorScheme.surfaceVariant,
            label = MaterialTheme.colorScheme.onSurfaceVariant,
            secondaryLabel = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

private const val CURRENT_TILES_GRID_TEST_TAG = "CurrentTilesGrid"
private const val AVAILABLE_TILES_GRID_TEST_TAG = "AvailableTilesGrid"
