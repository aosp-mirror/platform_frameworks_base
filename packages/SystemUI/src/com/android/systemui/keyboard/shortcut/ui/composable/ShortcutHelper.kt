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

package com.android.systemui.keyboard.shortcut.ui.composable

import android.graphics.drawable.Icon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut as ShortcutModel
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutIcon
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.ui.model.IconSource
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCategoryUi
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutsUiState
import com.android.systemui.res.R
import kotlinx.coroutines.delay

@Composable
fun ShortcutHelper(
    onSearchQueryChanged: (String) -> Unit,
    onKeyboardSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
    shortcutsUiState: ShortcutsUiState,
    useSinglePane: @Composable () -> Boolean = { shouldUseSinglePane() },
    onCustomizationRequested: (ShortcutCustomizationRequestInfo) -> Unit = {},
) {
    when (shortcutsUiState) {
        is ShortcutsUiState.Active -> {
            ActiveShortcutHelper(
                shortcutsUiState,
                useSinglePane,
                onSearchQueryChanged,
                modifier,
                onKeyboardSettingsClicked,
                onCustomizationRequested,
            )
        }

        else -> {
            // No-op for now.
        }
    }
}

@Composable
private fun ActiveShortcutHelper(
    shortcutsUiState: ShortcutsUiState.Active,
    useSinglePane: @Composable () -> Boolean,
    onSearchQueryChanged: (String) -> Unit,
    modifier: Modifier,
    onKeyboardSettingsClicked: () -> Unit,
    onCustomizationRequested: (ShortcutCustomizationRequestInfo) -> Unit = {},
) {
    var selectedCategoryType by
        remember(shortcutsUiState.defaultSelectedCategory) {
            mutableStateOf(shortcutsUiState.defaultSelectedCategory)
        }
    if (useSinglePane()) {
        ShortcutHelperSinglePane(
            shortcutsUiState.searchQuery,
            onSearchQueryChanged,
            shortcutsUiState.shortcutCategories,
            selectedCategoryType,
            onCategorySelected = { selectedCategoryType = it },
            onKeyboardSettingsClicked,
            modifier,
        )
    } else {
        ShortcutHelperTwoPane(
            shortcutsUiState.searchQuery,
            onSearchQueryChanged,
            modifier,
            shortcutsUiState.shortcutCategories,
            selectedCategoryType,
            onCategorySelected = { selectedCategoryType = it },
            onKeyboardSettingsClicked,
            shortcutsUiState.isShortcutCustomizerFlagEnabled,
            onCustomizationRequested,
            shortcutsUiState.shouldShowResetButton,
        )
    }
}

@Composable private fun shouldUseSinglePane() = hasCompactWindowSize()

@Composable
private fun ShortcutHelperSinglePane(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    categories: List<ShortcutCategoryUi>,
    selectedCategoryType: ShortcutCategoryType?,
    onCategorySelected: (ShortcutCategoryType?) -> Unit,
    onKeyboardSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 26.dp)
    ) {
        TitleBar()
        Spacer(modifier = Modifier.height(6.dp))
        ShortcutsSearchBar(onSearchQueryChanged)
        Spacer(modifier = Modifier.height(16.dp))
        if (categories.isEmpty()) {
            Box(modifier = Modifier.weight(1f)) {
                NoSearchResultsText(horizontalPadding = 16.dp, fillHeight = true)
            }
        } else {
            CategoriesPanelSinglePane(
                searchQuery,
                categories,
                selectedCategoryType,
                onCategorySelected,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        KeyboardSettings(
            horizontalPadding = 16.dp,
            verticalPadding = 32.dp,
            onClick = onKeyboardSettingsClicked,
        )
    }
}

@Composable
private fun CategoriesPanelSinglePane(
    searchQuery: String,
    categories: List<ShortcutCategoryUi>,
    selectedCategoryType: ShortcutCategoryType?,
    onCategorySelected: (ShortcutCategoryType?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        categories.fastForEachIndexed { index, category ->
            val isExpanded = selectedCategoryType == category.type
            val itemShape =
                if (categories.size == 1) {
                    ShortcutHelper.Shapes.singlePaneSingleCategory
                } else if (index == 0) {
                    ShortcutHelper.Shapes.singlePaneFirstCategory
                } else if (index == categories.lastIndex) {
                    ShortcutHelper.Shapes.singlePaneLastCategory
                } else {
                    ShortcutHelper.Shapes.singlePaneCategory
                }
            CategoryItemSinglePane(
                searchQuery = searchQuery,
                category = category,
                isExpanded = isExpanded,
                onClick = {
                    onCategorySelected(
                        if (isExpanded) {
                            null
                        } else {
                            category.type
                        }
                    )
                },
                shape = itemShape,
            )
        }
    }
}

@Composable
private fun CategoryItemSinglePane(
    searchQuery: String,
    category: ShortcutCategoryUi,
    isExpanded: Boolean,
    onClick: () -> Unit,
    shape: Shape,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceBright, shape = shape, onClick = onClick) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 16.dp),
            ) {
                ShortcutCategoryIcon(modifier = Modifier.size(24.dp), source = category.iconSource)
                Spacer(modifier = Modifier.width(16.dp))
                Text(category.label)
                Spacer(modifier = Modifier.weight(1f))
                RotatingExpandCollapseIcon(isExpanded)
            }
            AnimatedVisibility(visible = isExpanded) {
                ShortcutCategoryDetailsSinglePane(searchQuery, category)
            }
        }
    }
}

