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

package com.android.settingslib.spa.framework.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * *************************************************************************************************
 * This file was forked from
 * https://github.com/google/accompanist/blob/main/pager/src/main/java/com/google/accompanist/pager/Pager.kt
 * and will be removed once it lands in AndroidX.
 */

/**
 * A horizontally scrolling layout that allows users to flip between items to the left and right.
 *
 * @sample com.google.accompanist.sample.pager.HorizontalPagerSample
 *
 * @param count the number of pages.
 * @param modifier the modifier to apply to this layout.
 * @param state the state object to be used to control or observe the pager's state.
 * @param reverseLayout reverse the direction of scrolling and layout, when `true` items will be
 * composed from the end to the start and [PagerState.currentPage] == 0 will mean
 * the first item is located at the end.
 * @param itemSpacing horizontal spacing to add between items.
 * @param key the scroll position will be maintained based on the key, which means if you
 * add/remove items before the current visible item the item with the given key will be kept as the
 * first visible one.
 * @param content a block which describes the content. Inside this block you can reference
 * [PagerScope.currentPage] and other properties in [PagerScope].
 */
@Composable
fun HorizontalPager(
    count: Int,
    modifier: Modifier = Modifier,
    state: PagerState = rememberPagerState(),
    reverseLayout: Boolean = false,
    itemSpacing: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    key: ((page: Int) -> Any)? = null,
    content: @Composable PagerScope.(page: Int) -> Unit,
) {
    Pager(
        count = count,
        state = state,
        modifier = modifier,
        isVertical = false,
        reverseLayout = reverseLayout,
        itemSpacing = itemSpacing,
        verticalAlignment = verticalAlignment,
        key = key,
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * A vertically scrolling layout that allows users to flip between items to the top and bottom.
 *
 * @sample com.google.accompanist.sample.pager.VerticalPagerSample
 *
 * @param count the number of pages.
 * @param modifier the modifier to apply to this layout.
 * @param state the state object to be used to control or observe the pager's state.
 * @param reverseLayout reverse the direction of scrolling and layout, when `true` items will be
 * composed from the bottom to the top and [PagerState.currentPage] == 0 will mean
 * the first item is located at the bottom.
 * @param itemSpacing vertical spacing to add between items.
 * @param key the scroll position will be maintained based on the key, which means if you
 * add/remove items before the current visible item the item with the given key will be kept as the
 * first visible one.
 * @param content a block which describes the content. Inside this block you can reference
 * [PagerScope.currentPage] and other properties in [PagerScope].
 */
@Composable
fun VerticalPager(
    count: Int,
    modifier: Modifier = Modifier,
    state: PagerState = rememberPagerState(),
    reverseLayout: Boolean = false,
    itemSpacing: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    key: ((page: Int) -> Any)? = null,
    content: @Composable() (PagerScope.(page: Int) -> Unit),
) {
    Pager(
        count = count,
        state = state,
        modifier = modifier,
        isVertical = true,
        reverseLayout = reverseLayout,
        itemSpacing = itemSpacing,
        horizontalAlignment = horizontalAlignment,
        key = key,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
internal fun Pager(
    count: Int,
    modifier: Modifier,
    state: PagerState,
    reverseLayout: Boolean,
    itemSpacing: Dp,
    isVertical: Boolean,
    key: ((page: Int) -> Any)?,
    contentPadding: PaddingValues,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable PagerScope.(page: Int) -> Unit,
) {
    require(count >= 0) { "pageCount must be >= 0" }

    LaunchedEffect(count) {
        state.currentPage = minOf(count - 1, state.currentPage).coerceAtLeast(0)
    }

    // Once a fling (scroll) has finished, notify the state
    LaunchedEffect(state) {
        // When a 'scroll' has finished, notify the state
        snapshotFlow { state.isScrollInProgress }
            .filter { !it }
            // initially isScrollInProgress is false as well and we want to start receiving
            // the events only after the real scroll happens.
            .drop(1)
            .collect { state.onScrollFinished() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.mostVisiblePageLayoutInfo?.index }
            .distinctUntilChanged()
            .collect { state.updateCurrentPageBasedOnLazyListState() }
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(density, contentPadding, isVertical, layoutDirection, reverseLayout, state) {
        with(density) {
            // this should be exposed on LazyListLayoutInfo instead. b/200920410
            state.afterContentPadding = if (isVertical) {
                if (!reverseLayout) {
                    contentPadding.calculateBottomPadding()
                } else {
                    contentPadding.calculateTopPadding()
                }
            } else {
                if (!reverseLayout) {
                    contentPadding.calculateEndPadding(layoutDirection)
                } else {
                    contentPadding.calculateStartPadding(layoutDirection)
                }
            }.roundToPx()
        }
    }

    val pagerScope = remember(state) { PagerScopeImpl(state) }

    // We only consume nested flings in the main-axis, allowing cross-axis flings to propagate
    // as normal
    val consumeFlingNestedScrollConnection = remember(isVertical) {
        ConsumeFlingNestedScrollConnection(
            consumeHorizontal = !isVertical,
            consumeVertical = isVertical,
        )
    }

    if (isVertical) {
        LazyColumn(
            state = state.lazyListState,
            verticalArrangement = Arrangement.spacedBy(itemSpacing, verticalAlignment),
            horizontalAlignment = horizontalAlignment,
            reverseLayout = reverseLayout,
            contentPadding = contentPadding,
            modifier = modifier,
        ) {
            items(
                count = count,
                key = key,
            ) { page ->
                Box(
                    Modifier
                        // We don't any nested flings to continue in the pager, so we add a
                        // connection which consumes them.
                        // See: https://github.com/google/accompanist/issues/347
                        .nestedScroll(connection = consumeFlingNestedScrollConnection)
                        // Constraint the content height to be <= than the height of the pager.
                        .fillParentMaxHeight()
                        .wrapContentSize()
                ) {
                    pagerScope.content(page)
                }
            }
        }
    } else {
        LazyRow(
            state = state.lazyListState,
            verticalAlignment = verticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing, horizontalAlignment),
            reverseLayout = reverseLayout,
            contentPadding = contentPadding,
            modifier = modifier,
        ) {
            items(
                count = count,
                key = key,
            ) { page ->
                Box(
                    Modifier
                        // We don't any nested flings to continue in the pager, so we add a
                        // connection which consumes them.
                        // See: https://github.com/google/accompanist/issues/347
                        .nestedScroll(connection = consumeFlingNestedScrollConnection)
                        // Constraint the content width to be <= than the width of the pager.
                        .fillParentMaxWidth()
                        .wrapContentSize()
                ) {
                    pagerScope.content(page)
                }
            }
        }
    }
}

private class ConsumeFlingNestedScrollConnection(
    private val consumeHorizontal: Boolean,
    private val consumeVertical: Boolean,
) : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = when (source) {
        // We can consume all resting fling scrolls so that they don't propagate up to the
        // Pager
        NestedScrollSource.Fling -> available.consume(consumeHorizontal, consumeVertical)
        else -> Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // We can consume all post fling velocity on the main-axis
        // so that it doesn't propagate up to the Pager
        return available.consume(consumeHorizontal, consumeVertical)
    }
}

private fun Offset.consume(
    consumeHorizontal: Boolean,
    consumeVertical: Boolean,
): Offset = Offset(
    x = if (consumeHorizontal) this.x else 0f,
    y = if (consumeVertical) this.y else 0f,
)

private fun Velocity.consume(
    consumeHorizontal: Boolean,
    consumeVertical: Boolean,
): Velocity = Velocity(
    x = if (consumeHorizontal) this.x else 0f,
    y = if (consumeVertical) this.y else 0f,
)

/**
 * Scope for [HorizontalPager] content.
 */
@Stable
interface PagerScope {
    /**
     * Returns the current selected page
     */
    val currentPage: Int

    /**
     * The current offset from the start of [currentPage], as a ratio of the page width.
     */
    val currentPageOffset: Float
}

private class PagerScopeImpl(
    private val state: PagerState,
) : PagerScope {
    override val currentPage: Int get() = state.currentPage
    override val currentPageOffset: Float get() = state.currentPageOffset
}

/**
 * Calculate the offset for the given [page] from the current scroll position. This is useful
 * when using the scroll position to apply effects or animations to items.
 *
 * The returned offset can positive or negative, depending on whether which direction the [page] is
 * compared to the current scroll position.
 *
 * @sample com.google.accompanist.sample.pager.HorizontalPagerWithOffsetTransition
 */
fun PagerScope.calculateCurrentOffsetForPage(page: Int): Float {
    return (currentPage - page) + currentPageOffset
}
