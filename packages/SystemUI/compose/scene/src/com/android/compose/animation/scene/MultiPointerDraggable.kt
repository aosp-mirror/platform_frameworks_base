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

package com.android.compose.animation.scene

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastSumBy
import com.android.compose.ui.util.SpaceVectorConverter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sign
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Make an element draggable in the given [orientation].
 *
 * The main difference with [multiPointerDraggable] and
 * [androidx.compose.foundation.gestures.draggable] is that [onDragStarted] also receives the number
 * of pointers that are down when the drag is started. If you don't need this information, you
 * should use `draggable` instead.
 *
 * Note that the current implementation is trivial: we wait for the touch slope on the *first* down
 * pointer, then we count the number of distinct pointers that are down right before calling
 * [onDragStarted]. This means that the drag won't start when a first pointer is down (but not
 * dragged) and a second pointer is down and dragged. This is an implementation detail that might
 * change in the future.
 */
@VisibleForTesting
@Stable
internal fun Modifier.multiPointerDraggable(
    orientation: Orientation,
    enabled: () -> Boolean,
    startDragImmediately: (startedPosition: Offset) -> Boolean,
    onDragStarted: (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
    onFirstPointerDown: () -> Unit = {},
    swipeDetector: SwipeDetector = DefaultSwipeDetector,
    dispatcher: NestedScrollDispatcher,
): Modifier =
    this.then(
        MultiPointerDraggableElement(
            orientation,
            enabled,
            startDragImmediately,
            onDragStarted,
            onFirstPointerDown,
            swipeDetector,
            dispatcher,
        )
    )

private data class MultiPointerDraggableElement(
    private val orientation: Orientation,
    private val enabled: () -> Boolean,
    private val startDragImmediately: (startedPosition: Offset) -> Boolean,
    private val onDragStarted:
        (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
    private val onFirstPointerDown: () -> Unit,
    private val swipeDetector: SwipeDetector,
    private val dispatcher: NestedScrollDispatcher,
) : ModifierNodeElement<MultiPointerDraggableNode>() {
    override fun create(): MultiPointerDraggableNode =
        MultiPointerDraggableNode(
            orientation = orientation,
            enabled = enabled,
            startDragImmediately = startDragImmediately,
            onDragStarted = onDragStarted,
            onFirstPointerDown = onFirstPointerDown,
            swipeDetector = swipeDetector,
            dispatcher = dispatcher,
        )

    override fun update(node: MultiPointerDraggableNode) {
        node.orientation = orientation
        node.enabled = enabled
        node.startDragImmediately = startDragImmediately
        node.onDragStarted = onDragStarted
        node.onFirstPointerDown = onFirstPointerDown
        node.swipeDetector = swipeDetector
    }
}

internal class MultiPointerDraggableNode(
    orientation: Orientation,
    enabled: () -> Boolean,
    var startDragImmediately: (startedPosition: Offset) -> Boolean,
    var onDragStarted:
        (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
    var onFirstPointerDown: () -> Unit,
    var swipeDetector: SwipeDetector = DefaultSwipeDetector,
    private val dispatcher: NestedScrollDispatcher,
) :
    DelegatingNode(),
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    SpaceVectorConverter {
    private val pointerTracker = delegate(SuspendingPointerInputModifierNode { pointerTracker() })
    private val pointerInput = delegate(SuspendingPointerInputModifierNode { pointerInput() })
    private val velocityTracker = VelocityTracker()
    private var previousEnabled: Boolean = false

    var enabled: () -> Boolean = enabled
        set(value) {
            // Reset the pointer input whenever enabled changed.
            if (value != field) {
                field = value
                pointerInput.resetPointerInputHandler()
            }
        }

    private var converter = SpaceVectorConverter(orientation)

    override fun Offset.toFloat(): Float = with(converter) { this@toFloat.toFloat() }

    override fun Velocity.toFloat(): Float = with(converter) { this@toFloat.toFloat() }

    override fun Float.toOffset(): Offset = with(converter) { this@toOffset.toOffset() }

    override fun Float.toVelocity(): Velocity = with(converter) { this@toVelocity.toVelocity() }

    var orientation: Orientation = orientation
        set(value) {
            // Reset the pointer input whenever orientation changed.
            if (value != field) {
                field = value
                converter = SpaceVectorConverter(value)
                pointerInput.resetPointerInputHandler()
            }
        }

    override fun onAttach() {
        previousEnabled = enabled()
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads {
            val newEnabled = enabled()
            if (newEnabled != previousEnabled) {
                pointerInput.resetPointerInputHandler()
            }
            previousEnabled = newEnabled
        }
    }

    override fun onCancelPointerInput() {
        pointerTracker.onCancelPointerInput()
        pointerInput.onCancelPointerInput()
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        // The order is important here: the tracker is always called first.
        pointerTracker.onPointerEvent(pointerEvent, pass, bounds)
        pointerInput.onPointerEvent(pointerEvent, pass, bounds)
    }

    private var startedPosition: Offset? = null
    private var pointersDown: Int = 0

    internal fun pointersInfo(): PointersInfo {
        return PointersInfo(
            startedPosition = startedPosition,
            // Note: We could have 0 pointers during fling or for other reasons.
            pointersDown = pointersDown.coerceAtLeast(1),
        )
    }

    private suspend fun PointerInputScope.pointerTracker() {
        val currentContext = currentCoroutineContext()
        awaitPointerEventScope {
            var velocityPointerId: PointerId? = null
            // Intercepts pointer inputs and exposes [PointersInfo], via
            // [requireAncestorPointersInfoOwner], to our descendants.
            while (currentContext.isActive) {
                // During the Initial pass, we receive the event after our ancestors.
                val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                pointersDown = changes.countDown()

                when {
                    // There are no more pointers down.
                    pointersDown == 0 -> {
                        startedPosition = null

                        // In case of multiple events with 0 pointers down (not pressed) we may have
                        // already removed the velocityPointer
                        val lastPointerUp = changes.fastFilter { it.id == velocityPointerId }
                        check(lastPointerUp.isEmpty() || lastPointerUp.size == 1) {
                            "There are ${lastPointerUp.size} pointers up: $lastPointerUp"
                        }
                        if (lastPointerUp.size == 1) {
                            velocityTracker.addPointerInputChange(lastPointerUp.first())
                        }
                    }

                    // The first pointer down, startedPosition was not set.
                    startedPosition == null -> {
                        val firstPointerDown = changes.single()
                        velocityPointerId = firstPointerDown.id
                        velocityTracker.resetTracking()
                        velocityTracker.addPointerInputChange(firstPointerDown)
                        startedPosition = firstPointerDown.position
                        if (enabled()) {
                            onFirstPointerDown()
                        }
                    }

                    // Changes with at least one pointer
                    else -> {
                        val pointerChange = changes.first()

                        // Assuming that the list of changes doesn't have two changes with the same
                        // id (PointerId), we can check:
                        // - If the first change has `id` equals to `velocityPointerId` (this should
                        //   always be true unless the pointer has been removed).
                        // - If it does, we've found our change event (assuming there aren't any
                        //   others changes with the same id in this PointerEvent - not checked).
                        // - If it doesn't, we can check that the change with that id isn't in first
                        //   place (which should never happen - this will crash).
                        check(
                            pointerChange.id == velocityPointerId ||
                                !changes.fastAny { it.id == velocityPointerId }
                        ) {
                            "$velocityPointerId is present, but not the first: $changes"
                        }

                        // If the previous pointer has been removed, we use the first available
                        // change to keep tracking the velocity.
                        velocityPointerId =
                            if (pointerChange.pressed) {
                                pointerChange.id
                            } else {
                                changes.first { it.pressed }.id
                            }

                        velocityTracker.addPointerInputChange(pointerChange)
                    }
                }
            }
        }
    }

    private suspend fun PointerInputScope.pointerInput() {
        if (!enabled()) {
            return
        }

        val currentContext = currentCoroutineContext()
        awaitPointerEventScope {
            while (currentContext.isActive) {
                try {
                    detectDragGestures(
                        orientation = orientation,
                        startDragImmediately = startDragImmediately,
                        onDragStart = { startedPosition, overSlop, pointersDown ->
                            onDragStarted(startedPosition, overSlop, pointersDown)
                        },
                        onDrag = { controller, amount ->
                            dispatchScrollEvents(
                                availableOnPreScroll = amount,
                                onScroll = { controller.onDrag(it) },
                                source = NestedScrollSource.UserInput,
                            )
                        },
                        onDragEnd = { controller ->
                            startFlingGesture(
                                initialVelocity =
                                    currentValueOf(LocalViewConfiguration)
                                        .maximumFlingVelocity
                                        .let {
                                            val maxVelocity = Velocity(it, it)
                                            velocityTracker.calculateVelocity(maxVelocity)
                                        }
                                        .toFloat(),
                                onFling = { controller.onStop(it, canChangeContent = true) },
                            )
                        },
                        onDragCancel = { controller ->
                            startFlingGesture(
                                initialVelocity = 0f,
                                onFling = { controller.onStop(it, canChangeContent = true) },
                            )
                        },
                        swipeDetector = swipeDetector,
                    )
                } catch (exception: CancellationException) {
                    // If the coroutine scope is active, we can just restart the drag cycle.
                    if (!currentContext.isActive) {
                        throw exception
                    }
                }
            }
        }
    }

    /**
     * Start a fling gesture in another CoroutineScope, this is to ensure that even when the pointer
     * input scope is reset we will continue any coroutine scope that we started from these methods
     * while the pointer input scope was active.
     *
     * Note: Inspired by [androidx.compose.foundation.gestures.ScrollableNode.onDragStopped]
     */
    private fun startFlingGesture(initialVelocity: Float, onFling: (velocity: Float) -> Float) {
        // Note: [AwaitPointerEventScope] is annotated as @RestrictsSuspension, we need another
        // CoroutineScope to run the fling gestures.
        // We do not need to cancel this [Job], the source will take care of emitting an
        // [onPostFling] before starting a new gesture.
        dispatcher.coroutineScope.launch {
            dispatchFlingEvents(availableOnPreFling = initialVelocity, onFling = onFling)
        }
    }

    /**
     * Use the nested scroll system to fire scroll events. This allows us to consume events from our
     * ancestors during the pre-scroll and post-scroll phases.
     *
     * @param availableOnPreScroll amount available before the scroll, this can be partially
     *   consumed by our ancestors.
     * @param onScroll function that returns the amount consumed during a scroll given the amount
     *   available after the [NestedScrollConnection.onPreScroll].
     * @param source the source of the scroll event
     * @return Total offset consumed.
     */
    private inline fun dispatchScrollEvents(
        availableOnPreScroll: Float,
        onScroll: (delta: Float) -> Float,
        source: NestedScrollSource,
    ): Float {
        // PreScroll phase
        val consumedByPreScroll =
            dispatcher
                .dispatchPreScroll(available = availableOnPreScroll.toOffset(), source = source)
                .toFloat()

        // Scroll phase
        val availableOnScroll = availableOnPreScroll - consumedByPreScroll
        val consumedBySelfScroll = onScroll(availableOnScroll)

        // PostScroll phase
        val availableOnPostScroll = availableOnScroll - consumedBySelfScroll
        val consumedByPostScroll =
            dispatcher
                .dispatchPostScroll(
                    consumed = consumedBySelfScroll.toOffset(),
                    available = availableOnPostScroll.toOffset(),
                    source = source,
                )
                .toFloat()

        return consumedByPreScroll + consumedBySelfScroll + consumedByPostScroll
    }

    /**
     * Use the nested scroll system to fire fling events. This allows us to consume events from our
     * ancestors during the pre-fling and post-fling phases.
     *
     * @param availableOnPreFling velocity available before the fling, this can be partially
     *   consumed by our ancestors.
     * @param onFling function that returns the velocity consumed during the fling given the
     *   velocity available after the [NestedScrollConnection.onPreFling].
     * @return Total velocity consumed.
     */
    private suspend inline fun dispatchFlingEvents(
        availableOnPreFling: Float,
        onFling: (velocity: Float) -> Float,
    ): Float {
        // PreFling phase
        val consumedByPreFling =
            dispatcher.dispatchPreFling(available = availableOnPreFling.toVelocity()).toFloat()

        // Fling phase
        val availableOnFling = availableOnPreFling - consumedByPreFling
        val consumedBySelfFling = onFling(availableOnFling)

        // PostFling phase
        val availableOnPostFling = availableOnFling - consumedBySelfFling
        val consumedByPostFling =
            dispatcher
                .dispatchPostFling(
                    consumed = consumedBySelfFling.toVelocity(),
                    available = availableOnPostFling.toVelocity(),
                )
                .toFloat()

        return consumedByPreFling + consumedBySelfFling + consumedByPostFling
    }

    /**
     * Detect drag gestures in the given [orientation].
     *
     * This function is a mix of [androidx.compose.foundation.gestures.awaitDownAndSlop] and
     * [androidx.compose.foundation.gestures.detectVerticalDragGestures] to add support for:
     * 1) starting the gesture immediately without requiring a drag >= touch slope;
     * 2) passing the number of pointers down to [onDragStart].
     */
    private suspend fun AwaitPointerEventScope.detectDragGestures(
        orientation: Orientation,
        startDragImmediately: (startedPosition: Offset) -> Boolean,
        onDragStart:
            (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
        onDrag: (controller: DragController, dragAmount: Float) -> Unit,
        onDragEnd: (controller: DragController) -> Unit,
        onDragCancel: (controller: DragController) -> Unit,
        swipeDetector: SwipeDetector,
    ) {
        val consumablePointer =
            awaitConsumableEvent {
                    // We are searching for an event that can be used as the starting point for the
                    // drag gesture. Our options are:
                    // - Initial: These events should never be consumed by the MultiPointerDraggable
                    //   since our ancestors can consume the gesture, but we would eliminate this
                    //   possibility for our descendants.
                    // - Main: These events are consumed during the drag gesture, and they are a
                    //   good place to start if the previous event has not been consumed.
                    // - Final: If the previous event has been consumed, we can wait for the Main
                    //   pass to finish. If none of our ancestors were interested in the event, we
                    //   can wait for an unconsumed event in the Final pass.
                    val previousConsumed = currentEvent.changes.fastAny { it.isConsumed }
                    if (previousConsumed) PointerEventPass.Final else PointerEventPass.Main
                }
                .changes
                .first()

        var overSlop = 0f
        val drag =
            if (startDragImmediately(consumablePointer.position)) {
                consumablePointer.consume()
                consumablePointer
            } else {
                val onSlopReached = { change: PointerInputChange, over: Float ->
                    if (swipeDetector.detectSwipe(change)) {
                        change.consume()
                        overSlop = over
                    }
                }

                // TODO(b/291055080): Replace by await[Orientation]PointerSlopOrCancellation once it
                // is public.
                val drag =
                    when (orientation) {
                        Orientation.Horizontal ->
                            awaitHorizontalTouchSlopOrCancellation(
                                consumablePointer.id,
                                onSlopReached,
                            )
                        Orientation.Vertical ->
                            awaitVerticalTouchSlopOrCancellation(
                                consumablePointer.id,
                                onSlopReached,
                            )
                    }

                // Make sure that overSlop is not 0f. This can happen when the user drags by exactly
                // the touch slop. However, the overSlop we pass to onDragStarted() is used to
                // compute the direction we are dragging in, so overSlop should never be 0f unless
                // we intercept an ongoing swipe transition (i.e. startDragImmediately() returned
                // true).
                if (drag != null && overSlop == 0f) {
                    val delta = (drag.position - consumablePointer.position).toFloat()
                    check(delta != 0f) { "delta is equal to 0" }
                    overSlop = delta.sign
                }
                drag
            }

        if (drag != null) {
            val controller =
                onDragStart(
                    // The startedPosition is the starting position when a gesture begins (when the
                    // first pointer touches the screen), not the point where we begin dragging.
                    // For example, this could be different if one of our children intercepts the
                    // gesture first and then we do.
                    requireNotNull(startedPosition),
                    overSlop,
                    pointersDown,
                )

            val successful: Boolean
            try {
                onDrag(controller, overSlop)

                successful =
                    drag(
                        initialPointerId = drag.id,
                        hasDragged = { it.positionChangeIgnoreConsumed().toFloat() != 0f },
                        onDrag = {
                            onDrag(controller, it.positionChange().toFloat())
                            it.consume()
                        },
                        onIgnoredEvent = {
                            // We are still dragging an object, but this event is not of interest to
                            // the caller.
                            // This event will not trigger the onDrag event, but we will consume the
                            // event to prevent another pointerInput from interrupting the current
                            // gesture just because the event was ignored.
                            it.consume()
                        },
                    )
            } catch (t: Throwable) {
                onDragCancel(controller)
                throw t
            }

            if (successful) {
                onDragEnd(controller)
            } else {
                onDragCancel(controller)
            }
        }
    }

    private suspend fun AwaitPointerEventScope.awaitConsumableEvent(
        pass: () -> PointerEventPass
    ): PointerEvent {
        fun canBeConsumed(changes: List<PointerInputChange>): Boolean {
            // At least one pointer down AND
            return changes.fastAny { it.pressed } &&
                // All pointers must be either:
                changes.fastAll {
                    // A) unconsumed AND recently pressed
                    it.changedToDown() ||
                        // B) unconsumed AND in a new position (on the current axis)
                        it.positionChange().toFloat() != 0f
                }
        }

        var event: PointerEvent
        do {
            event = awaitPointerEvent(pass = pass())
        } while (!canBeConsumed(event.changes))

        // We found a consumable event in the Main pass
        return event
    }

    /**
     * Continues to read drag events until all pointers are up or the drag event is canceled. The
     * initial pointer to use for driving the drag is [initialPointerId]. [hasDragged] passes the
     * result whether a change was detected from the drag function or not.
     *
     * Whenever the pointer moves, if [hasDragged] returns true, [onDrag] is called; otherwise,
     * [onIgnoredEvent] is called.
     *
     * @return true when gesture ended with all pointers up and false when the gesture was canceled.
     *
     * Note: Inspired by DragGestureDetector.kt
     */
    private suspend inline fun AwaitPointerEventScope.drag(
        initialPointerId: PointerId,
        hasDragged: (PointerInputChange) -> Boolean,
        onDrag: (PointerInputChange) -> Unit,
        onIgnoredEvent: (PointerInputChange) -> Unit,
    ): Boolean {
        val pointer = currentEvent.changes.fastFirstOrNull { it.id == initialPointerId }
        val isPointerUp = pointer?.pressed != true
        if (isPointerUp) {
            return false // The pointer has already been lifted, so the gesture is canceled
        }
        var pointerId = initialPointerId
        while (true) {
            val change = awaitDragOrUp(pointerId, hasDragged, onIgnoredEvent) ?: return false

            if (change.isConsumed) {
                return false
            }

            if (change.changedToUpIgnoreConsumed()) {
                return true
            }

            onDrag(change)
            pointerId = change.id
        }
    }

    /**
     * Waits for a single drag in one axis, final pointer up, or all pointers are up. When
     * [initialPointerId] has lifted, another pointer that is down is chosen to be the finger
     * governing the drag. When the final pointer is lifted, that [PointerInputChange] is returned.
     * When a drag is detected, that [PointerInputChange] is returned. A drag is only detected when
     * [hasDragged] returns `true`. Events that should not be captured are passed to
     * [onIgnoredEvent].
     *
     * `null` is returned if there was an error in the pointer input stream and the pointer that was
     * down was dropped before the 'up' was received.
     *
     * Note: Inspired by DragGestureDetector.kt
     */
    private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
        initialPointerId: PointerId,
        hasDragged: (PointerInputChange) -> Boolean,
        onIgnoredEvent: (PointerInputChange) -> Unit,
    ): PointerInputChange? {
        var pointerId = initialPointerId
        while (true) {
            val event = awaitPointerEvent()
            val dragEvent = event.changes.fastFirstOrNull { it.id == pointerId } ?: return null
            if (dragEvent.changedToUpIgnoreConsumed()) {
                val otherDown = event.changes.fastFirstOrNull { it.pressed }
                if (otherDown == null) {
                    // This is the last "up"
                    return dragEvent
                } else {
                    pointerId = otherDown.id
                }
            } else if (hasDragged(dragEvent)) {
                return dragEvent
            } else {
                onIgnoredEvent(dragEvent)
            }
        }
    }

    private fun List<PointerInputChange>.countDown() = fastSumBy { if (it.pressed) 1 else 0 }
}

internal fun interface PointersInfoOwner {
    fun pointersInfo(): PointersInfo
}

internal data class PointersInfo(val startedPosition: Offset?, val pointersDown: Int)
