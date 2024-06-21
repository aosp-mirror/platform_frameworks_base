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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
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
import com.android.compose.animation.scene.TransitionState.Transition
import com.android.compose.animation.scene.subjects.assertThat
import com.android.compose.test.MonotonicClockTestScope
import com.android.compose.test.runMonotonicClockTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

private const val SCREEN_SIZE = 100f
private val LAYOUT_SIZE = IntSize(SCREEN_SIZE.toInt(), SCREEN_SIZE.toInt())

@RunWith(AndroidJUnit4::class)
class DraggableHandlerTest {
    private class TestGestureScope(
        private val testScope: MonotonicClockTestScope,
    ) {
        var canChangeScene: (SceneKey) -> Boolean = { true }
        val layoutState =
            MutableSceneTransitionLayoutStateImpl(
                SceneA,
                EmptyTestTransitions,
                canChangeScene = { canChangeScene(it) },
            )

        val mutableUserActionsA = mutableMapOf(Swipe.Up to SceneB, Swipe.Down to SceneC)
        val mutableUserActionsB = mutableMapOf(Swipe.Up to SceneC, Swipe.Down to SceneA)
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
                    coroutineScope = testScope,
                )
                .apply { setScenesTargetSizeForTest(LAYOUT_SIZE) }

        val draggableHandler = layoutImpl.draggableHandler(Orientation.Vertical)
        val horizontalDraggableHandler = layoutImpl.draggableHandler(Orientation.Horizontal)

        fun nestedScrollConnection(
            nestedScrollBehavior: NestedScrollBehavior,
            isExternalOverscrollGesture: Boolean = false
        ) =
            NestedScrollHandlerImpl(
                    layoutImpl = layoutImpl,
                    orientation = draggableHandler.orientation,
                    topOrLeftBehavior = nestedScrollBehavior,
                    bottomOrRightBehavior = nestedScrollBehavior,
                    isExternalOverscrollGesture = { isExternalOverscrollGesture },
                    pointersInfo = { PointersInfo(pointersDown = 1, startedPosition = Offset.Zero) }
                )
                .connection

        val velocityThreshold = draggableHandler.velocityThreshold

        fun down(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) error("use up()") else SCREEN_SIZE * fractionOfScreen

        fun up(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) error("use down()") else -down(fractionOfScreen)

        fun downOffset(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) {
                error("upOffset() is required, not implemented yet")
            } else {
                Offset(x = 0f, y = down(fractionOfScreen))
            }

        val transitionState: TransitionState
            get() = layoutState.transitionState

        val progress: Float
            get() = (transitionState as Transition).progress

        val isUserInputOngoing: Boolean
            get() = (transitionState as Transition).isUserInputOngoing

        fun advanceUntilIdle() {
            testScope.testScheduler.advanceUntilIdle()
        }

        fun runCurrent() {
            testScope.testScheduler.runCurrent()
        }

        fun assertIdle(currentScene: SceneKey) {
            assertThat(transitionState).isIdle()
            assertThat(transitionState).hasCurrentScene(currentScene)
        }

        fun assertTransition(
            currentScene: SceneKey? = null,
            fromScene: SceneKey? = null,
            toScene: SceneKey? = null,
            progress: Float? = null,
            isUserInputOngoing: Boolean? = null
        ): Transition {
            val transition = assertThat(transitionState).isTransition()
            currentScene?.let { assertThat(transition).hasCurrentScene(it) }
            fromScene?.let { assertThat(transition).hasFromScene(it) }
            toScene?.let { assertThat(transition).hasToScene(it) }
            progress?.let { assertThat(transition).hasProgress(it) }
            isUserInputOngoing?.let { assertThat(transition).hasIsUserInputOngoing(it) }
            return transition
        }

        fun onDragStarted(
            startedPosition: Offset = Offset.Zero,
            overSlop: Float,
            pointersDown: Int = 1,
        ): DragController {
            // overSlop should be 0f only if the drag gesture starts with startDragImmediately
            if (overSlop == 0f) error("Consider using onDragStartedImmediately()")
            return onDragStarted(draggableHandler, startedPosition, overSlop, pointersDown)
        }

        fun onDragStartedImmediately(
            startedPosition: Offset = Offset.Zero,
            pointersDown: Int = 1,
        ): DragController {
            return onDragStarted(draggableHandler, startedPosition, overSlop = 0f, pointersDown)
        }

