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

package com.android.systemui.fold.ui.helper

import androidx.annotation.VisibleForTesting
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

sealed interface FoldPosture {
    /** A foldable device that's fully closed/folded or a device that doesn't support folding. */
    data object Folded : FoldPosture
    /** A foldable that's halfway open with the hinge held vertically. */
    data object Book : FoldPosture
    /** A foldable that's halfway open with the hinge held horizontally. */
    data object Tabletop : FoldPosture
    /** A foldable that's fully unfolded / flat. */
    data object FullyUnfolded : FoldPosture
}

/**
 * Internal version of `foldPosture` in the System UI Compose library, extracted here to allow for
 * testing that's not dependent on Compose.
 */
@VisibleForTesting
fun foldPostureInternal(layoutInfo: WindowLayoutInfo?): FoldPosture {
    return layoutInfo
        ?.displayFeatures
        ?.firstNotNullOfOrNull { it as? FoldingFeature }
        .let { foldingFeature ->
            when (foldingFeature?.state) {
                null -> FoldPosture.Folded
                FoldingFeature.State.HALF_OPENED -> foldingFeature.orientation.toHalfwayPosture()
                FoldingFeature.State.FLAT ->
                    if (foldingFeature.isSeparating) {
                        // Dual screen device.
                        foldingFeature.orientation.toHalfwayPosture()
                    } else {
                        FoldPosture.FullyUnfolded
                    }
                else -> error("Unsupported state \"${foldingFeature.state}\"")
            }
        }
}

private fun FoldingFeature.Orientation.toHalfwayPosture(): FoldPosture {
    return when (this) {
        FoldingFeature.Orientation.HORIZONTAL -> FoldPosture.Tabletop
        FoldingFeature.Orientation.VERTICAL -> FoldPosture.Book
        else -> error("Unsupported orientation \"$this\"")
    }
}
