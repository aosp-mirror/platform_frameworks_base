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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.NestedScrollBehavior.Always
import com.android.compose.animation.scene.NestedScrollBehavior.DuringTransitionBetweenScenes
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeNoOverscroll
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeWithOverscroll
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.TransitionState.Idle
import com.android.compose.animation.scene.TransitionState.Transition
import com.android.compose.test.MonotonicClockTestScope
import com.android.compose.test.runMonotonicClockTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

private const val SCREEN_SIZE = 100f
private val LAYOUT_SIZE = IntSize(SCREEN_SIZE.toInt(), SCREEN_SIZE.toInt())

@RunWith(AndroidJUnit4::class)
class SceneGestureHandlerTest {
    private class TestGestureScope(
        val coroutineScope: MonotonicClockTestScope,
    ) {
        private var internalCurrentScene: SceneKey by mutableStateOf(SceneA)

        private val layoutState: SceneTransitionLayoutState =
            SceneTransitionLayoutState(internalCurrentScene)

        private val scenesBuilder: SceneTransitionLayoutScope.() -> Unit = {
            scene(
                key = SceneA,
                userActions = mapOf(Swipe.Up to SceneB, Swipe.Down to SceneC),
            ) {
                Text("SceneA")
            }
            scene(SceneB) { Text("SceneB") }
            scene(SceneC) { Text("SceneC") }
        }

        val sceneGestureHandler =
            SceneGestureHandler(
                layoutImpl =
                    SceneTransitionLayoutImpl(
                            onChangeScene = { internalCurrentScene = it },
                            builder = scenesBuilder,
                            transitions = EmptyTestTransitions,
                            state = layoutState,
                            density = Density(1f),
                            edgeDetector = DefaultEdgeDetector,
                            coroutineScope = coroutineScope,
                        )
                        .apply { setScenesTargetSizeForTest(LAYOUT_SIZE) },
                orientation = Orientation.Vertical,
                coroutineScope = coroutineScope,
            )

        val draggable = sceneGestureHandler.draggable

        fun nestedScrollConnection(nestedScrollBehavior: NestedScrollBehavior) =
            SceneNestedScrollHandler(
                    gestureHandler = sceneGestureHandler,
                    startBehavior = nestedScrollBehavior,
                    endBehavior = nestedScrollBehavior,
                )
                .connection

        val velocityThreshold = sceneGestureHandler.velocityThreshold

        // 10% of the screen
        val deltaInPixels10 = SCREEN_SIZE * 0.1f

        // Offset y: 10% of the screen
        val offsetY10 = Offset(x = 0f, y = deltaInPixels10)

        val transitionState: TransitionState
            get() = layoutState.transitionState

        fun advanceUntilIdle() {
            coroutineScope.testScheduler.advanceUntilIdle()
        }

        fun runCurrent() {
            coroutineScope.testScheduler.runCurrent()
        }

        fun assertScene(currentScene: SceneKey, isIdle: Boolean) {
            val idleMsg = if (isIdle) "MUST" else "MUST NOT"
            assertWithMessage("transitionState $idleMsg be Idle")
                .that(transitionState is Idle)
                .isEqualTo(isIdle)
            assertThat(transitionState.currentScene).isEqualTo(currentScene)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun runGestureTest(block: suspend TestGestureScope.() -> Unit) {
        runMonotonicClockTest { TestGestureScope(coroutineScope = this).block() }
    }

    private fun DraggableHandler.onDragStarted() =
        onDragStarted(layoutSize = LAYOUT_SIZE, startedPosition = Offset.Zero)

    @Test
    fun testPreconditions() = runGestureTest { assertScene(currentScene = SceneA, isIdle = true) }

    @Test
    fun onDragStarted_shouldStartATransition() = runGestureTest {
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)
    }

    @Test
    fun afterSceneTransitionIsStarted_interceptDragEvents() = runGestureTest {
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)
        val transition = transitionState as Transition

        draggable.onDelta(pixels = deltaInPixels10)
        assertThat(transition.progress).isEqualTo(0.1f)

        draggable.onDelta(pixels = deltaInPixels10)
        assertThat(transition.progress).isEqualTo(0.2f)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityLowerThanThreshold_remainSameScene() = runGestureTest {
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDelta(pixels = deltaInPixels10)
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDragStopped(
            velocity = velocityThreshold - 0.01f,
        )
        assertScene(currentScene = SceneA, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityAtLeastThreshold_goToNextScene() = runGestureTest {
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDelta(pixels = deltaInPixels10)
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDragStopped(
            velocity = velocityThreshold,
        )
        assertScene(currentScene = SceneC, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneC, isIdle = true)
    }

    @Test
    fun onDragStoppedAfterStarted_returnImmediatelyToIdle() = runGestureTest {
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDragStopped(velocity = 0f)
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun startGestureDuringAnimatingOffset_shouldImmediatelyStopTheAnimation() = runGestureTest {
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDelta(pixels = deltaInPixels10)
        assertScene(currentScene = SceneA, isIdle = false)

        draggable.onDragStopped(
            velocity = velocityThreshold,
        )

        // The stop animation is not started yet
        assertThat(sceneGestureHandler.isAnimatingOffset).isFalse()

        runCurrent()

        assertThat(sceneGestureHandler.isAnimatingOffset).isTrue()
        assertThat(sceneGestureHandler.isDrivingTransition).isTrue()
        assertScene(currentScene = SceneC, isIdle = false)

        // Start a new gesture while the offset is animating
        draggable.onDragStarted()
        assertThat(sceneGestureHandler.isAnimatingOffset).isFalse()
    }

    @Test
    fun onInitialPreScroll_EdgeWithOverscroll_doNotChangeState() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithOverscroll)
        nestedScroll.onPreScroll(available = offsetY10, source = NestedScrollSource.Drag)
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun onPostScrollWithNothingAvailable_EdgeWithOverscroll_doNotChangeState() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithOverscroll)
        val consumed =
            nestedScroll.onPostScroll(
                consumed = Offset.Zero,
                available = Offset.Zero,
                source = NestedScrollSource.Drag
            )

        assertScene(currentScene = SceneA, isIdle = true)
        assertThat(consumed).isEqualTo(Offset.Zero)
    }

    @Test
    fun onPostScrollWithSomethingAvailable_startSceneTransition() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithOverscroll)
        val consumed =
            nestedScroll.onPostScroll(
                consumed = Offset.Zero,
                available = offsetY10,
                source = NestedScrollSource.Drag
            )

