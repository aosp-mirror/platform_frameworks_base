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

package com.android.compose.gesture.effect

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun rememberOffsetOverscrollEffect(
    animationSpec: AnimationSpec<Float> = MaterialTheme.motionScheme.slowSpatialSpec()
): OffsetOverscrollEffect {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope, animationSpec) {
        OffsetOverscrollEffect(animationScope, animationSpec)
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun rememberOffsetOverscrollEffectFactory(
    animationSpec: AnimationSpec<Float> = MaterialTheme.motionScheme.slowSpatialSpec()
): OverscrollFactory {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope, animationSpec) {
        OffsetOverscrollEffectFactory(animationScope, animationSpec)
    }
}

data class OffsetOverscrollEffectFactory(
    private val animationScope: CoroutineScope,
    private val animationSpec: AnimationSpec<Float>,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect {
        return OffsetOverscrollEffect(animationScope, animationSpec)
    }
}

/** An [OverscrollEffect] that offsets the content by the overscroll value. */
class OffsetOverscrollEffect(animationScope: CoroutineScope, animationSpec: AnimationSpec<Float>) :
    BaseContentOverscrollEffect(animationScope, animationSpec) {
    override val node: DelegatableNode =
        object : Modifier.Node(), LayoutModifierNode {
            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints,
            ): MeasureResult {
                val placeable = measurable.measure(constraints)
                return layout(placeable.width, placeable.height) {
                    val offsetPx = computeOffset(density = this@measure, overscrollDistance)
                    if (offsetPx != 0) {
                        placeable.placeRelativeWithLayer(
                            with(requireConverter()) { offsetPx.toIntOffset() }
                        )
                    } else {
                        placeable.placeRelative(0, 0)
                    }
                }
            }
        }

    companion object {
        private val MaxDistance = 400.dp

        @VisibleForTesting
        fun computeOffset(density: Density, overscrollDistance: Float): Int {
            val maxDistancePx = with(density) { MaxDistance.toPx() }
            val progress = ProgressConverter.Default.convert(overscrollDistance / maxDistancePx)
            return (progress * maxDistancePx).roundToInt()
        }
    }
}

/** This converter lets you change a linear progress into a function of your choice. */
fun interface ProgressConverter {
    fun convert(progress: Float): Float

    companion object {
        /** Starts linearly with some resistance and slowly approaches to 0.2f */
        val Default = tanh(maxProgress = 0.2f, tilt = 3f)

        /**
         * The scroll stays linear, with [factor] you can control how much resistance there is.
         *
         * @param factor If you choose a value between 0f and 1f, the progress will grow more
         *   slowly, like there's resistance. A value of 1f means there's no resistance.
         */
        fun linear(factor: Float = 1f) = ProgressConverter { it * factor }

        /**
         * This function starts linear and slowly approaches [maxProgress].
         *
         * See a [visual representation](https://www.desmos.com/calculator/usgvvf0z1u) of this
         * function.
         *
         * @param maxProgress is the maximum progress value.
         * @param tilt behaves similarly to the factor in the [linear] function, and allows you to
         *   control how quickly you get to the [maxProgress].
         */
        fun tanh(maxProgress: Float, tilt: Float = 1f) = ProgressConverter {
            maxProgress * kotlin.math.tanh(x = it / (maxProgress * tilt))
        }
    }
}
