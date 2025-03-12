/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

/** A category title that is placed before a group of similar items. */
@Composable
fun CategoryTitle(title: String) {
    Text(
        text = title,
        modifier =
            Modifier.padding(
                start =
                    if (isSpaExpressiveEnabled) SettingsDimension.paddingSmall
                    else SettingsDimension.itemPaddingStart,
                top = 20.dp,
                end =
                    if (isSpaExpressiveEnabled) SettingsDimension.paddingSmall
                    else SettingsDimension.itemPaddingEnd,
                bottom = 8.dp,
            ),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * A container that is used to group similar items. A [Category] displays a [CategoryTitle] and
 * visually separates groups of items.
 */
@Composable
fun Category(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var displayTitle by remember { mutableStateOf(false) }
    Column(
        modifier =
            if (isSpaExpressiveEnabled && displayTitle)
                Modifier.padding(
                    horizontal = SettingsDimension.paddingLarge,
                    vertical = SettingsDimension.paddingSmall,
                )
            else Modifier
    ) {
        if (title != null && displayTitle) CategoryTitle(title = title)
        Column(
            modifier =
                modifier.onGloballyPositioned { coordinates ->
                        displayTitle = coordinates.size.height > 0
                    }
                    .then(
                        if (isSpaExpressiveEnabled)
                            Modifier.fillMaxWidth().clip(SettingsShape.CornerMedium2)
                        else Modifier
                    ),
            verticalArrangement =
                if (isSpaExpressiveEnabled) Arrangement.spacedBy(SettingsDimension.paddingTiny)
                else Arrangement.Top,
            content = { CompositionLocalProvider(LocalIsInCategory provides true) { content() } },
        )
    }
}

/**
 * A container that is used to group items with lazy loading.
 *
 * @param list The list of items to display.
 * @param entry The entry for each list item according to its index in list.
 * @param key Optional. The key for each item in list to provide unique item identifiers, making the
 *   list more efficient.
 * @param title Optional. Category title for each item or each group of items in the list. It should
 *   be decided by the index.
 * @param bottomPadding Optional. Bottom outside padding of the category.
 * @param state Optional. State of LazyList.
 * @param content Optional. Content to be shown at the top of the category.
 */
@Composable
fun LazyCategory(
    list: List<Any>,
    entry: (Int) -> @Composable () -> Unit,
    key: ((Int) -> Any)? = null,
    title: ((Int) -> String?)? = null,
    bottomPadding: Dp = SettingsDimension.paddingSmall,
    state: LazyListState = rememberLazyListState(),
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.padding(
                PaddingValues(
                    start = SettingsDimension.paddingLarge,
                    end = SettingsDimension.paddingLarge,
                    top = SettingsDimension.paddingSmall,
                    bottom = bottomPadding,
                )
            )
            .clip(SettingsShape.CornerMedium2)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SettingsDimension.paddingTiny),
            state = state,
        ) {
            item { CompositionLocalProvider(LocalIsInCategory provides true) { content() } }

            items(count = list.size, key = key) {
                title?.invoke(it)?.let { title -> CategoryTitle(title) }
                CompositionLocalProvider(LocalIsInCategory provides true) { entry(it)() }
            }
        }
    }
}

/** LocalIsInCategory containing the if the current composable is in a category. */
internal val LocalIsInCategory = compositionLocalOf { false }

@Preview
@Composable
private fun CategoryPreview() {
    SettingsTheme {
        Category(title = "Appearance") {
            Preference(
                object : PreferenceModel {
                    override val title = "Title"
                    override val summary = { "Summary" }
                }
            )
            Preference(
                object : PreferenceModel {
                    override val title = "Title"
                    override val summary = { "Summary" }
                    override val icon =
                        @Composable { SettingsIcon(imageVector = Icons.Outlined.TouchApp) }
                }
            )
        }
    }
}
