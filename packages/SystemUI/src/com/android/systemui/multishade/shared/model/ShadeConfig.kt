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
 *
 */

package com.android.systemui.multishade.shared.model

import androidx.annotation.FloatRange

/** Enumerates the various possible configurations of the shade system. */
sealed class ShadeConfig(

    /** IDs of the shade(s) in this configuration. */
    open val shadeIds: List<ShadeId>,

    /**
     * The amount that the user must swipe up when the shade is fully expanded to automatically
     * collapse once the user lets go of the shade. If the user swipes less than this amount, the
     * shade will automatically revert back to fully expanded once the user stops swiping.
     */
    @FloatRange(from = 0.0, to = 1.0) open val swipeCollapseThreshold: Float,

    /**
     * The amount that the user must swipe down when the shade is fully collapsed to automatically
     * expand once the user lets go of the shade. If the user swipes less than this amount, the
     * shade will automatically revert back to fully collapsed once the user stops swiping.
     */
    @FloatRange(from = 0.0, to = 1.0) open val swipeExpandThreshold: Float,
) {

    /** There is a single shade. */
    data class SingleShadeConfig(
        @FloatRange(from = 0.0, to = 1.0) override val swipeCollapseThreshold: Float,
        @FloatRange(from = 0.0, to = 1.0) override val swipeExpandThreshold: Float,
    ) :
        ShadeConfig(
            shadeIds = listOf(ShadeId.SINGLE),
            swipeCollapseThreshold = swipeCollapseThreshold,
            swipeExpandThreshold = swipeExpandThreshold,
        )

    /** There are two shades arranged side-by-side. */
    data class DualShadeConfig(
        /** Width of the left-hand side shade. */
        val leftShadeWidthPx: Int,
        /** Width of the right-hand side shade. */
        val rightShadeWidthPx: Int,
        @FloatRange(from = 0.0, to = 1.0) override val swipeCollapseThreshold: Float,
        @FloatRange(from = 0.0, to = 1.0) override val swipeExpandThreshold: Float,
        /**
         * The position of the "split" between interaction areas for each of the shades, as a
         * fraction of the width of the container.
         *
         * Interactions that occur on the start-side (left-hand side in left-to-right languages like
         * English) affect the start-side shade. Interactions that occur on the end-side (right-hand
         * side in left-to-right languages like English) affect the end-side shade.
         */
        @FloatRange(from = 0.0, to = 1.0) val splitFraction: Float,
        /** Maximum opacity when the scrim that shows up behind the dual shades is fully visible. */
        @FloatRange(from = 0.0, to = 1.0) val scrimAlpha: Float,
    ) :
        ShadeConfig(
            shadeIds = listOf(ShadeId.LEFT, ShadeId.RIGHT),
            swipeCollapseThreshold = swipeCollapseThreshold,
            swipeExpandThreshold = swipeExpandThreshold,
        )
}
