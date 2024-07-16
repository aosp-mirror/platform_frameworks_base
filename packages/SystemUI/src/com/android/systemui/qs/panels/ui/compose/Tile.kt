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
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
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
import com.android.compose.modifiers.thenIf
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.TileRow
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import java.util.function.Supplier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay

object TileType

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    showLabels: Boolean = false,
    modifier: Modifier,
) {
    val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
    val uiState = remember(state) { state.toUiState() }
    val colors = TileDefaults.getColorForState(uiState.state)

    TileContainer(
        colors = colors,
        showLabels = showLabels,
        label = uiState.label,
        iconOnly = iconOnly,
        clickEnabled = true,
        onClick = tile::onClick,
        onLongClick = tile::onLongClick,
        modifier = modifier,
    ) {
        val icon = getTileIcon(icon = uiState.icon)
        if (iconOnly) {
            TileIcon(icon = icon, color = colors.icon, modifier = Modifier.align(Alignment.Center))
        } else {
            LargeTileContent(
                label = uiState.label,
                secondaryLabel = uiState.secondaryLabel,
                icon = icon,
                colors = colors,
                clickEnabled = true,
                onClick = tile::onSecondaryClick,
                onLongClick = tile::onLongClick,
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
    clickEnabled: Boolean = false,
    onClick: (Expandable) -> Unit = {},
    onLongClick: (Expandable) -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
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
            shape = TileDefaults.TileShape,
            modifier =
                Modifier.height(dimensionResource(id = R.dimen.qs_tile_height))
                    .clip(TileDefaults.TileShape)
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
                content()
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
    clickEnabled: Boolean = false,
    onClick: (Expandable) -> Unit = {},
    onLongClick: (Expandable) -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement()
    ) {
        Expandable(
            color = colors.iconBackground,
            shape = TileDefaults.TileShape,
            modifier = Modifier.fillMaxHeight().aspectRatio(1f)
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize().clip(TileDefaults.TileShape).thenIf(clickEnabled) {
                        Modifier.combinedClickable(
                            onClick = { onClick(it) },
                            onLongClick = { onLongClick(it) }
                        )
                    }
            ) {
                TileIcon(
                    icon = icon,
                    color = colors.icon,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

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
    columns: GridCells,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        verticalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical)),
        horizontalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_horizontal)),
        modifier = modifier,
        content = content,
    )
}

@Composable
fun DefaultEditTileGrid(
    tiles: List<EditTileViewModel>,
    isIconOnly: (TileSpec) -> Boolean,
    columns: Int,
    modifier: Modifier,
    onAddTile: (TileSpec, Int) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    onResize: (TileSpec, Boolean) -> Unit,
) {
    val currentListState = rememberEditListState(tiles)
    val dragAndDropState = rememberDragAndDropState(currentListState)
    val (currentTiles, otherTiles) = currentListState.tiles.partition { it.isCurrent }

    val addTileToEnd: (TileSpec) -> Unit by rememberUpdatedState {
        onAddTile(it, CurrentTilesInteractor.POSITION_AT_END)
    }
    val onDropAdd: (TileSpec, Int) -> Unit by rememberUpdatedState { tileSpec, position ->
        onAddTile(tileSpec, position)
    }
    val onDoubleTap: (TileSpec) -> Unit by rememberUpdatedState { tileSpec ->
        onResize(tileSpec, !isIconOnly(tileSpec))
    }
    val tilePadding = dimensionResource(R.dimen.qs_tile_margin_vertical)

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Column(
            verticalArrangement =
                spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            AnimatedContent(
                targetState = dragAndDropState.dragInProgress,
                modifier = Modifier.wrapContentSize()
            ) { dragIsInProgress ->
                EditGridHeader(Modifier.dragAndDropRemoveZone(dragAndDropState, onRemoveTile)) {
                    if (dragIsInProgress) {
                        RemoveTileTarget()
                    } else {
                        Text(text = "Hold and drag to rearrange tiles.")
                    }
                }
            }

            CurrentTilesGrid(
                currentTiles,
                columns,
                tilePadding,
                isIconOnly,
                onRemoveTile,
                onDoubleTap,
                dragAndDropState,
                onDropAdd,
            )

            // Hide available tiles when dragging
            AnimatedVisibility(
                visible = !dragAndDropState.dragInProgress,
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
                        dragAndDropState,
                    )
                }
            }

            // Drop zone to remove tiles dragged out of the tile grid
            Spacer(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .dragAndDropRemoveZone(dragAndDropState, onRemoveTile)
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
            modifier = modifier.fillMaxWidth().height(TileDefaults.EditGridHeaderHeight)
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
                .border(1.dp, LocalContentColor.current, shape = TileDefaults.TileShape)
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
    tiles: List<EditTileViewModel>,
    columns: Int,
    tilePadding: Dp,
    isIconOnly: (TileSpec) -> Boolean,
    onClick: (TileSpec) -> Unit,
    onDoubleTap: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
    onDrop: (TileSpec, Int) -> Unit
) {
    val tileHeight = tileHeight()
    val currentRows =
        remember(tiles) {
            calculateRows(
                tiles.map {
                    SizedTile(
                        it,
                        if (isIconOnly(it.tileSpec)) {
                            1
                        } else {
                            2
                        }
                    )
                },
                columns
            )
        }
    val currentGridHeight = gridHeight(currentRows, tileHeight, tilePadding)
    // Current tiles
    CurrentTilesContainer {
        TileLazyGrid(
            modifier =
                Modifier.height(currentGridHeight)
                    .dragAndDropTileList(dragAndDropState, { true }, onDrop),
            columns = GridCells.Fixed(columns)
        ) {
            editTiles(
                tiles,
                ClickAction.REMOVE,
                onClick,
                isIconOnly,
                dragAndDropState,
                onDoubleTap = onDoubleTap,
                indicatePosition = true,
                acceptDrops = { true },
                onDrop = onDrop,
            )
        }
    }
}

