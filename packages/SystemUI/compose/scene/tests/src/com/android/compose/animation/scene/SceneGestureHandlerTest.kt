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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.NestedScrollBehavior.DuringTransitionBetweenScenes
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeAlways
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeNoPreview
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeWithPreview
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
        private val layoutState =
            MutableSceneTransitionLayoutStateImpl(SceneA, EmptyTestTransitions)

        val mutableUserActionsA: MutableMap<UserAction, SceneKey> =
            mutableMapOf(Swipe.Up to SceneB, Swipe.Down to SceneC)

        val mutableUserActionsB: MutableMap<UserAction, SceneKey> =
            mutableMapOf(Swipe.Up to SceneC, Swipe.Down to SceneA)

        private val scenesBuilder: SceneTransitionLayoutScope.() -> Unit = {
            scene(
                key = SceneA,
                userActions = mutableUserActionsA,
            ) {
                Text("SceneA")
            }
            scene(
                key = SceneB,
                userActions = mutableUserActionsB,
            ) {
                Text("SceneB")
            }
            scene(
                key = SceneC,
                userActions =
                    mapOf(
                        Swipe.Up to SceneB,
                        Swipe(SwipeDirection.Up, fromSource = Edge.Bottom) to SceneA
                    ),
            ) {
                Text("SceneC")
            }
        }

        val transitionInterceptionThreshold = 0.05f

        private val layoutImpl =
            SceneTransitionLayoutImpl(
                    state = layoutState,
                    density = Density(1f),
                    swipeSourceDetector = DefaultEdgeDetector,
                    transitionInterceptionThreshold = transitionInterceptionThreshold,
                    builder = scenesBuilder,
                    coroutineScope = coroutineScope,
                )
                .apply { setScenesTargetSizeForTest(LAYOUT_SIZE) }

        val sceneGestureHandler = layoutImpl.gestureHandler(Orientation.Vertical)
        val horizontalSceneGestureHandler = layoutImpl.gestureHandler(Orientation.Horizontal)
        val draggable = sceneGestureHandler.draggable

        fun nestedScrollConnection(nestedScrollBehavior: NestedScrollBehavior) =
            SceneNestedScrollHandler(
                    layoutImpl,
                    orientation = sceneGestureHandler.orientation,
                    topOrLeftBehavior = nestedScrollBehavior,
                    bottomOrRightBehavior = nestedScrollBehavior,
                )
                .connection

        val velocityThreshold = sceneGestureHandler.velocityThreshold

        fun down(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) error("use up()") else SCREEN_SIZE * fractionOfScreen

        fun up(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) error("use down()") else -down(fractionOfScreen)

        // Float tolerance for comparisons
        val tolerance = 0.00001f

        // Offset y: 10% of the screen
        val offsetY10 = Offset(x = 0f, y = down(0.1f))

        val transitionState: TransitionState
            get() = layoutState.transitionState

        val progress: Float
            get() = (transitionState as Transition).progress

        fun advanceUntilIdle() {
            coroutineScope.testScheduler.advanceUntilIdle()
        }

        fun runCurrent() {
            coroutineScope.testScheduler.runCurrent()
        }

        fun assertIdle(currentScene: SceneKey) {
            assertWithMessage("transitionState must be Idle").that(transitionState is Idle).isTrue()
            assertWithMessage("currentScene does not match")
                .that(transitionState.currentScene)
                .isEqualTo(currentScene)
        }

        fun assertTransition(
            currentScene: SceneKey? = null,
            fromScene: SceneKey? = null,
            toScene: SceneKey? = null,
            progress: Float? = null,
        ) {
            assertWithMessage("transitionState must be Transition")
                .that(transitionState is Transition)
                .isTrue()
            if (currentScene != null)
                assertWithMessage("currentScene does not match")
                    .that(transitionState.currentScene)
                    .isEqualTo(currentScene)
            if (fromScene != null)
                assertWithMessage("fromScene does not match")
                    .that((transitionState as? Transition)?.fromScene)
                    .isEqualTo(fromScene)
            if (toScene != null)
                assertWithMessage("toScene does not match")
                    .that((transitionState as? Transition)?.toScene)
                    .isEqualTo(toScene)
            if (progress != null)
                assertWithMessage("progress does not match")
                    .that((transitionState as? Transition)?.progress)
                    .isWithin(tolerance)
                    .of(progress)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun runGestureTest(block: suspend TestGestureScope.() -> Unit) {
        runMonotonicClockTest { TestGestureScope(coroutineScope = this).block() }
    }

    private fun DraggableHandler.onDragStarted(
        overSlop: Float = 0f,
        startedPosition: Offset = Offset.Zero,
    ) {
        onDragStarted(startedPosition, overSlop)
        // MultiPointerDraggable will always call onDelta with the initial overSlop right after
        onDelta(overSlop)
    }

    @Test fun testPreconditions() = runGestureTest { assertIdle(currentScene = SceneA) }

    @Test
    fun onDragStarted_shouldStartATransition() = runGestureTest {
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)
    }

    @Test
    fun afterSceneTransitionIsStarted_interceptDragEvents() = runGestureTest {
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)

        draggable.onDelta(pixels = down(0.1f))
        assertThat(progress).isEqualTo(0.1f)

        draggable.onDelta(pixels = down(0.1f))
        assertThat(progress).isEqualTo(0.2f)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityLowerThanThreshold_remainSameScene() = runGestureTest {
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)

        draggable.onDelta(pixels = down(0.1f))
        assertTransition(currentScene = SceneA)

        draggable.onDragStopped(
            velocity = velocityThreshold - 0.01f,
        )
        assertTransition(currentScene = SceneA)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityAtLeastThreshold_goToNextScene() = runGestureTest {
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)

        draggable.onDelta(pixels = down(0.1f))
        assertTransition(currentScene = SceneA)

        draggable.onDragStopped(velocity = velocityThreshold)

        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragStoppedAfterStarted_returnToIdle() = runGestureTest {
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)

        draggable.onDragStopped(velocity = 0f)
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragReversedDirection_changeToScene() = runGestureTest {
        // Drag A -> B with progress 0.6
        draggable.onDragStarted()
        draggable.onDelta(up(0.6f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.6f
        )

        // Reverse direction such that A -> C now with 0.4
        draggable.onDelta(down(1f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.4f
        )

        // After the drag stopped scene C should be committed
        draggable.onDragStopped(velocity = velocityThreshold)
        assertTransition(currentScene = SceneC, fromScene = SceneA, toScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragStartedWithoutActionsInBothDirections_stayIdle() = runGestureTest {
        horizontalSceneGestureHandler.draggable.onDragStarted(up(0.3f))
        assertIdle(currentScene = SceneA)
        horizontalSceneGestureHandler.draggable.onDragStarted(down(0.3f))
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragIntoNoAction_startTransitionToOppositeDirection() = runGestureTest {
        navigateToSceneC()

        // We are on SceneC which has no action in Down direction
        draggable.onDragStarted(down(0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = -0.1f
        )

        // Reverse drag direction, it will consume the previous drag
        draggable.onDelta(up(0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.0f
        )

        // Continue reverse drag direction, it should record progress to Scene B
        draggable.onDelta(up(0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.1f
        )
    }

    @Test
    fun onDragFromEdge_startTransitionToEdgeAction() = runGestureTest {
        navigateToSceneC()

        // Start dragging from the bottom
        draggable.onDragStarted(up(0.1f), Offset(SCREEN_SIZE * 0.5f, SCREEN_SIZE))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneA,
            progress = 0.1f
        )
    }

    @Test
    fun onDragToExactlyZero_toSceneIsSet() = runGestureTest {
        draggable.onDragStarted(down(0.3f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.3f
        )
        draggable.onDelta(up(0.3f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.0f
        )
    }

    private fun TestGestureScope.navigateToSceneC() {
        assertIdle(currentScene = SceneA)
        draggable.onDragStarted(down(1f))
        draggable.onDragStopped(0f)
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onAccelaratedScroll_scrollToThirdScene() = runGestureTest {
        // Drag A -> B with progress 0.2
        draggable.onDragStarted()
        draggable.onDelta(up(0.2f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.2f
        )

        // Start animation A -> B with progress 0.2 -> 1.0
        draggable.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        // While at A -> B do a 100% screen drag (progress 1.2). This should go past B and change
        // the transition to B -> C with progress 0.2
        draggable.onDragStarted()
        draggable.onDelta(up(1f))
        assertTransition(
            currentScene = SceneB,
            fromScene = SceneB,
            toScene = SceneC,
            progress = 0.2f
        )

        // After the drag stopped scene C should be committed
        draggable.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneC, fromScene = SceneB, toScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onAccelaratedScrollBothTargetsBecomeNull_settlesToIdle() = runGestureTest {
        draggable.onDragStarted()
        draggable.onDelta(up(0.2f))

        draggable.onDelta(up(0.2f))
        draggable.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        mutableUserActionsA.remove(Swipe.Up)
        mutableUserActionsA.remove(Swipe.Down)
        mutableUserActionsB.remove(Swipe.Up)
        mutableUserActionsB.remove(Swipe.Down)

        // start accelaratedScroll and scroll over to B -> null
        draggable.onDragStarted()
        draggable.onDelta(up(0.5f))
        draggable.onDelta(up(0.5f))

        // here onDragStopped is already triggered, but subsequent onDelta/onDragStopped calls may
        // still be called. Make sure that they don't crash or change the scene
        draggable.onDelta(up(0.5f))
        draggable.onDragStopped(0f)

        advanceUntilIdle()
        assertIdle(SceneB)

        // These events can still come in after the animation has settled
        draggable.onDelta(up(0.5f))
        draggable.onDragStopped(0f)
        assertIdle(SceneB)
    }

    @Test
    fun onDragTargetsChanged_targetStaysTheSame() = runGestureTest {
        draggable.onDragStarted(up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.1f)

        mutableUserActionsA[Swipe.Up] = SceneC
        draggable.onDelta(up(0.1f))
        // target stays B even though UserActions changed
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.2f)
        draggable.onDragStopped(down(0.1f))
        advanceUntilIdle()

        // now target changed to C for new drag
        draggable.onDragStarted(up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.1f)
    }

    @Test
    fun onDragTargetsChanged_targetsChangeWhenStartingNewDrag() = runGestureTest {
        draggable.onDragStarted(up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.1f)

        mutableUserActionsA[Swipe.Up] = SceneC
        draggable.onDelta(up(0.1f))
        draggable.onDragStopped(down(0.1f))

        // now target changed to C for new drag that started before previous drag settled to Idle
        draggable.onDragStarted(up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.3f)
    }

    @Test
    fun startGestureDuringAnimatingOffset_shouldImmediatelyStopTheAnimation() = runGestureTest {
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)

        draggable.onDelta(pixels = down(0.1f))
        assertTransition(currentScene = SceneA)

        draggable.onDragStopped(
            velocity = velocityThreshold,
        )

        // The stop animation is not started yet
        assertThat(sceneGestureHandler.swipeTransition.isAnimatingOffset).isFalse()

        runCurrent()

        assertThat(sceneGestureHandler.swipeTransition.isAnimatingOffset).isTrue()
        assertThat(sceneGestureHandler.isDrivingTransition).isTrue()
        assertTransition(currentScene = SceneC)

        // Start a new gesture while the offset is animating
        draggable.onDragStarted()
        assertThat(sceneGestureHandler.swipeTransition.isAnimatingOffset).isFalse()
    }

    @Test
    fun onInitialPreScroll_EdgeWithOverscroll_doNotChangeState() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.onPreScroll(available = offsetY10, source = NestedScrollSource.Drag)
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onPostScrollWithNothingAvailable_EdgeWithOverscroll_doNotChangeState() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        val consumed =
            nestedScroll.onPostScroll(
                consumed = Offset.Zero,
                available = Offset.Zero,
                source = NestedScrollSource.Drag
            )

        assertIdle(currentScene = SceneA)
        assertThat(consumed).isEqualTo(Offset.Zero)
    }

    @Test
    fun onPostScrollWithSomethingAvailable_startSceneTransition() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        val consumed =
            nestedScroll.onPostScroll(
                consumed = Offset.Zero,
                available = offsetY10,
                source = NestedScrollSource.Drag
            )

        assertTransition(currentScene = SceneA)
        assertThat(progress).isEqualTo(0.1f)
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
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.scroll(available = offsetY10)
        assertTransition(currentScene = SceneA)

        assertThat(progress).isEqualTo(0.1f)

        // start intercept preScroll
        val consumed =
            nestedScroll.onPreScroll(available = offsetY10, source = NestedScrollSource.Drag)
        assertThat(progress).isEqualTo(0.2f)

        // do nothing on postScroll
        nestedScroll.onPostScroll(
            consumed = consumed,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
        assertThat(progress).isEqualTo(0.2f)

        nestedScroll.scroll(available = offsetY10)
        assertThat(progress).isEqualTo(0.3f)
        assertTransition(currentScene = SceneA)
    }

    private suspend fun TestGestureScope.preScrollAfterSceneTransition(
        firstScroll: Float,
        secondScroll: Float
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        // start scene transition
        nestedScroll.scroll(available = Offset(0f, SCREEN_SIZE * firstScroll))

        // stop scene transition (start the "stop animation")
        nestedScroll.onPreFling(available = Velocity.Zero)

        // a pre scroll event, that could be intercepted by SceneGestureHandler
        nestedScroll.onPreScroll(Offset(0f, SCREEN_SIZE * secondScroll), NestedScrollSource.Drag)
    }

    @Test
    fun scrollAndFling_scrollLessThanInterceptable_goToIdleOnCurrentScene() = runGestureTest {
        val first = transitionInterceptionThreshold - tolerance
        val second = 0.01f

        preScrollAfterSceneTransition(firstScroll = first, secondScroll = second)

        assertIdle(SceneA)
    }

    @Test
    fun scrollAndFling_scrollMinInterceptable_interceptPreScrollEvents() = runGestureTest {
        val first = transitionInterceptionThreshold + tolerance
        val second = 0.01f

        preScrollAfterSceneTransition(firstScroll = first, secondScroll = second)

        assertTransition(progress = first + second)
    }

    @Test
    fun scrollAndFling_scrollMaxInterceptable_interceptPreScrollEvents() = runGestureTest {
        val first = 1f - transitionInterceptionThreshold - tolerance
        val second = 0.01f

        preScrollAfterSceneTransition(firstScroll = first, secondScroll = second)

        assertTransition(progress = first + second)
    }

    @Test
    fun scrollAndFling_scrollMoreThanInterceptable_goToIdleOnNextScene() = runGestureTest {
        val first = 1f - transitionInterceptionThreshold + tolerance
        val second = 0.01f

        preScrollAfterSceneTransition(firstScroll = first, secondScroll = second)

        assertIdle(SceneC)
    }

    @Test
    fun onPreFling_velocityLowerThanThreshold_remainSameScene() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.scroll(available = offsetY10)
        assertTransition(currentScene = SceneA)

        nestedScroll.onPreFling(available = Velocity.Zero)
        assertTransition(currentScene = SceneA)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    private suspend fun TestGestureScope.flingAfterScroll(
        use: NestedScrollBehavior,
        idleAfterScroll: Boolean,
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = use)
        nestedScroll.scroll(available = offsetY10)
        if (idleAfterScroll) assertIdle(SceneA) else assertTransition(SceneA)

        nestedScroll.onPreFling(available = Velocity(0f, velocityThreshold))
    }

    @Test
    fun flingAfterScroll_DuringTransitionBetweenScenes_doNothing() = runGestureTest {
        flingAfterScroll(use = DuringTransitionBetweenScenes, idleAfterScroll = true)

        assertIdle(currentScene = SceneA)
    }

    @Test
    fun flingAfterScroll_EdgeNoOverscroll_goToNextScene() = runGestureTest {
        flingAfterScroll(use = EdgeNoPreview, idleAfterScroll = false)

        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun flingAfterScroll_EdgeWithOverscroll_goToNextScene() = runGestureTest {
        flingAfterScroll(use = EdgeWithPreview, idleAfterScroll = false)

        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun flingAfterScroll_Always_goToNextScene() = runGestureTest {
        flingAfterScroll(use = EdgeAlways, idleAfterScroll = false)

        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
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
        if (idleAfterScroll) assertIdle(SceneA) else assertTransition(SceneA)

        nestedScroll.onPreFling(available = Velocity(0f, velocityThreshold))
    }

    @Test
    fun flingAfterScrollStartedInScene_DuringTransitionBetweenScenes_doNothing() = runGestureTest {
        flingAfterScrollStartedInScene(use = DuringTransitionBetweenScenes, idleAfterScroll = true)

        assertIdle(currentScene = SceneA)
    }

    @Test
    fun flingAfterScrollStartedInScene_EdgeNoOverscroll_doNothing() = runGestureTest {
        flingAfterScrollStartedInScene(use = EdgeNoPreview, idleAfterScroll = true)

        assertIdle(currentScene = SceneA)
    }

    @Test
    fun flingAfterScrollStartedInScene_EdgeWithOverscroll_doOverscrollAnimation() = runGestureTest {
        flingAfterScrollStartedInScene(use = EdgeWithPreview, idleAfterScroll = false)

        assertTransition(currentScene = SceneA)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun flingAfterScrollStartedInScene_Always_goToNextScene() = runGestureTest {
        flingAfterScrollStartedInScene(use = EdgeAlways, idleAfterScroll = false)

        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun beforeDraggableStart_drag_shouldBeIgnored() = runGestureTest {
        draggable.onDelta(down(0.1f))
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun beforeDraggableStart_stop_shouldBeIgnored() = runGestureTest {
        draggable.onDragStopped(velocityThreshold)
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun beforeNestedScrollStart_stop_shouldBeIgnored() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.onPreFling(Velocity(0f, velocityThreshold))
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun startNestedScrollWhileDragging() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeAlways)
        draggable.onDragStarted()
        assertTransition(currentScene = SceneA)

        draggable.onDelta(down(0.1f))
        assertThat(progress).isEqualTo(0.1f)

        // now we can intercept the scroll events
        nestedScroll.scroll(available = offsetY10)
        assertThat(progress).isEqualTo(0.2f)

        // this should be ignored, we are scrolling now!
        draggable.onDragStopped(velocityThreshold)
        assertTransition(currentScene = SceneA)

        nestedScroll.scroll(available = offsetY10)
        assertThat(progress).isEqualTo(0.3f)

        nestedScroll.scroll(available = offsetY10)
        assertThat(progress).isEqualTo(0.4f)

        nestedScroll.onPreFling(available = Velocity(0f, velocityThreshold))
        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }
}
