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

package com.android.compose.gesture

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.overscroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
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
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastSumBy
import com.android.compose.modifiers.thenIf
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * A draggable that plays nicely with the nested scroll mechanism.
 *
 * This can be used whenever you need a draggable inside a scrollable or a draggable that contains a
 * scrollable.
 */
interface NestedDraggable {
    /**
     * Called when a drag is started in the given [position] (*before* dragging the touch slop) and
     * in the direction given by [sign], with the given number of [pointersDown] when the touch slop
     * was detected.
     */
    fun onDragStarted(position: Offset, sign: Float, pointersDown: Int): Controller

    /**
     * Whether this draggable should consume any scroll amount with the given [sign] coming from a
     * nested scrollable.
     *
     * This is called whenever a nested scrollable does not consume some scroll amount. If this
     * returns `true`, then [onDragStarted] will be called and this draggable will have priority and
     * consume all future events during preScroll until the nested scroll is finished.
     */
    fun shouldConsumeNestedScroll(sign: Float): Boolean

    interface Controller {
        /**
         * Drag by [delta] pixels.
         *
         * @return the consumed [delta]. Any non-consumed delta will be dispatched to the next
         *   nested scroll connection to be consumed by any composable above in the hierarchy. If
         *   the drag was performed on this draggable directly (instead of on a nested scrollable),
         *   any remaining delta will be used to overscroll this draggable.
         */
        fun onDrag(delta: Float): Float

        /**
         * Stop the current drag with the given [velocity].
         *
         * @return the consumed [velocity]. Any non-consumed velocity will be dispatched to the next
         *   nested scroll connection to be consumed by any composable above in the hierarchy. If
         *   the drag was performed on this draggable directly (instead of on a nested scrollable),
         *   any remaining velocity will be used to animate the overscroll of this draggable.
         */
        suspend fun onDragStopped(velocity: Float): Float
    }
}

/**
 * A draggable that supports nested scrolling and overscroll effects.
 *
 * @see NestedDraggable
 */
fun Modifier.nestedDraggable(
    draggable: NestedDraggable,
    orientation: Orientation,
    overscrollEffect: OverscrollEffect? = null,
    enabled: Boolean = true,
): Modifier {
    return this.thenIf(overscrollEffect != null) { Modifier.overscroll(overscrollEffect) }
        .then(NestedDraggableElement(draggable, orientation, overscrollEffect, enabled))
}

private data class NestedDraggableElement(
    private val draggable: NestedDraggable,
    private val orientation: Orientation,
    private val overscrollEffect: OverscrollEffect?,
    private val enabled: Boolean,
) : ModifierNodeElement<NestedDraggableNode>() {
    override fun create(): NestedDraggableNode {
        return NestedDraggableNode(draggable, orientation, overscrollEffect, enabled)
    }

    override fun update(node: NestedDraggableNode) {
        node.update(draggable, orientation, overscrollEffect, enabled)
    }
}

