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

package com.android.systemui.qs.panels.ui.compose

import android.graphics.drawable.Animatable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.text.TextUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.compose.modifiers.background
import com.android.compose.modifiers.thenIf
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import java.util.function.Supplier
import kotlinx.coroutines.delay

object TileType

@Composable
fun Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    showLabels: Boolean = false,
    modifier: Modifier,
) {
    val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
    val uiState = remember(state) { state.toUiState() }
    val colors = TileDefaults.getColorForState(uiState)

    // TODO(b/361789146): Draw the shapes instead of clipping
    val tileShape = TileDefaults.animateTileShape(uiState.state)

    TileContainer(
        colors = colors,
        showLabels = showLabels,
        label = uiState.label,
        iconOnly = iconOnly,
        shape = tileShape,
        clickEnabled = true,
        onClick = tile::onClick,
        onLongClick = tile::onLongClick,
        modifier = modifier.height(tileHeight()),
    ) {
        val icon = getTileIcon(icon = uiState.icon)
        if (iconOnly) {
            TileIcon(icon = icon, color = colors.icon, modifier = Modifier.align(Alignment.Center))
        } else {
            val iconShape = TileDefaults.animateIconShape(uiState.state)
            LargeTileContent(
                label = uiState.label,
                secondaryLabel = uiState.secondaryLabel,
                icon = icon,
                colors = colors,
                iconShape = iconShape,
                toggleClickSupported = state.handlesSecondaryClick,
                onClick = {
                    if (state.handlesSecondaryClick) {
                        tile.onSecondaryClick()
                    }
                },
                onLongClick = { tile.onLongClick(it) },
            )
        }
    }
}

@Composable
private fun TileContainer(
    colors: TileColors,
    showLabels: Boolean,
    label: String,
    iconOnly: Boolean,
    shape: Shape,
    clickEnabled: Boolean = false,
    onClick: (Expandable) -> Unit = {},
    onLongClick: (Expandable) -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Expandable) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement =
            spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin), Alignment.Top),
        modifier = modifier,
    ) {
        val backgroundColor =
            if (iconOnly) {
                colors.iconBackground
            } else {
                colors.background
            }
        Expandable(
            color = backgroundColor,
            shape = shape,
            modifier = Modifier.height(tileHeight()).clip(shape)
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .thenIf(clickEnabled) {
                            Modifier.combinedClickable(
                                onClick = { onClick(it) },
                                onLongClick = { onLongClick(it) }
                            )
                        }
                        .tilePadding(),
            ) {
                content(it)
            }
        }

        if (showLabels && iconOnly) {
            Text(
                label,
                maxLines = 2,
                color = colors.label,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LargeTileContent(
    label: String,
    secondaryLabel: String?,
    icon: Icon,
    colors: TileColors,
    iconShape: Shape,
    toggleClickSupported: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement()
    ) {
        // Icon
        Box(
            modifier =
                Modifier.size(TileDefaults.ToggleTargetSize).thenIf(toggleClickSupported) {
                    Modifier.clip(iconShape)
                        .background(colors.iconBackground, { 1f })
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
        ) {
            TileIcon(icon = icon, color = colors.icon, modifier = Modifier.align(Alignment.Center))
        }

        // Labels
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                label,
                color = colors.label,
                modifier = Modifier.tileMarquee(),
            )
            if (!TextUtils.isEmpty(secondaryLabel)) {
                Text(
                    secondaryLabel ?: "",
                    color = colors.secondaryLabel,
                    modifier = Modifier.tileMarquee(),
                )
            }
        }
    }
}

private fun Modifier.tileMarquee(): Modifier {
    return basicMarquee(
        iterations = 1,
        initialDelayMillis = 200,
    )
}

