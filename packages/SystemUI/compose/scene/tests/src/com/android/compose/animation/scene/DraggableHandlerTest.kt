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
import androidx.compose.foundation.overscroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource.Companion.UserInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

private const val SCREEN_SIZE = 100f
private val LAYOUT_SIZE = IntSize(SCREEN_SIZE.toInt(), SCREEN_SIZE.toInt())

private fun pointersDown(
    startedPosition: Offset = Offset.Zero,
    pointersDown: Int = 1,
    pointersDownByType: Map<PointerType, Int> = mapOf(PointerType.Touch to pointersDown),
): PointersInfo.PointersDown {
    return PointersInfo.PointersDown(
        startedPosition = startedPosition,
        count = pointersDown,
        countByType = pointersDownByType,
    )
}

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
                    mapOf(Swipe.Up to SceneB, Swipe.Up(fromSource = Edge.Bottom) to SceneA),
            ) {
                Text("SceneC", Modifier.overscroll(verticalOverscrollEffect))
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

        val draggableHandler = layoutImpl.verticalDraggableHandler
        val horizontalDraggableHandler = layoutImpl.horizontalDraggableHandler

        var pointerInfoOwner: () -> PointersInfo = { pointersDown() }

        fun nestedScrollConnection() =
            NestedScrollHandlerImpl(
                    draggableHandler = draggableHandler,
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
            pointersInfo: PointersInfo.PointersDown = pointersDown(),
            overSlop: Float,
            expectedConsumedOverSlop: Float = overSlop,
        ): DragController {
            // overSlop should be 0f only if the drag gesture starts with startDragImmediately
            if (overSlop == 0f) error("Consider using onDragStartedImmediately()")
            return onDragStarted(
                draggableHandler = draggableHandler,
                pointersInfo = pointersInfo,
                overSlop = overSlop,
                expectedConsumedOverSlop = expectedConsumedOverSlop,
            )
        }

        fun onDragStarted(
            draggableHandler: DraggableHandler,
            pointersInfo: PointersInfo.PointersDown = pointersDown(),
            overSlop: Float = 0f,
            expectedConsumedOverSlop: Float = overSlop,
        ): DragController {
            val dragController =
                draggableHandler.onDragStarted(pointersDown = pointersInfo, overSlop = overSlop)

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
            onAnimationEnd(velocityConsumed.await())
        }

        suspend fun DragController.onDragStoppedAnimateNow(
            velocity: Float,
            canChangeScene: Boolean = true,
            onAnimationStart: () -> Unit,
        ) =
            onDragStoppedAnimateNow(
                velocity = velocity,
                canChangeScene = canChangeScene,
                onAnimationStart = onAnimationStart,
                onAnimationEnd = {},
            )

        fun DragController.onDragStoppedAnimateLater(
            velocity: Float,
            canChangeScene: Boolean = true,
        ): Deferred<Float> {
            val velocityConsumed = testScope.async { onStop(velocity, canChangeScene) }
            testScope.testScheduler.runCurrent()
            return velocityConsumed
        }

        fun NestedScrollConnection.scroll(
            available: Offset,
            consumedByScroll: Offset = Offset.Zero,
        ) {
            val consumedByPreScroll = onPreScroll(available = available, source = UserInput)
            val consumed = consumedByPreScroll + consumedByScroll

            onPostScroll(consumed = consumed, available = available - consumed, source = UserInput)
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
        )
        assertIdle(currentScene = SceneA)
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
    fun onDragIntoNoAction_stayIdle() = runGestureTest {
        navigateToSceneC()

        // We are on SceneC which has no action in Down direction
        onDragStarted(overSlop = 10f, expectedConsumedOverSlop = 0f)
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun onDragWithActionsInBothDirections_dragToOppositeDirectionNotReplaceable() = runGestureTest {
        // We are on SceneA. UP -> B, DOWN-> C. The up swipe is not replaceable though.
        mutableUserActionsA = mapOf(Swipe.Up to UserActionResult(SceneB), Swipe.Down to SceneC)
        val dragController =
            onDragStarted(
                pointersInfo =
                    pointersDown(startedPosition = Offset(SCREEN_SIZE * 0.5f, SCREEN_SIZE * 0.5f)),
                overSlop = up(fractionOfScreen = 0.2f),
            )
        assertTransition(
            currentScene = SceneA,
            fromScene = SceneA,
            toScene = SceneB,
            progress = 0.2f,
        )

        // Reverse drag direction, it does not replace the previous transition.
        dragController.onDragDelta(
            pixels = down(fractionOfScreen = 0.5f),
            expectedConsumed = down(0.2f),
        )
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB, progress = 0f)
    }

    @Test
    fun onDragFromEdge_startTransitionToEdgeAction() = runGestureTest {
        navigateToSceneC()

        // Start dragging from the bottom
        onDragStarted(
            pointersInfo = pointersDown(startedPosition = Offset(SCREEN_SIZE * 0.5f, SCREEN_SIZE)),
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
        )
        assertIdle(currentScene = SceneC)
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
        onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneC, progress = 0.1f)
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
        onDragStarted(overSlop = up(fractionOfScreen = 0.1f))
        assertThat(isUserInputOngoing).isTrue()
    }

    @Test
    fun freezeAndAnimateToCurrentState() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()

        // Swipe up from the middle to transition to scene B.
        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))
        onDragStarted(pointersInfo = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneC, toScene = SceneB, isUserInputOngoing = true)

        // Freeze the transition.
        val transition = transitionState as Transition
        transition.freezeAndAnimateToCurrentState()
        runCurrent()
        assertTransition(isUserInputOngoing = false)
        advanceUntilIdle()
        assertIdle(SceneC)
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
        )
        assertIdle(SceneA)
    }

    @Test
    fun blockTransition_animated() = runGestureTest {
        assertIdle(SceneA)

        // Swipe up to scene B. Overscroll 50%.
        val dragController = onDragStarted(overSlop = up(1.5f), expectedConsumedOverSlop = up(1.0f))
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB, progress = 1f)

        // Block the transition when the user release their finger.
        canChangeScene = { false }
        val velocityConsumed =
            dragController.onDragStoppedAnimateLater(velocity = -velocityThreshold)

        // Start an animation: overscroll and from 1f to 0f.
        assertTransition(currentScene = SceneA, fromScene = SceneA, toScene = SceneB, progress = 1f)

        val consumed = velocityConsumed.await()
        assertThat(consumed).isNotEqualTo(0f)
        assertIdle(SceneA)
    }

    @Test
    fun nestedScrollUseFromSourceInfo() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()
        val nestedScroll = nestedScrollConnection()

        // Drag from the **top** of the screen
        pointerInfoOwner = { pointersDown() }
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
        pointerInfoOwner = { pointersDown(startedPosition = Offset(0f, SCREEN_SIZE)) }
        assertIdle(currentScene = SceneC)

        nestedScroll.scroll(available = upOffset(fractionOfScreen = 0.1f))
        assertTransition(
            currentScene = SceneC,
            fromScene = SceneC,
            // userAction: Swipe.Up(fromSource = Edge.Bottom) to SceneA
            toScene = SceneA,
            progress = 0.1f,
        )
    }

    @Test
    fun ignoreMouseWheel() = runGestureTest {
        // Start at scene C.
        navigateToSceneC()
        val nestedScroll = nestedScrollConnection()

        // Use mouse wheel
        pointerInfoOwner = { PointersInfo.MouseWheel }
        assertIdle(currentScene = SceneC)

        nestedScroll.scroll(available = upOffset(fractionOfScreen = 0.1f))
        assertIdle(currentScene = SceneC)
    }

    @Test
    fun transitionIsImmediatelyUpdatedWhenReleasingFinger() = runGestureTest {
        // Swipe up from the middle to transition to scene B.
        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))
        val dragController = onDragStarted(pointersInfo = middle, overSlop = up(0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, isUserInputOngoing = true)

        dragController.onDragStoppedAnimateLater(velocity = 0f)
        assertTransition(isUserInputOngoing = false)
    }

    @Test
    fun emptyOverscrollAbortsSettleAnimationAndExposeTheConsumedVelocity() = runGestureTest {
        // Swipe up to scene B at progress = 200%.
        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))
        val dragController = onDragStarted(pointersInfo = middle, overSlop = up(0.99f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.99f)

        // Release the finger.
        dragController.onDragStoppedAnimateNow(
            velocity = -velocityThreshold,
            onAnimationStart = { assertTransition(fromScene = SceneA, toScene = SceneB) },
            onAnimationEnd = { consumedVelocity ->
                // Our progress value was 0.99f and it is coerced in `[0..1]`.
                // Some of the velocity will be used for animation, but not all of it.
                assertThat(consumedVelocity).isLessThan(0f)
                assertThat(consumedVelocity).isGreaterThan(-velocityThreshold)
            },
        )
    }

    @Test
    fun scrollKeepPriorityEvenIfWeCanNoLongerScrollOnThatDirection() = runGestureTest {
        val nestedScroll = nestedScrollConnection()

        // Overscroll is disabled, it will scroll up to 100%
        nestedScroll.scroll(available = upOffset(fractionOfScreen = 2f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)

        // We need to maintain scroll priority even if the scene transition can no longer consume
        // the scroll gesture.
        nestedScroll.scroll(available = upOffset(fractionOfScreen = 0.1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)

        // A scroll gesture in the opposite direction allows us to return to the previous scene.
        nestedScroll.scroll(available = downOffset(fractionOfScreen = 0.5f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 0.5f)
    }

    @Test
    fun overscroll_releaseBetween0And100Percent_up() = runGestureTest {
        // Make scene B overscrollable.
        layoutState.transitions = transitions {
            defaultSwipeSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
            from(SceneA, to = SceneB) {}
        }

        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))

        val dragController = onDragStarted(pointersInfo = middle, overSlop = up(0.5f))
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
        )

        // We didn't overscroll at the end of the transition.
        assertIdle(SceneB)
        assertThat(transition).hasProgress(1f)
    }

    @Test
    fun overscroll_releaseBetween0And100Percent_down() = runGestureTest {
        // Make scene C overscrollable.
        layoutState.transitions = transitions {
            defaultSwipeSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
            from(SceneA, to = SceneC) {}
        }

        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))

        val dragController = onDragStarted(pointersInfo = middle, overSlop = down(0.5f))
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
        )

        // We didn't overscroll at the end of the transition.
        assertIdle(SceneC)
        assertThat(transition).hasProgress(1f)
    }

    @Test
    fun overscroll_releaseAt150Percent_up() = runGestureTest {
        // Make scene B overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneB) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
        }

        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))

        val dragController =
            onDragStarted(
                pointersInfo = middle,
                overSlop = up(1.5f),
                expectedConsumedOverSlop = up(1f),
            )
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(1f)

        // Release to B.
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)
            },
        )

        // We kept the overscroll at 100% so that the placement logic didn't change at the end of
        // the animation.
        assertIdle(SceneB)
        assertThat(transition).hasProgress(1f)
    }

    @Test
    fun overscroll_releaseAt150Percent_down() = runGestureTest {
        // Make scene C overscrollable.
        layoutState.transitions = transitions {
            from(SceneA, to = SceneC) { spec = spring(dampingRatio = Spring.DampingRatioNoBouncy) }
        }

        val middle = pointersDown(startedPosition = Offset(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f))

        val dragController =
            onDragStarted(
                pointersInfo = middle,
                overSlop = down(1.5f),
                expectedConsumedOverSlop = down(1f),
            )
        val transition = assertThat(transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        assertThat(transition).hasProgress(1f)

        // Release to C.
        dragController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneC, progress = 1f)
            },
        )

        // We kept the overscroll at 100% so that the placement logic didn't change at the end of
        // the animation.
        assertIdle(SceneC)
        assertThat(transition).hasProgress(1f)
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
        )
        assertIdle(SceneA)

        val otherController = onDragStarted(overSlop = up(fractionOfScreen = 1f))
        assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)
        otherController.onDragStoppedAnimateNow(
            velocity = 0f,
            onAnimationStart = {
                assertTransition(fromScene = SceneA, toScene = SceneB, progress = 1f)
            },
        )
        assertIdle(SceneB)
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
        )
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayB)
    }

    @Test
    fun replaceOverlayNestedScroll() = runGestureTest {
        layoutState.showOverlay(OverlayA, animationScope = testScope)
        advanceUntilIdle()

        // Initial state.
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayA)

        // Swipe down to replace overlay A by overlay B.

        val nestedScroll = nestedScrollConnection()
        nestedScroll.scroll(downOffset(0.1f))
        val transition = assertThat(layoutState.transitionState).isReplaceOverlayTransition()
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasFromOverlay(OverlayA)
        assertThat(transition).hasToOverlay(OverlayB)
        assertThat(transition).hasCurrentOverlays(OverlayA)
        assertThat(transition).hasProgress(0.1f)

        nestedScroll.preFling(Velocity(0f, velocityThreshold))
        advanceUntilIdle()
        // Commit the gesture. The overlays are instantly swapped in the set of current overlays.
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).hasCurrentOverlays(OverlayB)
    }
}
