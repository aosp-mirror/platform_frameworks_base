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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeAlways
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeNoPreview
import com.android.compose.animation.scene.NestedScrollBehavior.EdgeWithPreview
import com.android.compose.animation.scene.TestOverlays.OverlayA
import com.android.compose.animation.scene.TestOverlays.OverlayB
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.Transition
import com.android.compose.animation.scene.subjects.assertThat
import com.android.compose.test.MonotonicClockTestScope
import com.android.compose.test.runMonotonicClockTest
import com.android.compose.test.transition
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

private const val SCREEN_SIZE = 100f
private val LAYOUT_SIZE = IntSize(SCREEN_SIZE.toInt(), SCREEN_SIZE.toInt())

@RunWith(AndroidJUnit4::class)
class DraggableHandlerTest {
    private class TestGestureScope(val testScope: MonotonicClockTestScope) {
        var canChangeScene: (SceneKey) -> Boolean = { true }
        val layoutState =
            MutableSceneTransitionLayoutStateImpl(
                SceneA,
                EmptyTestTransitions,
                canChangeScene = { canChangeScene(it) },
            )

        var layoutDirection = LayoutDirection.Rtl
            set(value) {
                field = value
                layoutImpl.updateContents(scenesBuilder, layoutDirection)
            }

        var mutableUserActionsA = mapOf(Swipe.Up to SceneB, Swipe.Down to SceneC)
            set(value) {
                field = value
                layoutImpl.updateContents(scenesBuilder, layoutDirection)
            }

        var mutableUserActionsB = mapOf(Swipe.Up to SceneC, Swipe.Down to SceneA)
            set(value) {
                field = value
                layoutImpl.updateContents(scenesBuilder, layoutDirection)
            }

        private val scenesBuilder: SceneTransitionLayoutScope.() -> Unit = {
            scene(key = SceneA, userActions = mutableUserActionsA) { Text("SceneA") }
            scene(key = SceneB, userActions = mutableUserActionsB) { Text("SceneB") }
            scene(
                key = SceneC,
                userActions =
                    mapOf(
                        Swipe.Up to SceneB,
                        Swipe(SwipeDirection.Up, fromSource = Edge.Bottom) to SceneA,
                    ),
            ) {
                Text("SceneC")
            }
            overlay(
                key = OverlayA,
                userActions =
                    mapOf(
                        Swipe.Up to UserActionResult.HideOverlay(OverlayA),
                        Swipe.Down to UserActionResult.ReplaceByOverlay(OverlayB),
                    ),
            ) {
                Text("OverlayA")
            }
            overlay(key = OverlayB) { Text("OverlayB") }
        }

        val transitionInterceptionThreshold = 0.05f

        private val layoutImpl =
            SceneTransitionLayoutImpl(
                    state = layoutState,
                    density = Density(1f),
                    layoutDirection = LayoutDirection.Ltr,
                    swipeSourceDetector = DefaultEdgeDetector,
                    transitionInterceptionThreshold = transitionInterceptionThreshold,
                    builder = scenesBuilder,

                    // Use testScope and not backgroundScope here because backgroundScope does not
                    // work well with advanceUntilIdle(), which is used by some tests.
                    animationScope = testScope,
                )
                .apply { setContentsAndLayoutTargetSizeForTest(LAYOUT_SIZE) }

        val draggableHandler = layoutImpl.draggableHandler(Orientation.Vertical)
        val horizontalDraggableHandler = layoutImpl.draggableHandler(Orientation.Horizontal)

        var pointerInfoOwner: () -> PointersInfo = {
            PointersInfo(startedPosition = Offset.Zero, pointersDown = 1, isMouseWheel = false)
        }

        fun nestedScrollConnection(
            nestedScrollBehavior: NestedScrollBehavior,
            isExternalOverscrollGesture: Boolean = false,
        ) =
            NestedScrollHandlerImpl(
                    layoutImpl = layoutImpl,
                    orientation = draggableHandler.orientation,
                    topOrLeftBehavior = nestedScrollBehavior,
                    bottomOrRightBehavior = nestedScrollBehavior,
                    isExternalOverscrollGesture = { isExternalOverscrollGesture },
                    pointersInfoOwner = { pointerInfoOwner() },
                )
                .connection

