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

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Icon
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalSplit
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.zIndex
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutIcon
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.ui.model.IconSource
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutsUiState
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.CentralSurfaces

@Composable
fun ShortcutHelper(
    onSearchQueryChanged: (String) -> Unit,
    onKeyboardSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
    shortcutsUiState: ShortcutsUiState,
    useSinglePane: @Composable () -> Boolean = { shouldUseSinglePane() },
) {
    when (shortcutsUiState) {
        is ShortcutsUiState.Active -> {
            ActiveShortcutHelper(
                shortcutsUiState,
                useSinglePane,
                onSearchQueryChanged,
                modifier,
                onKeyboardSettingsClicked,
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
        )
    }
}

@Composable private fun shouldUseSinglePane() = hasCompactWindowSize()

@Composable
private fun ShortcutHelperSinglePane(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    categories: List<ShortcutCategory>,
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
    categories: List<ShortcutCategory>,
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
    category: ShortcutCategory,
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
                ShortcutCategoryIcon(modifier = Modifier.size(24.dp), source = category.icon)
                Spacer(modifier = Modifier.width(16.dp))
                Text(category.label(LocalContext.current))
                Spacer(modifier = Modifier.weight(1f))
                RotatingExpandCollapseIcon(isExpanded)
            }
            AnimatedVisibility(visible = isExpanded) {
                ShortcutCategoryDetailsSinglePane(searchQuery, category)
            }
        }
    }
}