@Composable
fun TileLazyGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    columns: GridCells,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        verticalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical)),
        horizontalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_horizontal)),
        modifier = modifier,
        content = content,
    )
}

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
    val tilePadding = dimensionResource(R.dimen.qs_tile_margin_vertical)

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Column(
            verticalArrangement =
                spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            AnimatedContent(
                targetState = currentListState.dragInProgress,
                modifier = Modifier.wrapContentSize()
            ) { dragIsInProgress ->
                EditGridHeader(Modifier.dragAndDropRemoveZone(currentListState, onRemoveTile)) {
                    if (dragIsInProgress) {
                        RemoveTileTarget()
                    } else {
                        Text(text = "Hold and drag to rearrange tiles.")
                    }
                }
            }

            CurrentTilesGrid(
                currentListState,
                columns,
                tilePadding,
                onRemoveTile,
                onResize,
                onSetTiles,
            )

            // Hide available tiles when dragging
            AnimatedVisibility(
                visible = !currentListState.dragInProgress,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    verticalArrangement =
                        spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
                    modifier = modifier.fillMaxSize()
                ) {
                    EditGridHeader { Text(text = "Hold and drag to add tiles.") }

                    AvailableTileGrid(
                        otherTiles,
                        columns,
                        tilePadding,
                        addTileToEnd,
                        currentListState,
                    )
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
    content: @Composable BoxScope.() -> Unit
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onBackground.copy(alpha = .5f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.fillMaxWidth().height(EditModeTileDefaults.EditGridHeaderHeight)
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
                .padding(10.dp)
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
    tilePadding: Dp,
    onClick: (TileSpec) -> Unit,
    onResize: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
) {
    val currentListState by rememberUpdatedState(listState)

    CurrentTilesContainer {
        val tileHeight = tileHeight()
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
            columns = GridCells.Fixed(columns)
        ) {
            editTiles(
                listState.tiles,
                ClickAction.REMOVE,
                onClick,
                listState,
                onResize = onResize,
                indicatePosition = true,
            )
        }
    }
}

@Composable
private fun AvailableTileGrid(
    tiles: List<SizedTile<EditTileViewModel>>,
    columns: Int,
    tilePadding: Dp,
    onClick: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
) {
    // Available tiles aren't visible during drag and drop, so the row isn't needed
    val (otherTilesStock, otherTilesCustom) =
        tiles.map { TileGridCell(it, 0) }.partition { it.tile.appName == null }
    val availableTileHeight = tileHeight(true)
    val availableGridHeight = gridHeight(tiles.size, availableTileHeight, columns, tilePadding)

    // Available tiles
    TileLazyGrid(
        modifier = Modifier.height(availableGridHeight).testTag(AVAILABLE_TILES_GRID_TEST_TAG),
        columns = GridCells.Fixed(columns)
    ) {
        editTiles(
            otherTilesStock,
            ClickAction.ADD,
            onClick,
            dragAndDropState = dragAndDropState,
            showLabels = true,
        )
        editTiles(
            otherTilesCustom,
            ClickAction.ADD,
            onClick,
            dragAndDropState = dragAndDropState,
            showLabels = true,
        )
    }
}

fun gridHeight(nTiles: Int, tileHeight: Dp, columns: Int, padding: Dp): Dp {
    val rows = (nTiles + columns - 1) / columns
    return gridHeight(rows, tileHeight, padding)
}

fun gridHeight(rows: Int, tileHeight: Dp, padding: Dp): Dp {
    return ((tileHeight + padding) * rows) - padding
}

private fun GridCell.key(index: Int, dragAndDropState: DragAndDropState): Any {
    return if (this is TileGridCell && !dragAndDropState.isMoving(tile.tileSpec)) {
        key
    } else {
        index
    }
}

fun LazyGridScope.editTiles(
    cells: List<GridCell>,
    clickAction: ClickAction,
    onClick: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
    onResize: (TileSpec) -> Unit = {},
    showLabels: Boolean = false,
    indicatePosition: Boolean = false,
) {
    items(
        count = cells.size,
        key = { cells[it].key(it, dragAndDropState) },
        span = { cells[it].span },
        contentType = { TileType }
    ) { index ->
        when (val cell = cells[index]) {
            is TileGridCell ->
                if (dragAndDropState.isMoving(cell.tile.tileSpec)) {
                    // If the tile is being moved, replace it with a visible spacer
                    SpacerGridCell(
                        Modifier.background(
                                color = MaterialTheme.colorScheme.secondary,
                                alpha = { EditModeTileDefaults.PLACEHOLDER_ALPHA },
                                shape = RoundedCornerShape(TileDefaults.InactiveCornerRadius)
                            )
                            .animateItem()
                    )
                } else {
                    TileGridCell(
                        cell = cell,
                        index = index,
                        dragAndDropState = dragAndDropState,
                        clickAction = clickAction,
                        onClick = onClick,
                        onResize = onResize,
                        showLabels = showLabels,
                        indicatePosition = indicatePosition
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
    clickAction: ClickAction,
    onClick: (TileSpec) -> Unit,
    onResize: (TileSpec) -> Unit = {},
    showLabels: Boolean = false,
    indicatePosition: Boolean = false,
) {
    val tileHeight = tileHeight(cell.isIcon && showLabels)
    val onClickActionName =
        when (clickAction) {
            ClickAction.ADD -> stringResource(id = R.string.accessibility_qs_edit_tile_add_action)
            ClickAction.REMOVE ->
                stringResource(id = R.string.accessibility_qs_edit_remove_tile_action)
        }
    val stateDescription =
        if (indicatePosition) {
            stringResource(id = R.string.accessibility_qs_edit_position, index + 1)
        } else {
            ""
        }
    EditTile(
        tileViewModel = cell.tile,
        iconOnly = cell.isIcon,
        showLabels = showLabels,
        modifier =
            Modifier.height(tileHeight)
                .animateItem()
                .semantics {
                    onClick(onClickActionName) { false }
                    this.stateDescription = stateDescription
                }
                .dragAndDropTileSource(
                    SizedTileImpl(cell.tile, cell.width),
                    onClick,
                    onResize,
                    dragAndDropState,
                )
    )
}

@Composable
private fun SpacerGridCell(modifier: Modifier = Modifier) {
    // By default, spacers are invisible and exist purely to catch drag movements
    Box(modifier.height(tileHeight()).fillMaxWidth().tilePadding())
}

@Composable
fun EditTile(
    tileViewModel: EditTileViewModel,
    iconOnly: Boolean,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = tileViewModel.label.load() ?: tileViewModel.tileSpec.spec
    val colors = TileDefaults.inactiveTileColors()

    TileContainer(
        colors = colors,
        showLabels = showLabels,
        label = label,
        iconOnly = iconOnly,
        shape = RoundedCornerShape(TileDefaults.InactiveCornerRadius),
        modifier = modifier,
    ) {
        if (iconOnly) {
            TileIcon(
                icon = tileViewModel.icon,
                color = colors.icon,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LargeTileContent(
                label = label,
                secondaryLabel = tileViewModel.appName?.load(),
                icon = tileViewModel.icon,
                colors = colors,
                iconShape = RoundedCornerShape(TileDefaults.InactiveCornerRadius),
            )
        }
    }
}

enum class ClickAction {
    ADD,
    REMOVE,
}

@Composable
private fun getTileIcon(icon: Supplier<QSTile.Icon?>): Icon {
    val context = LocalContext.current
    return icon.get()?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(context), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun TileIcon(
    icon: Icon,
    color: Color,
    animateToEnd: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val iconModifier = modifier.size(TileDefaults.IconSize)
    val context = LocalContext.current
    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> context.getDrawable(icon.res)
            }
        }
    if (loadedDrawable !is Animatable) {
        Icon(
            icon = icon,
            tint = color,
            modifier = iconModifier,
        )
    } else if (icon is Icon.Resource) {
        val image = AnimatedImageVector.animatedVectorResource(id = icon.res)
        val painter =
            if (animateToEnd) {
                rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = true)
            } else {
                var atEnd by remember(icon.res) { mutableStateOf(false) }
                LaunchedEffect(key1 = icon.res) {
                    delay(350)
                    atEnd = true
                }
                rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = atEnd)
            }
        Image(
            painter = painter,
            contentDescription = icon.contentDescription?.load(),
            colorFilter = ColorFilter.tint(color = color),
            modifier = iconModifier
        )
    }
}

private fun Modifier.tilePadding(): Modifier {
    return padding(TileDefaults.TilePadding)
}

private fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = TileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

@Composable
fun tileHeight(iconWithLabel: Boolean = false): Dp {
    return if (iconWithLabel) {
        TileDefaults.IconTileWithLabelHeight
    } else {
        TileDefaults.TileHeight
    }
}

private data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

private object EditModeTileDefaults {
    const val PLACEHOLDER_ALPHA = .3f
    val EditGridHeaderHeight = 60.dp
}

private object TileDefaults {
    val InactiveCornerRadius = 50.dp
    val ActiveIconCornerRadius = 16.dp
    val ActiveTileCornerRadius = 24.dp

    val ToggleTargetSize = 56.dp
    val IconSize = 24.dp

    val TilePadding = 8.dp
    val TileArrangementPadding = 6.dp

    val TileHeight = 72.dp
    val IconTileWithLabelHeight = 140.dp

    /** An active tile without dual target uses the active color as background */
    @Composable
    fun activeTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    /** An active tile with dual target only show the active color on the icon */
    @Composable
    fun activeDualTargetTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurfaceVariant,
            secondaryLabel = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconBackground = MaterialTheme.colorScheme.surfaceVariant,
            label = MaterialTheme.colorScheme.onSurfaceVariant,
            secondaryLabel = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    @Composable
    fun unavailableTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.surface,
            iconBackground = MaterialTheme.colorScheme.surface,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    fun getColorForState(uiState: TileUiState): TileColors {
        return when (uiState.state) {
            STATE_ACTIVE -> {
                if (uiState.handlesSecondaryClick) {
                    activeDualTargetTileColors()
                } else {
                    activeTileColors()
                }
            }
            STATE_INACTIVE -> inactiveTileColors()
            else -> unavailableTileColors()
        }
    }

    @Composable
    fun animateIconShape(state: Int): Shape {
        return animateShape(
            state = state,
            activeCornerRadius = ActiveIconCornerRadius,
            label = "QSTileCornerRadius",
        )
    }

    @Composable
    fun animateTileShape(state: Int): Shape {
        return animateShape(
            state = state,
            activeCornerRadius = ActiveTileCornerRadius,
            label = "QSTileIconCornerRadius",
        )
    }

    @Composable
    fun animateShape(state: Int, activeCornerRadius: Dp, label: String): Shape {
        val animatedCornerRadius by
            animateDpAsState(
                targetValue =
                    if (state == STATE_ACTIVE) {
                        activeCornerRadius
                    } else {
                        InactiveCornerRadius
                    },
                label = label
            )
        return RoundedCornerShape(animatedCornerRadius)
    }
}

private const val CURRENT_TILES_GRID_TEST_TAG = "CurrentTilesGrid"
private const val AVAILABLE_TILES_GRID_TEST_TAG = "AvailableTilesGrid"
