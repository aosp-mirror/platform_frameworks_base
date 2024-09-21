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

package com.android.systemui.qs.panels.ui.compose.selection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.qs.panels.ui.compose.selection.ResizingDefaults.RESIZING_THRESHOLD

class ResizingState(private val widths: TileWidths, private val onResize: () -> Unit) {
    // Total drag offset of this resize operation
    private var totalOffset = 0f

    /** Width in pixels of the resizing tile. */
    var width by mutableIntStateOf(widths.base)

    // Whether the tile is currently over the threshold and should be a large tile
    private var passedThreshold: Boolean = passedThreshold(calculateProgression(width))

    fun onDrag(offset: Float) {
        totalOffset += offset
        width = (widths.base + totalOffset).toInt().coerceIn(widths.min, widths.max)

        passedThreshold(calculateProgression(width)).let {
            // Resize if we went over the threshold
            if (passedThreshold != it) {
                passedThreshold = it
                onResize()
            }
        }
    }

    private fun passedThreshold(progression: Float): Boolean {
        return progression >= RESIZING_THRESHOLD
    }

    /** The progression of the resizing tile between an icon tile (0f) and a large tile (1f) */
    private fun calculateProgression(width: Int): Float {
        return ((width - widths.min) / (widths.max - widths.min).toFloat()).coerceIn(0f, 1f)
    }
}

/** Holds the width of a tile as well as its min and max widths */
data class TileWidths(val base: Int, val min: Int, val max: Int) {
    init {
        check(max > min) { "The max width needs to be larger than the min width." }
    }
}

private object ResizingDefaults {
    const val RESIZING_THRESHOLD = .25f
}