        fun onDragStarted(
            draggableHandler: DraggableHandler,
            startedPosition: Offset = Offset.Zero,
            overSlop: Float = 0f,
            pointersDown: Int = 1
        ): DragController {
            val dragController =
                draggableHandler.onDragStarted(
                    startedPosition = startedPosition,
                    overSlop = overSlop,
                    pointersDown = pointersDown,
                )

            // MultiPointerDraggable will always call onDelta with the initial overSlop right after
            dragController.onDragDelta(pixels = overSlop)

            return dragController
        }

        fun DragController.onDragDelta(pixels: Float) {
            onDrag(delta = pixels)
        }

        fun DragController.onDragStopped(velocity: Float, canChangeScene: Boolean = true) {
            onStop(velocity, canChangeScene)
        }

        fun NestedScrollConnection.scroll(
            available: Offset,
            consumedByScroll: Offset = Offset.Zero,
        ) {
            val consumedByPreScroll =
                onPreScroll(
                    available = available,
                    source = NestedScrollSource.Drag,
                )
            val consumed = consumedByPreScroll + consumedByScroll

            onPostScroll(
                consumed = consumed,
                available = available - consumed,
                source = NestedScrollSource.Drag
            )
        }

        fun NestedScrollConnection.preFling(
            available: Velocity,
            coroutineScope: CoroutineScope = testScope,
        ) {
            // onPreFling is a suspend function that returns the consumed velocity once it finishes
            // consuming it. In the current scenario, it returns after completing the animation.
            // To return immediately, we can initiate a job that allows us to check the status
            // before the animation starts.
            coroutineScope.launch { onPreFling(available = available) }
            runCurrent()
        }
    }

    private fun runGestureTest(block: suspend TestGestureScope.() -> Unit) {
        runMonotonicClockTest {
            val testGestureScope = TestGestureScope(testScope = this)

            // run the test
            testGestureScope.block()
        }
    }

    @Test fun testPreconditions() = runGestureTest { assertIdle(currentScene = SceneA) }

    @Test
    fun onDragStarted_shouldStartATransition() = runGestureTest {
        onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)
    }

    @Test
    fun afterSceneTransitionIsStarted_interceptDragEvents() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)
        assertThat(progress).isEqualTo(0.1f)

        dragController.onDragDelta(pixels = down(fractionOfScreen = 0.1f))
        assertThat(progress).isEqualTo(0.2f)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityLowerThanThreshold_remainSameScene() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStopped(velocity = velocityThreshold - 0.01f)
        assertTransition(currentScene = SceneA)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityAtLeastThreshold_goToNextScene() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStopped(velocity = velocityThreshold)
        assertTransition(currentScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragStoppedAfterStarted_returnToIdle() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStopped(velocity = 0f)
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragReversedDirection_changeToScene() = runGestureTest {
        // Drag A -> B with progress 0.6
        val dragController = onDragStarted(overSlop = -60f)
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.6f
        )

        // Reverse direction such that A -> C now with 0.4
        dragController.onDragDelta(pixels = 100f)
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.4f
        )

        // After the drag stopped scene C should be committed
        dragController.onDragStopped(velocity = velocityThreshold)
        assertTransition(currentScene = SceneC, fromScene = SceneA, toScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragStartedWithoutActionsInBothDirections_stayIdle() = runGestureTest {
        onDragStarted(horizontalDraggableHandler, overSlop = up(fractionOfScreen = 0.3f))
        assertIdle(currentScene = SceneA)

        onDragStarted(horizontalDraggableHandler, overSlop = down(fractionOfScreen = 0.3f))
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragIntoNoAction_startTransitionToOppositeDirection() = runGestureTest {
        navigateToSceneC()

        // We are on SceneC which has no action in Down direction
        val dragController = onDragStarted(overSlop = 10f)
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = -0.1f
        )

        // Reverse drag direction, it will consume the previous drag
        dragController.onDragDelta(pixels = -10f)
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.0f
        )

        // Continue reverse drag direction, it should record progress to Scene B
        dragController.onDragDelta(pixels = -10f)
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
        onDragStarted(
            startedPosition = Offset(SCREEN_SIZE * 0.5f, SCREEN_SIZE),
            overSlop = up(fractionOfScreen = 0.1f)
        )
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneA,
            progress = 0.1f
        )
    }

    @Test
    fun onDragToExactlyZero_toSceneIsSet() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.3f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.3f
        )
        dragController.onDragDelta(pixels = up(fractionOfScreen = 0.3f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.0f
        )
    }

    private fun TestGestureScope.navigateToSceneC() {
        assertIdle(currentScene = SceneA)
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 1f))
        dragController.onDragStopped(velocity = 0f)
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onAccelaratedScroll_scrollToThirdScene() = runGestureTest {
        // Drag A -> B with progress 0.2
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.2f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.2f
        )

        // Start animation A -> B with progress 0.2 -> 1.0
        dragController1.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        // While at A -> B do a 100% screen drag (progress 1.2). This should go past B and change
        // the transition to B -> C with progress 0.2
        val dragController2 = onDragStartedImmediately()
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 1f))
        assertTransition(
            currentScene = SceneB,
            fromScene = SceneB,
            toScene = SceneC,
            progress = 0.2f
        )

        // After the drag stopped scene C should be committed
        dragController2.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneC, fromScene = SceneB, toScene = SceneC)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onAccelaratedScrollBothTargetsBecomeNull_settlesToIdle() = runGestureTest {
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.2f))
        dragController1.onDragDelta(pixels = up(fractionOfScreen = 0.2f))
        dragController1.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        mutableUserActionsA.remove(Swipe.Up)
        mutableUserActionsA.remove(Swipe.Down)
        mutableUserActionsB.remove(Swipe.Up)
        mutableUserActionsB.remove(Swipe.Down)

        // start accelaratedScroll and scroll over to B -> null
        val dragController2 = onDragStartedImmediately()
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f))
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f))

        // here onDragStopped is already triggered, but subsequent onDelta/onDragStopped calls may
        // still be called. Make sure that they don't crash or change the scene
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f))
        dragController2.onDragStopped(velocity = 0f)

        advanceUntilIdle()
        assertIdle(SceneB)

        // These events can still come in after the animation has settled
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f))
        dragController2.onDragStopped(velocity = 0f)
        assertIdle(SceneB)
    }

    @Test
    fun onDragTargetsChanged_targetStaysTheSame() = runGestureTest {
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.1f)

        mutableUserActionsA[Swipe.Up] = UserActionResult(SceneC)
        dragController1.onDragDelta(pixels = up(fractionOfScreen = 0.1f))
        // target stays B even though UserActions changed
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.2f)
        dragController1.onDragStopped(velocity = down(fractionOfScreen = 0.1f))
        advanceUntilIdle()

        // now target changed to C for new drag
        onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.1f)
    }

    @Test
    fun onDragTargetsChanged_targetsChangeWhenStartingNewDrag() = runGestureTest {
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.1f)

        mutableUserActionsA[Swipe.Up] = UserActionResult(SceneC)
        dragController1.onDragDelta(pixels = up(fractionOfScreen = 0.1f))
        dragController1.onDragStopped(velocity = down(fractionOfScreen = 0.1f))

        // now target changed to C for new drag that started before previous drag settled to Idle
        val dragController2 = onDragStartedImmediately()
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.3f)
    }

    @Test
    fun startGestureDuringAnimatingOffset_shouldImmediatelyStopTheAnimation() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStopped(velocity = velocityThreshold)
        runCurrent()

        assertTransition(currentScene = SceneC)
        assertThat(isUserInputOngoing).isFalse()

        // Start a new gesture while the offset is animating
        onDragStartedImmediately()
        assertThat(isUserInputOngoing).isTrue()
    }

    @Test
    fun onInitialPreScroll_EdgeWithOverscroll_doNotChangeState() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.onPreScroll(
            available = downOffset(fractionOfScreen = 0.1f),
            source = NestedScrollSource.Drag
        )
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
                available = downOffset(fractionOfScreen = 0.1f),
                source = NestedScrollSource.Drag
            )

        assertTransition(currentScene = SceneA)
        assertThat(progress).isEqualTo(0.1f)
        assertThat(consumed).isEqualTo(downOffset(fractionOfScreen = 0.1f))
    }

    @Test
    fun afterSceneTransitionIsStarted_interceptPreScrollEvents() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        assertThat(progress).isEqualTo(0.1f)

        // start intercept preScroll
        val consumed =
            nestedScroll.onPreScroll(
                available = downOffset(fractionOfScreen = 0.1f),
                source = NestedScrollSource.Drag
            )
        assertThat(progress).isEqualTo(0.2f)

        // do nothing on postScroll
        nestedScroll.onPostScroll(
            consumed = consumed,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
        assertThat(progress).isEqualTo(0.2f)

        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        assertThat(progress).isEqualTo(0.3f)
        assertTransition(currentScene = SceneA)
    }

    private fun TestGestureScope.preScrollAfterSceneTransition(
        firstScroll: Float,
        secondScroll: Float
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        // start scene transition
        nestedScroll.scroll(available = Offset(0f, firstScroll))

        // stop scene transition (start the "stop animation")
        nestedScroll.preFling(available = Velocity.Zero)

        // a pre scroll event, that could be intercepted by DraggableHandlerImpl
        nestedScroll.onPreScroll(
            available = Offset(0f, secondScroll),
            source = NestedScrollSource.Drag
        )
    }

    @Test
    fun scrollAndFling_scrollLessThanInterceptable_goToIdleOnCurrentScene() = runGestureTest {
        val firstScroll = (transitionInterceptionThreshold - 0.0001f) * SCREEN_SIZE
        val secondScroll = 1f

        preScrollAfterSceneTransition(firstScroll = firstScroll, secondScroll = secondScroll)

        assertIdle(SceneA)
    }

    @Test
    fun scrollAndFling_scrollMinInterceptable_interceptPreScrollEvents() = runGestureTest {
        val firstScroll = (transitionInterceptionThreshold + 0.0001f) * SCREEN_SIZE
        val secondScroll = 1f

        preScrollAfterSceneTransition(firstScroll = firstScroll, secondScroll = secondScroll)

        assertTransition(progress = (firstScroll + secondScroll) / SCREEN_SIZE)
    }

    @Test
    fun scrollAndFling_scrollMaxInterceptable_interceptPreScrollEvents() = runGestureTest {
        val firstScroll = -(1f - transitionInterceptionThreshold - 0.0001f) * SCREEN_SIZE
        val secondScroll = -1f

        preScrollAfterSceneTransition(firstScroll = firstScroll, secondScroll = secondScroll)

        assertTransition(progress = -(firstScroll + secondScroll) / SCREEN_SIZE)
    }

    @Test
    fun scrollAndFling_scrollMoreThanInterceptable_goToIdleOnNextScene() = runGestureTest {
        val firstScroll = -(1f - transitionInterceptionThreshold + 0.0001f) * SCREEN_SIZE
        val secondScroll = -0.01f

        preScrollAfterSceneTransition(firstScroll = firstScroll, secondScroll = secondScroll)

        advanceUntilIdle()
        assertIdle(SceneB)
    }

    @Test
    fun onPreFling_velocityLowerThanThreshold_remainSameScene() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        nestedScroll.preFling(available = Velocity.Zero)
        assertTransition(currentScene = SceneA)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneA)
    }

    private fun TestGestureScope.flingAfterScroll(
        use: NestedScrollBehavior,
        idleAfterScroll: Boolean,
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = use)
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        if (idleAfterScroll) assertIdle(SceneA) else assertTransition(SceneA)

        nestedScroll.preFling(available = Velocity(0f, velocityThreshold))
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
    private fun TestGestureScope.flingAfterScrollStartedInScene(
        use: NestedScrollBehavior,
        idleAfterScroll: Boolean,
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = use)
        // scroll consumed in child
        nestedScroll.scroll(
            available = downOffset(fractionOfScreen = 0.1f),
            consumedByScroll = downOffset(fractionOfScreen = 0.1f)
        )

        // scroll offsetY10 is all available for parents
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        if (idleAfterScroll) assertIdle(SceneA) else assertTransition(SceneA)

        nestedScroll.preFling(available = Velocity(0f, velocityThreshold))
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
    fun flingAfterScrollStartedByExternalOverscrollGesture() = runGestureTest {
        val nestedScroll =
            nestedScrollConnection(
                nestedScrollBehavior = EdgeWithPreview,
                isExternalOverscrollGesture = true
            )

        // scroll not consumed in child
        nestedScroll.scroll(
            available = downOffset(fractionOfScreen = 0.1f),
        )

        // scroll offsetY10 is all available for parents
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        assertTransition(SceneA)

        nestedScroll.preFling(available = Velocity(0f, velocityThreshold))
    }

    @Test
    fun beforeNestedScrollStart_stop_shouldBeIgnored() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        nestedScroll.preFling(available = Velocity(0f, velocityThreshold))
        assertIdle(currentScene = SceneA)
    }

    @Test
    fun startNestedScrollWhileDragging() = runGestureTest {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeAlways)

        val offsetY10 = downOffset(fractionOfScreen = 0.1f)

        // Start a drag and then stop it, given that
        val dragController = onDragStarted(overSlop = up(0.1f))

        assertTransition(currentScene = SceneA)
        assertThat(progress).isEqualTo(0.1f)

        // now we can intercept the scroll events
        nestedScroll.scroll(available = -offsetY10)
        assertThat(progress).isEqualTo(0.2f)

        // this should be ignored, we are scrolling now!
        dragController.onDragStopped(-velocityThreshold)
        assertTransition(currentScene = SceneA)

        nestedScroll.scroll(available = -offsetY10)
        assertThat(progress).isEqualTo(0.3f)

        nestedScroll.scroll(available = -offsetY10)
        assertThat(progress).isEqualTo(0.4f)

        nestedScroll.preFling(available = Velocity(0f, -velocityThreshold))
        assertTransition(currentScene = SceneB)

        // wait for the stop animation
        advanceUntilIdle()
        assertIdle(currentScene = SceneB)
    }

    @Test
    fun interceptTransition() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()

        // Swipe up from the middle to transition to scene B.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        onDragStarted(startedPosition = middle, overSlop = up(0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.1f,
            isUserInputOngoing = true,
        )

        val firstTransition = transitionState

        // During the current gesture, start a new gesture, still in the middle of the screen. We
        // should intercept it. Because it is intercepted, the overSlop passed to onDragStarted()
        // should be 0f.
        assertThat(draggableHandler.shouldImmediatelyIntercept(middle)).isTrue()
        onDragStartedImmediately(startedPosition = middle)

        // We should have intercepted the transition, so the transition should be the same object.
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.1f,
            isUserInputOngoing = true,
        )
        // We should have a new transition
        assertThat(transitionState).isNotSameInstanceAs(firstTransition)

        // Start a new gesture from the bottom of the screen. Because swiping up from the bottom of
        // C leads to scene A (and not B), the previous transitions is *not* intercepted and we
        // instead animate from C to A.
        val bottom = Offset(SCREEN_SIZE / 2, SCREEN_SIZE)
        assertThat(draggableHandler.shouldImmediatelyIntercept(bottom)).isFalse()
        onDragStarted(startedPosition = bottom, overSlop = up(0.1f))

        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneA,
            isUserInputOngoing = true,
        )
        assertThat(transitionState).isNotSameInstanceAs(firstTransition)
    }

    @Test
    fun finish() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()

        // Swipe up from the middle to transition to scene B.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        onDragStarted(startedPosition = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneC, toScene = SceneB, isUserInputOngoing = true)

        // The current transition can be intercepted.
        assertThat(draggableHandler.shouldImmediatelyIntercept(middle)).isTrue()

        // Finish the transition.
        val transition = transitionState as Transition
        val job = transition.finish()
        assertTransition(isUserInputOngoing = false)

        // The current transition can not be intercepted anymore.
        assertThat(draggableHandler.shouldImmediatelyIntercept(middle)).isFalse()

        // Calling finish() multiple times returns the same Job.
        assertThat(transition.finish()).isSameInstanceAs(job)
        assertThat(transition.finish()).isSameInstanceAs(job)
        assertThat(transition.finish()).isSameInstanceAs(job)

        // We can join the job to wait for the animation to end.
        assertTransition()
        job.join()
        assertIdle(SceneC)
    }

    @Test
    fun finish_cancelled() = runGestureTest {
        // Swipe up from the middle to transition to scene B.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        onDragStarted(startedPosition = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB)

        // Finish the transition and cancel the returned job.
        (transitionState as Transition).finish().cancelAndJoin()
        assertIdle(SceneA)
    }

    @Test
    fun blockTransition() = runGestureTest {
        assertIdle(SceneA)

        // Swipe up to scene B.
        val dragController = onDragStarted(overSlop = up(0.1f))
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB)

        // Block the transition when the user release their finger.
        canChangeScene = { false }
        dragController.onDragStopped(velocity = -velocityThreshold)
        advanceUntilIdle()
        assertIdle(SceneA)
    }

    @Test
    fun blockInterceptedTransition() = runGestureTest {
        assertIdle(SceneA)

        // Swipe up to B.
        val dragController1 = onDragStarted(overSlop = up(0.1f))
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB)
        dragController1.onDragStopped(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        // Intercept the transition and swipe down back to scene A.
        assertThat(draggableHandler.shouldImmediatelyIntercept(startedPosition = null)).isTrue()
        val dragController2 = onDragStartedImmediately()

        // Block the transition when the user release their finger.
        canChangeScene = { false }
        dragController2.onDragStopped(velocity = velocityThreshold)

        advanceUntilIdle()
        assertIdle(SceneB)
    }

    @Test
    fun scrollFromIdleWithNoTargetScene_shouldUseOverscrollSpecIfAvailable() = runGestureTest {
        layoutState.transitions = transitions {
            overscroll(SceneC, Orientation.Vertical) { fade(TestElements.Foo) }
        }
        // Start at scene C.
        navigateToSceneC()

        val scene = layoutState.transitionState.currentScene
        // We should have overscroll spec for scene C
        assertThat(layoutState.transitions.overscrollSpec(scene, Orientation.Vertical)).isNotNull()
        assertThat(layoutState.currentTransition?.currentOverscrollSpec).isNull()

        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeAlways)
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))

        // We scrolled down, under scene C there is nothing, so we can use the overscroll spec
        assertThat(layoutState.currentTransition?.currentOverscrollSpec).isNotNull()
        assertThat(layoutState.currentTransition?.currentOverscrollSpec?.scene).isEqualTo(SceneC)
        val transition = layoutState.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(-0.1f)
    }

    @Test
    fun transitionIsImmediatelyUpdatedWhenReleasingFinger() = runGestureTest {
        // Swipe up from the middle to transition to scene B.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, isUserInputOngoing = true)

        dragController.onDragStopped(velocity = 0f)
        assertTransition(isUserInputOngoing = false)
    }

    @Test
    fun emptyOverscrollImmediatelyAbortsSettleAnimationWhenOverProgress() = runGestureTest {
        // Overscrolling on scene B does nothing.
        layoutState.transitions = transitions { overscroll(SceneB, Orientation.Vertical) {} }

        // Swipe up to scene B at progress = 200%.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = up(2f))
        val transition = assertTransition(fromScene = SceneA, toScene = SceneB, progress = 2f)

        // Release the finger.
        dragController.onDragStopped(velocity = -velocityThreshold)

        // Exhaust all coroutines *without advancing the clock*. Given that we are at progress >=
        // 100% and that the overscroll on scene B is doing nothing, we are already idle.
        runCurrent()
        assertIdle(SceneB)

        // Progress is snapped to 100%.
        assertThat(transition).hasProgress(1f)
    }

    @Test
    fun overscroll_releaseBetween0And100Percent_up() = runGestureTest {
        // Make scene B overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneB) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
            overscroll(SceneB, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)

        val dragController = onDragStarted(startedPosition = middle, overSlop = up(0.5f))
        val transition = assertThat(transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0.5f)

        // Release to B.
        dragController.onDragStopped(velocity = -velocityThreshold)
        advanceUntilIdle()

        // We didn't overscroll at the end of the transition.
        assertIdle(SceneB)
        assertThat(transition).hasProgress(1f)
        assertThat(transition).hasNoOverscrollSpec()
    }

    @Test
    fun overscroll_releaseBetween0And100Percent_down() = runGestureTest {
        // Make scene C overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneC) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
            overscroll(SceneC, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)

        val dragController = onDragStarted(startedPosition = middle, overSlop = down(0.5f))
        val transition = assertThat(transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(0.5f)

        // Release to C.
        dragController.onDragStopped(velocity = velocityThreshold)
        advanceUntilIdle()

        // We didn't overscroll at the end of the transition.
        assertIdle(SceneC)
        assertThat(transition).hasProgress(1f)
        assertThat(transition).hasNoOverscrollSpec()
    }

    @Test
    fun overscroll_releaseAt150Percent_up() = runGestureTest {
        // Make scene B overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneB) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
            overscroll(SceneB, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)

        val dragController = onDragStarted(startedPosition = middle, overSlop = up(1.5f))
        val transition = assertThat(transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(1.5f)

        // Release to B.
        dragController.onDragStopped(velocity = 0f)
        advanceUntilIdle()

        // We kept the overscroll at 100% so that the placement logic didn't change at the end of
        // the animation.
        assertIdle(SceneB)
        assertThat(transition).hasProgress(1f)
        assertThat(transition).hasOverscrollSpec()
    }

    @Test
    fun overscroll_releaseAt150Percent_down() = runGestureTest {
        // Make scene C overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneC) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
            overscroll(SceneC, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)

        val dragController = onDragStarted(startedPosition = middle, overSlop = down(1.5f))
        val transition = assertThat(transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(1.5f)

        // Release to C.
        dragController.onDragStopped(velocity = 0f)
        advanceUntilIdle()

        // We kept the overscroll at 100% so that the placement logic didn't change at the end of
        // the animation.
        assertIdle(SceneC)
        assertThat(transition).hasProgress(1f)
        assertThat(transition).hasOverscrollSpec()
    }

    @Test
    fun overscroll_releaseAtNegativePercent_up() = runGestureTest {
        // Make Scene A overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneB) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
            overscroll(SceneA, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        mutableUserActionsA.clear()
        mutableUserActionsA[Swipe.Up] = UserActionResult(SceneB)

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = down(1f))
        val transition = assertThat(transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(-1f)

        // Release to A.
        dragController.onDragStopped(velocity = 0f)
        advanceUntilIdle()

        // We kept the overscroll at 100% so that the placement logic didn't change at the end of
        // the animation.
        assertIdle(SceneA)
        assertThat(transition).hasProgress(0f)
        assertThat(transition).hasOverscrollSpec()
    }

    @Test
    fun overscroll_releaseAtNegativePercent_down() = runGestureTest {
        // Make Scene A overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneC) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
            overscroll(SceneA, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        mutableUserActionsA.clear()
        mutableUserActionsA[Swipe.Down] = UserActionResult(SceneC)

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = up(1f))
        val transition = assertThat(transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(-1f)

        // Release to A.
        dragController.onDragStopped(velocity = 0f)
        advanceUntilIdle()

        // We kept the overscroll at 100% so that the placement logic didn't change at the end of
        // the animation.
        assertIdle(SceneA)
        assertThat(transition).hasProgress(0f)
        assertThat(transition).hasOverscrollSpec()
    }

    @Test
    fun interceptingTransitionKeepsDistance() = runGestureTest {
        var swipeDistance = 75f
        layoutState.transitions = transitions {
            from(SceneA, to = SceneB) { distance = UserActionDistance { _, _ -> swipeDistance } }
        }

        // Start transition.
        val controller = onDragStarted(overSlop = -50f)
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 50f / 75f)

        // Intercept the transition and change the swipe distance. The original distance and
        // progress should be the same.
        swipeDistance = 50f
        controller.onDragStopped(0f)
        onDragStartedImmediately()
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 50f / 75f)
    }

    @Test
    fun requireFullDistanceSwipe() = runGestureTest {
        mutableUserActionsA[Swipe.Up] = UserActionResult(SceneB, requiresFullDistanceSwipe = true)

        val controller = onDragStarted(overSlop = up(fractionOfScreen = 0.9f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.9f)

        controller.onDragStopped(velocity = 0f)
        advanceUntilIdle()
        assertIdle(SceneA)

        val otherController = onDragStarted(overSlop = up(fractionOfScreen = 1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)
        otherController.onDragStopped(velocity = 0f)
        advanceUntilIdle()
        assertIdle(SceneB)
    }

    @Test
    fun interceptingTransitionReplacesCurrentTransition() = runGestureTest {
        val controller = onDragStarted(overSlop = up(fractionOfScreen = 0.5f))
        val transition = assertThat(layoutState.transitionState).isTransition()
        controller.onDragStopped(velocity = 0f)

        // Intercept the transition.
        onDragStartedImmediately()
        val newTransition = assertThat(layoutState.transitionState).isTransition()
        assertThat(newTransition).isNotSameInstanceAs(transition)
        assertThat(newTransition.replacedTransition).isSameInstanceAs(transition)
    }
}
