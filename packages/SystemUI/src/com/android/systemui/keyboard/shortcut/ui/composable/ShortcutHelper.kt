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

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.res.R

@Composable
fun ShortcutHelper(modifier: Modifier = Modifier, onKeyboardSettingsClicked: () -> Unit) {
    if (shouldUseSinglePane()) {
        ShortcutHelperSinglePane(modifier, categories, onKeyboardSettingsClicked)
    } else {
        ShortcutHelperTwoPane(modifier, categories, onKeyboardSettingsClicked)
    }
}

@Composable
private fun shouldUseSinglePane() =
    LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact

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
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
        Text(
            modifier = Modifier.align(Alignment.Center),
            text = stringResource(category.labelResId),
        )
    }
}

@Composable
private fun ShortcutHelperTwoPane(
    modifier: Modifier = Modifier,
    categories: List<ShortcutHelperCategory>,
    onKeyboardSettingsClicked: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 26.dp)) {
        TitleBar()
        Spacer(modifier = Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StartSidePanel(
                modifier = Modifier.fillMaxWidth(fraction = 0.32f),
                categories = categories,
                onKeyboardSettingsClicked = onKeyboardSettingsClicked,
            )
            Spacer(modifier = Modifier.width(24.dp))
            EndSidePanel(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StartSidePanel(
    modifier: Modifier,
    categories: List<ShortcutHelperCategory>,
    onKeyboardSettingsClicked: () -> Unit,
) {
    Column(modifier) {
        ShortcutsSearchBar()
        Spacer(modifier = Modifier.heightIn(16.dp))
        CategoriesPanelTwoPane(categories)
        Spacer(modifier = Modifier.weight(1f))
        KeyboardSettings(onKeyboardSettingsClicked)
    }
}

@Composable
private fun CategoriesPanelTwoPane(categories: List<ShortcutHelperCategory>) {
    var selected by remember { mutableStateOf(categories.first()) }
    Column {
        categories.fastForEach {
            CategoryItemTwoPane(
                label = stringResource(it.labelResId),
                icon = it.icon,
                selected = selected == it,
                onClick = { selected = it }
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
fun EndSidePanel(modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright
    ) {}
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

/** Temporary data class just to populate the UI. */
private data class ShortcutHelperCategory(
    @StringRes val labelResId: Int,
    val icon: ImageVector,
)

// Temporarily populating the categories directly in the UI.
private val categories =
    listOf(
        ShortcutHelperCategory(R.string.shortcut_helper_category_system, Icons.Default.Tv),
        ShortcutHelperCategory(
            R.string.shortcut_helper_category_multitasking,
            Icons.Default.VerticalSplit
        ),
        ShortcutHelperCategory(R.string.shortcut_helper_category_input, Icons.Default.Keyboard),
        ShortcutHelperCategory(R.string.shortcut_helper_category_app_shortcuts, Icons.Default.Apps),
        ShortcutHelperCategory(R.string.shortcut_helper_category_a11y, Icons.Default.Accessibility),
    )

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
