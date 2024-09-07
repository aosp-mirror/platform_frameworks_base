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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.res.R

@Composable
fun ShortcutHelper(
    onKeyboardSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
    categories: List<ShortcutHelperCategory> = ShortcutHelperTemporaryData.categories,
    useSinglePane: @Composable () -> Boolean = { shouldUseSinglePane() },
) {
    if (useSinglePane()) {
        ShortcutHelperSinglePane(modifier, categories, onKeyboardSettingsClicked)
    } else {
        ShortcutHelperTwoPane(modifier, categories, onKeyboardSettingsClicked)
    }
}

@Composable
private fun shouldUseSinglePane() =
    LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact ||
        LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Compact

@Composable
private fun ShortcutHelperSinglePane(
    modifier: Modifier = Modifier,
    categories: List<ShortcutHelperCategory>,
    onKeyboardSettingsClicked: () -> Unit,
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
        ShortcutsSearchBar()
        Spacer(modifier = Modifier.height(16.dp))
        CategoriesPanelSinglePane(categories)
        Spacer(modifier = Modifier.weight(1f))
        KeyboardSettings(onClick = onKeyboardSettingsClicked)
    }
}

@Composable
private fun CategoriesPanelSinglePane(
    categories: List<ShortcutHelperCategory>,
) {
    var expandedCategory by remember { mutableStateOf<ShortcutHelperCategory?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        categories.fastForEachIndexed { index, category ->
            val isExpanded = expandedCategory == category
            val itemShape =
                if (index == 0) {
                    ShortcutHelper.Shapes.singlePaneFirstCategory
                } else if (index == categories.lastIndex) {
                    ShortcutHelper.Shapes.singlePaneLastCategory
                } else {
                    ShortcutHelper.Shapes.singlePaneCategory
                }
            CategoryItemSinglePane(
                category = category,
                isExpanded = isExpanded,
                onClick = {
                    expandedCategory =
                        if (isExpanded) {
                            null
                        } else {
                            category
                        }
                },
                shape = itemShape,
            )
        }
    }
}

@Composable
private fun CategoryItemSinglePane(
    category: ShortcutHelperCategory,
    isExpanded: Boolean,
    onClick: () -> Unit,
    shape: Shape,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceBright,
        shape = shape,
        onClick = onClick,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 16.dp)
            ) {
                Icon(category.icon, contentDescription = null)
                Spacer(modifier = Modifier.width(16.dp))
                Text(stringResource(category.labelResId))
                Spacer(modifier = Modifier.weight(1f))
                RotatingExpandCollapseIcon(isExpanded)
            }
            AnimatedVisibility(visible = isExpanded) { ShortcutCategoryDetailsSinglePane(category) }
        }
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
            label = "Expand icon rotation animation"
        )
    Icon(
        modifier =
            Modifier.background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape
                )
                .graphicsLayer { rotationZ = expandIconRotationDegrees },
        imageVector = Icons.Default.ExpandMore,
        contentDescription =
            if (isExpanded) {
                stringResource(R.string.shortcut_helper_content_description_collapse_icon)
            } else {
                stringResource(R.string.shortcut_helper_content_description_expand_icon)
            },
        tint = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ShortcutCategoryDetailsSinglePane(category: ShortcutHelperCategory) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        category.subCategories.fastForEach { subCategory ->
            ShortcutSubCategorySinglePane(subCategory)
        }
    }
}

@Composable
private fun ShortcutSubCategorySinglePane(subCategory: SubCategory) {
    // This @Composable is expected to be in a Column.
    SubCategoryTitle(subCategory.label)
    subCategory.shortcuts.fastForEachIndexed { index, shortcut ->
        if (index > 0) {
            HorizontalDivider()
        }
        ShortcutSinglePane(shortcut)
    }
}

@Composable
private fun ShortcutSinglePane(shortcut: Shortcut) {
    Column(Modifier.padding(vertical = 24.dp)) {
        ShortcutDescriptionText(shortcut = shortcut)
        Spacer(modifier = Modifier.height(12.dp))
        ShortcutKeyCombinations(shortcut = shortcut)
    }
}

@Composable
private fun ShortcutHelperTwoPane(
    modifier: Modifier = Modifier,
    categories: List<ShortcutHelperCategory>,
    onKeyboardSettingsClicked: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf(categories.first()) }
    Column(modifier = modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 26.dp)) {
        TitleBar()
        Spacer(modifier = Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StartSidePanel(
                modifier = Modifier.fillMaxWidth(fraction = 0.32f),
                categories = categories,
                selectedCategory = selectedCategory,
                onCategoryClicked = { selectedCategory = it },
                onKeyboardSettingsClicked = onKeyboardSettingsClicked,
            )
            Spacer(modifier = Modifier.width(24.dp))
            EndSidePanel(Modifier.fillMaxSize(), selectedCategory)
        }
    }
}

