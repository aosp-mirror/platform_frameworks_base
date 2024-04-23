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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import kotlinx.coroutines.launch

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
    indicatorCornerSize: CornerSize =
        CornerSize(VolumePanelRadioButtonBarDefaults.DefaultIndicatorCornerRadius),
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

    val coroutineScope = rememberCoroutineScope()
    val offsetAnimatable = remember { Animatable(UNSET_OFFSET, Int.VectorConverter) }
    Layout(
        modifier = modifier,
        content = {
            Spacer(
                modifier =
                    Modifier.layoutId(RadioButtonBarComponent.ButtonsBackground)
                        .background(
                            colors.indicatorBackgroundColor,
                            RoundedCornerShape(indicatorBackgroundCornerSize),
                        )
            )
            Spacer(
                modifier =
                    Modifier.layoutId(RadioButtonBarComponent.Indicator)
                        .offset { IntOffset(offsetAnimatable.value, 0) }
                        .padding(indicatorBackgroundPadding)
                        .background(
                            colors.indicatorColor,
                            RoundedCornerShape(indicatorCornerSize),
                        )
            )
            Row(
                modifier =
                    Modifier.layoutId(RadioButtonBarComponent.Buttons)
                        .padding(indicatorBackgroundPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (itemIndex in items.indices) {
                    val item = items[itemIndex]
                    val isSelected = itemIndex == scope.selectedIndex
                    Row(
                        modifier =
                            Modifier.height(48.dp)
                                .weight(1f)
                                .semantics {
                                    item.contentDescription?.let { contentDescription = it }
                                    role = Role.Switch
                                    selected = isSelected
                                }
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClick = { items[itemIndex].onItemSelected() }
                                ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.icon !== Empty) {
                            CompositionLocalProvider(
                                LocalContentColor provides colors.getIconColor(isSelected)
                            ) {
                                with(items[itemIndex]) { icon() }
                            }
                        }
                    }
                }
            }
            Row(
                modifier =
                    Modifier.layoutId(RadioButtonBarComponent.Labels)
                        .padding(
                            start = indicatorBackgroundPadding,
                            top = labelIndicatorBackgroundSpacing,
                            end = indicatorBackgroundPadding
                        )
                        .clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (itemIndex in items.indices) {
                    val cornersRadius = 4.dp
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { items[itemIndex].onItemSelected() },
                        shape = RoundedCornerShape(cornersRadius),
                        contentPadding = PaddingValues(cornersRadius)
                    ) {
                        val item = items[itemIndex]
                        if (item.icon !== Empty) {
                            val textColor = colors.getLabelColor(itemIndex == scope.selectedIndex)
                            CompositionLocalProvider(LocalContentColor provides textColor) {
                                with(items[itemIndex]) { label() }
                            }
                        }
                    }
                }
            }
        },
        measurePolicy =
            with(LocalDensity.current) {
                val spacingPx =
                    (spacing - indicatorBackgroundPadding * 2).roundToPx().coerceAtLeast(0)

                BarMeasurePolicy(
                    buttonsCount = items.size,
                    selectedIndex = scope.selectedIndex,
                    spacingPx = spacingPx,
                ) {
                    coroutineScope.launch {
                        if (offsetAnimatable.value == UNSET_OFFSET) {
                            offsetAnimatable.snapTo(it)
                        } else {
                            offsetAnimatable.animateTo(it)
                        }
                    }
                }
            },
    )
}

private class BarMeasurePolicy(
    private val buttonsCount: Int,
    private val selectedIndex: Int,
    private val spacingPx: Int,
    private val onTargetIndicatorOffsetMeasured: (Int) -> Unit,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints
    ): MeasureResult {
        val fillWidthConstraints = constraints.copy(minWidth = constraints.maxWidth)
        val buttonsPlaceable: Placeable =
            measurables
                .fastFirst { it.layoutId == RadioButtonBarComponent.Buttons }
                .measure(fillWidthConstraints)
        val labelsPlaceable: Placeable =
            measurables
                .fastFirst { it.layoutId == RadioButtonBarComponent.Labels }
                .measure(fillWidthConstraints)

        val buttonsBackgroundPlaceable: Placeable =
            measurables
                .fastFirst { it.layoutId == RadioButtonBarComponent.ButtonsBackground }
                .measure(
                    Constraints(
                        minWidth = buttonsPlaceable.width,
                        maxWidth = buttonsPlaceable.width,
                        minHeight = buttonsPlaceable.height,
                        maxHeight = buttonsPlaceable.height,
                    )
                )

        val totalSpacing = spacingPx * (buttonsCount - 1)
        val indicatorWidth = (buttonsBackgroundPlaceable.width - totalSpacing) / buttonsCount
        val indicatorPlaceable: Placeable =
            measurables
                .fastFirst { it.layoutId == RadioButtonBarComponent.Indicator }
                .measure(
                    Constraints(
                        minWidth = indicatorWidth,
                        maxWidth = indicatorWidth,
                        minHeight = buttonsBackgroundPlaceable.height,
                        maxHeight = buttonsBackgroundPlaceable.height,
                    )
                )

        onTargetIndicatorOffsetMeasured(
            selectedIndex * indicatorWidth + (spacingPx * selectedIndex)
        )

        return layout(constraints.maxWidth, buttonsPlaceable.height + labelsPlaceable.height) {
            buttonsBackgroundPlaceable.placeRelative(
                0,
                0,
                RadioButtonBarComponent.ButtonsBackground.zIndex,
            )
            indicatorPlaceable.placeRelative(0, 0, RadioButtonBarComponent.Indicator.zIndex)

            buttonsPlaceable.placeRelative(0, 0, RadioButtonBarComponent.Buttons.zIndex)
            labelsPlaceable.placeRelative(
                0,
                buttonsBackgroundPlaceable.height,
                RadioButtonBarComponent.Labels.zIndex,
            )
        }
    }
}

