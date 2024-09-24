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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.zIndex
import com.android.compose.modifiers.background
import com.android.compose.modifiers.height
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.DragAndDropState
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.dragAndDropRemoveZone
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileList
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileSource
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileArrangementPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ToggleTargetSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.CurrentTilesGridPadding
import com.android.systemui.qs.panels.ui.compose.selection.MutableSelectionState
import com.android.systemui.qs.panels.ui.compose.selection.ResizingHandle
import com.android.systemui.qs.panels.ui.compose.selection.TileWidths
import com.android.systemui.qs.panels.ui.compose.selection.clearSelectionTile
import com.android.systemui.qs.panels.ui.compose.selection.rememberSelectionState
import com.android.systemui.qs.panels.ui.compose.selection.selectableTile
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.res.R
import kotlinx.coroutines.delay

object TileType

@Composable
fun DefaultEditTileGrid(
    listState: EditTileListState,
    otherTiles: List<SizedTile<EditTileViewModel>>,
    columns: Int,
    modifier: Modifier,
    onRemoveTile: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
    onResize: (TileSpec, toIcon: Boolean) -> Unit,
) {
    val currentListState by rememberUpdatedState(listState)
    val selectionState =
        rememberSelectionState(
            onResize = { currentListState.toggleSize(it) },
            onResizeEnd = { spec ->
                // Commit the size currently in the list
                currentListState.isIcon(spec)?.let { onResize(spec, it) }
            },
        )

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Column(
            verticalArrangement =
                spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            AnimatedContent(
                targetState = listState.dragInProgress,
                modifier = Modifier.wrapContentSize(),
                label = "",
            ) { dragIsInProgress ->
                EditGridHeader(Modifier.dragAndDropRemoveZone(listState, onRemoveTile)) {
                    if (dragIsInProgress) {
                        RemoveTileTarget()
                    } else {
                        Text(text = "Hold and drag to rearrange tiles.")
                    }
                }
            }

            CurrentTilesGrid(listState, selectionState, columns, onResize, onSetTiles)

            // Hide available tiles when dragging
            AnimatedVisibility(
                visible = !listState.dragInProgress,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    verticalArrangement =
                        spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
                    modifier = modifier.fillMaxSize(),
                ) {
                    EditGridHeader { Text(text = "Hold and drag to add tiles.") }

                    AvailableTileGrid(otherTiles, selectionState, columns, listState)
                }
            }

            // Drop zone to remove tiles dragged out of the tile grid
            Spacer(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .dragAndDropRemoveZone(listState, onRemoveTile)
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
private fun CurrentTilesGrid(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    columns: Int,
    onResize: (TileSpec, toIcon: Boolean) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
) {
    val currentListState by rememberUpdatedState(listState)
    val tileHeight = CommonTileDefaults.TileHeight
    val totalRows = listState.tiles.lastOrNull()?.row ?: 0
    val totalHeight by
        animateDpAsState(
            gridHeight(totalRows + 1, tileHeight, TileArrangementPadding, CurrentTilesGridPadding),
            label = "QSEditCurrentTilesGridHeight",
        )
    val gridState = rememberLazyGridState()
    var gridContentOffset by remember { mutableStateOf(Offset(0f, 0f)) }

    TileLazyGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(CurrentTilesGridPadding),
        modifier =
            Modifier.fillMaxWidth()
                .height { totalHeight.roundToPx() }
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f),
                    shape = RoundedCornerShape(48.dp),
                )
                .dragAndDropTileList(gridState, { gridContentOffset }, listState) { spec ->
                    onSetTiles(currentListState.tileSpecs())
                    selectionState.select(spec, manual = false)
                }
                .onGloballyPositioned { coordinates ->
                    gridContentOffset = coordinates.positionInRoot()
                }
                .testTag(CURRENT_TILES_GRID_TEST_TAG),
    ) {
        EditTiles(listState.tiles, listState, selectionState) { spec ->
            // Toggle the current size of the tile
            currentListState.isIcon(spec)?.let { onResize(spec, !it) }
        }
    }
}

