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

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.compose.ui.util.SpaceVectorConverter
import kotlin.math.sign
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The [ScrollController] provides control over the scroll gesture. It allows you to:
 * - Scroll the content by a given pixel amount.
 * - Cancel the current scroll operation.
 * - Stop the scrolling with a given initial velocity.
 *
 * **Important Notes:**
 * - [onCancel] is called only when [PriorityNestedScrollConnection.reset] is invoked or when
 *   [canCancelScroll] returns `true` after a call to [onScroll]. It is never called after [onStop].
 * - [onStop] can be interrupted by a new gesture. In such cases, you need to handle a potential
 *   cancellation within your implementation of [onStop], although [onCancel] will not be called.
 */
interface ScrollController {
    /**
     * Scrolls the current content by [deltaScroll] pixels.
     *
     * @param deltaScroll The amount of pixels to scroll by.
     * @param source The source of the scroll event.
     * @return The amount of [deltaScroll] that was consumed.
     */
    fun onScroll(deltaScroll: Float, source: NestedScrollSource): Float

    /**
     * Checks if the current scroll operation can be canceled. This is typically called after
     * [onScroll] to determine if the [ScrollController] has lost priority and should cancel the
     * ongoing scroll operation.
     *
     * @param available The total amount of scroll available.
     * @param consumed The amount of scroll consumed by [onScroll].
     * @return `true` if the scroll can be canceled.
     */
    fun canCancelScroll(available: Float, consumed: Float): Boolean {
        return consumed == 0f
    }

    /**
     * Cancels the current scroll operation. This method is called when
     * [PriorityNestedScrollConnection.reset] is invoked or when [canCancelScroll] returns `true`.
     */
    fun onCancel()

    /**
     * Checks if the scroll can be stopped during the [NestedScrollConnection.onPreFling] phase.
     *
     * @return `true` if the scroll can be stopped.
     */
    fun canStopOnPreFling(): Boolean

    /**
     * Stops the controller with the given [initialVelocity]. This typically starts a decay
     * animation to smoothly bring the scrolling to a stop. This method can be interrupted by a new
     * gesture, requiring you to handle potential cancellation within your implementation.
     *
     * @param initialVelocity The initial velocity of the scroll when stopping.
     * @return The consumed [initialVelocity] when the animation completes.
     */
    suspend fun OnStopScope.onStop(initialVelocity: Float): Float
}

interface OnStopScope {
    /**
     * Emits scroll events by using the [initialVelocity] and the [FlingBehavior].
     *
     * @return consumed velocity
     */
    suspend fun flingToScroll(initialVelocity: Float, flingBehavior: FlingBehavior): Float
}

