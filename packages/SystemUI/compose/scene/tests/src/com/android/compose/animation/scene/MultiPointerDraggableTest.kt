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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiPointerDraggableTest {
    @get:Rule val rule = createComposeRule()

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
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { enabled },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            started = true
                            object : DragController {
                                override fun onDrag(delta: Float) {
                                    dragged = true
                                }

                                override fun onStop(velocity: Float, canChangeScene: Boolean) {
                                    stopped = true
                                }
                            }
                        },
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
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            started = true
                            object : DragController {
                                override fun onDrag(delta: Float) {
                                    dragged = true
                                }

                                override fun onStop(velocity: Float, canChangeScene: Boolean) {
                                    stopped = true
                                }
                            }
                        },
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
                                Orientation.Vertical
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
                    .multiPointerDraggable(
                        orientation = Orientation.Vertical,
                        enabled = { true },
                        startDragImmediately = { false },
                        onDragStarted = { _, _, _ ->
                            started = true
                            object : DragController {
                                override fun onDrag(delta: Float) {
                                    dragged = true
                                }

                                override fun onStop(velocity: Float, canChangeScene: Boolean) {
                                    stopped = true
                                }
                            }
                        },
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
    }
}
