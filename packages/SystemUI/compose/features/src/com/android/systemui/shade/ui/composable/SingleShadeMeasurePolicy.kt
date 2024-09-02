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

package com.android.systemui.shade.ui.composable

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastFirstOrNull
import com.android.systemui.shade.ui.composable.SingleShadeMeasurePolicy.LayoutId
import kotlin.math.max

/**
 * Lays out elements from the [LayoutId] in the shade. This policy supports the case when the QS and
 * UMO share the same row and when they should be one below another.
 */
class SingleShadeMeasurePolicy(
    private val isMediaInRow: Boolean,
    private val mediaOffset: MeasureScope.() -> Int,
    private val onNotificationsTopChanged: (Int) -> Unit,
    private val mediaZIndex: () -> Float,
    private val cutoutInsetsProvider: () -> WindowInsets?,
) : MeasurePolicy {

    enum class LayoutId {
        QuickSettings,
        Media,
        Notifications,
        ShadeHeader,
    }

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val cutoutInsets: WindowInsets? = cutoutInsetsProvider()
        val constraintsWithCutout = applyCutout(constraints, cutoutInsets)
        val insetsLeft = cutoutInsets?.getLeft(this, layoutDirection) ?: 0
        val insetsTop = cutoutInsets?.getTop(this) ?: 0

        val shadeHeaderPlaceable =
            measurables
                .fastFirst { it.layoutId == LayoutId.ShadeHeader }
                .measure(constraintsWithCutout)
        val mediaPlaceable =
            measurables
                .fastFirstOrNull { it.layoutId == LayoutId.Media }
                ?.measure(applyMediaConstraints(constraintsWithCutout, isMediaInRow))
        val quickSettingsPlaceable =
            measurables
                .fastFirst { it.layoutId == LayoutId.QuickSettings }
                .measure(constraintsWithCutout)
        val notificationsPlaceable =
            measurables.fastFirst { it.layoutId == LayoutId.Notifications }.measure(constraints)

        val notificationsTop =
            calculateNotificationsTop(
                statusBarHeaderPlaceable = shadeHeaderPlaceable,
                quickSettingsPlaceable = quickSettingsPlaceable,
                mediaPlaceable = mediaPlaceable,
                insetsTop = insetsTop,
                isMediaInRow = isMediaInRow,
            )
        onNotificationsTopChanged(notificationsTop)

        return layout(constraints.maxWidth, constraints.maxHeight) {
            shadeHeaderPlaceable.placeRelative(x = insetsLeft, y = insetsTop)
            quickSettingsPlaceable.placeRelative(
                x = insetsLeft,
                y = insetsTop + shadeHeaderPlaceable.height,
            )

            if (isMediaInRow) {
                mediaPlaceable?.placeRelative(
                    x = insetsLeft + constraintsWithCutout.maxWidth / 2,
                    y = mediaOffset() + insetsTop + shadeHeaderPlaceable.height,
                    zIndex = mediaZIndex(),
                )
            } else {
                mediaPlaceable?.placeRelative(
                    x = insetsLeft,
                    y = insetsTop + shadeHeaderPlaceable.height + quickSettingsPlaceable.height,
                    zIndex = mediaZIndex(),
                )
            }

            // Notifications don't need to accommodate for horizontal insets
            notificationsPlaceable.placeRelative(x = 0, y = notificationsTop)
        }
    }

    private fun calculateNotificationsTop(
        statusBarHeaderPlaceable: Placeable,
        quickSettingsPlaceable: Placeable,
        mediaPlaceable: Placeable?,
        insetsTop: Int,
        isMediaInRow: Boolean,
    ): Int {
        val mediaHeight = mediaPlaceable?.height ?: 0
        return insetsTop +
            statusBarHeaderPlaceable.height +
            if (isMediaInRow) {
                max(quickSettingsPlaceable.height, mediaHeight)
            } else {
                quickSettingsPlaceable.height + mediaHeight
            }
    }

    private fun applyMediaConstraints(
        constraints: Constraints,
        isMediaInRow: Boolean,
    ): Constraints {
        return if (isMediaInRow) {
            constraints.copy(maxWidth = constraints.maxWidth / 2)
        } else {
            constraints
        }
    }

    private fun MeasureScope.applyCutout(
        constraints: Constraints,
        cutoutInsets: WindowInsets?,
    ): Constraints {
        return if (cutoutInsets == null) {
            constraints
        } else {
            val left = cutoutInsets.getLeft(this, layoutDirection)
            val top = cutoutInsets.getTop(this)
            val right = cutoutInsets.getRight(this, layoutDirection)
            val bottom = cutoutInsets.getBottom(this)

            constraints.offset(horizontal = -(left + right), vertical = -(top + bottom))
        }
    }
}