private class NestedDraggableNode(
    private var draggable: NestedDraggable,
    override var orientation: Orientation,
    private var overscrollEffect: OverscrollEffect?,
    private var enabled: Boolean,
) :
    DelegatingNode(),
    PointerInputModifierNode,
    NestedScrollConnection,
    CompositionLocalConsumerModifierNode,
    OrientationAware {
    private val nestedScrollDispatcher = NestedScrollDispatcher()
    private var trackDownPositionDelegate: SuspendingPointerInputModifierNode? = null
        set(value) {
            field?.let { undelegate(it) }
            field = value?.also { delegate(it) }
        }

    private var detectDragsDelegate: SuspendingPointerInputModifierNode? = null
        set(value) {
            field?.let { undelegate(it) }
            field = value?.also { delegate(it) }
        }

    /** The controller created by the nested scroll logic (and *not* the drag logic). */
    private var nestedScrollController: WrappedController? = null
        set(value) {
            field?.ensureOnDragStoppedIsCalled()
            field = value
        }

    /**
     * The last pointer which was the first down since the last time all pointers were up.
     *
     * This is use to track the started position of a drag started on a nested scrollable.
     */
    private var lastFirstDown: Offset? = null

    /** The number of pointers down. */
    private var pointersDownCount = 0

    init {
        delegate(nestedScrollModifierNode(this, nestedScrollDispatcher))
    }

    override fun onDetach() {
        nestedScrollController?.ensureOnDragStoppedIsCalled()
    }

    fun update(
        draggable: NestedDraggable,
        orientation: Orientation,
        overscrollEffect: OverscrollEffect?,
        enabled: Boolean,
    ) {
        this.draggable = draggable
        this.orientation = orientation
        this.overscrollEffect = overscrollEffect
        this.enabled = enabled

        trackDownPositionDelegate?.resetPointerInputHandler()
        detectDragsDelegate?.resetPointerInputHandler()
        nestedScrollController?.ensureOnDragStoppedIsCalled()

        if (!enabled && trackDownPositionDelegate != null) {
            check(detectDragsDelegate != null)
            trackDownPositionDelegate = null
            detectDragsDelegate = null
        }
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (!enabled) return

        if (trackDownPositionDelegate == null) {
            check(detectDragsDelegate == null)
            trackDownPositionDelegate = SuspendingPointerInputModifierNode { trackDownPosition() }
            detectDragsDelegate = SuspendingPointerInputModifierNode { detectDrags() }
        }

        checkNotNull(trackDownPositionDelegate).onPointerEvent(pointerEvent, pass, bounds)
        checkNotNull(detectDragsDelegate).onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        trackDownPositionDelegate?.onCancelPointerInput()
        detectDragsDelegate?.onCancelPointerInput()
    }

    /*
     * ======================================
     * ===== Pointer input (drag) logic =====
     * ======================================
     */

    private suspend fun PointerInputScope.detectDrags() {
        // Lazily create the velocity tracker when the pointer input restarts.
        val velocityTracker = VelocityTracker()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            check(down.position == lastFirstDown) {
                "Position from detectDrags() is not the same as position in trackDownPosition()"
            }
            check(pointersDownCount == 1) { "pointersDownCount is equal to $pointersDownCount" }

            var overSlop = 0f
            val onTouchSlopReached = { change: PointerInputChange, over: Float ->
                change.consume()
                overSlop = over
            }

            suspend fun AwaitPointerEventScope.awaitTouchSlopOrCancellation(
                pointerId: PointerId
            ): PointerInputChange? {
                return when (orientation) {
                    Orientation.Horizontal ->
                        awaitHorizontalTouchSlopOrCancellation(pointerId, onTouchSlopReached)
                    Orientation.Vertical ->
                        awaitVerticalTouchSlopOrCancellation(pointerId, onTouchSlopReached)
                }
            }

            var drag = awaitTouchSlopOrCancellation(down.id)

            // We try to pick-up the drag gesture in case the touch slop swipe was consumed by a
            // nested scrollable child that disappeared.
            // This was copied from http://shortn/_10L8U02IoL.
            // TODO(b/380838584): Reuse detect(Horizontal|Vertical)DragGestures() instead.
            while (drag == null && currentEvent.changes.fastAny { it.pressed }) {
                var event: PointerEvent
                do {
                    event = awaitPointerEvent()
                } while (
                    event.changes.fastAny { it.isConsumed } && event.changes.fastAny { it.pressed }
                )

                // An event was not consumed and there's still a pointer in the screen.
                if (event.changes.fastAny { it.pressed }) {
                    // Await touch slop again, using the initial down as starting point.
                    // For most cases this should return immediately since we probably moved
                    // far enough from the initial down event.
                    drag = awaitTouchSlopOrCancellation(down.id)
                }
            }

            if (drag != null) {
                velocityTracker.resetTracking()
                val sign = (drag.position - down.position).toFloat().sign
                check(pointersDownCount > 0) { "pointersDownCount is equal to $pointersDownCount" }
                val wrappedController =
                    WrappedController(
                        coroutineScope,
                        draggable.onDragStarted(down.position, sign, pointersDownCount),
                    )
                if (overSlop != 0f) {
                    onDrag(wrappedController, drag, overSlop, velocityTracker)
                }

                // If a drag was started, we cancel any other drag started by a nested scrollable.
                //
                // Note: we cancel the nested drag here *after* starting the new drag so that in the
                // STL case, the cancelled drag will not change the current scene of the STL.
                nestedScrollController?.ensureOnDragStoppedIsCalled()

                val isSuccessful =
                    try {
                        val onDrag = { change: PointerInputChange ->
                            onDrag(
                                wrappedController,
                                change,
                                change.positionChange().toFloat(),
                                velocityTracker,
                            )
                            change.consume()
                        }

                        when (orientation) {
                            Orientation.Horizontal -> horizontalDrag(drag.id, onDrag)
                            Orientation.Vertical -> verticalDrag(drag.id, onDrag)
                        }
                    } catch (t: Throwable) {
                        wrappedController.ensureOnDragStoppedIsCalled()
                        throw t
                    }

                if (isSuccessful) {
                    val maxVelocity = currentValueOf(LocalViewConfiguration).maximumFlingVelocity
                    val velocity =
                        velocityTracker
                            .calculateVelocity(Velocity(maxVelocity, maxVelocity))
                            .toFloat()
                    onDragStopped(wrappedController, velocity)
                } else {
                    onDragStopped(wrappedController, velocity = 0f)
                }
            }
        }
    }

    private fun onDrag(
        controller: NestedDraggable.Controller,
        change: PointerInputChange,
        delta: Float,
        velocityTracker: VelocityTracker,
    ) {
        velocityTracker.addPointerInputChange(change)

        scrollWithOverscroll(delta) { deltaFromOverscroll ->
            scrollWithNestedScroll(deltaFromOverscroll) { deltaFromNestedScroll ->
                controller.onDrag(deltaFromNestedScroll)
            }
        }
    }

    private fun onDragStopped(controller: WrappedController, velocity: Float) {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                flingWithOverscroll(velocity) { velocityFromOverscroll ->
                    flingWithNestedScroll(velocityFromOverscroll) { velocityFromNestedScroll ->
                        controller.onDragStopped(velocityFromNestedScroll)
                    }
                }
            } finally {
                controller.ensureOnDragStoppedIsCalled()
            }
        }
    }

    private fun scrollWithOverscroll(delta: Float, performScroll: (Float) -> Float): Float {
        val effect = overscrollEffect
        return if (effect != null) {
            effect
                .applyToScroll(delta.toOffset(), source = NestedScrollSource.UserInput) {
                    performScroll(it.toFloat()).toOffset()
                }
                .toFloat()
        } else {
            performScroll(delta)
        }
    }

    private fun scrollWithNestedScroll(delta: Float, performScroll: (Float) -> Float): Float {
        val preConsumed =
            nestedScrollDispatcher
                .dispatchPreScroll(
                    available = delta.toOffset(),
                    source = NestedScrollSource.UserInput,
                )
                .toFloat()
        val available = delta - preConsumed
        val consumed = performScroll(available)
        val left = available - consumed
        val postConsumed =
            nestedScrollDispatcher
                .dispatchPostScroll(
                    consumed = (preConsumed + consumed).toOffset(),
                    available = left.toOffset(),
                    source = NestedScrollSource.UserInput,
                )
                .toFloat()
        return consumed + preConsumed + postConsumed
    }

    private suspend fun flingWithOverscroll(
        velocity: Float,
        performFling: suspend (Float) -> Float,
    ) {
        val effect = overscrollEffect
        if (effect != null) {
            effect.applyToFling(velocity.toVelocity()) { performFling(it.toFloat()).toVelocity() }
        } else {
            performFling(velocity)
        }
    }

    private suspend fun flingWithNestedScroll(
        velocity: Float,
        performFling: suspend (Float) -> Float,
    ): Float {
        val preConsumed = nestedScrollDispatcher.dispatchPreFling(available = velocity.toVelocity())
        val available = velocity - preConsumed.toFloat()
        val consumed = performFling(available)
        val left = available - consumed
        return nestedScrollDispatcher
            .dispatchPostFling(
                consumed = consumed.toVelocity() + preConsumed,
                available = left.toVelocity(),
            )
            .toFloat()
    }

    /*
     * ===============================
     * ===== Nested scroll logic =====
     * ===============================
     */

    private suspend fun PointerInputScope.trackDownPosition() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            lastFirstDown = down.position
            pointersDownCount = 1

            do {
                pointersDownCount +=
                    awaitPointerEvent().changes.fastSumBy { change ->
                        when {
                            change.changedToDownIgnoreConsumed() -> 1
                            change.changedToUpIgnoreConsumed() -> -1
                            else -> 0
                        }
                    }
            } while (pointersDownCount > 0)
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val controller = nestedScrollController ?: return Offset.Zero
        val consumed = controller.onDrag(available.toFloat())
        return consumed.toOffset()
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source == NestedScrollSource.SideEffect) {
            check(nestedScrollController == null)
            return Offset.Zero
        }

        val offset = available.toFloat()
        if (offset == 0f) {
            return Offset.Zero
        }

        val sign = offset.sign
        if (nestedScrollController == null && draggable.shouldConsumeNestedScroll(sign)) {
            val startedPosition = checkNotNull(lastFirstDown) { "lastFirstDown is not set" }

            // TODO(b/382665591): Replace this by check(pointersDownCount > 0).
            val pointersDown = pointersDownCount.coerceAtLeast(1)
            nestedScrollController =
                WrappedController(
                    coroutineScope,
                    draggable.onDragStarted(startedPosition, sign, pointersDown),
                )
        }

        val controller = nestedScrollController ?: return Offset.Zero
        return controller.onDrag(offset).toOffset()
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val controller = nestedScrollController ?: return Velocity.Zero
        nestedScrollController = null

        val consumed = controller.onDragStopped(available.toFloat())
        return consumed.toVelocity()
    }
}

/**
 * A controller that wraps [delegate] and can be used to ensure that [onDragStopped] is called, but
 * not more than once.
 */
private class WrappedController(
    private val coroutineScope: CoroutineScope,
    private val delegate: NestedDraggable.Controller,
) : NestedDraggable.Controller by delegate {
    private var onDragStoppedCalled = false

    override fun onDrag(delta: Float): Float {
        if (onDragStoppedCalled) return 0f
        return delegate.onDrag(delta)
    }

    override suspend fun onDragStopped(velocity: Float): Float {
        if (onDragStoppedCalled) return 0f
        onDragStoppedCalled = true
        return delegate.onDragStopped(velocity)
    }

    fun ensureOnDragStoppedIsCalled() {
        // Start with UNDISPATCHED so that onDragStopped() is always run until its first suspension
        // point, even if coroutineScope is cancelled.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { onDragStopped(velocity = 0f) }
    }
}