private val ShortcutCategory.icon: IconSource
    @Composable
    get() =
        when (type) {
            ShortcutCategoryType.System -> IconSource(imageVector = Icons.Default.Tv)
            ShortcutCategoryType.MultiTasking ->
                IconSource(imageVector = Icons.Default.VerticalSplit)
            ShortcutCategoryType.InputMethodEditor ->
                IconSource(imageVector = Icons.Default.Keyboard)
            ShortcutCategoryType.AppCategories -> IconSource(imageVector = Icons.Default.Apps)
            is ShortcutCategoryType.CurrentApp -> {
                val context = LocalContext.current
                val iconDrawable = context.packageManager.getApplicationIcon(type.packageName)
                IconSource(painter = rememberDrawablePainter(drawable = iconDrawable))
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

private fun ShortcutCategory.label(context: Context): String =
    when (type) {
        ShortcutCategoryType.System -> context.getString(R.string.shortcut_helper_category_system)
        ShortcutCategoryType.MultiTasking ->
            context.getString(R.string.shortcut_helper_category_multitasking)
        ShortcutCategoryType.InputMethodEditor ->
            context.getString(R.string.shortcut_helper_category_input)
        ShortcutCategoryType.AppCategories ->
            context.getString(R.string.shortcut_helper_category_app_shortcuts)
        is ShortcutCategoryType.CurrentApp -> getApplicationLabelForCurrentApp(type, context)
    }

private fun getApplicationLabelForCurrentApp(
    type: ShortcutCategoryType.CurrentApp,
    context: Context,
): String {
    val packageManagerForUser = CentralSurfaces.getPackageManagerForUser(context, context.userId)
    return try {
        val currentAppInfo =
            packageManagerForUser.getApplicationInfoAsUser(
                type.packageName,
                /* flags = */ 0,
                context.userId,
            )
        packageManagerForUser.getApplicationLabel(currentAppInfo).toString()
    } catch (e: NameNotFoundException) {
        Log.wtf(ShortcutHelper.TAG, "Couldn't find app info by package name ${type.packageName}")
        context.getString(R.string.shortcut_helper_category_current_app_shortcuts)
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
private fun ShortcutCategoryDetailsSinglePane(searchQuery: String, category: ShortcutCategory) {
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
            HorizontalDivider()
        }
        ShortcutView(Modifier.padding(vertical = 24.dp), searchQuery, shortcut)
    }
}

@Composable
private fun ShortcutHelperTwoPane(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<ShortcutCategory>,
    selectedCategoryType: ShortcutCategoryType?,
    onCategorySelected: (ShortcutCategoryType?) -> Unit,
    onKeyboardSettingsClicked: () -> Unit,
) {
    val selectedCategory = categories.fastFirstOrNull { it.type == selectedCategoryType }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        TitleBar()
        Spacer(modifier = Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StartSidePanel(
                onSearchQueryChanged = onSearchQueryChanged,
                modifier = Modifier.width(240.dp),
                categories = categories,
                onKeyboardSettingsClicked = onKeyboardSettingsClicked,
                selectedCategory = selectedCategoryType,
                onCategoryClicked = { onCategorySelected(it.type) },
            )
            Spacer(modifier = Modifier.width(24.dp))
            EndSidePanel(searchQuery, Modifier.fillMaxSize().padding(top = 8.dp), selectedCategory)
        }
    }
}

@Composable
private fun EndSidePanel(searchQuery: String, modifier: Modifier, category: ShortcutCategory?) {
    if (category == null) {
        NoSearchResultsText(horizontalPadding = 24.dp, fillHeight = false)
        return
    }
    LazyColumn(modifier.nestedScroll(rememberNestedScrollInteropConnection())) {
        items(items = category.subCategories, key = { item -> item.label }) {
            SubCategoryContainerDualPane(searchQuery, it)
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
private fun SubCategoryContainerDualPane(searchQuery: String, subCategory: ShortcutSubCategory) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(24.dp)) {
            SubCategoryTitle(subCategory.label)
            Spacer(Modifier.height(8.dp))
            subCategory.shortcuts.fastForEachIndexed { index, shortcut ->
                if (index > 0) {
                    HorizontalDivider()
                }
                ShortcutView(Modifier.padding(vertical = 16.dp), searchQuery, shortcut)
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
private fun ShortcutView(modifier: Modifier, searchQuery: String, shortcut: Shortcut) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier
            .focusable(interactionSource = interactionSource)
            .outlineFocusModifier(
                isFocused = isFocused,
                focusColor = MaterialTheme.colorScheme.secondary,
                padding = 8.dp,
                cornerRadius = 16.dp,
            )
    ) {
        Row(
            modifier = Modifier.width(128.dp).align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shortcut.icon != null) {
                ShortcutIcon(shortcut.icon, modifier = Modifier.size(24.dp))
            }
            ShortcutDescriptionText(searchQuery = searchQuery, shortcut = shortcut)
        }
        Spacer(modifier = Modifier.width(16.dp))
        ShortcutKeyCombinations(modifier = Modifier.weight(1f), shortcut = shortcut)
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
private fun ShortcutKeyCombinations(modifier: Modifier = Modifier, shortcut: Shortcut) {
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
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
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun BoxScope.ShortcutIconKey(key: ShortcutKey.Icon) {
    Icon(
        painter = painterResource(key.drawableResId),
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
        modifier = Modifier.align(Alignment.CenterVertically),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.width(spacing))
}

@Composable
private fun ShortcutDescriptionText(
    searchQuery: String,
    shortcut: Shortcut,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = textWithHighlightedSearchQuery(shortcut.label, searchQuery),
        style = MaterialTheme.typography.bodyMedium,
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
    categories: List<ShortcutCategory>,
    onKeyboardSettingsClicked: () -> Unit,
    selectedCategory: ShortcutCategoryType?,
    onCategoryClicked: (ShortcutCategory) -> Unit,
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

@Composable
private fun CategoriesPanelTwoPane(
    categories: List<ShortcutCategory>,
    selectedCategory: ShortcutCategoryType?,
    onCategoryClicked: (ShortcutCategory) -> Unit,
) {
    Column {
        categories.fastForEach {
            CategoryItemTwoPane(
                label = it.label(LocalContext.current),
                iconSource = it.icon,
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
                    style = MaterialTheme.typography.headlineSmall,
                    text = label,
                )
            }
        }
    }
}

private fun Modifier.outlineFocusModifier(
    isFocused: Boolean,
    focusColor: Color,
    padding: Dp,
    cornerRadius: Dp,
): Modifier {
    if (isFocused) {
        return this.drawWithContent {
                val focusOutline =
                    Rect(Offset.Zero, size).let {
                        if (padding > 0.dp) {
                            it.inflate(padding.toPx())
                        } else {
                            it.deflate(padding.unaryMinus().toPx())
                        }
                    }
                drawContent()
                drawRoundRect(
                    color = focusColor,
                    style = Stroke(width = 3.dp.toPx()),
                    topLeft = focusOutline.topLeft,
                    size = focusOutline.size,
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
            // Increasing Z-Index so focus outline is drawn on top of "selected" category
            // background.
            .zIndex(1f)
    } else {
        return this
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
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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
    val interactionSource = remember { MutableInteractionSource() }
    ClickableShortcutSurface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier =
            Modifier.semantics { role = Role.Button }.fillMaxWidth().padding(horizontal = 12.dp),
        interactionSource = interactionSource,
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
                "Keyboard Settings",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
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