@Composable
fun ShortcutCategoryIcon(
    source: IconSource,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
) {
    if (source.imageVector != null) {
        Icon(source.imageVector, contentDescription, modifier, tint)
    } else if (source.painter != null) {
        Image(source.painter, contentDescription, modifier)
    }
}

@Composable
private fun RotatingExpandCollapseIcon(isExpanded: Boolean) {
    val expandIconRotationDegrees by
        animateFloatAsState(
            targetValue =
                if (isExpanded) {
                    180f
                } else {
                    0f
                },
            label = "Expand icon rotation animation",
        )
    Icon(
        modifier =
            Modifier.background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                )
                .graphicsLayer { rotationZ = expandIconRotationDegrees },
        imageVector = Icons.Default.ExpandMore,
        contentDescription =
            if (isExpanded) {
                stringResource(R.string.shortcut_helper_content_description_collapse_icon)
            } else {
                stringResource(R.string.shortcut_helper_content_description_expand_icon)
            },
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ShortcutCategoryDetailsSinglePane(searchQuery: String, category: ShortcutCategoryUi) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        category.subCategories.fastForEach { subCategory ->
            ShortcutSubCategorySinglePane(searchQuery, subCategory)
        }
    }
}

@Composable
private fun ShortcutSubCategorySinglePane(searchQuery: String, subCategory: ShortcutSubCategory) {
    // This @Composable is expected to be in a Column.
    SubCategoryTitle(subCategory.label)
    subCategory.shortcuts.fastForEachIndexed { index, shortcut ->
        if (index > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        Shortcut(Modifier.padding(vertical = 24.dp), searchQuery, shortcut)
    }
}

@Composable
private fun ShortcutHelperTwoPane(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<ShortcutCategoryUi>,
    selectedCategoryType: ShortcutCategoryType?,
    onCategorySelected: (ShortcutCategoryType?) -> Unit,
    onKeyboardSettingsClicked: () -> Unit,
    isShortcutCustomizerFlagEnabled: Boolean,
    onCustomizationRequested: (ShortcutCustomizationRequestInfo) -> Unit = {},
    shouldShowResetButton: Boolean,
) {
    val selectedCategory = categories.fastFirstOrNull { it.type == selectedCategoryType }
    var isCustomizing by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Keep title centered whether customize button is visible or not.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TitleBar(isCustomizing)
                }
                if (isShortcutCustomizerFlagEnabled) {
                    CustomizationButtonsContainer(
                        isCustomizing = isCustomizing,
                        onToggleCustomizationMode = { isCustomizing = !isCustomizing },
                        onReset = {
                            onCustomizationRequested(ShortcutCustomizationRequestInfo.Reset)
                        },
                        shouldShowResetButton = shouldShowResetButton,
                    )
                } else {
                    Spacer(modifier = Modifier.width(if (isCustomizing) 69.dp else 133.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StartSidePanel(
                onSearchQueryChanged = onSearchQueryChanged,
                modifier = Modifier.width(240.dp).semantics { isTraversalGroup = true },
                categories = categories,
                onKeyboardSettingsClicked = onKeyboardSettingsClicked,
                selectedCategory = selectedCategoryType,
                onCategoryClicked = { onCategorySelected(it.type) },
            )
            Spacer(modifier = Modifier.width(24.dp))
            EndSidePanel(
                searchQuery,
                Modifier.fillMaxSize().padding(top = 8.dp).semantics { isTraversalGroup = true },
                selectedCategory,
                isCustomizing = isCustomizing,
                onCustomizationRequested = onCustomizationRequested,
            )
        }
    }
}

@Composable
private fun CustomizationButtonsContainer(
    isCustomizing: Boolean,
    shouldShowResetButton: Boolean,
    onToggleCustomizationMode: () -> Unit,
    onReset: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isCustomizing) {
            if (shouldShowResetButton) {
                ResetButton(onClick = onReset)
            }
            DoneButton(onClick = onToggleCustomizationMode)
        } else {
            CustomizeButton(onClick = onToggleCustomizationMode)
        }
    }
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    ShortcutHelperButton(
        onClick = onClick,
        color = Color.Transparent,
        width = 99.dp,
        iconSource = IconSource(imageVector = Icons.Default.Refresh),
        text = stringResource(id = R.string.shortcut_helper_reset_button_text),
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(color = MaterialTheme.colorScheme.outlineVariant, width = 1.dp),
    )
}

