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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.dagger.PaginatedBaseLayoutType
import com.android.systemui.qs.panels.ui.compose.PaginatedGridLayout.Dimensions.FooterHeight
import com.android.systemui.qs.panels.ui.compose.PaginatedGridLayout.Dimensions.InterPageSpacing
import com.android.systemui.qs.panels.ui.viewmodel.PaginatedGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R
import javax.inject.Inject

class PaginatedGridLayout
@Inject
constructor(
    private val viewModel: PaginatedGridViewModel,
    @PaginatedBaseLayoutType private val delegateGridLayout: PaginatableGridLayout,
) : GridLayout by delegateGridLayout {
    @Composable
    override fun SceneScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        editModeStart: () -> Unit,
    ) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }
        val columns by viewModel.columns.collectAsStateWithLifecycle()
        val rows by viewModel.rows.collectAsStateWithLifecycle()

        val pages =
            remember(tiles, columns, rows) {
                delegateGridLayout.splitIntoPages(tiles, rows = rows, columns = columns)
            }

        val pagerState = rememberPagerState(0) { pages.size }

        Column {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.sysuiResTag("qs_pager"),
                pageSpacing = if (pages.size > 1) InterPageSpacing else 0.dp,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
            ) {
                val page = pages[it]

                with(delegateGridLayout) {
                    TileGrid(tiles = page, modifier = Modifier, editModeStart = {})
                }
            }
            Box(modifier = Modifier.height(FooterHeight).fillMaxWidth()) {
                PagerDots(
                    pagerState = pagerState,
                    activeColor = MaterialTheme.colorScheme.primary,
                    nonActiveColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                CompositionLocalProvider(value = LocalContentColor provides Color.White) {
                    IconButton(
                        onClick = editModeStart,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(id = R.string.qs_edit),
                        )
                    }
                }
            }
        }
    }

    private object Dimensions {
        val FooterHeight = 48.dp
        val InterPageSpacing = 16.dp
    }
}
