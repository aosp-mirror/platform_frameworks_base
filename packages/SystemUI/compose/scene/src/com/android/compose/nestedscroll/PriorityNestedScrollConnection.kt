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

/**
 * This [NestedScrollConnection] waits for a child to scroll ([onPreScroll] or [onPostScroll]), and
 * then decides (via [canStartPreScroll] or [canStartPostScroll]) if it should take over scrolling.
 * If it does, it will scroll before its children, until [canContinueScroll] allows it.
 *
 * Note: Call [reset] before destroying this object to make sure you always get a call to [onStop]
 * after [onStart].
 *
 * @sample com.android.compose.animation.scene.rememberSwipeToSceneNestedScrollConnection
 */
class PriorityNestedScrollConnection(
    private val canStartPreScroll: (offsetAvailable: Offset, offsetBeforeStart: Offset) -> Boolean,
    private val canStartPostScroll: (offsetAvailable: Offset, offsetBeforeStart: Offset) -> Boolean,
    private val canStartPostFling: (velocityAvailable: Velocity) -> Boolean,
    private val canContinueScroll: (source: NestedScrollSource) -> Boolean,
    private val canScrollOnFling: Boolean,
    private val onStart: (offsetAvailable: Offset) -> Unit,
    private val onScroll: (offsetAvailable: Offset) -> Offset,
    private val onStop: (velocityAvailable: Velocity) -> Velocity,
) : NestedScrollConnection {

    /** In priority mode [onPreScroll] events are first consumed by the parent, via [onScroll]. */
    private var isPriorityMode = false

    private var offsetScrolledBeforePriorityMode = Offset.Zero

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // The offset before the start takes into account the up and down movements, starting from
        // the beginning or from the last fling gesture.
        val offsetBeforeStart = offsetScrolledBeforePriorityMode - available

        if (
            isPriorityMode ||
                (source == NestedScrollSource.SideEffect && !canScrollOnFling) ||
                !canStartPostScroll(available, offsetBeforeStart)
        ) {
            // The priority mode cannot start so we won't consume the available offset.
            return Offset.Zero
        }

        return onPriorityStart(available)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isPriorityMode) {
            if (source == NestedScrollSource.UserInput || canScrollOnFling) {
                if (canStartPreScroll(available, offsetScrolledBeforePriorityMode)) {
                    return onPriorityStart(available)
                }
                // We want to track the amount of offset consumed before entering priority mode
                offsetScrolledBeforePriorityMode += available
            }

            return Offset.Zero
        }

        if (!canContinueScroll(source)) {
            // Step 3a: We have lost priority and we no longer need to intercept scroll events.
            onPriorityStop(velocity = Velocity.Zero)

            // We've just reset offsetScrolledBeforePriorityMode to Offset.Zero
            // We want to track the amount of offset consumed before entering priority mode
            offsetScrolledBeforePriorityMode += available

            return Offset.Zero
        }

        // Step 2: We have the priority and can consume the scroll events.
        return onScroll(available)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (isPriorityMode && canScrollOnFling) {
            // We don't want to consume the velocity, we prefer to continue receiving scroll events.
            return Velocity.Zero
        }
        // Step 3b: The finger is lifted, we can stop intercepting scroll events and use the speed
        // of the fling gesture.
        return onPriorityStop(velocity = available)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (isPriorityMode) {
            return onPriorityStop(velocity = available)
        }

        if (!canStartPostFling(available)) {
            return Velocity.Zero
        }

        // The offset passed to onPriorityStart() must be != 0f, so we create a small offset of 1px
        // given the available velocity.
        // TODO(b/291053278): Remove canStartPostFling() and instead make it possible to define the
        // overscroll behavior on the Scene level.
        val smallOffset = Offset(available.x.sign, available.y.sign)
        onPriorityStart(available = smallOffset)

        // This is the last event of a scroll gesture.
        return onPriorityStop(available)
    }

    /**
     * Method to call before destroying the object or to reset the initial state.
     *
     * TODO(b/303224944) This method should be removed.
     */
    fun reset() {
        // Step 3c: To ensure that an onStop is always called for every onStart.
        onPriorityStop(velocity = Velocity.Zero)
    }

    private fun onPriorityStart(available: Offset): Offset {
        if (isPriorityMode) {
            error("This should never happen, onPriorityStart() was called when isPriorityMode")
        }

        // Step 1: It's our turn! We start capturing scroll events when one of our children has an
        // available offset following a scroll event.
        isPriorityMode = true

        // Note: onStop will be called if we cannot continue to scroll (step 3a), or the finger is
        // lifted (step 3b), or this object has been destroyed (step 3c).
        onStart(available)

        return onScroll(available)
    }

    private fun onPriorityStop(velocity: Velocity): Velocity {
        // We can restart tracking the consumed offsets from scratch.
        offsetScrolledBeforePriorityMode = Offset.Zero

        if (!isPriorityMode) {
            return Velocity.Zero
        }

        isPriorityMode = false

        return onStop(velocity)
    }
}

fun PriorityNestedScrollConnection(
    orientation: Orientation,
    canStartPreScroll: (offsetAvailable: Float, offsetBeforeStart: Float) -> Boolean,
    canStartPostScroll: (offsetAvailable: Float, offsetBeforeStart: Float) -> Boolean,
    canStartPostFling: (velocityAvailable: Float) -> Boolean,
    canContinueScroll: (source: NestedScrollSource) -> Boolean,
    canScrollOnFling: Boolean,
    onStart: (offsetAvailable: Float) -> Unit,
    onScroll: (offsetAvailable: Float) -> Float,
    onStop: (velocityAvailable: Float) -> Float,
) =
    with(SpaceVectorConverter(orientation)) {
        PriorityNestedScrollConnection(
            canStartPreScroll = { offsetAvailable: Offset, offsetBeforeStart: Offset ->
                canStartPreScroll(offsetAvailable.toFloat(), offsetBeforeStart.toFloat())
            },
            canStartPostScroll = { offsetAvailable: Offset, offsetBeforeStart: Offset ->
                canStartPostScroll(offsetAvailable.toFloat(), offsetBeforeStart.toFloat())
            },
            canStartPostFling = { velocityAvailable: Velocity ->
                canStartPostFling(velocityAvailable.toFloat())
            },
            canContinueScroll = canContinueScroll,
            canScrollOnFling = canScrollOnFling,
            onStart = { offsetAvailable -> onStart(offsetAvailable.toFloat()) },
            onScroll = { offsetAvailable: Offset ->
                onScroll(offsetAvailable.toFloat()).toOffset()
            },
            onStop = { velocityAvailable: Velocity ->
                onStop(velocityAvailable.toFloat()).toVelocity()
            },
        )
    }
