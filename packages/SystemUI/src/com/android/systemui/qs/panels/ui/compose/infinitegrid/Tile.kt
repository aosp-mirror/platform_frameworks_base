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

import android.content.res.Resources
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.modifiers.thenIf
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabel
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import java.util.function.Supplier
import kotlinx.coroutines.CoroutineScope
import com.android.app.tracing.coroutines.launchTraced as launch

private const val TEST_TAG_SMALL = "qs_tile_small"
private const val TEST_TAG_LARGE = "qs_tile_large"

@Composable
fun TileLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        verticalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        horizontalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        contentPadding = contentPadding,
        modifier = modifier,
        content = content,
    )
}

@Composable
fun Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo,
    tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
    modifier: Modifier = Modifier,
) {
    val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
    val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
    val resources = resources()
    val uiState = remember(state, resources) { state.toUiState(resources) }
    val colors = TileDefaults.getColorForState(uiState)
    val hapticsViewModel: TileHapticsViewModel? =
        rememberViewModel(traceName = "TileHapticsViewModel") {
            tileHapticsViewModelFactoryProvider.getHapticsViewModelFactory()?.create(tile)
        }

    // TODO(b/361789146): Draw the shapes instead of clipping
    val tileShape = TileDefaults.animateTileShape(uiState.state)

    TileExpandable(
        color =
            if (iconOnly || !uiState.handlesSecondaryClick) {
                colors.iconBackground
            } else {
                colors.background
            },
        shape = tileShape,
        squishiness = squishiness,
        hapticsViewModel = hapticsViewModel,
        modifier =
            modifier
                .fillMaxWidth()
                .bounceable(
                    bounceable = currentBounceableInfo.bounceable,
                    previousBounceable = currentBounceableInfo.previousTile,
                    nextBounceable = currentBounceableInfo.nextTile,
                    orientation = Orientation.Horizontal,
                    bounceEnd = currentBounceableInfo.bounceEnd,
                ),
    ) { expandable ->
        TileContainer(
            onClick = {
                tile.onClick(expandable)
                hapticsViewModel?.setTileInteractionState(
                    TileHapticsViewModel.TileInteractionState.CLICKED
                )
                if (uiState.accessibilityUiState.toggleableState != null) {
                    coroutineScope.launch { currentBounceableInfo.bounceable.animateBounce() }
                }
            },
            onLongClick = {
                hapticsViewModel?.setTileInteractionState(
                    TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                )
                tile.onLongClick(expandable)
            },
            uiState = uiState,
            iconOnly = iconOnly,
        ) {
            val icon = getTileIcon(icon = uiState.icon)
            if (iconOnly) {
                SmallTileContent(
                    icon = icon,
                    color = colors.icon,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val iconShape = TileDefaults.animateIconShape(uiState.state)
                val secondaryClick: (() -> Unit)? =
                    {
                            hapticsViewModel?.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.CLICKED
                            )
                            tile.onSecondaryClick()
                        }
                        .takeIf { uiState.handlesSecondaryClick }
                val longClick: (() -> Unit)? =
                    {
                            hapticsViewModel?.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                            )
                            tile.onLongClick(expandable)
                        }
                        .takeIf { uiState.handlesLongClick }
                LargeTileContent(
                    label = uiState.label,
                    secondaryLabel = uiState.secondaryLabel,
                    icon = icon,
                    colors = colors,
                    iconShape = iconShape,
                    toggleClick = secondaryClick,
                    onLongClick = longClick,
                    accessibilityUiState = uiState.accessibilityUiState,
                    squishiness = squishiness,
                )
            }
        }
    }
}

@Composable
private fun TileExpandable(
    color: Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    modifier: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        color = color,
        shape = shape,
        modifier = modifier.clip(shape).verticalSquish(squishiness),
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

@Composable
fun TileContainer(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    uiState: TileUiState,
    iconOnly: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier.height(CommonTileDefaults.TileHeight)
                .fillMaxWidth()
                .tileCombinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    uiState = uiState,
                    iconOnly = iconOnly,
                )
                .sysuiResTag(if (iconOnly) TEST_TAG_SMALL else TEST_TAG_LARGE)
                .tilePadding(),
        content = content,
    )
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

fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = CommonTileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

fun Modifier.tilePadding(): Modifier {
    return padding(CommonTileDefaults.TilePadding)
}

@Composable
fun Modifier.tileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    uiState: TileUiState,
    iconOnly: Boolean,
): Modifier {
    val longPressLabel = longPressLabel()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = uiState.accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = !Flags.msdlFeedback(),
        )
        .semantics {
            role = uiState.accessibilityUiState.accessibilityRole
            if (uiState.accessibilityUiState.accessibilityRole == Role.Switch) {
                uiState.accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            stateDescription = uiState.accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics {
                contentDescription = uiState.accessibilityUiState.contentDescription
            }
        }
}

data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

private object TileDefaults {
    val ActiveIconCornerRadius = 16.dp
    val ActiveTileCornerRadius = 24.dp

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
                label = label,
            )
        return RoundedCornerShape(animatedCornerRadius)
    }
}

/**
 * A composable function that returns the [Resources]. It will be recomposed when [Configuration]
 * gets updated.
 */
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
