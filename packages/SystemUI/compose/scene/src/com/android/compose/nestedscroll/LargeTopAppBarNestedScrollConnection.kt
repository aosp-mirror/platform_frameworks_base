/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.compose.nestedscroll

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost

/**
 * A [NestedScrollConnection] that listens for all vertical scroll events and responds in the
 * following way:
 * - If you **scroll up**, it **first brings the [height]** back to the [minHeight] and then allows
 *   scrolling of the children (usually the content).
 * - If you **scroll down**, it **first allows scrolling of the children** (usually the content) and
 *   then resets the [height] to [maxHeight].
 *
 * This behavior is useful for implementing a
 * [Large top app bar](https://m3.material.io/components/top-app-bar/specs) effect or something
 * similar.
 *
 * @sample com.android.compose.animation.scene.demo.Shade
 */
fun LargeTopAppBarNestedScrollConnection(
    height: () -> Float,
    onHeightChanged: (Float) -> Unit,
    minHeight: () -> Float,
    maxHeight: () -> Float,
    flingBehavior: FlingBehavior,
): PriorityNestedScrollConnection {
    return PriorityNestedScrollConnection(
        orientation = Orientation.Vertical,
        // When swiping up, the LargeTopAppBar will shrink (to [minHeight]) and the content will
        // expand. Then, you can then scroll down the content.
        canStartPreScroll = { offsetAvailable, _, _ ->
            offsetAvailable < 0 && height() > minHeight()
        },
        // When swiping down, the content will scroll up until it reaches the top. Then, the
        // LargeTopAppBar will expand until it reaches its [maxHeight].
        canStartPostScroll = { offsetAvailable, _, _ ->
            offsetAvailable > 0 && height() < maxHeight()
        },
        canStartPostFling = { false },
        onStart = {
            LargeTopAppBarScrollController(
                height = height,
                maxHeight = maxHeight,
                minHeight = minHeight,
                onHeightChanged = onHeightChanged,
                flingBehavior = flingBehavior,
            )
        },
    )
}

private class LargeTopAppBarScrollController(
    val height: () -> Float,
    val maxHeight: () -> Float,
    val minHeight: () -> Float,
    val onHeightChanged: (Float) -> Unit,
    val flingBehavior: FlingBehavior,
) : ScrollController {
    override fun onScroll(deltaScroll: Float, source: NestedScrollSource): Float {
        val currentHeight = height()
        val amountConsumed =
            if (deltaScroll > 0) {
                val amountLeft = maxHeight() - currentHeight
                deltaScroll.fastCoerceAtMost(amountLeft)
            } else {
                val amountLeft = minHeight() - currentHeight
                deltaScroll.fastCoerceAtLeast(amountLeft)
            }
        onHeightChanged(currentHeight + amountConsumed)
        return amountConsumed
    }

    override suspend fun OnStopScope.onStop(initialVelocity: Float): Float {
        return flingToScroll(initialVelocity, flingBehavior)
    }

    override fun onCancel() {
        // do nothing
    }

    override fun canStopOnPreFling() = false
}
