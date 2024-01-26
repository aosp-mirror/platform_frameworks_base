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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.fastForEach
import kotlin.math.sign

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
    startDragImmediately: () -> Boolean,
    onDragStarted: (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> Unit,
    onDragDelta: (delta: Float) -> Unit,
    onDragStopped: (velocity: Float) -> Unit,
): Modifier =
    this.then(
        MultiPointerDraggableElement(
            orientation,
            enabled,
            startDragImmediately,
            onDragStarted,
            onDragDelta,
            onDragStopped,
        )
    )

private data class MultiPointerDraggableElement(
    private val orientation: Orientation,
    private val enabled: () -> Boolean,
    private val startDragImmediately: () -> Boolean,
    private val onDragStarted:
        (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> Unit,
    private val onDragDelta: (Float) -> Unit,
    private val onDragStopped: (velocity: Float) -> Unit,
) : ModifierNodeElement<MultiPointerDraggableNode>() {
    override fun create(): MultiPointerDraggableNode =
        MultiPointerDraggableNode(
            orientation = orientation,
            enabled = enabled,
            startDragImmediately = startDragImmediately,
            onDragStarted = onDragStarted,
            onDragDelta = onDragDelta,
            onDragStopped = onDragStopped,
        )

    override fun update(node: MultiPointerDraggableNode) {
        node.orientation = orientation
        node.enabled = enabled
        node.startDragImmediately = startDragImmediately
        node.onDragStarted = onDragStarted
        node.onDragDelta = onDragDelta
        node.onDragStopped = onDragStopped
    }
}

internal class MultiPointerDraggableNode(
    orientation: Orientation,
    enabled: () -> Boolean,
    var startDragImmediately: () -> Boolean,
    var onDragStarted: (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> Unit,
    var onDragDelta: (Float) -> Unit,
    var onDragStopped: (velocity: Float) -> Unit,
) : PointerInputModifierNode, DelegatingNode(), CompositionLocalConsumerModifierNode {
    private val pointerInputHandler: suspend PointerInputScope.() -> Unit = { pointerInput() }
    private val delegate = delegate(SuspendingPointerInputModifierNode(pointerInputHandler))
    private val velocityTracker = VelocityTracker()

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

        val onDragStart: (Offset, Float, Int) -> Unit = { startedPosition, overSlop, pointersDown ->
            velocityTracker.resetTracking()
            onDragStarted(startedPosition, overSlop, pointersDown)
        }

        val onDragCancel: () -> Unit = { onDragStopped(/* velocity= */ 0f) }

        val onDragEnd: () -> Unit = {
            val maxFlingVelocity =
                currentValueOf(LocalViewConfiguration).maximumFlingVelocity.let { max ->
                    Velocity(max, max)
                }

            val velocity = velocityTracker.calculateVelocity(maxFlingVelocity)
            onDragStopped(
                when (orientation) {
                    Orientation.Horizontal -> velocity.x
                    Orientation.Vertical -> velocity.y
                }
            )
        }

        val onDrag: (change: PointerInputChange, dragAmount: Float) -> Unit = { change, amount ->
            velocityTracker.addPointerInputChange(change)
            onDragDelta(amount)
        }

        detectDragGestures(
            orientation = orientation,
            startDragImmediately = startDragImmediately,
            onDragStart = onDragStart,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            onDrag = onDrag,
        )
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
private suspend fun PointerInputScope.detectDragGestures(
    orientation: Orientation,
    startDragImmediately: () -> Boolean,
    onDragStart: (startedPosition: Offset, overSlop: Float, pointersDown: Int) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var overSlop = 0f
        val drag =
            if (startDragImmediately()) {
                initialDown.consume()
                initialDown
            } else {
                val down = awaitFirstDown(requireUnconsumed = false)
                val onSlopReached = { change: PointerInputChange, over: Float ->
                    change.consume()
                    overSlop = over
                }

                // TODO(b/291055080): Replace by await[Orientation]PointerSlopOrCancellation once
                // it is public.
                val drag =
                    when (orientation) {
                        Orientation.Horizontal ->
                            awaitHorizontalTouchSlopOrCancellation(down.id, onSlopReached)
                        Orientation.Vertical ->
                            awaitVerticalTouchSlopOrCancellation(down.id, onSlopReached)
                    }

                // Make sure that overSlop is not 0f. This can happen when the user drags by exactly
                // the touch slop. However, the overSlop we pass to onDragStarted() is used to
                // compute the direction we are dragging in, so overSlop should never be 0f unless
                // we intercept an ongoing swipe transition (i.e. startDragImmediately() returned
                // true).
                if (drag != null && overSlop == 0f) {
                    val deltaOffset = drag.position - initialDown.position
                    val delta =
                        when (orientation) {
                            Orientation.Horizontal -> deltaOffset.y
                            Orientation.Vertical -> deltaOffset.y
                        }
                    check(delta != 0f)
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

            onDragStart(drag.position, overSlop, pressed.size)
            onDrag(drag, overSlop)

            val successful =
                when (orientation) {
                    Orientation.Horizontal ->
                        horizontalDrag(drag.id) {
                            onDrag(it, it.positionChange().x)
                            it.consume()
                        }
                    Orientation.Vertical ->
                        verticalDrag(drag.id) {
                            onDrag(it, it.positionChange().y)
                            it.consume()
                        }
                }

            if (successful) {
                onDragEnd()
            } else {
                onDragCancel()
            }
        }
    }
}
