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

package com.android.systemui.notifications.ui.composable

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import com.android.compose.nestedscroll.OnStopScope
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import com.android.compose.nestedscroll.ScrollController

/**
 * A [NestedScrollConnection] that listens for all vertical scroll events and responds in the
 * following way:
 * - If you **scroll up**, it **first brings the [scrimOffset]** back to the [minScrimOffset] and
 *   then allows scrolling of the children (usually the content).
 * - If you **scroll down**, it **first allows scrolling of the children** (usually the content) and
 *   then resets the [scrimOffset] to [maxScrimOffset].
 */
fun NotificationScrimNestedScrollConnection(
    scrimOffset: () -> Float,
    snapScrimOffset: (Float) -> Unit,
    animateScrimOffset: (Float) -> Unit,
    minScrimOffset: () -> Float,
    maxScrimOffset: Float,
    contentHeight: () -> Float,
    minVisibleScrimHeight: () -> Float,
    isCurrentGestureOverscroll: () -> Boolean,
    onStart: (Float) -> Unit = {},
    onStop: (Float) -> Unit = {},
    flingBehavior: FlingBehavior,
): PriorityNestedScrollConnection {
    return PriorityNestedScrollConnection(
        orientation = Orientation.Vertical,
        // scrolling up and inner content is taller than the scrim, so scrim needs to
        // expand; content can scroll once scrim is at the minScrimOffset.
        canStartPreScroll = { offsetAvailable, offsetBeforeStart, _ ->
            offsetAvailable < 0 &&
                offsetBeforeStart == 0f &&
                contentHeight() > minVisibleScrimHeight() &&
                scrimOffset() > minScrimOffset()
        },
        // scrolling down and content is done scrolling to top. After that, the scrim
        // needs to collapse; collapse the scrim until it is at the maxScrimOffset.
        canStartPostScroll = { offsetAvailable, _, _ ->
            offsetAvailable > 0 && (scrimOffset() < maxScrimOffset || isCurrentGestureOverscroll())
        },
        canStartPostFling = { false },
        onStart = { firstScroll ->
            onStart(firstScroll)
            object : ScrollController {
                override fun onScroll(deltaScroll: Float, source: NestedScrollSource): Float {
                    val currentHeight = scrimOffset()
                    val amountConsumed =
                        if (deltaScroll > 0) {
                            val amountLeft = maxScrimOffset - currentHeight
                            deltaScroll.fastCoerceAtMost(amountLeft)
                        } else {
                            val amountLeft = minScrimOffset() - currentHeight
                            deltaScroll.fastCoerceAtLeast(amountLeft)
                        }
                    snapScrimOffset(currentHeight + amountConsumed)
                    return amountConsumed
                }

                override suspend fun OnStopScope.onStop(initialVelocity: Float): Float {
                    val consumedByScroll = flingToScroll(initialVelocity, flingBehavior)
                    onStop(initialVelocity - consumedByScroll)
                    if (scrimOffset() < minScrimOffset()) {
                        animateScrimOffset(minScrimOffset())
                    }
                    // Don't consume the velocity on pre/post fling
                    return 0f
                }

                override fun onCancel() {
                    onStop(0f)
                    if (scrimOffset() < minScrimOffset()) {
                        animateScrimOffset(minScrimOffset())
                    }
                }

                override fun canStopOnPreFling() = false
            }
        },
    )
}
