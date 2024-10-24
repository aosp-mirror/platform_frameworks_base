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

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.compose.ui.util.SpaceVectorConverter
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A [NestedScrollConnection] that intercepts scroll events in priority mode.
 *
 * Priority mode allows this connection to take control over scroll events within a nested scroll
 * hierarchy. When in priority mode, this connection consumes scroll events before its children,
 * enabling custom scrolling behaviors like sticky headers.
 *
 * @param orientation The orientation of the scroll.
 * @param canStartPreScroll lambda that returns true if the connection can start consuming scroll
 *   events in pre-scroll mode.
 * @param canStartPostScroll lambda that returns true if the connection can start consuming scroll
 *   events in post-scroll mode.
 * @param canStartPostFling lambda that returns true if the connection can start consuming scroll
 *   events in post-fling mode.
 * @param canStopOnScroll lambda that returns true if the connection can stop consuming scroll
 *   events in scroll mode.
 * @param canStopOnPreFling lambda that returns true if the connection can stop consuming scroll
 *   events in pre-fling (i.e. as soon as the user lifts their fingers).
 * @param onStart lambda that is called when the connection starts consuming scroll events.
 * @param onScroll lambda that is called when the connection consumes a scroll event and returns the
 *   consumed amount.
 * @param onStop lambda that is called when the connection stops consuming scroll events and returns
 *   the consumed velocity.
 * @param onCancel lambda that is called when the connection is cancelled.
 * @sample LargeTopAppBarNestedScrollConnection
 * @sample com.android.compose.animation.scene.NestedScrollHandlerImpl.nestedScrollConnection
 */
class PriorityNestedScrollConnection(
    orientation: Orientation,
    private val canStartPreScroll:
        (offsetAvailable: Float, offsetBeforeStart: Float, source: NestedScrollSource) -> Boolean,
    private val canStartPostScroll:
        (offsetAvailable: Float, offsetBeforeStart: Float, source: NestedScrollSource) -> Boolean,
    private val canStartPostFling: (velocityAvailable: Float) -> Boolean,
    private val canStopOnScroll: (available: Float, consumed: Float) -> Boolean = { _, consumed ->
        consumed == 0f
    },
    private val canStopOnPreFling: () -> Boolean,
    private val onStart: (offsetAvailable: Float) -> Unit,
    private val onScroll: (offsetAvailable: Float, source: NestedScrollSource) -> Float,
    private val onStop: suspend (velocityAvailable: Float) -> Float,
    private val onCancel: () -> Unit,
) : NestedScrollConnection, SpaceVectorConverter by SpaceVectorConverter(orientation) {

    /** In priority mode [onPreScroll] events are first consumed by the parent, via [onScroll]. */
    private var isPriorityMode = false

    private var offsetScrolledBeforePriorityMode = 0f

    /** This job allows us to interrupt the onStop animation */
    private var onStopJob: Deferred<Float> = CompletableDeferred(0f)

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val availableFloat = available.toFloat()
        // The offset before the start takes into account the up and down movements, starting from
        // the beginning or from the last fling gesture.
        val offsetBeforeStart = offsetScrolledBeforePriorityMode - availableFloat

        if (isPriorityMode || !canStartPostScroll(availableFloat, offsetBeforeStart, source)) {
            // The priority mode cannot start so we won't consume the available offset.
            return Offset.Zero
        }

        return start(availableFloat, source).toOffset()
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isPriorityMode) {
            val availableFloat = available.toFloat()
            if (canStartPreScroll(availableFloat, offsetScrolledBeforePriorityMode, source)) {
                return start(availableFloat, source).toOffset()
            }
            // We want to track the amount of offset consumed before entering priority mode
            offsetScrolledBeforePriorityMode += availableFloat
            return Offset.Zero
        }

        return scroll(available.toFloat(), source).toOffset()
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!isPriorityMode) {
            resetOffsetTracker()
            return Velocity.Zero
        }

        if (canStopOnPreFling()) {
            // Step 3b: The finger is lifted, we can stop intercepting scroll events and use the
            // velocity of the fling gesture.
            return stop(velocityAvailable = available.toFloat()).toVelocity()
        }

        // We don't want to consume the velocity, we prefer to continue receiving scroll events.
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val availableFloat = available.toFloat()
        if (isPriorityMode) {
            return stop(velocityAvailable = availableFloat).toVelocity()
        }

        if (!canStartPostFling(availableFloat)) {
            return Velocity.Zero
        }

        // The offset passed to onPriorityStart() must be != 0f, so we create a small offset of 1px
        // given the available velocity.
        // TODO(b/291053278): Remove canStartPostFling() and instead make it possible to define the
        // overscroll behavior on the Scene level.
        val smallOffset = availableFloat.sign
        start(
            availableOffset = smallOffset,
            source = NestedScrollSource.SideEffect,
            skipScroll = true,
        )

        // This is the last event of a scroll gesture.
        return stop(availableFloat).toVelocity()
    }

    /**
     * Method to call before destroying the object or to reset the initial state.
     *
     * TODO(b/303224944) This method should be removed.
     */
    fun reset() {
        if (isPriorityMode) {
            // Step 3c: To ensure that an onStop (or onCancel) is always called for every onStart.
            cancel()
        } else {
            resetOffsetTracker()
        }
    }

    private fun start(
        availableOffset: Float,
        source: NestedScrollSource,
        skipScroll: Boolean = false,
    ): Float {
        check(!isPriorityMode) {
            "This should never happen, start() was called when isPriorityMode"
        }

        // Step 1: It's our turn! We start capturing scroll events when one of our children has an
        // available offset following a scroll event.
        isPriorityMode = true

        onStopJob.cancel()

        // Note: onStop will be called if we cannot continue to scroll (step 3a), or the finger is
        // lifted (step 3b), or this object has been destroyed (step 3c).
        onStart(availableOffset)

        return if (skipScroll) 0f else scroll(availableOffset, source)
    }

    private fun scroll(offsetAvailable: Float, source: NestedScrollSource): Float {
        // Step 2: We have the priority and can consume the scroll events.
        val consumedByScroll = onScroll(offsetAvailable, source)

        if (canStopOnScroll(offsetAvailable, consumedByScroll)) {
            // Step 3a: We have lost priority and we no longer need to intercept scroll events.
            cancel()

            // We've just reset offsetScrolledBeforePriorityMode to 0f
            // We want to track the amount of offset consumed before entering priority mode
            offsetScrolledBeforePriorityMode += offsetAvailable - consumedByScroll
        }

        return consumedByScroll
    }

    /** Reset the tracking of consumed offsets before entering in priority mode. */
    private fun resetOffsetTracker() {
        offsetScrolledBeforePriorityMode = 0f
    }

    private suspend fun stop(velocityAvailable: Float): Float {
        check(isPriorityMode) { "This should never happen, stop() was called before start()" }
        isPriorityMode = false
        resetOffsetTracker()

        return coroutineScope {
            onStopJob = async { onStop(velocityAvailable) }
            onStopJob.await()
        }
    }

    private fun cancel() {
        check(isPriorityMode) { "This should never happen, cancel() was called before start()" }
        isPriorityMode = false
        resetOffsetTracker()
        onCancel()
    }
}
