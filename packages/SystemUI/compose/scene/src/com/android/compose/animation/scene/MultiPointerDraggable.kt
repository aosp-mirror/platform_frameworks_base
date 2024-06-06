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

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
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
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

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
@Stable
internal fun Modifier.multiPointerDraggable(
    orientation: Orientation,
    enabled: () -> Boolean,
    startDragImmediately: (startedPosition: Offset) -> Boolean,
    onDragStarted: (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
    swipeDetector: SwipeDetector = DefaultSwipeDetector,
): Modifier =
    this.then(
        MultiPointerDraggableElement(
            orientation,
            enabled,
            startDragImmediately,
            onDragStarted,
            swipeDetector,
        )
    )

private data class MultiPointerDraggableElement(
    private val orientation: Orientation,
    private val enabled: () -> Boolean,
    private val startDragImmediately: (startedPosition: Offset) -> Boolean,
    private val onDragStarted:
        (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
    private val swipeDetector: SwipeDetector,
) : ModifierNodeElement<MultiPointerDraggableNode>() {
    override fun create(): MultiPointerDraggableNode =
        MultiPointerDraggableNode(
            orientation = orientation,
            enabled = enabled,
            startDragImmediately = startDragImmediately,
            onDragStarted = onDragStarted,
            swipeDetector = swipeDetector,
        )

    override fun update(node: MultiPointerDraggableNode) {
        node.orientation = orientation
        node.enabled = enabled
        node.startDragImmediately = startDragImmediately
        node.onDragStarted = onDragStarted
        node.swipeDetector = swipeDetector
    }
}

internal class MultiPointerDraggableNode(
    orientation: Orientation,
    enabled: () -> Boolean,
    var startDragImmediately: (startedPosition: Offset) -> Boolean,
    var onDragStarted:
        (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> DragController,
    var swipeDetector: SwipeDetector = DefaultSwipeDetector,
) :
    PointerInputModifierNode,
    DelegatingNode(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode {
    private val pointerInputHandler: suspend PointerInputScope.() -> Unit = { pointerInput() }
    private val delegate = delegate(SuspendingPointerInputModifierNode(pointerInputHandler))
    private val velocityTracker = VelocityTracker()
    private var previousEnabled: Boolean = false

    var enabled: () -> Boolean = enabled
        set(value) {
            // Reset the pointer input whenever enabled changed.
            if (value != field) {
                field = value
                delegate.resetPointerInputHandler()
            }
        }

    var orientation: Orientation = orientation
        set(value) {
            // Reset the pointer input whenever orientation changed.
            if (value != field) {
                field = value
                delegate.resetPointerInputHandler()
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
                delegate.resetPointerInputHandler()
            }
            previousEnabled = newEnabled
        }
    }

    override fun onCancelPointerInput() = delegate.onCancelPointerInput()

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) = delegate.onPointerEvent(pointerEvent, pass, bounds)

    private suspend fun PointerInputScope.pointerInput() {
        if (!enabled()) {
            return
        }

        coroutineScope {
            awaitPointerEventScope {
                while (isActive) {
                    try {
                        detectDragGestures(
                            orientation = orientation,
                            startDragImmediately = startDragImmediately,
                            onDragStart = { startedPosition, overSlop, pointersDown ->
                                velocityTracker.resetTracking()
                                onDragStarted(startedPosition, overSlop, pointersDown)
                            },
                            onDrag = { controller, change, amount ->
                                velocityTracker.addPointerInputChange(change)
                                controller.onDrag(amount)
                            },
                            onDragEnd = { controller ->
                                val viewConfiguration = currentValueOf(LocalViewConfiguration)
                                val maxVelocity =
                                    viewConfiguration.maximumFlingVelocity.let { Velocity(it, it) }
                                val velocity = velocityTracker.calculateVelocity(maxVelocity)
                                controller.onStop(
                                    velocity =
                                        when (orientation) {
                                            Orientation.Horizontal -> velocity.x
                                            Orientation.Vertical -> velocity.y
                                        },
                                    canChangeScene = true,
                                )
                            },
                            onDragCancel = { controller ->
                                controller.onStop(velocity = 0f, canChangeScene = true)
                            },
                            swipeDetector = swipeDetector
                        )
                    } catch (exception: CancellationException) {
                        // If the coroutine scope is active, we can just restart the drag cycle.
                        if (!isActive) {
                            throw exception
                        }
                    }
                }
            }
        }
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
        onDrag: (controller: DragController, change: PointerInputChange, dragAmount: Float) -> Unit,
        onDragEnd: (controller: DragController) -> Unit,
        onDragCancel: (controller: DragController) -> Unit,
        swipeDetector: SwipeDetector,
    ) {
        // Wait for a consumable event in [PointerEventPass.Main] pass
        val consumablePointer = awaitConsumableEvent().changes.first()

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
                                onSlopReached
                            )
                        Orientation.Vertical ->
                            awaitVerticalTouchSlopOrCancellation(
                                consumablePointer.id,
                                onSlopReached
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
            // Count the number of pressed pointers.
            val pressed = mutableSetOf<PointerId>()
            currentEvent.changes.fastForEach { change ->
                if (change.pressed) {
                    pressed.add(change.id)
                }
            }

            val controller = onDragStart(drag.position, overSlop, pressed.size)

            val successful: Boolean
            try {
                onDrag(controller, drag, overSlop)

                successful =
                    drag(
                        initialPointerId = drag.id,
                        hasDragged = { it.positionChangeIgnoreConsumed().toFloat() != 0f },
                        onDrag = {
                            onDrag(controller, it, it.positionChange().toFloat())
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

    private suspend fun AwaitPointerEventScope.awaitConsumableEvent(): PointerEvent {
        fun canBeConsumed(changes: List<PointerInputChange>): Boolean {
            // All pointers must be:
            return changes.fastAll {
                // A) recently pressed: even if the event has already been consumed, we can still
                // use the recently added finger event to determine whether to initiate dragging the
                // scene.
                it.changedToDownIgnoreConsumed() ||
                    // B) unconsumed AND in a new position (on the current axis)
                    it.positionChange().toFloat() != 0f
            }
        }

        var event: PointerEvent
        do {
            // To allow the descendants with the opportunity to consume the event, we wait for it in
            // the Main pass.
            event = awaitPointerEvent()
        } while (!canBeConsumed(event.changes))

        // We found a consumable event in the Main pass
        return event
    }

    private fun Offset.toFloat(): Float {
        return when (orientation) {
            Orientation.Vertical -> y
            Orientation.Horizontal -> x
        }
    }

    /**
     * Continues to read drag events until all pointers are up or the drag event is canceled. The
     * initial pointer to use for driving the drag is [initialPointerId]. [hasDragged] passes the
     * result whether a change was detected from the drag function or not. [onDrag] is called
     * whenever the pointer moves and [hasDragged] returns non-zero.
     *
     * @return true when gesture ended with all pointers up and false when the gesture was canceled.
     *
     * Note: Inspired by DragGestureDetector.kt
     */
    private suspend inline fun AwaitPointerEventScope.drag(
        initialPointerId: PointerId,
        hasDragged: (PointerInputChange) -> Boolean,
        onDrag: (PointerInputChange) -> Unit,
    ): Boolean {
        val pointer = currentEvent.changes.fastFirstOrNull { it.id == initialPointerId }
        val isPointerUp = pointer?.pressed != true
        if (isPointerUp) {
            return false // The pointer has already been lifted, so the gesture is canceled
        }
        var pointerId = initialPointerId
        while (true) {
            val change = awaitDragOrUp(pointerId, hasDragged) ?: return false

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
     * [hasDragged] returns `true`.
     *
     * `null` is returned if there was an error in the pointer input stream and the pointer that was
     * down was dropped before the 'up' was received.
     *
     * Note: Copied from DragGestureDetector.kt
     */
    private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
        initialPointerId: PointerId,
        hasDragged: (PointerInputChange) -> Boolean,
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
            }
        }
    }
}