        val velocityThreshold = draggableHandler.velocityThreshold

        fun down(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) error("use up()") else SCREEN_SIZE * fractionOfScreen

        fun up(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) error("use down()") else -down(fractionOfScreen)

        fun downOffset(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) {
                error("use upOffset()")
            } else {
                Offset(x = 0f, y = down(fractionOfScreen))
            }

        fun upOffset(fractionOfScreen: Float) =
            if (fractionOfScreen < 0f) {
                error("use downOffset()")
            } else {
                Offset(x = 0f, y = up(fractionOfScreen))
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
            previewProgress: Float? = null,
            isInPreviewStage: Boolean? = null,
            isUserInputOngoing: Boolean? = null,
        ): Transition {
            val transition = assertThat(transitionState).isSceneTransition()
            currentScene?.let { assertThat(transition).hasCurrentScene(it) }
            fromScene?.let { assertThat(transition).hasFromScene(it) }
            toScene?.let { assertThat(transition).hasToScene(it) }
            progress?.let { assertThat(transition).hasProgress(it) }
            previewProgress?.let { assertThat(transition).hasPreviewProgress(it) }
            isInPreviewStage?.let {
                assertThat(transition).run { if (it) isInPreviewStage() else isNotInPreviewStage() }
            }
            isUserInputOngoing?.let { assertThat(transition).hasIsUserInputOngoing(it) }
            return transition
        }

        fun onDragStarted(
            startedPosition: Offset = Offset.Zero,
            overSlop: Float,
            pointersDown: Int = 1,
            expectedConsumedOverSlop: Float = overSlop,
        ): DragController {
            // overSlop should be 0f only if the drag gesture starts with startDragImmediately
            if (overSlop == 0f) error("Consider using onDragStartedImmediately()")
            return onDragStarted(
                draggableHandler = draggableHandler,
                startedPosition = startedPosition,
                overSlop = overSlop,
                pointersDown = pointersDown,
                expectedConsumedOverSlop = expectedConsumedOverSlop,
            )
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
            pointersDown: Int = 1,
            expectedConsumedOverSlop: Float = overSlop,
        ): DragController {
            val dragController =
                draggableHandler.onDragStarted(
                    startedPosition = startedPosition,
                    overSlop = overSlop,
                    pointersDown = pointersDown,
                )

            // MultiPointerDraggable will always call onDelta with the initial overSlop right after
            dragController.onDragDelta(pixels = overSlop, expectedConsumedOverSlop)

            return dragController
        }

        fun DragController.onDragDelta(pixels: Float, expectedConsumed: Float = pixels) {
            val consumed = onDrag(delta = pixels)
            assertThat(consumed).isEqualTo(expectedConsumed)
        }

        suspend fun DragController.onDragStoppedAnimateNow(
            velocity: Float,
            canChangeScene: Boolean = true,
            onAnimationStart: () -> Unit,
            onAnimationEnd: (Float) -> Unit,
        ) {
            val velocityConsumed = onDragStoppedAnimateLater(velocity, canChangeScene)
            onAnimationStart()
            onAnimationEnd(velocityConsumed.invoke())
        }

        suspend fun DragController.onDragStoppedAnimateNow(
            velocity: Float,
            canChangeScene: Boolean = true,
            onAnimationStart: () -> Unit,
            expectedConsumedVelocity: Float,
        ) =
            onDragStoppedAnimateNow(
                velocity = velocity,
                canChangeScene = canChangeScene,
                onAnimationStart = onAnimationStart,
                onAnimationEnd = { assertThat(it).isEqualTo(expectedConsumedVelocity) },
            )

        fun DragController.onDragStoppedAnimateLater(
            velocity: Float,
            canChangeScene: Boolean = true,
        ): SuspendedValue<Float> {
            return onStop(velocity, canChangeScene)
        }