@Composable
private fun EndSidePanel(modifier: Modifier, category: ShortcutHelperCategory) {
    LazyColumn(modifier.nestedScroll(rememberNestedScrollInteropConnection())) {
        items(items = category.subCategories, key = { item -> item.label }) {
            SubCategoryContainerDualPane(it)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SubCategoryContainerDualPane(subCategory: SubCategory) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright
    ) {
        Column(Modifier.padding(horizontal = 32.dp, vertical = 24.dp)) {
            SubCategoryTitle(subCategory.label)
            Spacer(Modifier.height(24.dp))
            subCategory.shortcuts.fastForEachIndexed { index, shortcut ->
                if (index > 0) {
                    HorizontalDivider()
                }
                ShortcutViewDualPane(shortcut)
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
private fun ShortcutViewDualPane(shortcut: Shortcut) {
    Row(Modifier.padding(vertical = 16.dp)) {
        ShortcutDescriptionText(
            modifier = Modifier.weight(0.25f).align(Alignment.CenterVertically),
            shortcut = shortcut,
        )
        ShortcutKeyCombinations(
            modifier = Modifier.weight(0.75f),
            shortcut = shortcut,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutKeyCombinations(
    modifier: Modifier = Modifier,
    shortcut: Shortcut,
) {
    FlowRow(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shortcut.commands.forEachIndexed { index, command ->
            if (index > 0) {
                ShortcutOrSeparator(spacing = 16.dp)
            }
            ShortcutCommand(command)
        }
    }
}

@Composable
private fun ShortcutCommand(command: ShortcutCommand) {
    // This @Composable is expected to be in a Row or FlowRow.
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

@Composable
private fun ShortcutKeyContainer(shortcutKeyContent: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            Modifier.height(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp)
                ),
    ) {
        shortcutKeyContent()
    }
}

@Composable
private fun BoxScope.ShortcutTextKey(key: ShortcutKey.Text) {
    Text(
        text = key.value,
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun BoxScope.ShortcutIconKey(key: ShortcutKey.Icon) {
    Icon(
        imageVector = key.value,
        contentDescription = null,
        modifier = Modifier.align(Alignment.Center).padding(6.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.ShortcutOrSeparator(spacing: Dp) {
    Spacer(Modifier.width(spacing))
    Text(
        text = stringResource(R.string.shortcut_helper_key_combinations_or_separator),
        modifier = Modifier.align(Alignment.CenterVertically),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.width(spacing))
}

@Composable
private fun ShortcutDescriptionText(
    shortcut: Shortcut,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = shortcut.label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StartSidePanel(
    modifier: Modifier,
    categories: List<ShortcutHelperCategory>,
    onKeyboardSettingsClicked: () -> Unit,
    selectedCategory: ShortcutHelperCategory,
    onCategoryClicked: (ShortcutHelperCategory) -> Unit,
) {
    Column(modifier) {
        ShortcutsSearchBar()
        Spacer(modifier = Modifier.heightIn(16.dp))
        CategoriesPanelTwoPane(categories, selectedCategory, onCategoryClicked)
        Spacer(modifier = Modifier.weight(1f))
        KeyboardSettings(onKeyboardSettingsClicked)
    }
}

@Composable
private fun CategoriesPanelTwoPane(
    categories: List<ShortcutHelperCategory>,
    selectedCategory: ShortcutHelperCategory,
    onCategoryClicked: (ShortcutHelperCategory) -> Unit
) {
    Column {
        categories.fastForEach {
            CategoryItemTwoPane(
                label = stringResource(it.labelResId),
                icon = it.icon,
                selected = selectedCategory == it,
                onClick = { onCategoryClicked(it) }
            )
        }
    }
}

@Composable
private fun CategoryItemTwoPane(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    colors: NavigationDrawerItemColors =
        NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics { role = Role.Tab }.heightIn(min = 72.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = colors.containerColor(selected).value,
    ) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = icon,
                contentDescription = null,
                tint = colors.iconColor(selected).value
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    fontSize = 18.sp,
                    color = colors.textColor(selected).value,
                    style = MaterialTheme.typography.headlineSmall,
                    text = label
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TitleBar() {
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
        title = {
            Text(
                text = stringResource(R.string.shortcut_helper_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShortcutsSearchBar() {
    var query by remember { mutableStateOf("") }
    SearchBar(
        modifier = Modifier.fillMaxWidth(),
        colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        query = query,
        active = false,
        onActiveChange = {},
        onQueryChange = { query = it },
        onSearch = {},
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(text = stringResource(R.string.shortcut_helper_search_placeholder)) },
        content = {}
    )
}

@Composable
private fun KeyboardSettings(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = Modifier.semantics { role = Role.Button }.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Keyboard Settings",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Default.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

object ShortcutHelper {

    object Shapes {
        val singlePaneFirstCategory =
            RoundedCornerShape(
                topStart = Dimensions.SinglePaneCategoryCornerRadius,
                topEnd = Dimensions.SinglePaneCategoryCornerRadius
            )
        val singlePaneLastCategory =
            RoundedCornerShape(
                bottomStart = Dimensions.SinglePaneCategoryCornerRadius,
                bottomEnd = Dimensions.SinglePaneCategoryCornerRadius
            )
        val singlePaneCategory = RectangleShape
    }

    object Dimensions {
        val SinglePaneCategoryCornerRadius = 28.dp
    }
}
