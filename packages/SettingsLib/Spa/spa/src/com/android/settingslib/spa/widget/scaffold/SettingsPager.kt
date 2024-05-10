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

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.settingslib.spa.framework.theme.SettingsDimension
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPager(titles: List<String>, content: @Composable (page: Int) -> Unit) {
    check(titles.isNotEmpty())
    if (titles.size == 1) {
        content(0)
        return
    }

    Column {
        val coroutineScope = rememberCoroutineScope()
        val pagerState = rememberPagerState { titles.size }

        TabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier.padding(horizontal = SettingsDimension.itemPaddingEnd),
            containerColor = Color.Transparent,
            indicator = {},
            divider = {},
        ) {
            titles.forEachIndexed { page, title ->
                SettingsTab(
                    title = title,
                    selected = pagerState.currentPage == page,
                    currentPageOffset = pagerState.currentPageOffsetFraction.absoluteValue,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    },
                )
            }
        }

        HorizontalPager(state = pagerState) { page ->
            content(page)
        }
    }
}
