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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
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
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import com.android.compose.modifiers.thenIf
import kotlin.math.sign
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * A draggable that plays nicely with the nested scroll mechanism.
 *
 * This can be used whenever you need a draggable inside a scrollable or a draggable that contains a
 * scrollable.
 */
interface NestedDraggable {
    /**
     * Return whether we should start a drag given the pointer [change].
     *
     * This is called when the touch slop is reached. If this returns `true`, then the [change] will
     * be consumed and [onDragStarted] will be called. If this returns `false`, then the current
     * touch slop detection will be reset and restarted at the current
     * [change position][PointerInputChange.position].
     */
    fun shouldStartDrag(change: PointerInputChange): Boolean = true

    /**
     * Called when a drag is started in the given [position] (*before* dragging the touch slop) and
     * in the direction given by [sign], with the given number of [pointersDown] when the touch slop
     * was detected.
     */
    fun onDragStarted(
        position: Offset,
        sign: Float,
        pointersDown: Int,
        // TODO(b/382665591): Make this non-nullable.
        pointerType: PointerType?,
    ): Controller

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
         * @param velocity the velocity of the drag when it stopped.
         * @param awaitFling a lambda that can be used to wait for the end of the full fling, i.e.
         *   wait for the end of the nested scroll fling or overscroll fling performed with the
         *   unconsumed velocity *after* this call to [onDragStopped] returned.
         * @return the consumed [velocity]. Any non-consumed velocity will be dispatched to the next
         *   nested scroll connection to be consumed by any composable above in the hierarchy. If
         *   the drag was performed on this draggable directly (instead of on a nested scrollable),
         *   any remaining velocity will be used to animate the overscroll of this draggable.
         */
        suspend fun onDragStopped(velocity: Float, awaitFling: suspend () -> Unit): Float
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
) : ModifierNodeElement<NestedDraggableRootNode>() {
    override fun create(): NestedDraggableRootNode {
        return NestedDraggableRootNode(draggable, orientation, overscrollEffect, enabled)
    }

    override fun update(node: NestedDraggableRootNode) {
        node.update(draggable, orientation, overscrollEffect, enabled)
    }
}

/**
 * A root node on top of [NestedDraggableNode] so that no [PointerInputModifierNode] is installed
 * when this draggable is disabled.
 */
private class NestedDraggableRootNode(
    draggable: NestedDraggable,
    orientation: Orientation,
    overscrollEffect: OverscrollEffect?,
    enabled: Boolean,
) : DelegatingNode() {
    private var delegateNode =
        if (enabled) create(draggable, orientation, overscrollEffect) else null

    fun update(
        draggable: NestedDraggable,
        orientation: Orientation,
        overscrollEffect: OverscrollEffect?,
        enabled: Boolean,
    ) {
        // Disabled.
        if (!enabled) {
            delegateNode?.let { undelegate(it) }
            delegateNode = null
            return
        }

        // Disabled => Enabled.
        val nullableDelegate = delegateNode
        if (nullableDelegate == null) {
            delegateNode = create(draggable, orientation, overscrollEffect)
            return
        }

        // Enabled => Enabled (update).
        nullableDelegate.update(draggable, orientation, overscrollEffect)
    }

    private fun create(
        draggable: NestedDraggable,
        orientation: Orientation,
        overscrollEffect: OverscrollEffect?,
    ): NestedDraggableNode {
        return delegate(NestedDraggableNode(draggable, orientation, overscrollEffect))
    }
}