@Composable
private fun CustomizeButton(onClick: () -> Unit) {
    ShortcutHelperButton(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        width = 133.dp,
        iconSource = IconSource(imageVector = Icons.Default.Tune),
        text = stringResource(id = R.string.shortcut_helper_customize_button_text),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    ShortcutHelperButton(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        width = 69.dp,
        text = stringResource(R.string.shortcut_helper_done_button_text),
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun EndSidePanel(
    searchQuery: String,
    modifier: Modifier,
    category: ShortcutCategoryUi?,
    isCustomizing: Boolean,
    onCustomizationRequested: (ShortcutCustomizationRequestInfo) -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(key1 = category) { if (category != null) listState.animateScrollToItem(0) }
    if (category == null) {
        NoSearchResultsText(horizontalPadding = 24.dp, fillHeight = false)
        return
    }
    LazyColumn(modifier = modifier, state = listState) {
        items(category.subCategories) { subcategory ->
            SubCategoryContainerDualPane(
                searchQuery = searchQuery,
                subCategory = subcategory,
                isCustomizing = isCustomizing and category.type.includeInCustomization,
                onCustomizationRequested = { requestInfo ->
                    when (requestInfo) {
                        is ShortcutCustomizationRequestInfo.Add ->
                            onCustomizationRequested(requestInfo.copy(categoryType = category.type))

                        is ShortcutCustomizationRequestInfo.Delete ->
                            onCustomizationRequested(requestInfo.copy(categoryType = category.type))

                        ShortcutCustomizationRequestInfo.Reset ->
                            onCustomizationRequested(requestInfo)
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NoSearchResultsText(horizontalPadding: Dp, fillHeight: Boolean) {
    var modifier = Modifier.fillMaxWidth()
    if (fillHeight) {
        modifier = modifier.fillMaxHeight()
    }
    Text(
        stringResource(R.string.shortcut_helper_no_search_results),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(28.dp))
                .padding(horizontal = horizontalPadding, vertical = 24.dp),
    )
}

@Composable
private fun SubCategoryContainerDualPane(
    searchQuery: String,
    subCategory: ShortcutSubCategory,
    isCustomizing: Boolean,
    onCustomizationRequested: (ShortcutCustomizationRequestInfo) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(16.dp)) {
            SubCategoryTitle(subCategory.label)
            Spacer(Modifier.height(8.dp))
            subCategory.shortcuts.fastForEachIndexed { index, shortcut ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
                Shortcut(
                    modifier = Modifier.padding(vertical = 8.dp),
                    searchQuery = searchQuery,
                    shortcut = shortcut,
                    isCustomizing = isCustomizing && shortcut.isCustomizable,
                    onCustomizationRequested = { requestInfo ->
                        when (requestInfo) {
                            is ShortcutCustomizationRequestInfo.Add ->
                                onCustomizationRequested(
                                    requestInfo.copy(subCategoryLabel = subCategory.label)
                                )

                            is ShortcutCustomizationRequestInfo.Delete ->
                                onCustomizationRequested(
                                    requestInfo.copy(subCategoryLabel = subCategory.label)
                                )

                            ShortcutCustomizationRequestInfo.Reset ->
                                onCustomizationRequested(requestInfo)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SubCategoryTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Shortcut(
    modifier: Modifier,
    searchQuery: String,
    shortcut: ShortcutModel,
    isCustomizing: Boolean = false,
    onCustomizationRequested: (ShortcutCustomizationRequestInfo) -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusColor = MaterialTheme.colorScheme.secondary
    Row(
        modifier
            .thenIf(isFocused) {
                Modifier.border(width = 3.dp, color = focusColor, shape = RoundedCornerShape(16.dp))
            }
            .focusable(interactionSource = interactionSource)
            .padding(8.dp)
            .semantics(mergeDescendants = true) { contentDescription = shortcut.contentDescription }
    ) {
        Row(
            modifier =
                Modifier.width(128.dp).align(Alignment.CenterVertically).weight(0.333f).semantics {
                    hideFromAccessibility()
                },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shortcut.icon != null) {
                ShortcutIcon(
                    shortcut.icon,
                    modifier = Modifier.size(24.dp).semantics { hideFromAccessibility() },
                )
            }
            ShortcutDescriptionText(
                searchQuery = searchQuery,
                shortcut = shortcut,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
        Spacer(modifier = Modifier.width(24.dp).semantics { hideFromAccessibility() })
        ShortcutKeyCombinations(
            modifier = Modifier.weight(.666f).semantics { hideFromAccessibility() },
            shortcut = shortcut,
            isCustomizing = isCustomizing,
            onAddShortcutRequested = {
                onCustomizationRequested(
                    ShortcutCustomizationRequestInfo.Add(label = shortcut.label)
                )
            },
            onDeleteShortcutRequested = {
                onCustomizationRequested(
                    ShortcutCustomizationRequestInfo.Delete(label = shortcut.label)
                )
            },
        )
    }
}

@Composable
fun ShortcutIcon(
    icon: ShortcutIcon,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val drawable =
        remember(icon.packageName, icon.resourceId) {
            Icon.createWithResource(icon.packageName, icon.resourceId).loadDrawable(context)
        } ?: return
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutKeyCombinations(
    modifier: Modifier = Modifier,
    shortcut: ShortcutModel,
    isCustomizing: Boolean = false,
    onAddShortcutRequested: () -> Unit = {},
    onDeleteShortcutRequested: () -> Unit = {},
) {
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        shortcut.commands.forEachIndexed { index, command ->
            if (index > 0) {
                ShortcutOrSeparator(spacing = 16.dp)
            }
            ShortcutCommandContainer(showBackground = command.isCustom) { ShortcutCommand(command) }
        }
        if (isCustomizing) {
            Spacer(modifier = Modifier.width(16.dp))
            if (shortcut.containsCustomShortcutCommands) {
                DeleteShortcutButton(onDeleteShortcutRequested)
            } else {
                AddShortcutButton(onAddShortcutRequested)
            }
        }
    }
}

@Composable
private fun AddShortcutButton(onClick: () -> Unit) {
    ShortcutHelperButton(
        modifier =
            Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        onClick = onClick,
        color = Color.Transparent,
        width = 32.dp,
        height = 32.dp,
        iconSource = IconSource(imageVector = Icons.Default.Add),
        contentColor = MaterialTheme.colorScheme.primary,
        contentPaddingVertical = 0.dp,
        contentPaddingHorizontal = 0.dp,
    )
}

@Composable
private fun DeleteShortcutButton(onClick: () -> Unit) {
    ShortcutHelperButton(
        modifier =
            Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        onClick = onClick,
        color = Color.Transparent,
        width = 32.dp,
        height = 32.dp,
        iconSource = IconSource(imageVector = Icons.Default.DeleteOutline),
        contentColor = MaterialTheme.colorScheme.primary,
        contentPaddingVertical = 0.dp,
        contentPaddingHorizontal = 0.dp,
    )
}

@Composable
private fun ShortcutCommandContainer(showBackground: Boolean, content: @Composable () -> Unit) {
    if (showBackground) {
        Box(
            modifier =
                Modifier.wrapContentSize()
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(4.dp)
        ) {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun ShortcutCommand(command: ShortcutCommand) {
    Row {
        command.keys.forEachIndexed { keyIndex, key ->
            if (keyIndex > 0) {
                Spacer(Modifier.width(4.dp))
            }
            ShortcutKeyContainer {
                if (key is ShortcutKey.Text) {
                    ShortcutTextKey(key)
                } else if (key is ShortcutKey.Icon) {
                    ShortcutIconKey(key)
                }
            }
        }
    }
}

@Composable
private fun ShortcutKeyContainer(shortcutKeyContent: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            Modifier.height(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                )
    ) {
        shortcutKeyContent()
    }
}

@Composable
private fun BoxScope.ShortcutTextKey(key: ShortcutKey.Text) {
    Text(
        text = key.value,
        modifier =
            Modifier.align(Alignment.Center).padding(horizontal = 12.dp).semantics {
                hideFromAccessibility()
            },
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun BoxScope.ShortcutIconKey(key: ShortcutKey.Icon) {
    Icon(
        painter =
            when (key) {
                is ShortcutKey.Icon.ResIdIcon -> painterResource(key.drawableResId)
                is ShortcutKey.Icon.DrawableIcon -> rememberDrawablePainter(drawable = key.drawable)
            },
        contentDescription = null,
        modifier = Modifier.align(Alignment.Center).padding(6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.ShortcutOrSeparator(spacing: Dp) {
    Spacer(Modifier.width(spacing))
    Text(
        text = stringResource(R.string.shortcut_helper_key_combinations_or_separator),
        modifier = Modifier.align(Alignment.CenterVertically).semantics { hideFromAccessibility() },
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.width(spacing))
}

@Composable
private fun ShortcutDescriptionText(
    searchQuery: String,
    shortcut: ShortcutModel,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = textWithHighlightedSearchQuery(shortcut.label, searchQuery),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun textWithHighlightedSearchQuery(text: String, searchValue: String) =
    buildAnnotatedString {
        val searchIndex = text.lowercase().indexOf(searchValue.trim().lowercase())
        val postSearchIndex = searchIndex + searchValue.trim().length

        if (searchIndex > 0) {
            val preSearchText = text.substring(0, searchIndex)
            append(preSearchText)
        }
        if (searchIndex >= 0) {
            val searchText = text.substring(searchIndex, postSearchIndex)
            withStyle(style = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer)) {
                append(searchText)
            }
            if (postSearchIndex < text.length) {
                val postSearchText = text.substring(postSearchIndex)
                append(postSearchText)
            }
        } else {
            append(text)
        }
    }

@Composable
private fun StartSidePanel(
    onSearchQueryChanged: (String) -> Unit,
    modifier: Modifier,
    categories: List<ShortcutCategoryUi>,
    onKeyboardSettingsClicked: () -> Unit,
    selectedCategory: ShortcutCategoryType?,
    onCategoryClicked: (ShortcutCategoryUi) -> Unit,
) {
    CompositionLocalProvider(
        // Restrict system font scale increases up to a max so categories display correctly.
        LocalDensity provides
            Density(
                density = LocalDensity.current.density,
                fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f),
            )
    ) {
        Column(modifier) {
            ShortcutsSearchBar(onSearchQueryChanged)
            Spacer(modifier = Modifier.heightIn(8.dp))
            CategoriesPanelTwoPane(categories, selectedCategory, onCategoryClicked)
            Spacer(modifier = Modifier.weight(1f))
            KeyboardSettings(
                horizontalPadding = 24.dp,
                verticalPadding = 24.dp,
                onKeyboardSettingsClicked,
            )
        }
    }
}

@Composable
private fun CategoriesPanelTwoPane(
    categories: List<ShortcutCategoryUi>,
    selectedCategory: ShortcutCategoryType?,
    onCategoryClicked: (ShortcutCategoryUi) -> Unit,
) {
    Column {
        categories.fastForEach {
            CategoryItemTwoPane(
                label = it.label,
                iconSource = it.iconSource,
                selected = selectedCategory == it.type,
                onClick = { onCategoryClicked(it) },
            )
        }
    }
}

@Composable
private fun CategoryItemTwoPane(
    label: String,
    iconSource: IconSource,
    selected: Boolean,
    onClick: () -> Unit,
    colors: NavigationDrawerItemColors =
        NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
) {
    SelectableShortcutSurface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics { role = Role.Tab }.heightIn(min = 64.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = colors.containerColor(selected).value,
        interactionsConfig =
            InteractionsConfig(
                hoverOverlayColor = MaterialTheme.colorScheme.onSurface,
                hoverOverlayAlpha = 0.11f,
                pressedOverlayColor = MaterialTheme.colorScheme.onSurface,
                pressedOverlayAlpha = 0.15f,
                focusOutlineColor = MaterialTheme.colorScheme.secondary,
                focusOutlineStrokeWidth = 3.dp,
                focusOutlinePadding = 2.dp,
                surfaceCornerRadius = 28.dp,
                focusOutlineCornerRadius = 33.dp,
            ),
    ) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            ShortcutCategoryIcon(
                modifier = Modifier.size(24.dp),
                source = iconSource,
                contentDescription = null,
                tint = colors.iconColor(selected).value,
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    fontSize = 18.sp,
                    color = colors.textColor(selected).value,
                    style = MaterialTheme.typography.titleSmall,
                    text = label,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TitleBar(isCustomizing: Boolean = false) {
    val text =
        if (isCustomizing) {
            stringResource(R.string.shortcut_helper_customize_mode_title)
        } else {
            stringResource(R.string.shortcut_helper_title)
        }
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
        title = {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        windowInsets = WindowInsets(top = 0.dp, bottom = 0.dp, left = 0.dp, right = 0.dp),
        expandedHeight = 64.dp,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShortcutsSearchBar(onQueryChange: (String) -> Unit) {
    // Using an "internal query" to make sure the SearchBar is immediately updated, otherwise
    // the cursor moves to the wrong position sometimes, when waiting for the query to come back
    // from the ViewModel.
    var queryInternal by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // TODO(b/272065229): Added minor delay so TalkBack can take focus of search box by default,
        //  remove when default a11y focus is fixed.
        delay(50)
        focusRequester.requestFocus()
    }
    SearchBar(
        modifier =
            Modifier.fillMaxWidth().focusRequester(focusRequester).onKeyEvent {
                if (it.key == Key.DirectionDown) {
                    focusManager.moveFocus(FocusDirection.Down)
                    return@onKeyEvent true
                } else {
                    return@onKeyEvent false
                }
            },
        colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        query = queryInternal,
        active = false,
        onActiveChange = {},
        onQueryChange = {
            queryInternal = it
            onQueryChange(it)
        },
        onSearch = {},
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(text = stringResource(R.string.shortcut_helper_search_placeholder)) },
        windowInsets = WindowInsets(top = 0.dp, bottom = 0.dp, left = 0.dp, right = 0.dp),
        content = {},
    )
}

@Composable
private fun KeyboardSettings(horizontalPadding: Dp, verticalPadding: Dp, onClick: () -> Unit) {
    ClickableShortcutSurface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier =
            Modifier.semantics { role = Role.Button }.fillMaxWidth().padding(horizontal = 12.dp),
        interactionsConfig =
            InteractionsConfig(
                hoverOverlayColor = MaterialTheme.colorScheme.onSurface,
                hoverOverlayAlpha = 0.11f,
                pressedOverlayColor = MaterialTheme.colorScheme.onSurface,
                pressedOverlayAlpha = 0.15f,
                focusOutlineColor = MaterialTheme.colorScheme.secondary,
                focusOutlinePadding = 8.dp,
                focusOutlineStrokeWidth = 3.dp,
                surfaceCornerRadius = 24.dp,
                focusOutlineCornerRadius = 28.dp,
                hoverPadding = 8.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    stringResource(id = R.string.shortcut_helper_keyboard_settings_buttons_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Default.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

object ShortcutHelper {

    object Shapes {
        val singlePaneFirstCategory =
            RoundedCornerShape(
                topStart = Dimensions.SinglePaneCategoryCornerRadius,
                topEnd = Dimensions.SinglePaneCategoryCornerRadius,
            )
        val singlePaneLastCategory =
            RoundedCornerShape(
                bottomStart = Dimensions.SinglePaneCategoryCornerRadius,
                bottomEnd = Dimensions.SinglePaneCategoryCornerRadius,
            )
        val singlePaneSingleCategory =
            RoundedCornerShape(size = Dimensions.SinglePaneCategoryCornerRadius)
        val singlePaneCategory = RectangleShape
    }

    object Dimensions {
        val SinglePaneCategoryCornerRadius = 28.dp
    }

    internal const val TAG = "ShortcutHelperUI"
}
