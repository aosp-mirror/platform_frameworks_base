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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Velocity
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NestedDraggableTest(override val orientation: Orientation) : OrientationAware {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun orientations() = listOf(Orientation.Horizontal, Orientation.Vertical)
    }

    @get:Rule val rule = createComposeRule()

    @Test
    fun simpleDrag() {
        val draggable = TestDraggable()
        val effect = TestOverscrollEffect(orientation) { 0f }
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(Modifier.fillMaxSize().nestedDraggable(draggable, orientation, effect))
            }

        assertThat(draggable.onDragStartedCalled).isFalse()
        assertThat(draggable.onDragCalled).isFalse()
        assertThat(draggable.onDragStoppedCalled).isFalse()

        var rootCenter = Offset.Zero
        rule.onRoot().performTouchInput {
            rootCenter = center
            down(center)
            moveBy((touchSlop + 10f).toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragCalled).isTrue()
        assertThat(draggable.onDragDelta).isEqualTo(10f)
        assertThat(draggable.onDragStartedPosition).isEqualTo(rootCenter)
        assertThat(draggable.onDragStartedSign).isEqualTo(1f)
        assertThat(draggable.onDragStoppedCalled).isFalse()

        rule.onRoot().performTouchInput { moveBy(20f.toOffset()) }

        assertThat(draggable.onDragDelta).isEqualTo(30f)
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()

        rule.onRoot().performTouchInput {
            moveBy((-15f).toOffset())
            up()
        }

        assertThat(draggable.onDragDelta).isEqualTo(15f)
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun nestedScrollable() {
        val draggable = TestDraggable()
        val effect = TestOverscrollEffect(orientation) { 0f }
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation, effect)
                        .nestedScrollable(rememberScrollState())
                )
            }

        assertThat(draggable.onDragStartedCalled).isFalse()
        assertThat(draggable.onDragCalled).isFalse()
        assertThat(draggable.onDragStoppedCalled).isFalse()

        var rootCenter = Offset.Zero
        rule.onRoot().performTouchInput {
            rootCenter = center
            down(center)
            moveBy((-touchSlop - 10f).toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragCalled).isTrue()
        assertThat(draggable.onDragDelta).isEqualTo(-10f)
        assertThat(draggable.onDragStartedPosition).isEqualTo(rootCenter)
        assertThat(draggable.onDragStartedSign).isEqualTo(-1f)
        assertThat(draggable.onDragStoppedCalled).isFalse()

        rule.onRoot().performTouchInput { moveBy((-20f).toOffset()) }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragCalled).isTrue()
        assertThat(draggable.onDragDelta).isEqualTo(-30f)
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()

        rule.onRoot().performTouchInput {
            moveBy(15f.toOffset())
            up()
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragCalled).isTrue()
        assertThat(draggable.onDragDelta).isEqualTo(-15f)
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun onDragStoppedIsCalledWhenDraggableIsUpdatedAndReset() {
        val draggable = TestDraggable()
        val effect = TestOverscrollEffect(orientation) { 0f }
        var orientation by mutableStateOf(orientation)
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(Modifier.fillMaxSize().nestedDraggable(draggable, orientation, effect))
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(touchSlop.toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()

        orientation =
            when (orientation) {
                Orientation.Horizontal -> Orientation.Vertical
                Orientation.Vertical -> Orientation.Horizontal
            }
        rule.waitForIdle()
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun onDragStoppedIsCalledWhenDraggableIsUpdatedAndReset_nestedScroll() {
        val draggable = TestDraggable()
        val effect = TestOverscrollEffect(orientation) { 0f }
        var orientation by mutableStateOf(orientation)
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation, effect)
                        .nestedScrollable(rememberScrollState())
                )
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy((touchSlop + 1f).toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()

        orientation =
            when (orientation) {
                Orientation.Horizontal -> Orientation.Vertical
                Orientation.Vertical -> Orientation.Horizontal
            }
        rule.waitForIdle()
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun onDragStoppedIsCalledWhenDraggableIsRemovedDuringDrag() {
        val draggable = TestDraggable()
        val postFlingDelay = 10 * 16L
        val effect =
            TestOverscrollEffect(
                orientation,
                onPostFling = {
                    // We delay the fling so that we can check that the draggable node methods are
                    // still called until completion even when the node is removed.
                    delay(postFlingDelay)
                    it
                },
            ) {
                0f
            }
        var composeContent by mutableStateOf(true)
        val touchSlop =
            rule.setContentWithTouchSlop {
                // We add an empty nested scroll connection here from which the scope will be used
                // when dispatching the flings.
                Box(Modifier.nestedScroll(remember { object : NestedScrollConnection {} })) {
                    if (composeContent) {
                        Box(Modifier.fillMaxSize().nestedDraggable(draggable, orientation, effect))
                    }
                }
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(touchSlop.toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()

        composeContent = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(postFlingDelay)
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun onDragStoppedIsCalledWhenDraggableIsRemovedDuringDrag_nestedScroll() {
        val draggable = TestDraggable()
        val postFlingDelay = 10 * 16L
        val effect =
            TestOverscrollEffect(
                orientation,
                onPostFling = {
                    // We delay the fling so that we can check that the draggable node methods are
                    // still called until completion even when the node is removed.
                    delay(postFlingDelay)
                    it
                },
            ) {
                0f
            }
        var composeContent by mutableStateOf(true)
        val touchSlop =
            rule.setContentWithTouchSlop {
                // We add an empty nested scroll connection here from which the scope will be used
                // when dispatching the flings.
                Box(Modifier.nestedScroll(remember { object : NestedScrollConnection {} })) {
                    if (composeContent) {
                        Box(
                            Modifier.fillMaxSize()
                                .nestedDraggable(draggable, orientation, effect)
                                .nestedScrollable(rememberScrollState())
                        )
                    }
                }
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy((touchSlop + 1f).toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()

        composeContent = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(postFlingDelay)
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun onDragStoppedIsCalledWhenDraggableIsRemovedDuringFling() {
        val draggable = TestDraggable()
        val effect = TestOverscrollEffect(orientation) { 0f }
        var composeContent by mutableStateOf(true)
        var preFlingCalled = false
        val unblockPrefling = CompletableDeferred<Velocity>()
        rule.setContent {
            if (composeContent) {
                Box(
                    Modifier.fillMaxSize()
                        // This nested scroll connection indefinitely suspends on pre fling, so that
                        // we can emulate what happens when the draggable is removed from
                        // composition while the pre-fling happens and onDragStopped() was not
                        // called yet.
                        .nestedScroll(
                            remember {
                                object : NestedScrollConnection {
                                    override suspend fun onPreFling(available: Velocity): Velocity {
                                        preFlingCalled = true
                                        return unblockPrefling.await()
                                    }
                                }
                            }
                        )
                        .nestedDraggable(draggable, orientation, effect)
                )
            }
        }

        assertThat(draggable.onDragStartedCalled).isFalse()

        // Swipe down.
        rule.onRoot().performTouchInput {
            when (orientation) {
                Orientation.Horizontal -> swipeLeft()
                Orientation.Vertical -> swipeDown()
            }
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()
        assertThat(effect.applyToFlingDone).isFalse()
        assertThat(preFlingCalled).isTrue()

        composeContent = false
        unblockPrefling.complete(Velocity.Zero)
        rule.waitForIdle()
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    @Ignore("b/303224944#comment22")
    fun onDragStoppedIsCalledWhenNestedScrollableIsRemoved() {
        val draggable = TestDraggable()
        var composeNestedScrollable by mutableStateOf(true)
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation)
                        .then(
                            if (composeNestedScrollable) {
                                Modifier.nestedScrollable(rememberScrollState())
                            } else {
                                Modifier
                            }
                        )
                )
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy((touchSlop + 1f).toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()

        composeNestedScrollable = false
        rule.waitForIdle()
        assertThat(draggable.onDragStoppedCalled).isTrue()
    }

    @Test
    fun enabled() {
        val draggable = TestDraggable()
        var enabled by mutableStateOf(false)
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation, enabled = enabled)
                )
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(touchSlop.toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isFalse()
        assertThat(draggable.onDragStoppedCalled).isFalse()

        enabled = true
        rule.onRoot().performTouchInput {
            // Release previously up finger.
            up()

            down(center)
            moveBy(touchSlop.toOffset())
        }

        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isFalse()

        enabled = false
        rule.waitForIdle()
        assertThat(draggable.onDragStoppedCalled).isTrue()
    }

    @Test
    fun pointersDown() {
        val draggable = TestDraggable()
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(Modifier.fillMaxSize().nestedDraggable(draggable, orientation))
            }

        (1..5).forEach { nDown ->
            rule.onRoot().performTouchInput {
                repeat(nDown) { pointerId -> down(pointerId, center) }

                moveBy(pointerId = 0, touchSlop.toOffset())
            }

            assertThat(draggable.onDragStartedPointersDown).isEqualTo(nDown)

            rule.onRoot().performTouchInput {
                repeat(nDown) { pointerId -> up(pointerId = pointerId) }
            }
        }
    }

    @Test
    fun pointersDown_nestedScroll() {
        val draggable = TestDraggable()
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation)
                        .nestedScrollable(rememberScrollState())
                )
            }

        (1..5).forEach { nDown ->
            rule.onRoot().performTouchInput {
                repeat(nDown) { pointerId -> down(pointerId, center) }

                moveBy(pointerId = 0, (touchSlop + 1f).toOffset())
            }

            assertThat(draggable.onDragStartedPointersDown).isEqualTo(nDown)

            rule.onRoot().performTouchInput {
                repeat(nDown) { pointerId -> up(pointerId = pointerId) }
            }
        }
    }

    @Test
    fun pointersDown_downThenUpThenDown() {
        val draggable = TestDraggable()
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(Modifier.fillMaxSize().nestedDraggable(draggable, orientation))
            }

        val slopThird = ceil(touchSlop / 3f).toOffset()
        rule.onRoot().performTouchInput {
            repeat(5) { down(pointerId = it, center) } // + 5
            moveBy(pointerId = 0, slopThird)

            listOf(2, 3).forEach { up(pointerId = it) } // - 2
            moveBy(pointerId = 0, slopThird)

            listOf(5, 6, 7).forEach { down(pointerId = it, center) } // + 3
            moveBy(pointerId = 0, slopThird)
        }

        assertThat(draggable.onDragStartedPointersDown).isEqualTo(6)
    }

    @Test
    fun shouldStartDrag() {
        val draggable = TestDraggable()
        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(Modifier.fillMaxSize().nestedDraggable(draggable, orientation))
            }

        // Drag in one direction.
        draggable.shouldStartDrag = false
        rule.onRoot().performTouchInput {
            down(center)
            moveBy(touchSlop.toOffset())
        }
        assertThat(draggable.onDragStartedCalled).isFalse()

        // Drag in the other direction.
        draggable.shouldStartDrag = true
        rule.onRoot().performTouchInput { moveBy(-touchSlop.toOffset()) }
        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStartedSign).isEqualTo(-1f)
        assertThat(draggable.onDragDelta).isEqualTo(0f)
    }

    @Test
    fun overscrollEffectIsUsedDuringNestedScroll() {
        var consumeDrag = true
        var consumedByDrag = 0f
        var consumedByEffect = 0f
        val draggable =
            TestDraggable(
                onDrag = {
                    if (consumeDrag) {
                        consumedByDrag += it
                        it
                    } else {
                        0f
                    }
                }
            )
        val effect =
            TestOverscrollEffect(orientation) { delta ->
                /* Consumes everything. */
                consumedByEffect += delta
                delta
            }

        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation, overscrollEffect = effect)
                        .nestedScrollable(rememberScrollState())
                )
            }

        // Swipe on the nested scroll. The draggable consumes the scrolls.
        rule.onRoot().performTouchInput {
            down(center)
            moveBy((touchSlop + 10f).toOffset())
        }
        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(consumedByDrag).isEqualTo(10f)
        assertThat(consumedByEffect).isEqualTo(0f)

        // Stop consuming the scrolls in the draggable. The overscroll effect should now consume
        // the scrolls.
        consumeDrag = false
        rule.onRoot().performTouchInput { moveBy(20f.toOffset()) }
        assertThat(consumedByDrag).isEqualTo(10f)
        assertThat(consumedByEffect).isEqualTo(20f)

        assertThat(effect.applyToFlingDone).isFalse()
        rule.onRoot().performTouchInput { up() }
        assertThat(effect.applyToFlingDone).isTrue()
    }

    @Test
    fun awaitFling() = runTest {
        var flingIsDone = false
        val draggable =
            TestDraggable(
                onDragStopped = { _, awaitFling ->
                    // Start a coroutine in the background that waits for the fling to be finished.
                    launch {
                        awaitFling()
                        flingIsDone = true
                    }

                    0f
                }
            )

        val effectPostFlingCompletable = CompletableDeferred<Unit>()
        val effect =
            TestOverscrollEffect(
                orientation,
                onPostScroll = { 0f },
                onPostFling = {
                    effectPostFlingCompletable.await()
                    it
                },
            )

        val touchSlop =
            rule.setContentWithTouchSlop {
                Box(
                    Modifier.fillMaxSize()
                        .nestedDraggable(draggable, orientation, overscrollEffect = effect)
                )
            }

        assertThat(draggable.onDragStartedCalled).isFalse()

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(touchSlop.toOffset())
            up()
        }

        // The drag was started and stopped, but the fling is not finished yet as the overscroll
        // effect is stuck on the effectPostFlingCompletable.
        runCurrent()
        rule.waitForIdle()
        assertThat(draggable.onDragStartedCalled).isTrue()
        assertThat(draggable.onDragStoppedCalled).isTrue()
        assertThat(flingIsDone).isFalse()

        effectPostFlingCompletable.complete(Unit)
        runCurrent()
        rule.waitForIdle()
        assertThat(flingIsDone).isTrue()
    }

    private fun ComposeContentTestRule.setContentWithTouchSlop(
        content: @Composable () -> Unit
    ): Float {
        var touchSlop = 0f
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            content()
        }
        return touchSlop
    }

    private fun Modifier.nestedScrollable(scrollState: ScrollState): Modifier {
        return when (orientation) {
            Orientation.Vertical -> verticalScroll(scrollState)
            Orientation.Horizontal -> horizontalScroll(scrollState)
        }
    }

    private class TestDraggable(
        private val onDragStarted: (Offset, Float) -> Unit = { _, _ -> },
        private val onDrag: (Float) -> Float = { it },
        private val onDragStopped: suspend (Float, awaitFling: suspend () -> Unit) -> Float =
            { velocity, _ ->
                velocity
            },
        private val shouldConsumeNestedScroll: (Float) -> Boolean = { true },
    ) : NestedDraggable {
        var shouldStartDrag = true
        var onDragStartedCalled = false
        var onDragCalled = false
        var onDragStoppedCalled = false

        var onDragStartedPosition = Offset.Zero
        var onDragStartedSign = 0f
        var onDragStartedPointersDown = 0
        var onDragDelta = 0f

        override fun shouldStartDrag(change: PointerInputChange): Boolean = shouldStartDrag

        override fun onDragStarted(
            position: Offset,
            sign: Float,
            pointersDown: Int,
        ): NestedDraggable.Controller {
            onDragStartedCalled = true
            onDragStartedPosition = position
            onDragStartedSign = sign
            onDragStartedPointersDown = pointersDown
            onDragDelta = 0f

            onDragStarted.invoke(position, sign)
            return object : NestedDraggable.Controller {
                override fun onDrag(delta: Float): Float {
                    onDragCalled = true
                    onDragDelta += delta
                    return onDrag.invoke(delta)
                }

                override suspend fun onDragStopped(
                    velocity: Float,
                    awaitFling: suspend () -> Unit,
                ): Float {
                    onDragStoppedCalled = true
                    return onDragStopped.invoke(velocity, awaitFling)
                }
            }
        }

        override fun shouldConsumeNestedScroll(sign: Float): Boolean {
            return shouldConsumeNestedScroll.invoke(sign)
        }
    }
}