@Composable
private fun AvailableTileGrid(
    tiles: List<SizedTile<EditTileViewModel>>,
    selectionState: MutableSelectionState,
    columns: Int,
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
                            selectionState = selectionState,
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

fun gridHeight(rows: Int, tileHeight: Dp, tilePadding: Dp, gridPadding: Dp): Dp {
    return ((tileHeight + tilePadding) * rows) - tilePadding + gridPadding * 2
}

private fun GridCell.key(index: Int, dragAndDropState: DragAndDropState): Any {
    return when (this) {
        is TileGridCell -> {
            if (dragAndDropState.isMoving(tile.tileSpec)) index else key
        }
        is SpacerGridCell -> index
    }
}

/**
 * Adds a list of [GridCell] to the lazy grid
 *
 * @param cells the list of [GridCell]
 * @param dragAndDropState the [DragAndDropState] for this grid
 * @param selectionState the [MutableSelectionState] for this grid
 * @param onToggleSize the callback when a tile's size is toggled
 */
fun LazyGridScope.EditTiles(
    cells: List<GridCell>,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
    onToggleSize: (spec: TileSpec) -> Unit,
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
                        selectionState = selectionState,
                        onToggleSize = onToggleSize,
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
    selectionState: MutableSelectionState,
    onToggleSize: (spec: TileSpec) -> Unit,
) {
    val stateDescription = stringResource(id = R.string.accessibility_qs_edit_position, index + 1)
    var selected by remember { mutableStateOf(false) }
    val selectionAlpha by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            label = "QSEditTileSelectionAlpha",
        )

    LaunchedEffect(selectionState.selection?.tileSpec) {
        selectionState.selection?.let {
            // A delay is introduced on automatic selections such as dragged tiles or reflow caused
            // by resizing. This avoids clipping issues on the border and resizing handle, as well
            // as letting the selection animation play correctly.
            if (!it.manual) {
                delay(250)
            }
        }
        selected = selectionState.selection?.tileSpec == cell.tile.tileSpec
    }

    val modifier =
        Modifier.animateItem()
            .semantics(mergeDescendants = true) {
                this.stateDescription = stateDescription
                contentDescription = cell.tile.label.text
                customActions =
                    listOf(
                        // TODO(b/367748260): Add final accessibility actions
                        CustomAccessibilityAction("Toggle size") {
                            onToggleSize(cell.tile.tileSpec)
                            true
                        }
                    )
            }
            .height(CommonTileDefaults.TileHeight)
            .fillMaxWidth()

    val content =
        @Composable {
            EditTile(
                tileViewModel = cell.tile,
                iconOnly = cell.isIcon,
                selectionAlpha = { selectionAlpha },
                modifier =
                    Modifier.fillMaxSize()
                        .selectableTile(cell.tile.tileSpec, selectionState)
                        .dragAndDropTileSource(
                            SizedTileImpl(cell.tile, cell.width),
                            dragAndDropState,
                            selectionState::unSelect,
                        ),
            )
        }

    if (selected) {
        SelectedTile(
            isIcon = cell.isIcon,
            selectionAlpha = { selectionAlpha },
            selectionState = selectionState,
            modifier = modifier.zIndex(2f), // 2f to display this tile over neighbors when dragged
            content = content,
        )
    } else {
        UnselectedTile(
            selectionAlpha = { selectionAlpha },
            selectionState = selectionState,
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun SelectedTile(
    isIcon: Boolean,
    selectionAlpha: () -> Float,
    selectionState: MutableSelectionState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Current base, min and max width of this tile
    var tileWidths: TileWidths? by remember { mutableStateOf(null) }

    // Animated diff between the current width and the resized width of the tile. We can't use
    // animateContentSize here as the tile is sometimes unbounded.
    val remainingOffset by
        animateIntAsState(
            selectionState.resizingState?.let { tileWidths?.base?.minus(it.width) ?: 0 } ?: 0,
            label = "QSEditTileWidthOffset",
        )

    val padding = with(LocalDensity.current) { TileArrangementPadding.roundToPx() }
    Box(
        modifier.onSizeChanged {
            val min = if (isIcon) it.width else (it.width - padding) / 2
            val max = if (isIcon) (it.width * 2) + padding else it.width
            tileWidths = TileWidths(it.width, min, max)
        }
    ) {
        val handle =
            @Composable {
                ResizingHandle(
                    enabled = true,
                    selectionState = selectionState,
                    transition = selectionAlpha,
                    tileWidths = { tileWidths },
                )
            }

        Layout(contents = listOf(content, handle)) {
            (contentMeasurables, handleMeasurables),
            constraints ->
            // Grab the width from the resizing state if a resize is in progress, otherwise fill the
            // max width
            val width =
                selectionState.resizingState?.width ?: (constraints.maxWidth - remainingOffset)
            val contentPlaceable =
                contentMeasurables.first().measure(constraints.copy(maxWidth = width))
            val handlePlaceable = handleMeasurables.first().measure(constraints)

            // Place the dot vertically centered on the right edge
            val handleX = contentPlaceable.width - (handlePlaceable.width / 2)
            val handleY = (contentPlaceable.height / 2) - (handlePlaceable.height / 2)

            layout(constraints.maxWidth, constraints.maxHeight) {
                contentPlaceable.place(0, 0)
                handlePlaceable.place(handleX, handleY)
            }
        }
    }
}

@Composable
private fun UnselectedTile(
    selectionAlpha: () -> Float,
    selectionState: MutableSelectionState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val handle =
        @Composable {
            ResizingHandle(
                enabled = false,
                selectionState = selectionState,
                transition = selectionAlpha,
            )
        }

    Box(modifier) {
        Layout(contents = listOf(content, handle)) {
            (contentMeasurables, handleMeasurables),
            constraints ->
            val contentPlaceable =
                contentMeasurables
                    .first()
                    .measure(constraints.copy(maxWidth = constraints.maxWidth))
            val handlePlaceable = handleMeasurables.first().measure(constraints)

            // Place the dot vertically centered on the right edge
            val handleX = contentPlaceable.width - (handlePlaceable.width / 2)
            val handleY = (contentPlaceable.height / 2) - (handlePlaceable.height / 2)

            layout(constraints.maxWidth, constraints.maxHeight) {
                contentPlaceable.place(0, 0)
                handlePlaceable.place(handleX, handleY)
            }
        }
    }
}

@Composable
private fun AvailableTileGridCell(
    cell: TileGridCell,
    index: Int,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
    modifier: Modifier = Modifier,
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
        EditTileContainer(
            colors = colors,
            modifier =
                Modifier.fillMaxWidth()
                    .height(CommonTileDefaults.TileHeight)
                    .clearSelectionTile(selectionState)
                    .semantics(mergeDescendants = true) {
                        onClick(onClickActionName) { false }
                        this.stateDescription = stateDescription
                    }
                    .dragAndDropTileSource(SizedTileImpl(cell.tile, cell.width), dragAndDropState) {
                        selectionState.unSelect()
                    },
        ) {
            // Icon
            SmallTileContent(
                icon = cell.tile.icon,
                color = colors.icon,
                modifier = Modifier.align(Alignment.Center),
            )
        }
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
    Box(modifier.height(CommonTileDefaults.TileHeight).fillMaxWidth())
}

@Composable
fun EditTile(
    tileViewModel: EditTileViewModel,
    iconOnly: Boolean,
    modifier: Modifier = Modifier,
    colors: TileColors = EditModeTileDefaults.editTileColors(),
    selectionAlpha: () -> Float = { 1f },
) {
    // Animated horizontal alignment from center (0f) to start (-1f)
    val alignmentValue by
        animateFloatAsState(
            targetValue = if (iconOnly) 0f else -1f,
            label = "QSEditTileContentAlignment",
        )
    val alignment by remember {
        derivedStateOf { BiasAlignment(horizontalBias = alignmentValue, verticalBias = 0f) }
    }

    EditTileContainer(colors = colors, selectionAlpha = selectionAlpha, modifier = modifier) {
        // Icon
        Box(Modifier.size(ToggleTargetSize).align(alignment)) {
            SmallTileContent(
                icon = tileViewModel.icon,
                color = colors.icon,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Labels, positioned after the icon
        AnimatedVisibility(visible = !iconOnly, enter = fadeIn(), exit = fadeOut()) {
            LargeTileLabels(
                label = tileViewModel.label.text,
                secondaryLabel = tileViewModel.appName?.text,
                colors = colors,
                modifier = Modifier.padding(start = ToggleTargetSize + TileArrangementPadding),
            )
        }
    }
}

@Composable
private fun EditTileContainer(
    colors: TileColors,
    modifier: Modifier = Modifier,
    selectionAlpha: () -> Float = { 0f },
    selectionColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        Modifier.wrapContentSize().drawWithContent {
            drawContent()
            drawRoundRect(
                SolidColor(selectionColor),
                cornerRadius = CornerRadius(InactiveCornerRadius.toPx()),
                style = Stroke(EditModeTileDefaults.SelectedBorderWidth.toPx()),
                alpha = selectionAlpha(),
            )
        }
    ) {
        Box(
            modifier =
                modifier
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
}

private object EditModeTileDefaults {
    const val PLACEHOLDER_ALPHA = .3f
    val EditGridHeaderHeight = 60.dp
    val SelectedBorderWidth = 2.dp
    val CurrentTilesGridPadding = 8.dp

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
