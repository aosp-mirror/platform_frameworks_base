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

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun PagerDots(
    pagerState: PagerState,
    activeColor: Color,
    nonActiveColor: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    spaceSize: Dp = 4.dp,
) {
    if (pagerState.pageCount < 2) {
        return
    }
    val inPageTransition by
        remember(pagerState) {
            derivedStateOf {
                pagerState.currentPageOffsetFraction.absoluteValue > 0.01 &&
                    !pagerState.isOverscrolling()
            }
        }
    val coroutineScope = rememberCoroutineScope()
    Row(
        modifier =
            modifier
                .wrapContentWidth()
                .pagerDotsSemantics(
                    pagerState,
                    coroutineScope,
                ),
        horizontalArrangement = spacedBy(spaceSize),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!inPageTransition) {
            repeat(pagerState.pageCount) { i ->
                // We use canvas directly to only invalidate the draw phase when the page is
                // changing.
                Canvas(Modifier.size(dotSize)) {
                    if (pagerState.currentPage == i) {
                        drawCircle(activeColor)
                    } else {
                        drawCircle(nonActiveColor)
                    }
                }
            }
        } else {
            val doubleDotWidth = dotSize * 2 + spaceSize
            val cornerRadius = dotSize / 2
            val width by
                animateDpAsState(targetValue = if (inPageTransition) doubleDotWidth else dotSize)

            fun DrawScope.drawDoubleRect() {
                drawRoundRect(
                    color = activeColor,
                    size = Size(width.toPx(), dotSize.toPx()),
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                )
            }

            repeat(pagerState.pageCount) { page ->
                Canvas(Modifier.size(dotSize)) {
                    val withPrevious = pagerState.currentPageOffsetFraction < 0
                    val ltr = layoutDirection == LayoutDirection.Ltr
                    if (
                        withPrevious && page == (pagerState.currentPage - 1) ||
                            !withPrevious && page == pagerState.currentPage
                    ) {
                        if (ltr) {
                            drawDoubleRect()
                        }
                    } else if (
                        withPrevious && page == pagerState.currentPage ||
                            !withPrevious && page == (pagerState.currentPage + 1)
                    ) {
                        if (!ltr) {
                            drawDoubleRect()
                        }
                    } else {
                        drawCircle(nonActiveColor)
                    }
                }
            }
        }
    }
}

private fun Modifier.pagerDotsSemantics(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
): Modifier {
    return then(
        Modifier.semantics {
            pageLeft {
                if (pagerState.canScrollBackward) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                    true
                } else {
                    false
                }
            }
            pageRight {
                if (pagerState.canScrollForward) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                    true
                } else {
                    false
                }
            }
            stateDescription = "Page ${pagerState.settledPage + 1} of ${pagerState.pageCount}"
        }
    )
}

private fun PagerState.isOverscrolling(): Boolean {
    val position = currentPage + currentPageOffsetFraction
    return position < 0 || position > pageCount - 1
}
