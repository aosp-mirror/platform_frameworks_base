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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.compose.ui.util.SpaceVectorConverter
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
    orientation: Orientation,
    private val animationScope: CoroutineScope,
    private val animationSpec: AnimationSpec<Float>,
) : ContentOverscrollEffect, SpaceVectorConverter by SpaceVectorConverter(orientation) {

    /** The [Animatable] that holds the current overscroll value. */
    private val animatable = Animatable(initialValue = 0f, visibilityThreshold = 0.5f)

    override val overscrollDistance: Float
        get() = animatable.value

    override val isInProgress: Boolean
        get() = overscrollDistance != 0f

    override fun applyToScroll(
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
        // We launch a coroutine to ensure the fling animation starts after any pending [snapTo]
        // animations have finished.
        // This guarantees a smooth, sequential execution of animations on the overscroll value.
        coroutineScope {
            launch {
                val consumed = performFling(velocity)
                val remaining = velocity - consumed
                animatable.animateTo(0f, animationSpec, remaining.toFloat())
            }
        }
    }
}

/** An overscroll effect that ensures only a single fling animation is triggered. */
internal class GestureEffect(private val delegate: ContentOverscrollEffect) :
    ContentOverscrollEffect by delegate {
    private var shouldFling = false

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        shouldFling = true
        return delegate.applyToScroll(delta, source, performScroll)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        if (!shouldFling) {
            performFling(velocity)
            return
        }
        shouldFling = false
        delegate.applyToFling(velocity, performFling)
    }

    suspend fun ensureApplyToFlingIsCalled() {
        applyToFling(Velocity.Zero) { Velocity.Zero }
    }
}

/**
 * An overscroll effect that only applies visual effects and does not interfere with the actual
 * scrolling or flinging behavior.
 */
internal class VisualEffect(private val delegate: ContentOverscrollEffect) :
    ContentOverscrollEffect by delegate {
    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        return performScroll(delta)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        performFling(velocity)
    }
}
