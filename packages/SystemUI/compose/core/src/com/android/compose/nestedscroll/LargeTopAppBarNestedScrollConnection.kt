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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

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
class LargeTopAppBarNestedScrollConnection(
    private val height: () -> Float,
    private val onChangeHeight: (Float) -> Unit,
    private val minHeight: Float,
    private val maxHeight: Float,
) : NestedScrollConnection {

    constructor(
        height: () -> Float,
        onHeightChanged: (Float) -> Unit,
        heightRange: ClosedFloatingPointRange<Float>,
    ) : this(
        height = height,
        onChangeHeight = onHeightChanged,
        minHeight = heightRange.start,
        maxHeight = heightRange.endInclusive,
    )

    /**
     * When swiping up, the LargeTopAppBar will shrink (to [minHeight]) and the content will expand.
     * Then, you can then scroll down the content.
     */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val y = available.y
        val currentHeight = height()
        if (y >= 0 || currentHeight <= minHeight) {
            return Offset.Zero
        }

        val amountLeft = minHeight - currentHeight
        val amountConsumed = y.coerceAtLeast(amountLeft)
        onChangeHeight(currentHeight + amountConsumed)
        return Offset(0f, amountConsumed)
    }

    /**
     * When swiping down, the content will scroll up until it reaches the top. Then, the
     * LargeTopAppBar will expand until it reaches its [maxHeight].
     */
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        val y = available.y
        val currentHeight = height()
        if (y <= 0 || currentHeight >= maxHeight) {
            return Offset.Zero
        }

        val amountLeft = maxHeight - currentHeight
        val amountConsumed = y.coerceAtMost(amountLeft)
        onChangeHeight(currentHeight + amountConsumed)
        return Offset(0f, amountConsumed)
    }
}
