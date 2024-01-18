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

package com.android.systemui.communal.ui.compose.extensions

import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.toRect

/**
 * Determine the item at the specified offset, or null if none exist.
 *
 * @param offset The offset in pixels, relative to the top start of the grid.
 */
fun Iterable<LazyGridItemInfo>.firstItemAtOffset(offset: Offset): LazyGridItemInfo? =
    firstOrNull { item ->
        isItemAtOffset(item, offset)
    }

/**
 * Determine the item at the specified offset, or null if none exist.
 *
 * @param offset The offset in pixels, relative to the top start of the grid.
 */
fun Sequence<LazyGridItemInfo>.firstItemAtOffset(offset: Offset): LazyGridItemInfo? =
    firstOrNull { item ->
        isItemAtOffset(item, offset)
    }

private fun isItemAtOffset(item: LazyGridItemInfo, offset: Offset): Boolean {
    val boundingBox = IntRect(item.offset, item.size)
    return boundingBox.toRect().contains(offset)
}