        assertScene(currentScene = SceneA, isIdle = false)
        val transition = transitionState as Transition
        assertThat(transition.progress).isEqualTo(0.1f)
        assertThat(consumed).isEqualTo(offsetY10)
    }

    private fun NestedScrollConnection.scroll(
        available: Offset,
        consumedByScroll: Offset = Offset.Zero,
    ) {
        val consumedByPreScroll =
            onPreScroll(available = available, source = NestedScrollSource.Drag)
        val consumed = consumedByPreScroll + consumedByScroll
        onPostScroll(
            consumed = consumed,
            available = available - consumed,
            source = NestedScrollSource.Drag
        )
    }

    @Test
    fun afterSceneTransitionIsStarted_interceptPreScrollEvents() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithOverscroll)
        nestedScroll.scroll(available = offsetY10)
        assertScene(currentScene = SceneA, isIdle = false)

        val transition = transitionState as Transition
        assertThat(transition.progress).isEqualTo(0.1f)

        // start intercept preScroll
        val consumed =
            nestedScroll.onPreScroll(available = offsetY10, source = NestedScrollSource.Drag)
        assertThat(transition.progress).isEqualTo(0.2f)

        // do nothing on postScroll
        nestedScroll.onPostScroll(
            consumed = consumed,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
        assertThat(transition.progress).isEqualTo(0.2f)

        nestedScroll.scroll(available = offsetY10)
        assertThat(transition.progress).isEqualTo(0.3f)
        assertScene(currentScene = SceneA, isIdle = false)
    }

    @Test
    fun onPreFling_velocityLowerThanThreshold_remainSameScene() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithOverscroll)
        nestedScroll.scroll(available = offsetY10)
        assertScene(currentScene = SceneA, isIdle = false)

        nestedScroll.onPreFling(available = Velocity.Zero)
        assertScene(currentScene = SceneA, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneA, isIdle = true)
    }

    private suspend fun TestGestureScope.flingAfterScroll(
        use: NestedScrollBehavior,
        idleAfterScroll: Boolean,
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = use)
        nestedScroll.scroll(available = offsetY10)
        assertScene(currentScene = SceneA, isIdle = idleAfterScroll)

        nestedScroll.onPreFling(available = Velocity(0f, velocityThreshold))
    }

    @Test
    fun flingAfterScroll_DuringTransitionBetweenScenes_doNothing() = runGestureTest {
        flingAfterScroll(use = DuringTransitionBetweenScenes, idleAfterScroll = true)

        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun flingAfterScroll_EdgeNoOverscroll_goToNextScene() = runGestureTest {
        flingAfterScroll(use = EdgeNoOverscroll, idleAfterScroll = false)

        assertScene(currentScene = SceneC, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneC, isIdle = true)
    }

    @Test
    fun flingAfterScroll_EdgeWithOverscroll_goToNextScene() = runGestureTest {
        flingAfterScroll(use = EdgeWithOverscroll, idleAfterScroll = false)

        assertScene(currentScene = SceneC, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneC, isIdle = true)
    }

    @Test
    fun flingAfterScroll_Always_goToNextScene() = runGestureTest {
        flingAfterScroll(use = Always, idleAfterScroll = false)

        assertScene(currentScene = SceneC, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneC, isIdle = true)
    }

    /** we started the scroll in the scene, then fling with the velocityThreshold */
    private suspend fun TestGestureScope.flingAfterScrollStartedInScene(
        use: NestedScrollBehavior,
        idleAfterScroll: Boolean,
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = use)
        // scroll consumed in child
        nestedScroll.scroll(available = offsetY10, consumedByScroll = offsetY10)

        // scroll offsetY10 is all available for parents
        nestedScroll.scroll(available = offsetY10)
        assertScene(currentScene = SceneA, isIdle = idleAfterScroll)

        nestedScroll.onPreFling(available = Velocity(0f, velocityThreshold))
    }

    @Test
    fun flingAfterScrollStartedInScene_DuringTransitionBetweenScenes_doNothing() = runGestureTest {
        flingAfterScrollStartedInScene(use = DuringTransitionBetweenScenes, idleAfterScroll = true)

        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun flingAfterScrollStartedInScene_EdgeNoOverscroll_doNothing() = runGestureTest {
        flingAfterScrollStartedInScene(use = EdgeNoOverscroll, idleAfterScroll = true)

        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun flingAfterScrollStartedInScene_EdgeWithOverscroll_doOverscrollAnimation() = runGestureTest {
        flingAfterScrollStartedInScene(use = EdgeWithOverscroll, idleAfterScroll = false)

        assertScene(currentScene = SceneA, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun flingAfterScrollStartedInScene_Always_goToNextScene() = runGestureTest {
        flingAfterScrollStartedInScene(use = Always, idleAfterScroll = false)

        assertScene(currentScene = SceneC, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneC, isIdle = true)
    }

    @Test
    fun beforeDraggableStart_drag_shouldBeIgnored() = runGestureTest {
        draggable.onDelta(deltaInPixels10)
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun beforeDraggableStart_stop_shouldBeIgnored() = runGestureTest {
        draggable.onDragStopped(velocityThreshold)
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun beforeNestedScrollStart_stop_shouldBeIgnored() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithOverscroll)
        nestedScroll.onPreFling(Velocity(0f, velocityThreshold))
        assertScene(currentScene = SceneA, isIdle = true)
    }

    @Test
    fun startNestedScrollWhileDragging() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = Always)
        draggable.onDragStarted()
        assertScene(currentScene = SceneA, isIdle = false)
        val transition = transitionState as Transition

        draggable.onDelta(deltaInPixels10)
        assertThat(transition.progress).isEqualTo(0.1f)

        // now we can intercept the scroll events
        nestedScroll.scroll(available = offsetY10)
        assertThat(transition.progress).isEqualTo(0.2f)

        // this should be ignored, we are scrolling now!
        draggable.onDragStopped(velocityThreshold)
        assertScene(currentScene = SceneA, isIdle = false)

        nestedScroll.scroll(available = offsetY10)
        assertThat(transition.progress).isEqualTo(0.3f)

        nestedScroll.scroll(available = offsetY10)
        assertThat(transition.progress).isEqualTo(0.4f)

        nestedScroll.onPreFling(available = Velocity(0f, velocityThreshold))
        assertScene(currentScene = SceneC, isIdle = false)

        // wait for the stop animation
        advanceUntilIdle()
        assertScene(currentScene = SceneC, isIdle = true)
    }
}