@Composable
private fun AvailableTileGrid(
    tiles: List<EditTileViewModel>,
    columns: Int,
    tilePadding: Dp,
    onClick: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
) {
    val (otherTilesStock, otherTilesCustom) =
        tiles.filter { !dragAndDropState.isMoving(it.tileSpec) }.partition { it.appName == null }
    val availableTileHeight = tileHeight(true)
    val availableGridHeight = gridHeight(tiles.size, availableTileHeight, columns, tilePadding)

    // Available tiles
    TileLazyGrid(
        modifier =
            Modifier.height(availableGridHeight)
                .dragAndDropTileList(dragAndDropState, { false }, { _, _ -> }),
        columns = GridCells.Fixed(columns)
    ) {
        editTiles(
            otherTilesStock,
            ClickAction.ADD,
            onClick,
            isIconOnly = { true },
            dragAndDropState = dragAndDropState,
            acceptDrops = { false },
            showLabels = true,
        )
        editTiles(
            otherTilesCustom,
            ClickAction.ADD,
            onClick,
            isIconOnly = { true },
            dragAndDropState = dragAndDropState,
            acceptDrops = { false },
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

private fun calculateRows(tiles: List<SizedTile<EditTileViewModel>>, columns: Int): Int {
    val row = TileRow<EditTileViewModel>(columns)
    var count = 0

    for (tile in tiles) {
        if (row.maybeAddTile(tile)) {
            if (row.isFull()) {
                // Row is full, no need to stretch tiles
                count += 1
                row.clear()
            }
        } else {
            count += 1
            row.clear()
            row.maybeAddTile(tile)
        }
    }
    if (row.tiles.isNotEmpty()) {
        count += 1
    }
    return count
}

fun LazyGridScope.editTiles(
    tiles: List<EditTileViewModel>,
    clickAction: ClickAction,
    onClick: (TileSpec) -> Unit,
    isIconOnly: (TileSpec) -> Boolean,
    dragAndDropState: DragAndDropState,
    acceptDrops: (TileSpec) -> Boolean,
    onDoubleTap: (TileSpec) -> Unit = {},
    onDrop: (TileSpec, Int) -> Unit = { _, _ -> },
    showLabels: Boolean = false,
    indicatePosition: Boolean = false,
) {
    items(
        count = tiles.size,
        key = { tiles[it].tileSpec.spec },
        span = { GridItemSpan(if (isIconOnly(tiles[it].tileSpec)) 1 else 2) },
        contentType = { TileType }
    ) { index ->
        val viewModel = tiles[index]
        val iconOnly = isIconOnly(viewModel.tileSpec)
        val tileHeight = tileHeight(iconOnly && showLabels)

        if (!dragAndDropState.isMoving(viewModel.tileSpec)) {
            val onClickActionName =
                when (clickAction) {
                    ClickAction.ADD ->
                        stringResource(id = R.string.accessibility_qs_edit_tile_add_action)
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
                tileViewModel = viewModel,
                iconOnly = iconOnly,
                showLabels = showLabels,
                modifier =
                    Modifier.height(tileHeight)
                        .animateItem()
                        .semantics {
                            onClick(onClickActionName) { false }
                            this.stateDescription = stateDescription
                        }
                        .dragAndDropTile(dragAndDropState, viewModel.tileSpec, acceptDrops, onDrop)
                        .dragAndDropTileSource(
                            viewModel.tileSpec,
                            onClick,
                            onDoubleTap,
                            dragAndDropState,
                        )
            )
        }
    }
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
            )
        }
    }
}

enum class ClickAction {
    ADD,
    REMOVE,
}

@Composable
private fun getTileIcon(icon: Supplier<QSTile.Icon>): Icon {
    val context = LocalContext.current
    return icon.get().let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(context), null)
        }
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun TileIcon(
    icon: Icon,
    color: Color,
    animateToEnd: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val iconModifier = modifier.size(dimensionResource(id = R.dimen.qs_icon_size))
    val context = LocalContext.current
    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> AppCompatResources.getDrawable(context, icon.res)
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
            contentDescription = null,
            colorFilter = ColorFilter.tint(color = color),
            modifier = iconModifier
        )
    }
}

@Composable
private fun Modifier.tilePadding(): Modifier {
    return padding(dimensionResource(id = R.dimen.qs_label_container_margin))
}

@Composable
private fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(
        space = dimensionResource(id = R.dimen.qs_label_container_margin),
        alignment = Alignment.Start
    )
}

@Composable
fun tileHeight(iconWithLabel: Boolean = false): Dp {
    return if (iconWithLabel) {
        TileDefaults.IconTileWithLabelHeight
    } else {
        dimensionResource(id = R.dimen.qs_tile_height)
    }
}

private data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

private object TileDefaults {
    val TileShape = CircleShape
    val IconTileWithLabelHeight = 140.dp
    val EditGridHeaderHeight = 60.dp

    @Composable
    fun activeTileColors(): TileColors =
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
    fun getColorForState(state: Int): TileColors {
        return when (state) {
            STATE_ACTIVE -> activeTileColors()
            STATE_INACTIVE -> inactiveTileColors()
            else -> unavailableTileColors()
        }
    }
}
