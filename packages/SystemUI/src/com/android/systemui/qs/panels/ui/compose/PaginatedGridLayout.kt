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

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.padding
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.development.ui.compose.BuildNumber
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.dagger.PaginatedBaseLayoutType
import com.android.systemui.qs.panels.ui.compose.Dimensions.FooterHeight
import com.android.systemui.qs.panels.ui.compose.Dimensions.InterPageSpacing
import com.android.systemui.qs.panels.ui.compose.toolbar.EditModeButton
import com.android.systemui.qs.panels.ui.viewmodel.PaginatedGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import javax.inject.Inject

class PaginatedGridLayout
@Inject
constructor(
    private val viewModelFactory: PaginatedGridViewModel.Factory,
    @PaginatedBaseLayoutType private val delegateGridLayout: PaginatableGridLayout,
) : GridLayout by delegateGridLayout {
    @Composable
    override fun SceneScope.TileGrid(tiles: List<TileViewModel>, modifier: Modifier) {
        val viewModel =
            rememberViewModel(traceName = "PaginatedGridLayout-TileGrid") {
                viewModelFactory.create()
            }

        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }
        val columns = viewModel.columns
        val rows = viewModel.rows

        val pages =
            remember(tiles, columns, rows) {
                delegateGridLayout.splitIntoPages(tiles, rows = rows, columns = columns)
            }

        val pagerState = rememberPagerState(0) { pages.size }

        // Used to track if this is currently in the first page or not, for animations
        LaunchedEffect(key1 = pagerState) {
            snapshotFlow { pagerState.currentPage == 0 }.collect { viewModel.inFirstPage = it }
        }

        Column {
            val contentPaddingValue =
                if (pages.size > 1) {
                    InterPageSpacing
                } else {
                    0.dp
                }
            val contentPadding = PaddingValues(horizontal = contentPaddingValue)

            /* Use negative padding equal with value equal to content padding. That way, each page
             * layout extends to the sides, but the content is as if there was no padding. That
             * way, the clipping bounds of the HorizontalPager extend beyond the tiles in each page.
             */
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier.sysuiResTag("qs_pager")
                        .padding(horizontal = { -contentPaddingValue.roundToPx() })
                        .pointerInteropFilter { event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                viewModel.registerSideSwipeGesture()
                            }
                            false
                        },
                contentPadding = contentPadding,
                pageSpacing = if (pages.size > 1) InterPageSpacing else 0.dp,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
            ) {
                val page = pages[it]

                with(delegateGridLayout) { TileGrid(tiles = page, modifier = Modifier) }
            }
            FooterBar(
                buildNumberViewModelFactory = viewModel.buildNumberViewModelFactory,
                pagerState = pagerState,
                editButtonViewModelFactory = viewModel.editModeButtonViewModelFactory,
            )
        }
    }
}

private object Dimensions {
    val FooterHeight = 48.dp
    val InterPageSpacing = 16.dp
}

@Composable
private fun FooterBar(
    buildNumberViewModelFactory: BuildNumberViewModel.Factory,
    pagerState: PagerState,
    editButtonViewModelFactory: EditModeButtonViewModel.Factory,
) {
    // Use requiredHeight so it won't be squished if the view doesn't quite fit. As this is
    // expected to be inside a scrollable container, this should not be an issue.
    // Also, we construct the layout this way to do the following:
    // * PagerDots is centered in the row, taking as much space as it needs.
    // * On the start side, we place the BuildNumber, taking as much space as it needs, but
    //   constrained by the available space left over after PagerDots
    // * On the end side, we place the edit mode button, with the same constraints as for
    //   BuildNumber (but it will usually fit, as it's just a square button).
    Row(
        modifier = Modifier.requiredHeight(FooterHeight).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f)) {
            BuildNumber(
                viewModelFactory = buildNumberViewModelFactory,
                textColor = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.borderOnFocus(
                            color = MaterialTheme.colorScheme.secondary,
                            cornerSize = CornerSize(1.dp),
                        )
                        .wrapContentSize(),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        PagerDots(
            pagerState = pagerState,
            activeColor = MaterialTheme.colorScheme.primary,
            nonActiveColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.wrapContentWidth(),
        )
        Row(Modifier.weight(1f)) {
            Spacer(modifier = Modifier.weight(1f))
            EditModeButton(viewModelFactory = editButtonViewModelFactory)
        }
    }
}
