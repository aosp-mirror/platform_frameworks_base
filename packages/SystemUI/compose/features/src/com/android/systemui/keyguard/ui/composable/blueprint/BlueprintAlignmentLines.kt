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

package com.android.systemui.keyguard.ui.composable.blueprint

import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import kotlin.math.max
import kotlin.math.min

/**
 * Encapsulates all blueprint alignment lines.
 *
 * These can be used to communicate alignment lines emitted by elements that the blueprint should
 * consume and use to know how to constrain and/or place other elements in that blueprint.
 *
 * For more information, please see
 * [the official documentation](https://developer.android.com/jetpack/compose/layouts/alignment-lines).
 */
object BlueprintAlignmentLines {

    /**
     * Encapsulates alignment lines produced by the lock icon element.
     *
     * Because the lock icon is also the same element as the under-display fingerprint sensor
     * (UDFPS), blueprints should use its alignment lines to make sure that other elements on screen
     * do not overlap with the lock icon.
     */
    object LockIcon {

        /** The left edge of the lock icon. */
        val Left =
            VerticalAlignmentLine(
                merger = { old, new ->
                    // When two left alignment line values are provided, choose the leftmost one:
                    min(old, new)
                },
            )

        /** The top edge of the lock icon. */
        val Top =
            HorizontalAlignmentLine(
                merger = { old, new ->
                    // When two top alignment line values are provided, choose the topmost one:
                    min(old, new)
                },
            )

        /** The right edge of the lock icon. */
        val Right =
            VerticalAlignmentLine(
                merger = { old, new ->
                    // When two right alignment line values are provided, choose the rightmost one:
                    max(old, new)
                },
            )

        /** The bottom edge of the lock icon. */
        val Bottom =
            HorizontalAlignmentLine(
                merger = { old, new ->
                    // When two bottom alignment line values are provided, choose the bottommost
                    // one:
                    max(old, new)
                },
            )
    }
}
