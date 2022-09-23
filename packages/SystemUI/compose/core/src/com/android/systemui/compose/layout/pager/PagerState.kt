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

package com.android.systemui.compose.layout.pager

import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
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
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Deprecated(
    "Replaced with rememberPagerState(initialPage) and count parameter on Pager composables",
    ReplaceWith("rememberPagerState(initialPage)"),
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
@ExperimentalPagerApi
@Composable
inline fun rememberPagerState(
    @IntRange(from = 0) pageCount: Int,
    @IntRange(from = 0) initialPage: Int = 0,
    @FloatRange(from = 0.0, to = 1.0) initialPageOffset: Float = 0f,
    @IntRange(from = 1) initialOffscreenLimit: Int = 1,
    infiniteLoop: Boolean = false
): PagerState {
    return rememberPagerState(initialPage = initialPage)
}

/**
 * Creates a [PagerState] that is remembered across compositions.
 *
 * Changes to the provided values for [initialPage] will **not** result in the state being recreated
 * or changed in any way if it has already been created.
 *
 * @param initialPage the initial value for [PagerState.currentPage]
 */
@ExperimentalPagerApi
@Composable
fun rememberPagerState(
    @IntRange(from = 0) initialPage: Int = 0,
): PagerState =
    rememberSaveable(saver = PagerState.Saver) {
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
@ExperimentalPagerApi
@Stable
class PagerState(
    @IntRange(from = 0) currentPage: Int = 0,
) : ScrollableState {
    // Should this be public?
    internal val lazyListState = LazyListState(firstVisibleItemIndex = currentPage)

    private var _currentPage by mutableStateOf(currentPage)

    private val currentLayoutPageInfo: LazyListItemInfo?
        get() =
            lazyListState.layoutInfo.visibleItemsInfo
                .asSequence()
                .filter { it.offset <= 0 && it.offset + it.size > 0 }
                .lastOrNull()

    private val currentLayoutPageOffset: Float
        get() =
            currentLayoutPageInfo?.let { current ->
                // We coerce since itemSpacing can make the offset > 1f.
                // We don't want to count spacing in the offset so cap it to 1f
                (-current.offset / current.size.toFloat()).coerceIn(0f, 1f)
            }
                ?: 0f

    /**
     * [InteractionSource] that will be used to dispatch drag events when this list is being
     * dragged. If you want to know whether the fling (or animated scroll) is in progress, use
     * [isScrollInProgress].
     */
    val interactionSource: InteractionSource
        get() = lazyListState.interactionSource

    /** The number of pages to display. */
    @get:IntRange(from = 0)
    val pageCount: Int by derivedStateOf { lazyListState.layoutInfo.totalItemsCount }

    /**
     * The index of the currently selected page. This may not be the page which is currently
     * displayed on screen.
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
        currentLayoutPageInfo?.let {
            // The current page offset is the current layout page delta from `currentPage`
            // (which is only updated after a scroll/animation).
            // We calculate this by looking at the current layout page + it's offset,
            // then subtracting the 'current page'.
            it.index + currentLayoutPageOffset - _currentPage
        }
            ?: 0f
    }

    /** The target page for any on-going animations. */
    private var animationTargetPage: Int? by mutableStateOf(null)

    internal var flingAnimationTarget: (() -> Int?)? by mutableStateOf(null)

    /**
     * The target page for any on-going animations or scrolls by the user. Returns the current page
     * if a scroll or animation is not currently in progress.
     */
    val targetPage: Int
        get() =
            animationTargetPage
                ?: flingAnimationTarget?.invoke()
                    ?: when {
                    // If a scroll isn't in progress, return the current page
                    !isScrollInProgress -> currentPage
                    // If the offset is 0f (or very close), return the current page
                    currentPageOffset.absoluteValue < 0.001f -> currentPage
                    // If we're offset towards the start, guess the previous page
                    currentPageOffset < -0.5f -> (currentPage - 1).coerceAtLeast(0)
                    // If we're offset towards the end, guess the next page
                    else -> (currentPage + 1).coerceAtMost(pageCount - 1)
                }

    @Deprecated(
        "Replaced with animateScrollToPage(page, pageOffset)",
        ReplaceWith("animateScrollToPage(page = page, pageOffset = pageOffset)")
    )
    @Suppress("UNUSED_PARAMETER")
    suspend fun animateScrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = 0.0, to = 1.0) pageOffset: Float = 0f,
        animationSpec: AnimationSpec<Float> = spring(),
        initialVelocity: Float = 0f,
        skipPages: Boolean = true,
    ) {
        animateScrollToPage(page = page, pageOffset = pageOffset)
    }

    /**
     * Animate (smooth scroll) to the given page to the middle of the viewport.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to animate to. Must be between 0 and [pageCount] (inclusive).
     * @param pageOffset the percentage of the page width to offset, from the start of [page]. Must
     * be in the range 0f..1f.
     */
    suspend fun animateScrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = 0.0, to = 1.0) pageOffset: Float = 0f,
    ) {
        requireCurrentPage(page, "page")
        requireCurrentPageOffset(pageOffset, "pageOffset")
        try {
            animationTargetPage = page

            if (pageOffset <= 0.005f) {
                // If the offset is (close to) zero, just call animateScrollToItem and we're done
                lazyListState.animateScrollToItem(index = page)
            } else {
                // Else we need to figure out what the offset is in pixels...

                var target =
                    lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }

                if (target != null) {
                    // If we have access to the target page layout, we can calculate the pixel
                    // offset from the size
                    lazyListState.animateScrollToItem(
                        index = page,
                        scrollOffset = (target.size * pageOffset).roundToInt()
                    )
                } else {
                    // If we don't, we use the current page size as a guide
                    val currentSize = currentLayoutPageInfo!!.size
                    lazyListState.animateScrollToItem(
                        index = page,
                        scrollOffset = (currentSize * pageOffset).roundToInt()
                    )

                    // The target should be visible now
                    target = lazyListState.layoutInfo.visibleItemsInfo.first { it.index == page }

                    if (target.size != currentSize) {
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
     * @param page the page to snap to. Must be between 0 and [pageCount] (inclusive).
     */
    suspend fun scrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = 0.0, to = 1.0) pageOffset: Float = 0f,
    ) {
        requireCurrentPage(page, "page")
        requireCurrentPageOffset(pageOffset, "pageOffset")
        try {
            animationTargetPage = page

            // First scroll to the given page. It will now be laid out at offset 0
            lazyListState.scrollToItem(index = page)

            // If we have a start spacing, we need to offset (scroll) by that too
            if (pageOffset > 0.0001f) {
                scroll { currentLayoutPageInfo?.let { scrollBy(it.size * pageOffset) } }
            }
        } finally {
            // We need to manually call this, as the `scroll` call above will happen in 1 frame,
            // which is usually too fast for the LaunchedEffect in Pager to detect the change.
            // This is especially true when running unit tests.
            onScrollFinished()
        }
    }

    internal fun onScrollFinished() {
        // Then update the current page to our layout page
        currentPage = currentLayoutPageInfo?.index ?: 0
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

    override fun toString(): String =
        "PagerState(" +
            "pageCount=$pageCount, " +
            "currentPage=$currentPage, " +
            "currentPageOffset=$currentPageOffset" +
            ")"

    private fun requireCurrentPage(value: Int, name: String) {
        if (pageCount == 0) {
            require(value == 0) { "$name must be 0 when pageCount is 0" }
        } else {
            require(value in 0 until pageCount) { "$name[$value] must be >= 0 and < pageCount" }
        }
    }

    private fun requireCurrentPageOffset(value: Float, name: String) {
        if (pageCount == 0) {
            require(value == 0f) { "$name must be 0f when pageCount is 0" }
        } else {
            require(value in 0f..1f) { "$name must be >= 0 and <= 1" }
        }
    }

    companion object {
        /** The default [Saver] implementation for [PagerState]. */
        val Saver: Saver<PagerState, *> =
            listSaver(
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