        fun NestedScrollConnection.scroll(
            available: Offset,
            consumedByScroll: Offset = Offset.Zero,
        ) {
            val consumedByPreScroll =
                onPreScroll(available = available, source = NestedScrollSource.Drag)
            val consumed = consumedByPreScroll + consumedByScroll

            onPostScroll(
                consumed = consumed,
                available = available - consumed,
                source = NestedScrollSource.Drag,
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

            try {
                // Run the test.
                testGestureScope.block()
            } finally {
                // Make sure we stop the last transition if it was not explicitly stopped, otherwise
                // tests will time out after 10s given that the transitions are now started on the
                // test scope. We don't use backgroundScope when starting the test transitions
                // because coroutines started on the background scope don't work well with
                // advanceUntilIdle(), which is used in a few tests.
                if (testGestureScope.draggableHandler.isDrivingTransition) {
                    (testGestureScope.layoutState.transitionState as Transition)
                        .freezeAndAnimateToCurrentState()
                }
            }
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

        dragController.onDragStoppedAnimateNow(
            velocity = velocityThreshold - 0.01f,
            onAnimationStart = { assertTransition(currentScene = SceneA) },
            expectedConsumedVelocity = velocityThreshold - 0.01f,
        )

        assertIdle(currentScene = SceneA)
    }

    @Test
    fun onDragStoppedAfterDrag_velocityLowerThanThreshold_remainSameScene_previewAnimated() =
        runGestureTest {
            layoutState.transitions = transitions {
                // set a preview for the transition
                from(SceneA, to = SceneC, preview = {}) {}
            }
            val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
            assertTransition(currentScene = SceneA)

            dragController.onDragStoppedAnimateNow(
                velocity = velocityThreshold - 0.01f,
                onAnimationStart = {
                    // verify that transition remains in preview stage and animates back to
                    // fromScene
                    assertTransition(
                        currentScene = SceneA,
                        isInPreviewStage = true,
                        previewProgress = 0.1f,
                        progress = 0f,
                    )
                },
                expectedConsumedVelocity = velocityThreshold - 0.01f,
            )

            assertIdle(currentScene = SceneA)
        }

    @Test
    fun onDragStoppedAfterDrag_velocityAtLeastThreshold_goToNextScene() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStoppedAnimateNow(
            velocity = velocityThreshold,
            onAnimationStart = { assertTransition(currentScene = SceneC) },
            expectedConsumedVelocity = velocityThreshold,
        )
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragStoppedAfterStarted_returnToIdle() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = { assertTransition(currentScene = SceneA) },
            expectedConsumedVelocity = 0f,
        )
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
            progress = 0.6f,
        )

