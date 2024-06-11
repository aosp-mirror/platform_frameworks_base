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

import android.graphics.drawable.Animatable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest

object TileType

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
@Composable
fun Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    showLabels: Boolean = false,
    modifier: Modifier,
) {
    val state: TileUiState by
        tile.state
            .mapLatest { it.toUiState() }
            .collectAsStateWithLifecycle(tile.currentState.toUiState())
    val colors = TileDefaults.getColorForState(state.state)

    val context = LocalContext.current

    Expandable(
        color = colors.background,
        shape = RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius)),
    ) {
        Row(
            modifier =
                modifier
                    .combinedClickable(
                        onClick = { tile.onClick(it) },
                        onLongClick = { tile.onLongClick(it) }
                    )
                    .tileModifier(colors),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = tileHorizontalArrangement(iconOnly),
        ) {
            val icon =
                remember(state.icon) {
                    state.icon.get().let {
                        if (it is QSTileImpl.ResourceIcon) {
                            Icon.Resource(it.resId, null)
                        } else {
                            Icon.Loaded(it.getDrawable(context), null)
                        }
                    }
                }
            TileContent(
                label = state.label.toString(),
                secondaryLabel = state.secondaryLabel.toString(),
                icon = icon,
                colors = colors,
                iconOnly = iconOnly,
                showLabels = showLabels,
            )
        }
    }
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
    columns: GridCells,
    modifier: Modifier,
    onAddTile: (TileSpec, Int) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
) {
    val (currentTiles, otherTiles) = tiles.partition { it.isCurrent }
    val (otherTilesStock, otherTilesCustom) = otherTiles.partition { it.appName == null }
    val addTileToEnd: (TileSpec) -> Unit by rememberUpdatedState {
        onAddTile(it, CurrentTilesInteractor.POSITION_AT_END)
    }

    TileLazyGrid(modifier = modifier, columns = columns) {
        // These Text are just placeholders to see the different sections. Not final UI.
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Current tiles", color = Color.White) }

        editTiles(
            currentTiles,
            ClickAction.REMOVE,
            onRemoveTile,
            isIconOnly,
            indicatePosition = true,
        )

        item(span = { GridItemSpan(maxLineSpan) }) { Text("Tiles to add", color = Color.White) }

        editTiles(
            otherTilesStock,
            ClickAction.ADD,
            addTileToEnd,
            isIconOnly,
        )

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Custom tiles to add", color = Color.White)
        }

        editTiles(
            otherTilesCustom,
            ClickAction.ADD,
            addTileToEnd,
            isIconOnly,
        )
    }
}

fun LazyGridScope.editTiles(
    tiles: List<EditTileViewModel>,
    clickAction: ClickAction,
    onClick: (TileSpec) -> Unit,
    isIconOnly: (TileSpec) -> Boolean,
    showLabels: Boolean = false,
    indicatePosition: Boolean = false,
) {
    items(
        count = tiles.size,
        key = { tiles[it].tileSpec.spec },
        span = { GridItemSpan(if (isIconOnly(tiles[it].tileSpec)) 1 else 2) },
        contentType = { TileType }
    ) {
        val viewModel = tiles[it]
        val canClick =
            when (clickAction) {
                ClickAction.ADD -> AvailableEditActions.ADD in viewModel.availableEditActions
                ClickAction.REMOVE -> AvailableEditActions.REMOVE in viewModel.availableEditActions
            }
        val onClickActionName =
            when (clickAction) {
                ClickAction.ADD ->
                    stringResource(id = R.string.accessibility_qs_edit_tile_add_action)
                ClickAction.REMOVE ->
                    stringResource(id = R.string.accessibility_qs_edit_remove_tile_action)
            }
        val stateDescription =
            if (indicatePosition) {
                stringResource(id = R.string.accessibility_qs_edit_position, it + 1)
            } else {
                ""
            }

        Box(
            modifier =
                Modifier.clickable(enabled = canClick) { onClick.invoke(viewModel.tileSpec) }
                    .animateItem()
                    .semantics {
                        onClick(onClickActionName) { false }
                        this.stateDescription = stateDescription
                    }
        ) {
            val iconOnly = isIconOnly(viewModel.tileSpec)
            val tileHeight = tileHeight(iconOnly && showLabels)
            EditTile(
                tileViewModel = viewModel,
                iconOnly = iconOnly,
                showLabels = showLabels,
                modifier = Modifier.height(tileHeight)
            )
            if (canClick) {
                Badge(clickAction, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
fun Badge(action: ClickAction, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(16.dp).background(Color.Cyan, shape = CircleShape)) {
        Icon(
            imageVector =
                when (action) {
                    ClickAction.ADD -> Icons.Filled.Add
                    ClickAction.REMOVE -> Icons.Filled.Remove
                },
            "",
            tint = Color.Black,
        )
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

    Row(
        modifier = modifier.tileModifier(colors).semantics { this.contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement(iconOnly)
    ) {
        TileContent(
            label = label,
            secondaryLabel = tileViewModel.appName?.load(),
            colors = colors,
            icon = tileViewModel.icon,
            iconOnly = iconOnly,
            showLabels = showLabels,
            animateIconToEnd = true,
        )
    }
}

enum class ClickAction {
    ADD,
    REMOVE,
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun TileIcon(
    icon: Icon,
    color: Color,
    animateToEnd: Boolean = false,
) {
    val modifier = Modifier.size(dimensionResource(id = R.dimen.qs_icon_size))
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
            modifier = modifier,
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
            modifier = modifier
        )
    }
}

@Composable
private fun Modifier.tileModifier(colors: TileColors): Modifier {
    return fillMaxWidth()
        .clip(RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius)))
        .background(colors.background)
        .padding(horizontal = dimensionResource(id = R.dimen.qs_label_container_margin))
}

@Composable
private fun tileHorizontalArrangement(iconOnly: Boolean): Arrangement.Horizontal {
    val horizontalAlignment =
        if (iconOnly) {
            Alignment.CenterHorizontally
        } else {
            Alignment.Start
        }
    return spacedBy(
        space = dimensionResource(id = R.dimen.qs_label_container_margin),
        alignment = horizontalAlignment
    )
}

@Composable
private fun TileContent(
    label: String,
    secondaryLabel: String?,
    icon: Icon,
    colors: TileColors,
    iconOnly: Boolean,
    showLabels: Boolean = false,
    animateIconToEnd: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxHeight()
    ) {
        TileIcon(icon, colors.icon, animateIconToEnd)

        if (iconOnly && showLabels) {
            Text(
                label,
                maxLines = 2,
                color = colors.label,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (!iconOnly) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                label,
                color = colors.label,
                modifier = Modifier.basicMarquee(),
            )
            if (!TextUtils.isEmpty(secondaryLabel)) {
                Text(
                    secondaryLabel ?: "",
                    color = colors.secondaryLabel,
                    modifier = Modifier.basicMarquee(),
                )
            }
        }
    }
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
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

private object TileDefaults {
    val IconTileWithLabelHeight = 100.dp

    @Composable
    fun activeTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            label = MaterialTheme.colorScheme.onSurfaceVariant,
            secondaryLabel = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    @Composable
    fun unavailableTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.surface,
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