data class VolumePanelRadioButtonBarColors(
    /** Color of the indicator. */
    val indicatorColor: Color,
    /** Color of the indicator background. */
    val indicatorBackgroundColor: Color,
    /** Color of the icon. */
    val iconColor: Color,
    /** Color of the icon when it's selected. */
    val selectedIconColor: Color,
    /** Color of the label. */
    val labelColor: Color,
    /** Color of the label when it's selected. */
    val selectedLabelColor: Color,
)

private fun VolumePanelRadioButtonBarColors.getIconColor(selected: Boolean): Color =
    if (selected) selectedIconColor else iconColor

private fun VolumePanelRadioButtonBarColors.getLabelColor(selected: Boolean): Color =
    if (selected) selectedLabelColor else labelColor

object VolumePanelRadioButtonBarDefaults {

    val DefaultIndicatorBackgroundPadding = 8.dp
    val DefaultSpacing = 24.dp
    val DefaultLabelIndicatorBackgroundSpacing = 12.dp
    val DefaultIndicatorCornerRadius = 20.dp
    val DefaultIndicatorBackgroundCornerRadius = 28.dp

    /**
     * Returns the default VolumePanelRadioButtonBar colors.
     *
     * @param indicatorColor is the color of the indicator
     * @param indicatorBackgroundColor is the color of the indicator background
     */
    @Composable
    fun defaultColors(
        indicatorColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
        indicatorBackgroundColor: Color = MaterialTheme.colorScheme.surface,
        iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedIconColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
        labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedLabelColor: Color = MaterialTheme.colorScheme.onSurface,
    ): VolumePanelRadioButtonBarColors =
        VolumePanelRadioButtonBarColors(
            indicatorColor = indicatorColor,
            indicatorBackgroundColor = indicatorBackgroundColor,
            iconColor = iconColor,
            selectedIconColor = selectedIconColor,
            labelColor = labelColor,
            selectedLabelColor = selectedLabelColor,
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
        contentDescription: String? = null,
    )
}

private val Empty: @Composable RowScope.() -> Unit = {}

private class VolumePanelRadioButtonBarScopeImpl : VolumePanelRadioButtonBarScope {

    var selectedIndex: Int = UNSET_INDEX
        private set

    val hasSelectedItem: Boolean
        get() = selectedIndex != UNSET_INDEX

    private val mutableItems: MutableList<Item> = mutableListOf()
    val items: List<Item> = mutableItems

    override fun item(
        isSelected: Boolean,
        onItemSelected: () -> Unit,
        icon: @Composable RowScope.() -> Unit,
        label: @Composable RowScope.() -> Unit,
        contentDescription: String?,
    ) {
        require(!isSelected || !hasSelectedItem) { "Only one item should be selected at a time" }
        if (isSelected) {
            selectedIndex = mutableItems.size
        }
        mutableItems.add(
            Item(
                onItemSelected = onItemSelected,
                icon = icon,
                label = label,
                contentDescription = contentDescription,
            )
        )
    }

    private companion object {
        const val UNSET_INDEX = -1
    }
}

private class Item(
    val onItemSelected: () -> Unit,
    val icon: @Composable RowScope.() -> Unit,
    val label: @Composable RowScope.() -> Unit,
    val contentDescription: String?,
)

private const val UNSET_OFFSET = -1

private enum class RadioButtonBarComponent(val zIndex: Float) {
    ButtonsBackground(0f),
    Indicator(1f),
    Buttons(2f),
    Labels(2f),
}