        // Reverse direction such that A -> C now with 0.4
        dragController.onDragDelta(pixels = 100f)
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.4f,
        )

        // After the drag stopped scene C should be committed
        dragController.onDragStoppedAnimateNow(
            velocity = velocityThreshold,
            onAnimationStart = {
                assertTransition(currentScene = SceneC, fromScene = SceneA, toScene = SceneC)
            },
            expectedConsumedVelocity = velocityThreshold,
        )
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragStartedWithoutActionsInBothDirections_stayIdle() = runGestureTest {
        onDragStarted(
            horizontalDraggableHandler,
            overSlop = up(fractionOfScreen = 0.3f),
            expectedConsumedOverSlop = 0f,
        )
        assertIdle(currentScene = SceneA)

        onDragStarted(
            horizontalDraggableHandler,
            overSlop = down(fractionOfScreen = 0.3f),
            expectedConsumedOverSlop = 0f,
        )
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
            progress = -0.1f,
        )

        // Reverse drag direction, it will consume the previous drag
        dragController.onDragDelta(pixels = -10f)
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.0f,
        )

        // Continue reverse drag direction, it should record progress to Scene B
        dragController.onDragDelta(pixels = -10f)
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneB,
            progress = 0.1f,
        )
    }

    @Test
    fun onDragWithActionsInBothDirections_dragToOppositeDirectionReplacesAction() = runGestureTest {
        // We are on SceneA. UP -> B, DOWN-> C.
        val dragController = onDragStarted(overSlop = up(fractionOfScreen = 0.2f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.2f,
        )

        // Reverse drag direction, it will replace the previous transition
        dragController.onDragDelta(pixels = down(fractionOfScreen = 0.5f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.3f,
        )
    }

    @Test
    fun onDragWithActionsInBothDirections_dragToOppositeDirectionNotReplaceable() = runGestureTest {
        // We are on SceneA. UP -> B, DOWN-> C. The up swipe is not replaceable though.
        mutableUserActionsA =
            mapOf(Swipe.Up to UserActionResult(SceneB, isIrreversible = true), Swipe.Down to SceneC)
        val dragController =
            onDragStarted(
                startedPosition = Offset(SCREEN_SIZE * 0.5f, SCREEN_SIZE * 0.5f),
                overSlop = up(fractionOfScreen = 0.2f),
            )
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.2f,
        )

        // Reverse drag direction, it cannot replace the previous transition
        dragController.onDragDelta(pixels = down(fractionOfScreen = 0.5f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = -0.3f,
        )
    }

    @Test
    fun onDragFromEdge_startTransitionToEdgeAction() = runGestureTest {
        navigateToSceneC()

        // Start dragging from the bottom
        onDragStarted(
            startedPosition = Offset(SCREEN_SIZE * 0.5f, SCREEN_SIZE),
            overSlop = up(fractionOfScreen = 0.1f),
        )
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            toScene = SceneA,
            progress = 0.1f,
        )
    }

    @Test
    fun onDragToExactlyZero_toSceneIsSet() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.3f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.3f,
        )
        dragController.onDragDelta(pixels = up(fractionOfScreen = 0.3f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneC,
            progress = 0.0f,
        )
    }

    private suspend fun TestGestureScope.navigateToSceneC() {
        assertIdle(currentScene = SceneA)
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 1f))
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneC)
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(currentScene = SceneC, fromScene = SceneA, toScene = SceneC)
            },
            expectedConsumedVelocity = 0f,
        )
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onAcceleratedScroll_scrollToThirdScene() = runGestureTest {
        // Drag A -> B with progress 0.2
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.2f))
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.2f,
        )

        // Start animation A -> B with progress 0.2 -> 1.0
        dragController1.onDragStoppedAnimateLater(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        // While at A -> B do a 100% screen drag (progress 1.2). This should go past B and change
        // the transition to B -> C with progress 0.2
        val dragController2 = onDragStartedImmediately()
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 1f))
        assertTransition(
            currentScene = SceneB,
            fromScene = SceneB,
            toScene = SceneC,
            progress = 0.2f,
        )

        // After the drag stopped scene C should be committed
        dragController2.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = {
                assertTransition(currentScene = SceneC, fromScene = SceneB, toScene = SceneC)
            },
            expectedConsumedVelocity = -velocityThreshold,
        )
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onAcceleratedScrollBothTargetsBecomeNull_settlesToIdle() = runGestureTest {
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.2f))
        dragController1.onDragDelta(pixels = up(fractionOfScreen = 0.2f))
        dragController1.onDragStoppedAnimateLater(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        mutableUserActionsA = emptyMap()
        mutableUserActionsB = emptyMap()

        // start accelaratedScroll and scroll over to B -> null
        val dragController2 = onDragStartedImmediately()
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f), expectedConsumed = 0f)
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f), expectedConsumed = 0f)

        // here onDragStopped is already triggered, but subsequent onDelta/onDragStopped calls may
        // still be called. Make sure that they don't crash or change the scene
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f), expectedConsumed = 0f)
        dragController2.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)
            },
            expectedConsumedVelocity = 0f,
        )

        advanceUntilIdle()
        assertIdle(SceneB)

        // These events can still come in after the animation has settled
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.5f), expectedConsumed = 0f)
        dragController2.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = { assertIdle(SceneB) },
            expectedConsumedVelocity = 0f,
        )
        assertIdle(SceneB)
    }

    @Test
    fun onDragTargetsChanged_targetStaysTheSame() = runGestureTest {
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.1f)

        mutableUserActionsA += Swipe.Up to UserActionResult(SceneC)
        dragController1.onDragDelta(pixels = up(fractionOfScreen = 0.1f))
        // target stays B even though UserActions changed
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.2f)

        dragController1.onDragStoppedAnimateNow(
            velocity = down(fractionOfScreen = 0.1f),
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.2f)
            },
            expectedConsumedVelocity = down(fractionOfScreen = 0.1f),
        )
        assertIdle(SceneA)

        // now target changed to C for new drag
        onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.1f)
    }

    @Test
    fun onDragTargetsChanged_targetsChangeWhenStartingNewDrag() = runGestureTest {
        val dragController1 = onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.1f)

        mutableUserActionsA += Swipe.Up to UserActionResult(SceneC)
        dragController1.onDragDelta(pixels = up(fractionOfScreen = 0.1f))
        dragController1.onDragStoppedAnimateLater(velocity = down(fractionOfScreen = 0.1f))

        // now target changed to C for new drag that started before previous drag settled to Idle
        val dragController2 = onDragStartedImmediately()
        dragController2.onDragDelta(pixels = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.3f)
    }

    @Test
    fun startGestureDuringAnimatingOffset_shouldImmediatelyStopTheAnimation() = runGestureTest {
        val dragController = onDragStarted(overSlop = down(fractionOfScreen = 0.1f))
        assertTransition(currentScene = SceneA)

        dragController.onDragStoppedAnimateLater(velocity = velocityThreshold)
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
            source = NestedScrollSource.Drag,
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
                source = NestedScrollSource.Drag,
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
                source = NestedScrollSource.Drag,
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
                source = NestedScrollSource.Drag,
            )
        assertThat(progress).isEqualTo(0.2f)

        // do nothing on postScroll
        nestedScroll.onPostScroll(
            consumed = consumed,
            available = Offset.Zero,
            source = NestedScrollSource.Drag,
        )
        assertThat(progress).isEqualTo(0.2f)

        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        assertThat(progress).isEqualTo(0.3f)
        assertTransition(currentScene = SceneA)
    }

    private fun TestGestureScope.preScrollAfterSceneTransition(
        firstScroll: Float,
        secondScroll: Float,
    ) {
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeWithPreview)
        // start scene transition
        nestedScroll.scroll(available = Offset(0f, firstScroll))

        // stop scene transition (start the "stop animation")
        nestedScroll.preFling(available = Velocity.Zero)

        // a pre scroll event, that could be intercepted by DraggableHandlerImpl
        nestedScroll.onPreScroll(
            available = Offset(0f, secondScroll),
            source = NestedScrollSource.Drag,
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
            consumedByScroll = downOffset(fractionOfScreen = 0.1f),
        )

        // scroll offsetY10 is all available for parents
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))
        if (idleAfterScroll) assertIdle(SceneA) else assertTransition(SceneA)

        nestedScroll.preFling(available = Velocity(0f, velocityThreshold))
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
                isExternalOverscrollGesture = true,
            )

        // scroll not consumed in child
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.1f))

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
        dragController.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = { assertTransition(currentScene = SceneA) },
            expectedConsumedVelocity = 0f,
        )
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
    fun freezeAndAnimateToCurrentState() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()

        // Swipe up from the middle to transition to scene B.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        onDragStarted(startedPosition = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneC, toScene = SceneB, isUserInputOngoing = true)

        // The current transition can be intercepted.
        assertThat(draggableHandler.shouldImmediatelyIntercept(middle)).isTrue()

        // Freeze the transition.
        val transition = transitionState as Transition
        transition.freezeAndAnimateToCurrentState()
        assertTransition(isUserInputOngoing = false)
        advanceUntilIdle()
        assertIdle(SceneC)
    }

    @Test
    fun interruptedTransitionCanNotBeImmediatelyIntercepted() = runGestureTest {
        assertThat(draggableHandler.shouldImmediatelyIntercept(startedPosition = null)).isFalse()
        onDragStarted(overSlop = up(0.1f))
        assertThat(draggableHandler.shouldImmediatelyIntercept(startedPosition = null)).isTrue()

        layoutState.startTransitionImmediately(
            animationScope = testScope.backgroundScope,
            transition(SceneA, SceneB),
        )
        assertThat(draggableHandler.shouldImmediatelyIntercept(startedPosition = null)).isFalse()
    }

    @Test
    fun blockTransition() = runGestureTest {
        assertIdle(SceneA)

        // Swipe up to scene B.
        val dragController = onDragStarted(overSlop = up(0.1f))
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB)

        // Block the transition when the user release their finger.
        canChangeScene = { false }
        dragController.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = { assertTransition(fromScene = SceneA, toScene = SceneB) },
            expectedConsumedVelocity = -velocityThreshold,
        )
        assertIdle(SceneA)
    }

    @Test
    fun blockInterceptedTransition() = runGestureTest {
        assertIdle(SceneA)

        // Swipe up to B.
        val dragController1 = onDragStarted(overSlop = up(0.1f))
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB)
        dragController1.onDragStoppedAnimateLater(velocity = -velocityThreshold)
        assertTransition(currentScene = SceneB, fromScene = SceneA, toScene = SceneB)

        // Intercept the transition and swipe down back to scene A.
        assertThat(draggableHandler.shouldImmediatelyIntercept(startedPosition = null)).isTrue()
        val dragController2 = onDragStartedImmediately()

        // Block the transition when the user release their finger.
        canChangeScene = { false }
        dragController2.onDragStoppedAnimateNow(
            velocity = velocityThreshold,
            onAnimationStart = { assertTransition(fromScene = SceneA, toScene = SceneB) },
            expectedConsumedVelocity = velocityThreshold,
        )
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
        assertThat(layoutState.currentTransition?.currentOverscrollSpec?.content).isEqualTo(SceneC)
        val transition = layoutState.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(-0.1f)
    }

    @Test
    fun nestedScrollUseFromSourceInfo() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeAlways)

        // Drag from the **top** of the screen
        pointerInfoOwner = {
            PointersInfo(startedPosition = Offset(0f, 0f), pointersDown = 1, isMouseWheel = false)
        }
        assertIdle(currentScene = SceneC)

        nestedScroll.scroll(available = upOffset(fractionOfScreen = 0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            // userAction: Swipe.Up to SceneB
            toScene = SceneB,
            progress = 0.1f,
        )

        // Reset to SceneC
        nestedScroll.preFling(Velocity.Zero)
        advanceUntilIdle()

        // Drag from the **bottom** of the screen
        pointerInfoOwner = {
            PointersInfo(
                startedPosition = Offset(0f, SCREEN_SIZE),
                pointersDown = 1,
                isMouseWheel = false,
            )
        }
        assertIdle(currentScene = SceneC)

        nestedScroll.scroll(available = upOffset(fractionOfScreen = 0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            // userAction: Swipe(SwipeDirection.Up, fromSource = Edge.Bottom) to SceneA
            toScene = SceneA,
            progress = 0.1f,
        )
    }

    @Test
    fun ignoreMouseWheel() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()
        val nestedScroll = nestedScrollConnection(nestedScrollBehavior = EdgeAlways)

        // Use mouse wheel
        pointerInfoOwner = {
            PointersInfo(startedPosition = Offset(0f, 0f), pointersDown = 1, isMouseWheel = true)
        }
        assertIdle(currentScene = SceneC)

        nestedScroll.scroll(available = upOffset(fractionOfScreen = 0.1f))
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun transitionIsImmediatelyUpdatedWhenReleasingFinger() = runGestureTest {
        // Swipe up from the middle to transition to scene B.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, isUserInputOngoing = true)

        dragController.onDragStoppedAnimateLater(velocity = 0f)
        assertTransition(isUserInputOngoing = false)
    }

    @Test
    fun emptyOverscrollImmediatelyAbortsSettleAnimationWhenOverProgress() = runGestureTest {
        // Overscrolling on scene B does nothing.
        layoutState.transitions = transitions { overscrollDisabled(SceneB, Orientation.Vertical) }

        // Swipe up to scene B at progress = 200%.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController =
            onDragStarted(
                startedPosition = middle,
                overSlop = up(2f),
                // Overscroll is disabled, it will scroll up to 100%
                expectedConsumedOverSlop = up(1f),
            )

        // The progress value is coerced in `[0..1]`
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)

        // Release the finger.
        dragController.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = { assertTransition(fromScene = SceneA, toScene = SceneB) },
            expectedConsumedVelocity = 0f,
        )

        // Exhaust all coroutines *without advancing the clock*. Given that we are at progress >=
        // 100% and that the overscroll on scene B is doing nothing, we are already idle.
        runCurrent()
        assertIdle(SceneB)
    }

    @Test
    fun emptyOverscrollAbortsSettleAnimationAndExposeTheConsumedVelocity() = runGestureTest {
        // Overscrolling on scene B does nothing.
        layoutState.transitions = transitions { overscrollDisabled(SceneB, Orientation.Vertical) }

        // Swipe up to scene B at progress = 200%.
        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = up(0.99f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.99f)

        // Release the finger.
        dragController.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = { assertTransition(fromScene = SceneA, toScene = SceneB) },
            onAnimationEnd = { consumedVelocity ->
                // Our progress value was 0.99f and it is coerced in `[0..1]` (overscrollDisabled).
                // Some of the velocity will be used for animation, but not all of it.
                assertThat(consumedVelocity).isLessThan(0f)
                assertThat(consumedVelocity).isGreaterThan(-velocityThreshold)
            },
        )
    }

    @Test
    fun overscroll_releaseBetween0And100Percent_up() = runGestureTest {
        // Make scene B overscrollable.
        layoutState.transitions = transitions {
            defaultSwipeSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
            from(SceneA, to = SceneB) {}
            overscroll(SceneB, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)

        val dragController = onDragStarted(startedPosition = middle, overSlop = up(0.5f))
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0.5f)

        // Release to B.
        dragController.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.5f)
            },
            expectedConsumedVelocity = -velocityThreshold,
        )

        // We didn't overscroll at the end of the transition.
        assertIdle(SceneB)
        assertThat(transition).hasProgress(1f)
        assertThat(transition).hasNoOverscrollSpec()
    }

    @Test
    fun overscroll_releaseBetween0And100Percent_down() = runGestureTest {
        // Make scene C overscrollable.
        layoutState.transitions = transitions {
            defaultSwipeSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
            from(SceneA, to = SceneC) {}
            overscroll(SceneC, Orientation.Vertical) { fade(TestElements.Foo) }
        }

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)

        val dragController = onDragStarted(startedPosition = middle, overSlop = down(0.5f))
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(0.5f)

        // Release to C.
        dragController.onDragStoppedAnimateNow(
            velocity = velocityThreshold,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.5f)
            },
            expectedConsumedVelocity = velocityThreshold,
        )

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
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(1.5f)

        // Release to B.
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1.5f)
            },
            expectedConsumedVelocity = 0f,
        )

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
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(1.5f)

        // Release to C.
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneC, progress = 1.5f)
            },
            expectedConsumedVelocity = 0f,
        )

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

        mutableUserActionsA = mapOf(Swipe.Up to UserActionResult(SceneB))

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = down(1f))
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(-1f)

        // Release to A.
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = -1f)
            },
            expectedConsumedVelocity = 0f,
        )

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

        mutableUserActionsA = mapOf(Swipe.Down to UserActionResult(SceneC))

        val middle = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f)
        val dragController = onDragStarted(startedPosition = middle, overSlop = up(1f))
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(-1f)

        // Release to A.
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneC, progress = -1f)
            },
            expectedConsumedVelocity = 0f,
        )

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
        controller.onDragStoppedAnimateLater(0f)
        onDragStartedImmediately()
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 50f / 75f)
    }

    @Test
    fun requireFullDistanceSwipe() = runGestureTest {
        mutableUserActionsA +=
            Swipe.Up to UserActionResult(SceneB, requiresFullDistanceSwipe = true)

        val controller = onDragStarted(overSlop = up(fractionOfScreen = 0.9f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.9f)

        controller.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.9f)
            },
            expectedConsumedVelocity = 0f,
        )
        assertIdle(SceneA)

        val otherController = onDragStarted(overSlop = up(fractionOfScreen = 1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)
        otherController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)
            },
            expectedConsumedVelocity = 0f,
        )
        assertIdle(SceneB)
    }

    @Test
    fun interceptingTransitionReplacesCurrentTransition() = runGestureTest {
        val controller = onDragStarted(overSlop = up(fractionOfScreen = 0.5f))
        val transition = assertThat(layoutState.transitionState).isSceneTransition()
        controller.onDragStoppedAnimateLater(velocity = 0f)

        // Intercept the transition.
        onDragStartedImmediately()
        val newTransition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(newTransition).isNotSameInstanceAs(transition)
        assertThat(newTransition.replacedTransition).isSameInstanceAs(transition)
    }

    @Test
    fun showOverlay() = runGestureTest {
        mutableUserActionsA = mapOf(Swipe.Down to UserActionResult.ShowOverlay(OverlayA))

        // Initial state.
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(/* empty */ )

        // Swipe down to show overlay A.
        val controller = onDragStarted(overSlop = down(0.1f))
        val transition = assertThat(layoutState.transitionState).isShowOrHideOverlayTransition()
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasFromOrToScene(SceneA)
        assertThat(transition).hasOverlay(OverlayA)
        assertThat(transition).hasCurrentOverlays(/* empty, gesture not committed yet. */ )
        assertThat(transition).hasProgress(0.1f)

        // Commit the gesture. The overlay is instantly added in the set of current overlays.
        controller.onDragStoppedAnimateNow(
            velocity = velocityThreshold,
            onAnimationStart = { assertThat(transition).hasCurrentOverlays(OverlayA) },
            expectedConsumedVelocity = velocityThreshold,
        )
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayA)
    }

    @Test
    fun hideOverlay() = runGestureTest {
        layoutState.showOverlay(OverlayA, animationScope = testScope)
        advanceUntilIdle()

        // Initial state.
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayA)

        // Swipe up to hide overlay A.
        val controller = onDragStarted(overSlop = up(0.1f))
        val transition = assertThat(layoutState.transitionState).isShowOrHideOverlayTransition()
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasFromOrToScene(SceneA)
        assertThat(transition).hasOverlay(OverlayA)
        assertThat(transition).hasCurrentOverlays(OverlayA)
        assertThat(transition).hasProgress(0.1f)

        // Commit the gesture. The overlay is instantly removed from the set of current overlays.
        controller.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = { assertThat(transition).hasCurrentOverlays(/* empty */ ) },
            expectedConsumedVelocity = -velocityThreshold,
        )
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(/* empty */ )
    }

    @Test
    fun replaceOverlay() = runGestureTest {
        layoutState.showOverlay(OverlayA, animationScope = testScope)
        advanceUntilIdle()

        // Initial state.
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayA)

        // Swipe down to replace overlay A by overlay B.
        val controller = onDragStarted(overSlop = down(0.1f))
        val transition = assertThat(layoutState.transitionState).isReplaceOverlayTransition()
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasFromOverlay(OverlayA)
        assertThat(transition).hasToOverlay(OverlayB)
        assertThat(transition).hasCurrentOverlays(OverlayA)
        assertThat(transition).hasProgress(0.1f)

        // Commit the gesture. The overlays are instantly swapped in the set of current overlays.
        controller.onDragStoppedAnimateNow(
            velocity = velocityThreshold,
            onAnimationStart = { assertThat(transition).hasCurrentOverlays(OverlayB) },
            expectedConsumedVelocity = velocityThreshold,
        )
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayB)
    }
}