/**
 * A [NestedScrollConnection] that lets you implement custom scroll behaviors that take priority
 * over the default nested scrolling logic.
 *
 * When started, this connection intercepts scroll events *before* they reach child composables.
 * This "priority mode" is activated activated when either [canStartPreScroll], [canStartPostScroll]
 * or [canStartPostFling] returns `true`.
 *
 * Once started, the [onStart] lambda provides a [ScrollController] to manage the scrolling. This
 * controller allows you to directly manipulate the scroll state and define how scroll events are
 * consumed.
 *
 * **Important Considerations:**
 * - When started, scroll events are typically consumed in `onPreScroll`.
 * - The provided [ScrollController] should handle potential cancellation of `onStop` due to new
 *   gestures.
 * - Use [reset] to release the current [ScrollController] and reset the connection to its initial
 *   state.
 *
 * @param orientation The orientation of the scroll.
 * @param canStartPreScroll A lambda that returns `true` if the connection should enter priority
 *   mode during the pre-scroll phase. This is called before child connections have a chance to
 *   consume the scroll.
 * @param canStartPostScroll A lambda that returns `true` if the connection should enter priority
 *   mode during the post-scroll phase. This is called after child connections have consumed the
 *   scroll.
 * @param canStartPostFling A lambda that returns `true` if the connection should enter priority
 *   mode during the post-fling phase. This is called after a fling gesture has been initiated.
 * @param onStart A lambda that is called when the connection enters priority mode. It should return
 *   a [ScrollController] that will be used to control the scroll.
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
    private val onStart: (firstScroll: Float) -> ScrollController,
) : NestedScrollConnection, SpaceVectorConverter by SpaceVectorConverter(orientation) {

    /** The currently active [ScrollController], or `null` if not in priority mode. */
    private var currentController: ScrollController? = null

    /**
     * A [Deferred] representing the ongoing `onStop` animation. Used to interrupt the animation if
     * a new gesture occurs.
     */
    private var stoppingJob: Deferred<Float>? = null

    /**
     * Indicates whether the connection is currently in the process of stopping the scroll with the
     * [ScrollController.onStop] animation.
     */
    private val isStopping
        get() = stoppingJob?.isActive ?: false

    /**
     * Tracks the cumulative scroll offset that has been consumed by other composables before this
     * connection enters priority mode. This is used to determine when the connection should take
     * over scrolling based on the [canStartPreScroll] and [canStartPostScroll] conditions.
     */
    private var offsetScrolledBeforePriorityMode = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // If stopping, interrupt the animation and clear the controller.
        if (isStopping) {
            interruptStopping()
        }

        // If in priority mode, consume the scroll using the current controller.
        if (currentController != null) {
            return scroll(available.toFloat(), source)
        }

        // Check if pre-scroll condition is met, and start priority mode if necessary.
        val availableFloat = available.toFloat()
        if (canStartPreScroll(availableFloat, offsetScrolledBeforePriorityMode, source)) {
            start(availableFloat)
            return scroll(availableFloat, source)
        }

        // Track offset consumed before entering priority mode.
        offsetScrolledBeforePriorityMode += availableFloat
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // If in priority mode, scroll events are consumed only in pre-scroll phase.
        if (currentController != null) return Offset.Zero

        // Check if post-scroll condition is met, and start priority mode if necessary.
        val availableFloat = available.toFloat()
        val offsetBeforeStart = offsetScrolledBeforePriorityMode - availableFloat
        if (canStartPostScroll(availableFloat, offsetBeforeStart, source)) {
            start(availableFloat)
            return scroll(availableFloat, source)
        }

        // Do not consume the offset if priority mode is not activated.
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // Note: This method may be called multiple times. Due to NestedScrollDispatcher, the order
        // of method calls (pre/post scroll/fling) cannot be guaranteed.
        if (isStopping) return Velocity.Zero
        val controller = currentController ?: return Velocity.Zero

        // If in priority mode and can stop on pre-fling phase, stop the scroll.
        if (controller.canStopOnPreFling()) {
            return stop(velocity = available.toFloat())
        }

        // Do not consume the velocity if not stopping on pre-fling phase.
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // Note: This method may be called multiple times. Due to NestedScrollDispatcher, the order
        // of method calls (pre/post scroll/fling) cannot be guaranteed.
        if (isStopping) return Velocity.Zero
        val availableFloat = available.toFloat()
        val controller = currentController

        // If in priority mode, stop the scroll.
        if (controller != null) {
            return stop(velocity = availableFloat)
        }

        // Check if post-fling condition is met, and start priority mode if necessary.
        // TODO(b/291053278): Remove canStartPostFling() and instead make it possible to define the
        // overscroll behavior on the Scene level.
        if (canStartPostFling(availableFloat)) {
            // The offset passed to onPriorityStart() must be != 0f, so we create a small offset of
            // 1px given the available velocity.
            val smallOffset = availableFloat.sign
            start(availableOffset = smallOffset)
            return stop(availableFloat)
        }

        // Reset offset tracking after the fling gesture is finished.
        resetOffsetTracker()
        return Velocity.Zero
    }

    /**
     * Resets the connection to its initial state. This cancels any ongoing scroll operation and
     * clears the current [ScrollController].
     */
    fun reset() {
        if (currentController != null && !isStopping) {
            cancel()
        } else {
            resetOffsetTracker()
        }
    }

    /**
     * Starts priority mode by creating a new [ScrollController] using the [onStart] lambda.
     *
     * @param availableOffset The initial scroll offset available.
     */
    private fun start(availableOffset: Float) {
        check(currentController == null) { "Another controller is active: $currentController" }

        resetOffsetTracker()

        currentController = onStart(availableOffset)
    }

    /**
     * Retrieves the current [ScrollController], ensuring that it is not null and that the
     * [isStopping] state matches the expected value.
     */
    private fun requireController(isStopping: Boolean): ScrollController {
        check(this.isStopping == isStopping) {
            "isStopping is ${this.isStopping}, instead of $isStopping"
        }
        check(offsetScrolledBeforePriorityMode == 0f) {
            "offset scrolled should be zero, but it was $offsetScrolledBeforePriorityMode"
        }
        return checkNotNull(currentController) { "The controller is $currentController" }
    }

    /**
     * Scrolls the content using the current [ScrollController].
     *
     * @param delta The amount of scroll to apply.
     * @param source The source of the scroll event.
     * @return The amount of scroll consumed.
     */
    private fun scroll(delta: Float, source: NestedScrollSource): Offset {
        val controller = requireController(isStopping = false)
        val consumedByScroll = controller.onScroll(delta, source)

        if (controller.canCancelScroll(delta, consumedByScroll)) {
            // We have lost priority and we no longer need to intercept scroll events.
            cancel()
            offsetScrolledBeforePriorityMode = delta - consumedByScroll
        }

        return consumedByScroll.toOffset()
    }

    /** Cancels the current scroll operation and clears the current [ScrollController]. */
    private fun cancel() {
        requireController(isStopping = false).onCancel()
        currentController = null
    }

    /**
     * Stops the scroll with the given velocity using the current [ScrollController].
     *
     * @param velocity The velocity to stop with.
     * @return The consumed velocity.
     */
    suspend fun stop(velocity: Float): Velocity {
        if (isStopping) return Velocity.Zero
        val controller = requireController(isStopping = false)
        return coroutineScope {
            try {
                async {
                        with(controller) {
                            OnStopScopeImpl(controller = controller).onStop(velocity)
                        }
                    }
                    // Allows others to interrupt the job.
                    .also { stoppingJob = it }
                    // Note: this can be cancelled by [interruptStopping]
                    .await()
                    .toVelocity()
            } finally {
                // If the job is interrupted, it might take a while to cancel. We need to make sure
                // the current controller is still the initial one.
                if (currentController == controller) {
                    currentController = null
                }
            }
        }
    }

    /** Interrupts the ongoing stop animation and clears the current [ScrollController]. */
    private fun interruptStopping() {
        requireController(isStopping = true)
        // We are throwing a CancellationException in the [ScrollController.onStop] method.
        stoppingJob?.cancel()
        currentController = null
    }

    /** Resets the tracking of consumed offsets before entering priority mode. */
    private fun resetOffsetTracker() {
        offsetScrolledBeforePriorityMode = 0f
    }
}

private class OnStopScopeImpl(private val controller: ScrollController) : OnStopScope {
    override suspend fun flingToScroll(
        initialVelocity: Float,
        flingBehavior: FlingBehavior,
    ): Float {
        return with(flingBehavior) {
            val remainingVelocity =
                object : ScrollScope {
                        override fun scrollBy(pixels: Float): Float {
                            return controller.onScroll(pixels, NestedScrollSource.SideEffect)
                        }
                    }
                    .performFling(initialVelocity)

            // returns the consumed velocity
            initialVelocity - remainingVelocity
        }
    }
}
