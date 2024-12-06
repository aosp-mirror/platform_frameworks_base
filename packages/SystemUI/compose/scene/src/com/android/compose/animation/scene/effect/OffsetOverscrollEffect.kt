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

package com.android.compose.animation.scene.effect

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ProgressConverter
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

/** An [OverscrollEffect] that offsets the content by the overscroll value. */
class OffsetOverscrollEffect(
    orientation: Orientation,
    animationScope: CoroutineScope,
    animationSpec: AnimationSpec<Float> = DefaultAnimationSpec,
) : BaseContentOverscrollEffect(orientation, animationScope, animationSpec) {
    private var _node: DelegatableNode = newNode()
    override val node: DelegatableNode
        get() = _node

    fun newNode(): DelegatableNode {
        return object : Modifier.Node(), LayoutModifierNode {
            override fun onDetach() {
                super.onDetach()
                // TODO(b/379086317) Remove this workaround: avoid to reuse the same node.
                _node = newNode()
            }

            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints,
            ): MeasureResult {
                val placeable = measurable.measure(constraints)
                return layout(placeable.width, placeable.height) {
                    val offsetPx = computeOffset(density = this@measure, overscrollDistance)
                    placeable.placeRelativeWithLayer(position = offsetPx.toIntOffset())
                }
            }
        }
    }

    companion object {
        private val MaxDistance = 400.dp

        internal val DefaultAnimationSpec =
            spring(
                stiffness = Spring.StiffnessLow,
                dampingRatio = Spring.DampingRatioLowBouncy,
                visibilityThreshold = 0.5f,
            )

        @VisibleForTesting
        internal fun computeOffset(density: Density, overscrollDistance: Float): Int {
            val maxDistancePx = with(density) { MaxDistance.toPx() }
            val progress = ProgressConverter.Default.convert(overscrollDistance / maxDistancePx)
            return (progress * maxDistancePx).roundToInt()
        }
    }
}

@Composable
fun rememberOffsetOverscrollEffect(
    orientation: Orientation,
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
): OffsetOverscrollEffect {
    val animationScope = rememberCoroutineScope()
    return remember(orientation, animationScope, animationSpec) {
        OffsetOverscrollEffect(orientation, animationScope, animationSpec)
    }
}