private class NestedDraggableNode(
    private var draggable: NestedDraggable,
    override var orientation: Orientation,
    private var overscrollEffect: OverscrollEffect?,
) :
    DelegatingNode(),
    PointerInputModifierNode,
    NestedScrollConnection,
    CompositionLocalConsumerModifierNode,
    OrientationAware {
    private val nestedScrollDispatcher = NestedScrollDispatcher()
    private val trackWheelScroll =
        delegate(SuspendingPointerInputModifierNode { trackWheelScroll() })
    private val trackDownPositionDelegate =
        delegate(SuspendingPointerInputModifierNode { trackDownPosition() })
    private val detectDragsDelegate = delegate(SuspendingPointerInputModifierNode { detectDrags() })

    /** The controller created by the nested scroll logic (and *not* the drag logic). */
    private var nestedScrollController: NestedScrollController? = null

    /**
     * The last pointer which was the first down since the last time all pointers were up.
     *
     * This is use to track the started position of a drag started on a nested scrollable.
     */
    private var lastFirstDown: Offset? = null
    private var lastEventWasScrollWheel: Boolean = false

    /** The pointers currently down, in order of which they were done and mapping to their type. */
    private val pointersDown = linkedMapOf<PointerId, PointerType>()

    init {
        delegate(nestedScrollModifierNode(this, nestedScrollDispatcher))
    }

    override fun onDetach() {
        nestedScrollController?.ensureOnDragStoppedIsCalled()
        nestedScrollController = null
    }

    fun update(
        draggable: NestedDraggable,
        orientation: Orientation,
        overscrollEffect: OverscrollEffect?,
    ) {
        if (
            draggable == this.draggable &&
                orientation == this.orientation &&
                overscrollEffect == this.overscrollEffect
        ) {
            return
        }

        this.draggable = draggable
        this.orientation = orientation
        this.overscrollEffect = overscrollEffect

        trackWheelScroll.resetPointerInputHandler()
        trackDownPositionDelegate.resetPointerInputHandler()
        detectDragsDelegate.resetPointerInputHandler()

        nestedScrollController?.ensureOnDragStoppedIsCalled()
        nestedScrollController = null
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        trackWheelScroll.onPointerEvent(pointerEvent, pass, bounds)
        trackDownPositionDelegate.onPointerEvent(pointerEvent, pass, bounds)
        detectDragsDelegate.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        trackWheelScroll.onCancelPointerInput()
        trackDownPositionDelegate.onCancelPointerInput()
        detectDragsDelegate.onCancelPointerInput()
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
            var overSlop = 0f
            val onTouchSlopReached = { change: PointerInputChange, over: Float ->
                if (draggable.shouldStartDrag(change)) {
                    change.consume()
                    overSlop = over
                }

                // If shouldStartDrag() returned false, then we didn't consume the event and
                // awaitTouchSlopOrCancellation() will reset the touch slop detector so that the
                // user has to drag by at least the touch slop again.
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

            val drag = awaitTouchSlopOrCancellation(down.id)
            if (drag != null) {
                velocityTracker.resetTracking()
                val sign = drag.positionChangeIgnoreConsumed().toFloat().sign
                check(sign != 0f) {
                    buildString {
                        append("sign is equal to 0 ")
                        append("touchSlop ${currentValueOf(LocalViewConfiguration).touchSlop} ")
                        append("down.position ${down.position} ")
                        append("drag.position ${drag.position} ")
                        append("drag.previousPosition ${drag.previousPosition}")
                    }
                }

                check(pointersDown.size > 0) { "pointersDown is empty" }
                val controller =
                    draggable.onDragStarted(
                        down.position,
                        sign,
                        pointersDown.size.coerceAtLeast(1),
                        drag.type,
                    )
                if (overSlop != 0f) {
                    onDrag(controller, drag, overSlop, velocityTracker)
                }

                // If a drag was started, we cancel any other drag started by a nested scrollable.
                //
                // Note: we cancel the nested drag here *after* starting the new drag so that in the
                // STL case, the cancelled drag will not change the current scene of the STL.
                nestedScrollController?.ensureOnDragStoppedIsCalled()
                nestedScrollController = null

                val isSuccessful =
                    try {
                        val onDrag = { change: PointerInputChange ->
                            onDrag(
                                controller,
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
                        onDragStopped(controller, Velocity.Zero)
                        throw t
                    }

                if (isSuccessful) {
                    val maxVelocity = currentValueOf(LocalViewConfiguration).maximumFlingVelocity
                    val velocity =
                        velocityTracker.calculateVelocity(Velocity(maxVelocity, maxVelocity))
                    onDragStopped(controller, velocity)
                } else {
                    onDragStopped(controller, Velocity.Zero)
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

        scrollWithOverscroll(delta.toOffset()) { deltaFromOverscroll ->
            scrollWithNestedScroll(deltaFromOverscroll) { deltaFromNestedScroll ->
                controller.onDrag(deltaFromNestedScroll.toFloat()).toOffset()
            }
        }
    }

    private fun onDragStopped(controller: NestedDraggable.Controller, velocity: Velocity) {
        // We launch in the scope of the dispatcher so that the fling is not cancelled if this node
        // is removed right after onDragStopped() is called.
        nestedScrollDispatcher.coroutineScope.launch {
            val flingCompletable = CompletableDeferred<Unit>()
            try {
                flingWithOverscroll(velocity) { velocityFromOverscroll ->
                    flingWithNestedScroll(velocityFromOverscroll) { velocityFromNestedScroll ->
                        controller
                            .onDragStopped(
                                velocityFromNestedScroll.toFloat(),
                                awaitFling = { flingCompletable.await() },
                            )
                            .toVelocity()
                    }
                }
            } finally {
                flingCompletable.complete(Unit)
            }
        }
    }

    private fun scrollWithOverscroll(delta: Offset, performScroll: (Offset) -> Offset): Offset {
        val effect = overscrollEffect
        return if (effect != null) {
            effect.applyToScroll(delta, source = NestedScrollSource.UserInput) { performScroll(it) }
        } else {
            performScroll(delta)
        }
    }

    private fun scrollWithNestedScroll(delta: Offset, performScroll: (Offset) -> Offset): Offset {
        val preConsumed =
            nestedScrollDispatcher.dispatchPreScroll(
                available = delta,
                source = NestedScrollSource.UserInput,
            )
        val available = delta - preConsumed
        val consumed = performScroll(available)
        val left = available - consumed
        val postConsumed =
            nestedScrollDispatcher.dispatchPostScroll(
                consumed = consumed,
                available = left,
                source = NestedScrollSource.UserInput,
            )
        return consumed + preConsumed + postConsumed
    }

    private suspend fun flingWithOverscroll(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ): Velocity {
        val effect = overscrollEffect
        return flingWithOverscroll(effect, velocity, performFling)
    }

    private suspend fun flingWithOverscroll(
        overscrollEffect: OverscrollEffect?,
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ): Velocity {
        // Make sure we only use the velocity in this draggable orientation.
        val orientationVelocity = velocity.toFloat().toVelocity()
        return if (overscrollEffect != null) {
            overscrollEffect.applyToFling(orientationVelocity) { performFling(it) }

            // Effects always consume the whole velocity.
            velocity
        } else {
            performFling(orientationVelocity)
        }
    }

    private suspend fun flingWithNestedScroll(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ): Velocity {
        val preConsumed = nestedScrollDispatcher.dispatchPreFling(available = velocity)
        val available = velocity - preConsumed
        val consumed = performFling(available)
        val left = available - consumed
        val postConsumed =
            nestedScrollDispatcher.dispatchPostFling(consumed = consumed, available = left)
        return preConsumed + consumed + postConsumed
    }

    /*
     * ===============================
     * ===== Nested scroll logic =====
     * ===============================
     */

    private suspend fun PointerInputScope.trackWheelScroll() {
        awaitEachGesture {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            lastEventWasScrollWheel = event.type == PointerEventType.Scroll
        }
    }

    private suspend fun PointerInputScope.trackDownPosition() {
        awaitEachGesture {
            try {
                val down = awaitFirstDown(requireUnconsumed = false)
                lastFirstDown = down.position
                pointersDown[down.id] = down.type

                do {
                    awaitPointerEvent().changes.forEach { change ->
                        when {
                            change.changedToDownIgnoreConsumed() -> {
                                pointersDown[change.id] = change.type
                            }
                            change.changedToUpIgnoreConsumed() -> pointersDown.remove(change.id)
                        }
                    }
                } while (pointersDown.size > 0)
            } finally {
                pointersDown.clear()
            }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val controller = nestedScrollController ?: return Offset.Zero
        return scrollWithOverscroll(controller, available)
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
        if (
            nestedScrollController == null &&
                // TODO(b/388231324): Remove this.
                !lastEventWasScrollWheel &&
                draggable.shouldConsumeNestedScroll(sign) &&
                lastFirstDown != null
        ) {
            val startedPosition = checkNotNull(lastFirstDown)

            // TODO(b/382665591): Ensure that there is at least one pointer down.
            val pointersDownCount = pointersDown.size.coerceAtLeast(1)
            val pointerType = pointersDown.entries.firstOrNull()?.value
            nestedScrollController =
                NestedScrollController(
                    overscrollEffect,
                    draggable.onDragStarted(startedPosition, sign, pointersDownCount, pointerType),
                )
        }

        val controller = nestedScrollController ?: return Offset.Zero
        return scrollWithOverscroll(controller, available)
    }

    private fun scrollWithOverscroll(controller: NestedScrollController, offset: Offset): Offset {
        return scrollWithOverscroll(offset) {
            controller.controller.onDrag(it.toFloat()).toOffset()
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val controller = nestedScrollController ?: return Velocity.Zero
        nestedScrollController = null

        return nestedScrollDispatcher.coroutineScope
            .async { controller.flingWithOverscroll(available) }
            .await()
    }

    private inner class NestedScrollController(
        private val overscrollEffect: OverscrollEffect?,
        val controller: NestedDraggable.Controller,
    ) {
        fun ensureOnDragStoppedIsCalled() {
            nestedScrollDispatcher.coroutineScope.launch { flingWithOverscroll(Velocity.Zero) }
        }

        suspend fun flingWithOverscroll(velocity: Velocity): Velocity {
            val flingCompletable = CompletableDeferred<Unit>()
            return try {
                flingWithOverscroll(overscrollEffect, velocity) {
                    controller
                        .onDragStopped(it.toFloat(), awaitFling = { flingCompletable.await() })
                        .toVelocity()
                }
            } finally {
                flingCompletable.complete(Unit)
            }
        }
    }
}
