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

package com.android.systemui.qs.ui.composable

import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.fastFirst
import kotlin.math.max

/*
 This layout puts QS taking all horizontal space and media taking the right half of the space.
 However, QS (in QSPanel) puts an empty view taking half the horizontal space so that it can be
 covered by media.
*/
class QSMediaMeasurePolicy(
    val qsHeight: () -> Int,
    val mediaVerticalOffset: Density.() -> Int = { 0 },
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints
    ): MeasureResult {
        val qsMeasurable = measurables.fastFirst { it.layoutId == LayoutId.QS }
        val mediaMeasurable = measurables.fastFirst { it.layoutId == LayoutId.Media }

        val qsPlaceable = qsMeasurable.measure(constraints)
        val mediaPlaceable =
            mediaMeasurable.measure(constraints.copy(maxWidth = constraints.maxWidth / 2))

        val width = qsPlaceable.width
        val height = max(qsHeight(), mediaPlaceable.height)
        return layout(width, height) {
            qsPlaceable.placeRelative(0, 0)
            mediaPlaceable.placeRelative(width / 2, mediaVerticalOffset())
        }
    }

    enum class LayoutId {
        QS,
        Media,
    }
}
