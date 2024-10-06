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

package com.android.compose.nestedscroll

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.compose.ui.util.SpaceVectorConverter
import kotlin.math.sign

internal typealias SuspendedValue<T> = suspend () -> T

/**
 * This [NestedScrollConnection] waits for a child to scroll ([onPreScroll] or [onPostScroll]), and
 * then decides (via [canStartPreScroll] or [canStartPostScroll]) if it should take over scrolling.
 * If it does, it will scroll before its children, until [canContinueScroll] allows it.
 *
 * Note: Call [reset] before destroying this object to make sure you always get a call to [onStop]
 * after [onStart].
 *
 * @sample LargeTopAppBarNestedScrollConnection
 * @sample com.android.compose.animation.scene.NestedScrollHandlerImpl.nestedScrollConnection
 */
class PriorityNestedScrollConnection(
    orientation: Orientation,
    private val canStartPreScroll: (offsetAvailable: Float, offsetBeforeStart: Float) -> Boolean,
    private val canStartPostScroll: (offsetAvailable: Float, offsetBeforeStart: Float) -> Boolean,
    private val canStartPostFling: (velocityAvailable: Float) -> Boolean,
    private val canContinueScroll: (source: NestedScrollSource) -> Boolean,
    private val canScrollOnFling: Boolean,
    private val onStart: (offsetAvailable: Float) -> Unit,
    private val onScroll: (offsetAvailable: Float) -> Float,
    private val onStop: (velocityAvailable: Float) -> SuspendedValue<Float>,
) : NestedScrollConnection, SpaceVectorConverter by SpaceVectorConverter(orientation) {

    /** In priority mode [onPreScroll] events are first consumed by the parent, via [onScroll]. */
    private var isPriorityMode = false

    private var offsetScrolledBeforePriorityMode = 0f

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val availableFloat = available.toFloat()
        // The offset before the start takes into account the up and down movements, starting from
        // the beginning or from the last fling gesture.
        val offsetBeforeStart = offsetScrolledBeforePriorityMode - availableFloat

        if (
            isPriorityMode ||
                (source == NestedScrollSource.SideEffect && !canScrollOnFling) ||
                !canStartPostScroll(availableFloat, offsetBeforeStart)
        ) {
            // The priority mode cannot start so we won't consume the available offset.
            return Offset.Zero
        }

        return onPriorityStart(availableFloat).toOffset()
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isPriorityMode) {
            if (source == NestedScrollSource.UserInput || canScrollOnFling) {
                val availableFloat = available.toFloat()
                if (canStartPreScroll(availableFloat, offsetScrolledBeforePriorityMode)) {
                    return onPriorityStart(availableFloat).toOffset()
                }
                // We want to track the amount of offset consumed before entering priority mode
                offsetScrolledBeforePriorityMode += availableFloat
            }

            return Offset.Zero
        }

        val availableFloat = available.toFloat()
        if (!canContinueScroll(source)) {
            // Step 3a: We have lost priority and we no longer need to intercept scroll events.
            onPriorityStop(velocity = 0f)

            // We've just reset offsetScrolledBeforePriorityMode to 0f
            // We want to track the amount of offset consumed before entering priority mode
            offsetScrolledBeforePriorityMode += availableFloat

            return Offset.Zero
        }

        // Step 2: We have the priority and can consume the scroll events.
        return onScroll(availableFloat).toOffset()
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (isPriorityMode && canScrollOnFling) {
            // We don't want to consume the velocity, we prefer to continue receiving scroll events.
            return Velocity.Zero
        }
        // Step 3b: The finger is lifted, we can stop intercepting scroll events and use the speed
        // of the fling gesture.
        return onPriorityStop(velocity = available.toFloat()).invoke().toVelocity()
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val availableFloat = available.toFloat()
        if (isPriorityMode) {
            return onPriorityStop(velocity = availableFloat).invoke().toVelocity()
        }

        if (!canStartPostFling(availableFloat)) {
            return Velocity.Zero
        }

        // The offset passed to onPriorityStart() must be != 0f, so we create a small offset of 1px
        // given the available velocity.
        // TODO(b/291053278): Remove canStartPostFling() and instead make it possible to define the
        // overscroll behavior on the Scene level.
        val smallOffset = availableFloat.sign
        onPriorityStart(availableOffset = smallOffset)

        // This is the last event of a scroll gesture.
        return onPriorityStop(availableFloat).invoke().toVelocity()
    }

    /**
     * Method to call before destroying the object or to reset the initial state.
     *
     * TODO(b/303224944) This method should be removed.
     */
    fun reset() {
        // Step 3c: To ensure that an onStop is always called for every onStart.
        onPriorityStop(velocity = 0f)
    }

    private fun onPriorityStart(availableOffset: Float): Float {
        if (isPriorityMode) {
            error("This should never happen, onPriorityStart() was called when isPriorityMode")
        }

        // Step 1: It's our turn! We start capturing scroll events when one of our children has an
        // available offset following a scroll event.
        isPriorityMode = true

        // Note: onStop will be called if we cannot continue to scroll (step 3a), or the finger is
        // lifted (step 3b), or this object has been destroyed (step 3c).
        onStart(availableOffset)

        return onScroll(availableOffset)
    }

    private fun onPriorityStop(velocity: Float): SuspendedValue<Float> {
        // We can restart tracking the consumed offsets from scratch.
        offsetScrolledBeforePriorityMode = 0f

        if (!isPriorityMode) {
            return { 0f }
        }

        isPriorityMode = false

        return onStop(velocity)
    }
}
