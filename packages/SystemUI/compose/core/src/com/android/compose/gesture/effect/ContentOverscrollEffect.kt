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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.compose.ui.util.HorizontalSpaceVectorConverter
import com.android.compose.ui.util.SpaceVectorConverter
import com.android.compose.ui.util.VerticalSpaceVectorConverter
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * An [OverscrollEffect] that uses an [Animatable] to track and animate overscroll values along a
 * specific [Orientation].
 */
interface ContentOverscrollEffect : OverscrollEffect {
    /** The current overscroll value. */
    val overscrollDistance: Float
}

open class BaseContentOverscrollEffect(
    private val animationScope: CoroutineScope,
    private val animationSpec: AnimationSpec<Float>,
) : ContentOverscrollEffect {
    /** The [Animatable] that holds the current overscroll value. */
    private val animatable = Animatable(initialValue = 0f)
    private var lastConverter: SpaceVectorConverter? = null

    override val overscrollDistance: Float
        get() = animatable.value

    override val isInProgress: Boolean
        get() = overscrollDistance != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val converter = converterOrNull(delta.x, delta.y) ?: return performScroll(delta)
        return converter.applyToScroll(delta, source, performScroll)
    }

    private fun SpaceVectorConverter.applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val deltaForAxis = delta.toFloat()

        // If we're currently overscrolled, and the user scrolls in the opposite direction, we need
        // to "relax" the overscroll by consuming some of the scroll delta to bring it back towards
        // zero.
        val currentOffset = animatable.value
        val sameDirection = deltaForAxis.sign == currentOffset.sign
        val consumedByPreScroll =
            if (abs(currentOffset) > 0.5 && !sameDirection) {
                    // The user has scrolled in the opposite direction.
                    val prevOverscrollValue = currentOffset
                    val newOverscrollValue = currentOffset + deltaForAxis
                    if (sign(prevOverscrollValue) != sign(newOverscrollValue)) {
                        // Enough to completely cancel the overscroll. We snap the overscroll value
                        // back to zero and consume the corresponding amount of the scroll delta.
                        animationScope.launch { animatable.snapTo(0f) }
                        -prevOverscrollValue
                    } else {
                        // Not enough to cancel the overscroll. We update the overscroll value
                        // accordingly and consume the entire scroll delta.
                        animationScope.launch { animatable.snapTo(newOverscrollValue) }
                        deltaForAxis
                    }
                } else {
                    0f
                }
                .toOffset()

        // After handling any overscroll relaxation, we pass the remaining scroll delta to the
        // standard scrolling logic.
        val leftForScroll = delta - consumedByPreScroll
        val consumedByScroll = performScroll(leftForScroll)
        val overscrollDelta = leftForScroll - consumedByScroll

        // If the user is dragging (not flinging), and there's any remaining scroll delta after the
        // standard scrolling logic has been applied, we add it to the overscroll.
        if (abs(overscrollDelta.toFloat()) > 0.5 && source == NestedScrollSource.UserInput) {
            animationScope.launch { animatable.snapTo(currentOffset + overscrollDelta.toFloat()) }
        }

        return delta
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val converter = converterOrNull(velocity.x, velocity.y) ?: return
        converter.applyToFling(velocity, performFling)
    }

    private suspend fun SpaceVectorConverter.applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        // We launch a coroutine to ensure the fling animation starts after any pending [snapTo]
        // animations have finished.
        // This guarantees a smooth, sequential execution of animations on the overscroll value.
        coroutineScope {
            launch {
                val consumed = performFling(velocity)
                val remaining = velocity - consumed
                animatable.animateTo(
                    0f,
                    animationSpec.withVisibilityThreshold(1f),
                    remaining.toFloat(),
                )
            }
        }
    }

    private fun <T> AnimationSpec<T>.withVisibilityThreshold(
        visibilityThreshold: T
    ): AnimationSpec<T> {
        return when (this) {
            is SpringSpec ->
                spring(
                    stiffness = stiffness,
                    dampingRatio = dampingRatio,
                    visibilityThreshold = visibilityThreshold,
                )
            else -> this
        }
    }

    protected fun requireConverter(): SpaceVectorConverter {
        return checkNotNull(lastConverter) {
            "lastConverter is null, make sure to call requireConverter() only when " +
                "overscrollDistance != 0f"
        }
    }

    private fun converterOrNull(x: Float, y: Float): SpaceVectorConverter? {
        val converter: SpaceVectorConverter =
            when {
                x != 0f && y != 0f ->
                    error(
                        "BaseContentOverscrollEffect only supports single orientation scrolls " +
                            "and velocities"
                    )
                x == 0f && y == 0f -> lastConverter ?: return null
                x != 0f -> HorizontalSpaceVectorConverter
                else -> VerticalSpaceVectorConverter
            }

        if (lastConverter != null) {
            check(lastConverter == converter) {
                "BaseContentOverscrollEffect should always be used in the same orientation"
            }
        } else {
            lastConverter = converter
        }

        return converter
    }
}
