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

import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * *************************************************************************************************
 * This file was forked from
 * https://github.com/google/accompanist/blob/main/pager/src/main/java/com/google/accompanist/pager/PagerState.kt
 * and will be removed once it lands in AndroidX.
 */

/**
 * Creates a [PagerState] that is remembered across compositions.
 *
 * Changes to the provided values for [initialPage] will **not** result in the state being
 * recreated or changed in any way if it has already
 * been created.
 *
 * @param initialPage the initial value for [PagerState.currentPage]
 */
@Composable
fun rememberPagerState(
    @IntRange(from = 0) initialPage: Int = 0,
): PagerState = rememberSaveable(saver = PagerState.Saver) {
    PagerState(
        currentPage = initialPage,
    )
}

/**
 * A state object that can be hoisted to control and observe scrolling for [HorizontalPager].
 *
 * In most cases, this will be created via [rememberPagerState].
 *
 * @param currentPage the initial value for [PagerState.currentPage]
 */
@Stable
class PagerState(
    @IntRange(from = 0) currentPage: Int = 0,
) : ScrollableState {
    // Should this be public?
    internal val lazyListState = LazyListState(firstVisibleItemIndex = currentPage)

    private var _currentPage by mutableStateOf(currentPage)

    // finds the page which has larger visible area within the viewport not including paddings
    internal val mostVisiblePageLayoutInfo: LazyListItemInfo?
        get() {
            val layoutInfo = lazyListState.layoutInfo
            return layoutInfo.visibleItemsInfo.maxByOrNull {
                val start = maxOf(it.offset, 0)
                val end = minOf(
                    it.offset + it.size, layoutInfo.viewportEndOffset - afterContentPadding)
                end - start
            }
        }

    internal var afterContentPadding = 0

    private val currentPageLayoutInfo: LazyListItemInfo?
        get() = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull {
            it.index == currentPage
        }

    /**
     * [InteractionSource] that will be used to dispatch drag events when this
     * list is being dragged. If you want to know whether the fling (or animated scroll) is in
     * progress, use [isScrollInProgress].
     */
    val interactionSource: InteractionSource
        get() = lazyListState.interactionSource

    /**
     * The number of pages to display.
     */
    @get:IntRange(from = 0)
    val pageCount: Int by derivedStateOf {
        lazyListState.layoutInfo.totalItemsCount
    }

    /**
     * The index of the currently selected page. This may not be the page which is
     * currently displayed on screen.
     *
     * To update the scroll position, use [scrollToPage] or [animateScrollToPage].
     */
    @get:IntRange(from = 0)
    var currentPage: Int
        get() = _currentPage
        internal set(value) {
            if (value != _currentPage) {
                _currentPage = value
            }
        }

    /**
     * The current offset from the start of [currentPage], as a ratio of the page width.
     *
     * To update the scroll position, use [scrollToPage] or [animateScrollToPage].
     */
    val currentPageOffset: Float by derivedStateOf {
        currentPageLayoutInfo?.let {
            // We coerce since itemSpacing can make the offset > 1f.
            // We don't want to count spacing in the offset so cap it to 1f
            (-it.offset / it.size.toFloat()).coerceIn(-1f, 1f)
        } ?: 0f
    }

    /**
     * The target page for any on-going animations.
     */
    private var animationTargetPage: Int? by mutableStateOf(null)

    /**
     * Animate (smooth scroll) to the given page to the middle of the viewport.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to animate to. Must be >= 0.
     * @param pageOffset the percentage of the page size to offset, from the start of [page].
     * Must be in the range -1f..1f.
     */
    suspend fun animateScrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = -1.0, to = 1.0) pageOffset: Float = 0f,
    ) {
        requireCurrentPage(page, "page")
        requireCurrentPageOffset(pageOffset, "pageOffset")
        try {
            animationTargetPage = page

            // pre-jump to nearby item for long jumps as an optimization
            // the same trick is done in ViewPager2
            val oldPage = lazyListState.firstVisibleItemIndex
            if (abs(page - oldPage) > 3) {
                lazyListState.scrollToItem(if (page > oldPage) page - 3 else page + 3)
            }

            if (pageOffset.absoluteValue <= 0.005f) {
                // If the offset is (close to) zero, just call animateScrollToItem and we're done
                lazyListState.animateScrollToItem(index = page)
            } else {
                // Else we need to figure out what the offset is in pixels...
                lazyListState.scroll { } // this will await for the first layout.
                val layoutInfo = lazyListState.layoutInfo
                var target = layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == page }

                if (target != null) {
                    // If we have access to the target page layout, we can calculate the pixel
                    // offset from the size
                    lazyListState.animateScrollToItem(
                        index = page,
                        scrollOffset = (target.size * pageOffset).roundToInt()
                    )
                } else if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
                    // If we don't, we use the current page size as a guide
                    val currentSize = layoutInfo.visibleItemsInfo.first().size
                    lazyListState.animateScrollToItem(
                        index = page,
                        scrollOffset = (currentSize * pageOffset).roundToInt()
                    )

                    // The target should be visible now
                    target = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull {
                        it.index == page
                    }

                    if (target != null && target.size != currentSize) {
                        // If the size we used for calculating the offset differs from the actual
                        // target page size, we need to scroll again. This doesn't look great,
                        // but there's not much else we can do.
                        lazyListState.animateScrollToItem(
                            index = page,
                            scrollOffset = (target.size * pageOffset).roundToInt()
                        )
                    }
                }
            }
        } finally {
            // We need to manually call this, as the `animateScrollToItem` call above will happen
            // in 1 frame, which is usually too fast for the LaunchedEffect in Pager to detect
            // the change. This is especially true when running unit tests.
            onScrollFinished()
        }
    }

    /**
     * Instantly brings the item at [page] to the middle of the viewport.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to snap to. Must be >= 0.
     * @param pageOffset the percentage of the page size to offset, from the start of [page].
     * Must be in the range -1f..1f.
     */
    suspend fun scrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = -1.0, to = 1.0) pageOffset: Float = 0f,
    ) {
        requireCurrentPage(page, "page")
        requireCurrentPageOffset(pageOffset, "pageOffset")
        try {
            animationTargetPage = page

            // First scroll to the given page. It will now be laid out at offset 0
            lazyListState.scrollToItem(index = page)
            updateCurrentPageBasedOnLazyListState()

            // If we have a start spacing, we need to offset (scroll) by that too
            if (pageOffset.absoluteValue > 0.0001f) {
                currentPageLayoutInfo?.let {
                    scroll {
                        scrollBy(it.size * pageOffset)
                    }
                }
            }
        } finally {
            // We need to manually call this, as the `scroll` call above will happen in 1 frame,
            // which is usually too fast for the LaunchedEffect in Pager to detect the change.
            // This is especially true when running unit tests.
            onScrollFinished()
        }
    }

    internal fun updateCurrentPageBasedOnLazyListState() {
        // Then update the current page to our layout page
        mostVisiblePageLayoutInfo?.let {
            currentPage = it.index
        }
    }

    internal fun onScrollFinished() {
        // Clear the animation target page
        animationTargetPage = null
    }

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit
    ) = lazyListState.scroll(scrollPriority, block)

    override fun dispatchRawDelta(delta: Float): Float {
        return lazyListState.dispatchRawDelta(delta)
    }

    override val isScrollInProgress: Boolean
        get() = lazyListState.isScrollInProgress

    override fun toString(): String = "PagerState(" +
        "pageCount=$pageCount, " +
        "currentPage=$currentPage, " +
        "currentPageOffset=$currentPageOffset" +
        ")"

    private fun requireCurrentPage(value: Int, name: String) {
        require(value >= 0) { "$name[$value] must be >= 0" }
    }

    private fun requireCurrentPageOffset(value: Float, name: String) {
        require(value in -1f..1f) { "$name must be >= 0 and <= 1" }
    }

    companion object {
        /**
         * The default [Saver] implementation for [PagerState].
         */
        val Saver: Saver<PagerState, *> = listSaver(
            save = {
                listOf<Any>(
                    it.currentPage,
                )
            },
            restore = {
                PagerState(
                    currentPage = it[0] as Int,
                )
            }
        )
    }
}
