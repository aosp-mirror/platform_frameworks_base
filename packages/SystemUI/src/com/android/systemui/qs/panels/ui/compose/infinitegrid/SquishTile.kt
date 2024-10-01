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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * Modifier to squish the vertical bounds of a composable (usually a QS tile).
 *
 * It will squish the vertical bounds of the inner composable node by the value returned by
 * [squishiness] on the measure/layout pass.
 *
 * The squished composable will be center aligned.
 */
fun Modifier.verticalSquish(squishiness: () -> Float): Modifier {
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val actualHeight = placeable.height
        val squishedHeight = actualHeight * squishiness()
        // Center the content by moving it UP (squishedHeight < actualHeight)
        val scroll = (squishedHeight - actualHeight) / 2

        layout(placeable.width, squishedHeight.roundToInt()) {
            placeable.place(0, scroll.roundToInt())
        }
    }
}
