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

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import com.android.compose.nestedscroll.PriorityNestedScrollConnection

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
): PriorityNestedScrollConnection {
    return PriorityNestedScrollConnection(
        orientation = Orientation.Vertical,
        // scrolling up and inner content is taller than the scrim, so scrim needs to
        // expand; content can scroll once scrim is at the minScrimOffset.
        canStartPreScroll = { offsetAvailable, offsetBeforeStart ->
            offsetAvailable < 0 &&
                offsetBeforeStart == 0f &&
                contentHeight() > minVisibleScrimHeight() &&
                scrimOffset() > minScrimOffset()
        },
        // scrolling down and content is done scrolling to top. After that, the scrim
        // needs to collapse; collapse the scrim until it is at the maxScrimOffset.
        canStartPostScroll = { offsetAvailable, _ ->
            offsetAvailable > 0 && (scrimOffset() < maxScrimOffset || isCurrentGestureOverscroll())
        },
        canStartPostFling = { false },
        canContinueScroll = {
            val currentHeight = scrimOffset()
            minScrimOffset() < currentHeight && currentHeight < maxScrimOffset
        },
        canScrollOnFling = true,
        onStart = { offsetAvailable -> onStart(offsetAvailable) },
        onScroll = { offsetAvailable ->
            val currentHeight = scrimOffset()
            val amountConsumed =
                if (offsetAvailable > 0) {
                    val amountLeft = maxScrimOffset - currentHeight
                    offsetAvailable.coerceAtMost(amountLeft)
                } else {
                    val amountLeft = minScrimOffset() - currentHeight
                    offsetAvailable.coerceAtLeast(amountLeft)
                }
            snapScrimOffset(currentHeight + amountConsumed)
            amountConsumed
        },
        // Don't consume the velocity on pre/post fling
        onStop = { velocityAvailable ->
            onStop(velocityAvailable)
            if (scrimOffset() < minScrimOffset()) {
                animateScrimOffset(minScrimOffset())
            }
            0f
        },
    )
}
