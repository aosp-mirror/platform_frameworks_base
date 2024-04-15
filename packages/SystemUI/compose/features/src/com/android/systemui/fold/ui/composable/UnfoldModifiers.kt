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

package com.android.systemui.fold.ui.composable

import androidx.annotation.FloatRange
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.android.compose.modifiers.padding
import kotlin.math.roundToInt

/**
 * Applies a translation that feeds off of the unfold transition that's active while the device is
 * being folded or unfolded, effectively shifting the element towards the fold hinge.
 *
 * @param startSide `true` if the affected element is on the start side (left-hand side in
 *   left-to-right layouts), `false` otherwise.
 * @param fullTranslation The maximum translation to apply when the element is the most shifted. The
 *   modifier will never apply more than this much translation on the element.
 * @param unfoldProgress A provider for the amount of progress of the unfold transition. This should
 *   be sourced from the `UnfoldTransitionInteractor`, ideally through a view-model.
 */
@Composable
fun Modifier.unfoldTranslation(
    startSide: Boolean,
    fullTranslation: Dp,
    @FloatRange(from = 0.0, to = 1.0) unfoldProgress: () -> Float,
): Modifier {
    val translateToTheRight = startSide && LocalLayoutDirection.current == LayoutDirection.Ltr
    return this.graphicsLayer {
        translationX =
            fullTranslation.toPx() *
                if (translateToTheRight) {
                    1 - unfoldProgress()
                } else {
                    unfoldProgress() - 1
                }
    }
}

/**
 * Applies horizontal padding that feeds off of the unfold transition that's active while the device
 * is being folded or unfolded, effectively "squishing" the element on both sides.
 *
 * This is horizontal padding so it's applied on both the start and end sides of the element.
 *
 * @param fullPadding The maximum padding to apply when the element is the most padded. The modifier
 *   will never apply more than this much horizontal padding on the element.
 * @param unfoldProgress A provider for the amount of progress of the unfold transition. This should
 *   be sourced from the `UnfoldTransitionInteractor`, ideally through a view-model.
 */
@Composable
fun Modifier.unfoldHorizontalPadding(
    fullPadding: Dp,
    @FloatRange(from = 0.0, to = 1.0) unfoldProgress: () -> Float,
): Modifier {
    return this.padding(
        horizontal = { (fullPadding.toPx() * (1 - unfoldProgress())).roundToInt() },
    )
}
