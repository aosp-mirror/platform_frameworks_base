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

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.properties.Delegates
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiPointerDraggableTest {
    @get:Rule val rule = createComposeRule()

    private val emptyConnection = object : NestedScrollConnection {}
    private val defaultDispatcher = NestedScrollDispatcher()

    private fun Modifier.nestedScrollDispatcher() = nestedScroll(emptyConnection, defaultDispatcher)

    private class SimpleDragController(
        val onDrag: (delta: Float) -> Unit,
        val onStop: (velocity: Float) -> Unit,
    ) : DragController {
        override fun onDrag(delta: Float): Float {
            onDrag.invoke(delta)
            return delta
        }

        override fun onStop(velocity: Float, canChangeContent: Boolean): Float {
            onStop.invoke(velocity)
            return velocity
        }
    }

    @Test
    fun cancellingPointerCallsOnDragStopped() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var enabled by mutableStateOf(false)
        var started = false
        var dragged = false
        var stopped = false

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { enabled },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            started = true
                            SimpleDragController(
                                onDrag = { dragged = true },
                                onStop = { stopped = true },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            )
        }

        fun startDraggingDown() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(0f, touchSlop))
            }
        }

        fun releaseFinger() {
            rule.onRoot().performTouchInput { up() }
        }

        // Swiping down does nothing because enabled is false.
        startDraggingDown()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()
        releaseFinger()

        // Enable the draggable and swipe down. This should both call onDragStarted() and
        // onDragDelta().
        enabled = true
        rule.waitForIdle()
        startDraggingDown()
        assertThat(started).isTrue()
        assertThat(dragged).isTrue()
        assertThat(stopped).isFalse()

        // Disable the pointer input. This should call onDragStopped() even if didn't release the
        // finger yet.
        enabled = false
        rule.waitForIdle()
        assertThat(started).isTrue()
        assertThat(dragged).isTrue()
        assertThat(stopped).isTrue()
    }

    @Test
    fun shouldNotStartDragEventsWith0PointersDown() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var started = false
        var dragged = false
        var stopped = false
        var consumedByDescendant = false

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        // We want to start a drag gesture immediately
                        startDragImmediately = { true },
                        onDragStarted = { _, _, _ ->
                            started = true
                            SimpleDragController(
                                onDrag = { dragged = true },
                                onStop = { stopped = true },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
                    .pointerInput(Unit) {
                        coroutineScope {
                            awaitPointerEventScope {
                                while (isActive) {
                                    val change = awaitPointerEvent().changes.first()
                                    if (consumedByDescendant) {
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // The first part of the gesture is consumed by our descendant
        consumedByDescendant = true
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, touchSlop))
        }

        // The events were consumed by our descendant, we should not start a drag gesture.
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        // The next events could be consumed by us
        consumedByDescendant = false
        rule.onRoot().performTouchInput {
            // The pointer is moved to a new position without reporting it
            updatePointerBy(0, Offset(0f, touchSlop))

            // The pointer report an "up" (0 pointers down) with a new position
            up()
        }

        // The "up" event should not be used to start a drag gesture
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()
    }

    @Test
    fun handleDisappearingScrollableDuringAGesture() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var started = false
        var dragged = false
        var stopped = false
        var consumedByScroll = false
        var hasScrollable by mutableStateOf(true)

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            started = true
                            SimpleDragController(
                                onDrag = { dragged = true },
                                onStop = { stopped = true },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            ) {
                if (hasScrollable) {
                    Box(
                        Modifier.scrollable(
                                // Consume all the vertical scroll gestures
                                rememberScrollableState(
                                    consumeScrollDelta = {
                                        consumedByScroll = true
                                        it
                                    }
                                ),
                                Orientation.Vertical,
                            )
                            .fillMaxSize()
                    )
                }
            }
        }

        fun startDraggingDown() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(0f, touchSlop))
            }
        }

        fun continueDraggingDown() {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, touchSlop)) }
        }

        fun releaseFinger() {
            rule.onRoot().performTouchInput { up() }
        }

        // Swipe down. This should intercepted by the scrollable modifier.
        startDraggingDown()
        assertThat(consumedByScroll).isTrue()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        // Reset the scroll state for the test
        consumedByScroll = false

        // Suddenly remove the scrollable container
        hasScrollable = false
        rule.waitForIdle()

        // Swipe down. This will be intercepted by multiPointerDraggable, it will wait touchSlop
        // before consuming it.
        continueDraggingDown()
        assertThat(consumedByScroll).isFalse()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        // Swipe down. This should both call onDragStarted() and onDragDelta().
        continueDraggingDown()
        assertThat(consumedByScroll).isFalse()
        assertThat(started).isTrue()
        assertThat(dragged).isTrue()
        assertThat(stopped).isFalse()

        rule.waitForIdle()
        releaseFinger()
        assertThat(stopped).isTrue()
    }

    @Test
    fun multiPointerWaitAConsumableEventInMainPass() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var started = false
        var dragged = false
        var stopped = false

        var childConsumesOnPass: PointerEventPass? = null

        suspend fun AwaitPointerEventScope.childPointerInputScope() {
            awaitPointerEvent(PointerEventPass.Initial).also { initial ->
                // Check unconsumed: it should be always true
                assertThat(initial.changes.any { it.isConsumed }).isFalse()

                if (childConsumesOnPass == PointerEventPass.Initial) {
                    initial.changes.first().consume()
                }
            }

            awaitPointerEvent(PointerEventPass.Main).also { main ->
                // Check unconsumed
                if (childConsumesOnPass != PointerEventPass.Initial) {
                    assertThat(main.changes.any { it.isConsumed }).isFalse()
                }

                if (childConsumesOnPass == PointerEventPass.Main) {
                    main.changes.first().consume()
                }
            }
        }

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            started = true
                            SimpleDragController(
                                onDrag = { dragged = true },
                                onStop = { stopped = true },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            ) {
                Box(
                    Modifier.pointerInput(Unit) {
                            coroutineScope {
                                awaitPointerEventScope {
                                    while (isActive) {
                                        childPointerInputScope()
                                    }
                                }
                            }
                        }
                        .fillMaxSize()
                )
            }
        }

        fun startDraggingDown() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(0f, touchSlop))
            }
        }

        fun continueDraggingDown() {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, touchSlop)) }
        }

        childConsumesOnPass = PointerEventPass.Initial

        startDraggingDown()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        continueDraggingDown()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        childConsumesOnPass = PointerEventPass.Main

        continueDraggingDown()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        continueDraggingDown()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        childConsumesOnPass = null

        // Swipe down. This will be intercepted by multiPointerDraggable, it will wait touchSlop
        // before consuming it.
        continueDraggingDown()
        assertThat(started).isFalse()
        assertThat(dragged).isFalse()
        assertThat(stopped).isFalse()

        // Swipe down. This should both call onDragStarted() and onDragDelta().
        continueDraggingDown()
        assertThat(started).isTrue()
        assertThat(dragged).isTrue()
        assertThat(stopped).isFalse()

        childConsumesOnPass = PointerEventPass.Main

        continueDraggingDown()
        assertThat(stopped).isTrue()

        // Complete the gesture
        rule.onRoot().performTouchInput { up() }
    }

    @Test
    fun multiPointerDuringAnotherGestureWaitAConsumableEventAfterMainPass() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var verticalStarted = false
        var verticalDragged = false
        var verticalStopped = false
        var horizontalStarted = false
        var horizontalDragged = false
        var horizontalStopped = false

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            verticalStarted = true
                            SimpleDragController(
                                onDrag = { verticalDragged = true },
                                onStop = { verticalStopped = true },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
                    .multiPointerDraggable(
                        orientation = Orientation.Horizontal,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            horizontalStarted = true
                            SimpleDragController(
                                onDrag = { horizontalDragged = true },
                                onStop = { horizontalStopped = true },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            )
        }

        fun startDraggingDown() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(0f, touchSlop))
            }
        }

        fun startDraggingRight() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(touchSlop, 0f))
            }
        }

        fun stopDragging() {
            rule.onRoot().performTouchInput { up() }
        }

        fun continueDown() {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, touchSlop)) }
        }

        fun continueRight() {
            rule.onRoot().performTouchInput { moveBy(Offset(touchSlop, 0f)) }
        }

        startDraggingDown()
        assertThat(verticalStarted).isTrue()
        assertThat(verticalDragged).isTrue()
        assertThat(verticalStopped).isFalse()

        // Ignore right swipe, do not interrupt the dragging gesture.
        continueRight()
        assertThat(horizontalStarted).isFalse()
        assertThat(horizontalDragged).isFalse()
        assertThat(horizontalStopped).isFalse()
        assertThat(verticalStopped).isFalse()

        stopDragging()
        assertThat(verticalStopped).isTrue()

        verticalStarted = false
        verticalDragged = false
        verticalStopped = false

        startDraggingRight()
        assertThat(horizontalStarted).isTrue()
        assertThat(horizontalDragged).isTrue()
        assertThat(horizontalStopped).isFalse()

        // Ignore down swipe, do not interrupt the dragging gesture.
        continueDown()
        assertThat(verticalStarted).isFalse()
        assertThat(verticalDragged).isFalse()
        assertThat(verticalStopped).isFalse()
        assertThat(horizontalStopped).isFalse()

        stopDragging()
        assertThat(horizontalStopped).isTrue()
    }

    @Test
    fun multiPointerSwipeDetectorInteraction() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var started = false

        var capturedChange: PointerInputChange? = null
        var swipeConsume = false

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        swipeDetector =
                            object : SwipeDetector {
                                override fun detectSwipe(change: PointerInputChange): Boolean {
                                    capturedChange = change
                                    return swipeConsume
                                }
                            },
                        onDragStarted = { _, _, _ ->
                            started = true
                            SimpleDragController(
                                onDrag = { /* do nothing */ },
                                onStop = { /* do nothing */ },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            ) {}
        }

        fun startDraggingDown() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(0f, touchSlop))
            }
        }

        fun continueDraggingDown() {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, touchSlop)) }
        }

        startDraggingDown()
        assertThat(capturedChange).isNotNull()
        capturedChange = null
        assertThat(started).isFalse()

        swipeConsume = true
        continueDraggingDown()
        assertThat(capturedChange).isNotNull()
        capturedChange = null

        continueDraggingDown()
        assertThat(capturedChange).isNull()

        assertThat(started).isTrue()
    }

    @Test
    fun multiPointerNestedScrollDispatcher() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)
        var touchSlop = 0f

        var consumedOnPreScroll = 0f

        var availableOnPreScroll = Float.MIN_VALUE
        var availableOnPostScroll = Float.MIN_VALUE
        var availableOnPreFling = Float.MIN_VALUE
        var availableOnPostFling = Float.MIN_VALUE

        var consumedOnDrag = 0f
        var consumedOnDragStop = 0f

        val connection =
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    availableOnPreScroll = available.y
                    return Offset(0f, consumedOnPreScroll)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    availableOnPostScroll = available.y
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    availableOnPreFling = available.y
                    return Velocity.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    availableOnPostFling = available.y
                    return Velocity.Zero
                }
            }

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.size(with(LocalDensity.current) { Size(size, size).toDpSize() })
                    .nestedScroll(connection)
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            SimpleDragController(
                                onDrag = { consumedOnDrag = it },
                                onStop = { consumedOnDragStop = it },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            )
        }

        fun startDrag() {
            rule.onRoot().performTouchInput {
                down(middle)
                moveBy(Offset(0f, touchSlop))
            }
        }

        fun continueDrag() {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, touchSlop)) }
        }

        fun stopDrag() {
            rule.onRoot().performTouchInput { up() }
        }

        startDrag()

        continueDrag()
        assertThat(availableOnPreScroll).isEqualTo(touchSlop)
        assertThat(consumedOnDrag).isEqualTo(touchSlop)
        assertThat(availableOnPostScroll).isEqualTo(0f)

        // Parent node consumes half of the gesture
        consumedOnPreScroll = touchSlop / 2f
        continueDrag()
        assertThat(availableOnPreScroll).isEqualTo(touchSlop)
        assertThat(consumedOnDrag).isEqualTo(touchSlop / 2f)
        assertThat(availableOnPostScroll).isEqualTo(0f)

        // Parent node consumes the gesture
        consumedOnPreScroll = touchSlop
        continueDrag()
        assertThat(availableOnPreScroll).isEqualTo(touchSlop)
        assertThat(consumedOnDrag).isEqualTo(0f)
        assertThat(availableOnPostScroll).isEqualTo(0f)

        // Parent node can intercept the velocity on stop
        stopDrag()
        assertThat(availableOnPreFling).isEqualTo(consumedOnDragStop)
        assertThat(availableOnPostFling).isEqualTo(0f)
    }

    @Test
    fun multiPointerOnStopVelocity() {
        val size = 200f
        val middle = Offset(size / 2f, size / 2f)

        var stopped = false
        var lastVelocity = -1f
        var touchSlop = 0f
        var density: Density by Delegates.notNull()
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            density = LocalDensity.current
            Box(
                Modifier.size(with(density) { Size(size, size).toDpSize() })
                    .nestedScrollDispatcher()
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            SimpleDragController(
                                onDrag = { /* do nothing */ },
                                onStop = {
                                    stopped = true
                                    lastVelocity = it
                                },
                            )
                        },
                        dispatcher = defaultDispatcher,
                    )
            )
        }

        var eventMillis: Long by Delegates.notNull()
        rule.onRoot().performTouchInput { eventMillis = eventPeriodMillis }

        fun swipeGesture(block: TouchInjectionScope.() -> Unit) {
            stopped = false
            rule.onRoot().performTouchInput {
                down(middle)
                block()
                up()
            }
            assertThat(stopped).isEqualTo(true)
        }

        val shortDistance = touchSlop / 2f
        swipeGesture {
            moveBy(delta = Offset(0f, shortDistance), delayMillis = eventMillis)
            moveBy(delta = Offset(0f, shortDistance), delayMillis = eventMillis)
        }
        assertThat(lastVelocity).isGreaterThan(0f)
        assertThat(lastVelocity).isWithin(1f).of((shortDistance / eventMillis) * 1000f)

        val longDistance = touchSlop * 4f
        swipeGesture {
            moveBy(delta = Offset(0f, longDistance), delayMillis = eventMillis)
            moveBy(delta = Offset(0f, longDistance), delayMillis = eventMillis)
        }
        assertThat(lastVelocity).isGreaterThan(0f)
        assertThat(lastVelocity).isWithin(1f).of((longDistance / eventMillis) * 1000f)

        rule.onRoot().performTouchInput {
            down(pointerId = 0, position = middle)
            down(pointerId = 1, position = middle)
            moveBy(pointerId = 0, delta = Offset(0f, longDistance), delayMillis = eventMillis)
            moveBy(pointerId = 0, delta = Offset(0f, longDistance), delayMillis = eventMillis)
            // The velocity should be:
            // (longDistance / eventMillis) pixels/ms

            // 1 pointer left, the second one
            up(pointerId = 0)

            // After a few events the velocity should be:
            // (shortDistance / eventMillis) pixels/ms
            repeat(10) {
                moveBy(pointerId = 1, delta = Offset(0f, shortDistance), delayMillis = eventMillis)
            }
            up(pointerId = 1)
        }
        assertThat(lastVelocity).isGreaterThan(0f)
        assertThat(lastVelocity).isWithin(1f).of((shortDistance / eventMillis) * 1000f)
    }
}
