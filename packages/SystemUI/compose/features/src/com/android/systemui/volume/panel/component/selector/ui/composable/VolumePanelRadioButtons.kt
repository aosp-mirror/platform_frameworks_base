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

package com.android.systemui.volume.panel.component.selector.ui.composable

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Radio button group for the Volume Panel. It allows selecting a single item
 *
 * @param indicatorBackgroundPadding is the distance between the edge of the indicator and the
 *   indicator background
 * @param labelIndicatorBackgroundSpacing is the distance between indicator background and labels
 *   row
 */
@Composable
fun VolumePanelRadioButtonBar(
    modifier: Modifier = Modifier,
    indicatorBackgroundPadding: Dp =
        VolumePanelRadioButtonBarDefaults.DefaultIndicatorBackgroundPadding,
    spacing: Dp = VolumePanelRadioButtonBarDefaults.DefaultSpacing,
    labelIndicatorBackgroundSpacing: Dp =
        VolumePanelRadioButtonBarDefaults.DefaultLabelIndicatorBackgroundSpacing,
    indicatorCornerRadius: CornerRadius =
        VolumePanelRadioButtonBarDefaults.defaultIndicatorCornerRadius(),
    indicatorBackgroundCornerSize: CornerSize =
        CornerSize(VolumePanelRadioButtonBarDefaults.DefaultIndicatorBackgroundCornerRadius),
    colors: VolumePanelRadioButtonBarColors = VolumePanelRadioButtonBarDefaults.defaultColors(),
    content: VolumePanelRadioButtonBarScope.() -> Unit
) {
    val scope =
        VolumePanelRadioButtonBarScopeImpl().apply(content).apply {
            require(hasSelectedItem) { "At least one item should be selected" }
        }

    val items = scope.items

    var selectedIndex by remember { mutableIntStateOf(items.indexOfFirst { it.isSelected }) }

    var size by remember { mutableStateOf(IntSize(0, 0)) }
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val indicatorWidth = size.width / items.size - (spacingPx * (items.size - 1) / items.size)
    val offset by
        animateOffsetAsState(
            targetValue =
                Offset(
                    selectedIndex * indicatorWidth + (spacingPx * selectedIndex),
                    0f,
                ),
            label = "VolumePanelRadioButtonOffsetAnimation",
            finishedListener = {
                for (itemIndex in items.indices) {
                    val item = items[itemIndex]
                    if (itemIndex == selectedIndex) {
                        item.onItemSelected()
                        break
                    }
                }
            }
        )

    Column(modifier = modifier) {
        Box(modifier = Modifier.height(IntrinsicSize.Max)) {
            Canvas(
                modifier =
                    Modifier.fillMaxSize()
                        .background(
                            colors.indicatorBackgroundColor,
                            RoundedCornerShape(indicatorBackgroundCornerSize),
                        )
                        .padding(indicatorBackgroundPadding)
                        .onGloballyPositioned { size = it.size }
            ) {
                drawRoundRect(
                    color = colors.indicatorColor,
                    topLeft = offset,
                    size = Size(indicatorWidth, size.height.toFloat()),
                    cornerRadius = indicatorCornerRadius,
                )
            }
            Row(
                modifier = Modifier.padding(indicatorBackgroundPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (itemIndex in items.indices) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { selectedIndex = itemIndex },
                    ) {
                        val item = items[itemIndex]
                        if (item.icon !== Empty) {
                            with(items[itemIndex]) { icon() }
                        }
                    }
                }
            }
        }

        Row(
            modifier =
                Modifier.padding(
                    start = indicatorBackgroundPadding,
                    top = labelIndicatorBackgroundSpacing,
                    end = indicatorBackgroundPadding
                ),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            for (itemIndex in items.indices) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = { selectedIndex = itemIndex },
                ) {
                    val item = items[itemIndex]
                    if (item.icon !== Empty) {
                        with(items[itemIndex]) { label() }
                    }
                }
            }
        }
    }
}

data class VolumePanelRadioButtonBarColors(
    /** Color of the indicator. */
    val indicatorColor: Color,
    /** Color of the indicator background. */
    val indicatorBackgroundColor: Color,
)

object VolumePanelRadioButtonBarDefaults {

    val DefaultIndicatorBackgroundPadding = 8.dp
    val DefaultSpacing = 24.dp
    val DefaultLabelIndicatorBackgroundSpacing = 12.dp
    val DefaultIndicatorCornerRadius = 20.dp
    val DefaultIndicatorBackgroundCornerRadius = 20.dp

    @Composable
    fun defaultIndicatorCornerRadius(
        x: Dp = DefaultIndicatorCornerRadius,
        y: Dp = DefaultIndicatorCornerRadius,
    ): CornerRadius = with(LocalDensity.current) { CornerRadius(x.toPx(), y.toPx()) }

    /**
     * Returns the default VolumePanelRadioButtonBar colors.
     *
     * @param indicatorColor is the color of the indicator
     * @param indicatorBackgroundColor is the color of the indicator background
     */
    @Composable
    fun defaultColors(
        indicatorColor: Color = MaterialTheme.colorScheme.primaryContainer,
        indicatorBackgroundColor: Color = MaterialTheme.colorScheme.surface,
    ): VolumePanelRadioButtonBarColors =
        VolumePanelRadioButtonBarColors(
            indicatorColor = indicatorColor,
            indicatorBackgroundColor = indicatorBackgroundColor,
        )
}

/** [VolumePanelRadioButtonBar] content scope. Use [item] to add more items. */
interface VolumePanelRadioButtonBarScope {

    /**
     * Adds a single item to the radio button group.
     *
     * @param isSelected true when the item is selected and false the otherwise
     * @param onItemSelected is called when the item is selected
     * @param icon of the to show in the indicator bar
     * @param label to show below the indicator bar for the corresponding [icon]
     */
    fun item(
        isSelected: Boolean,
        onItemSelected: () -> Unit,
        icon: @Composable RowScope.() -> Unit = Empty,
        label: @Composable RowScope.() -> Unit = Empty,
    )
}

private val Empty: @Composable RowScope.() -> Unit = {}

private class VolumePanelRadioButtonBarScopeImpl : VolumePanelRadioButtonBarScope {

    var hasSelectedItem: Boolean = false
        private set

    private val mutableItems: MutableList<Item> = mutableListOf()
    val items: List<Item> = mutableItems

    override fun item(
        isSelected: Boolean,
        onItemSelected: () -> Unit,
        icon: @Composable RowScope.() -> Unit,
        label: @Composable RowScope.() -> Unit,
    ) {
        require(!isSelected || !hasSelectedItem) { "Only one item should be selected at a time" }
        hasSelectedItem = hasSelectedItem || isSelected
        mutableItems.add(
            Item(
                isSelected = isSelected,
                onItemSelected = onItemSelected,
                icon = icon,
                label = label,
            )
        )
    }
}

private class Item(
    val isSelected: Boolean,
    val onItemSelected: () -> Unit,
    val icon: @Composable RowScope.() -> Unit,
    val label: @Composable RowScope.() -> Unit,
)
