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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import com.android.app.tracing.coroutines.launchTraced as launch
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

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
                pagerState.currentPageOffsetFraction.absoluteValue > 0.05 &&
                    !pagerState.isOverscrolling()
            }
        }
    val coroutineScope = rememberCoroutineScope()
    val doubleDotWidth = dotSize * 2 + spaceSize
    val activeMarkerWidth by
        animateDpAsState(
            targetValue = if (inPageTransition) doubleDotWidth else dotSize,
            label = "PagerDotsTransitionAnimation",
        )
    val cornerRadius = dotSize / 2

    fun DrawScope.drawDoubleRect(withPrevious: Boolean, width: Dp) {
        drawRoundRect(
            topLeft =
                Offset(
                    if (withPrevious) {
                        dotSize.toPx() - width.toPx()
                    } else {
                        -(dotSize.toPx() + spaceSize.toPx())
                    },
                    0f,
                ),
            color = activeColor,
            size = Size(width.toPx(), dotSize.toPx()),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
        )
    }

    Row(
        modifier =
            modifier
                .motionTestValues { activeMarkerWidth exportAs PagerDotsMotionKeys.indicatorWidth }
                .wrapContentWidth()
                .pagerDotsSemantics(pagerState, coroutineScope),
        horizontalArrangement = spacedBy(spaceSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // This means that the active rounded rect has to be drawn between the current page
        // and the previous one (as we are animating back), or the current one if not transitioning
        val withPrevious by
            remember(pagerState) {
                derivedStateOf {
                    pagerState.currentPageOffsetFraction <= 0 || pagerState.isOverscrolling()
                }
            }
        repeat(pagerState.pageCount) { page ->
            Canvas(Modifier.size(dotSize)) {
                val rtl = layoutDirection == LayoutDirection.Rtl
                scale(if (rtl) -1f else 1f, 1f, Offset(0f, center.y)) {
                    drawCircle(nonActiveColor)
                    // We always want to draw the rounded rect on the rightmost dot iteration, so
                    // the inactive dot is always drawn behind.
                    // This means that:
                    // * if we are scrolling back, we draw it when we are in the current page (so it
                    //   extends between this page and the previous one).
                    // * if we are scrolling forward, we draw it when we are in the next page (so it
                    //   extends between the next page and the current one).
                    // * if we are not scrolling, withPrevious is true (pageOffset 0) and we
                    //   draw in the current page.
                    // drawDoubleRect calculates the offset based on the above.
                    if (
                        withPrevious && page == pagerState.currentPage ||
                            (!withPrevious && page == pagerState.currentPage + 1)
                    ) {
                        drawDoubleRect(withPrevious, activeMarkerWidth)
                    }
                }
            }
        }
    }
}

object PagerDotsMotionKeys {
    val indicatorWidth = MotionTestValueKey<Dp>("indicatorWidth")
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
