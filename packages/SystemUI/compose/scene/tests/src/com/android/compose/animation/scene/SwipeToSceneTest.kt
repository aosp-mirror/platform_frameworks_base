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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.subjects.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeToSceneTest {
    companion object {
        private val LayoutWidth = 200.dp
        private val LayoutHeight = 400.dp

        /** The middle of the layout, in pixels. */
        private val Density.middle: Offset
            get() = Offset((LayoutWidth / 2).toPx(), (LayoutHeight / 2).toPx())

        /** The middle-top of the layout, in pixels. */
        private val Density.middleTop: Offset
            get() = Offset((LayoutWidth / 2).toPx(), 0f)

        /** The middle-left of the layout, in pixels. */
        private val Density.middleLeft: Offset
            get() = Offset(0f, (LayoutHeight / 2).toPx())
    }

    @get:Rule val rule = createComposeRule()

    private fun layoutState(
        initialScene: SceneKey = SceneA,
        transitions: SceneTransitions = EmptyTestTransitions,
    ): MutableSceneTransitionLayoutState {
        return rule.runOnUiThread { MutableSceneTransitionLayoutState(initialScene, transitions) }
    }

    /** The content under test. */
    @Composable
    private fun TestContent(
        layoutState: SceneTransitionLayoutState,
        swipesEnabled: () -> Boolean = { true },
    ) {
        SceneTransitionLayout(
            state = layoutState,
            modifier = Modifier.size(LayoutWidth, LayoutHeight).testTag(TestElements.Foo.debugName),
        ) {
            scene(
                SceneA,
                userActions =
                    if (swipesEnabled())
                        mapOf(
                            Swipe.Left to SceneB,
                            Swipe.Down to TestScenes.SceneC,
                            Swipe.Up to SceneB,
                        )
                    else emptyMap(),
            ) {
                Box(Modifier.fillMaxSize())
            }
            scene(
                SceneB,
                userActions = if (swipesEnabled()) mapOf(Swipe.Right to SceneA) else emptyMap(),
            ) {
                Box(Modifier.fillMaxSize())
            }
            scene(
                TestScenes.SceneC,
                userActions =
                    if (swipesEnabled())
                        mapOf(
                            Swipe.Down to SceneA,
                            Swipe(SwipeDirection.Down, pointerCount = 2) to SceneB,
                            Swipe(SwipeDirection.Right, fromSource = Edge.Left) to SceneB,
                            Swipe(SwipeDirection.Down, fromSource = Edge.Top) to SceneB,
                        )
                    else emptyMap(),
            ) {
                Box(Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun testDragWithPositionalThreshold() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f

        val layoutState = layoutState()
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            TestContent(layoutState)
        }

        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Drag left (i.e. from right to left) by 55dp. We pick 55dp here because 56dp is the
        // positional threshold from which we commit the gesture.
        rule.onRoot().performTouchInput {
            down(middle)

            // We use a high delay so that the velocity of the gesture is slow (otherwise it would
            // commit the gesture, even if we are below the positional threshold).
            moveBy(Offset(-55.dp.toPx() - touchSlop, 0f), delayMillis = 1_000)
        }

        // We should be at a progress = 55dp / LayoutWidth given that we use the layout size in
        // the gesture axis as swipe distance.
        var transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasProgress(55.dp / LayoutWidth)
        assertThat(transition).isInitiatedByUserInput()

        // Release the finger. We should now be animating back to A (currentScene = SceneA) given
        // that 55dp < positional threshold.
        rule.onRoot().performTouchInput { up() }
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasProgress(55.dp / LayoutWidth)
        assertThat(transition).isInitiatedByUserInput()

        // Wait for the animation to finish. We should now be in scene A.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Now we do the same but vertically and with a drag distance of 56dp, which is >=
        // positional threshold.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, 56.dp.toPx() + touchSlop), delayMillis = 1_000)
        }

        // Drag is in progress, so currentScene = SceneA and progress = 56dp / LayoutHeight
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(TestScenes.SceneC)
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasProgress(56.dp / LayoutHeight)
        assertThat(transition).isInitiatedByUserInput()

        // Release the finger. We should now be animating to C (currentScene = SceneC) given
        // that 56dp >= positional threshold.
        rule.onRoot().performTouchInput { up() }
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(TestScenes.SceneC)
        assertThat(transition).hasCurrentScene(TestScenes.SceneC)
        assertThat(transition).hasProgress(56.dp / LayoutHeight)
        assertThat(transition).isInitiatedByUserInput()

        // Wait for the animation to finish. We should now be in scene C.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)
    }

    @Test
    fun testSwipeWithVelocityThreshold() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        val layoutState = layoutState()
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            TestContent(layoutState)
        }

        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Swipe left (i.e. from right to left) using a velocity of 124 dp/s. We pick 124 dp/s here
        // because 125 dp/s is the velocity threshold from which we commit the gesture. We also use
        // a swipe distance < 56dp, the positional threshold, to make sure that we don't commit
        // the gesture because of a large enough swipe distance.
        rule.onRoot().performTouchInput {
            swipeWithVelocity(
                start = middle,
                end = middle - Offset(55.dp.toPx() + touchSlop, 0f),
                endVelocity = 124.dp.toPx(),
            )
        }

        // We should be animating back to A (currentScene = SceneA) given that 124 dp/s < velocity
        // threshold.
        var transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasCurrentScene(SceneA)
        assertThat(transition).hasProgress(55.dp / LayoutWidth)

        // Wait for the animation to finish. We should now be in scene A.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Now we do the same but vertically and with a swipe velocity of 126dp, which is >
        // velocity threshold. Note that in theory we could have used 125 dp (= velocity threshold)
        // but it doesn't work reliably with how swipeWithVelocity() computes move events to get to
        // the target velocity, probably because of float rounding errors.
        rule.onRoot().performTouchInput {
            swipeWithVelocity(
                start = middle,
                end = middle + Offset(0f, 55.dp.toPx() + touchSlop),
                endVelocity = 126.dp.toPx(),
            )
        }

        // We should be animating to C (currentScene = SceneC).
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(TestScenes.SceneC)
        assertThat(transition).hasCurrentScene(TestScenes.SceneC)
        assertThat(transition).hasProgress(55.dp / LayoutHeight)

        // Wait for the animation to finish. We should now be in scene C.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)
    }

    @Test
    fun multiPointerSwipe() {
        // Start at scene C.
        val layoutState = layoutState(TestScenes.SceneC)

        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            TestContent(layoutState)
        }

        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)

        // Swipe down with two fingers.
        rule.onRoot().performTouchInput {
            repeat(2) { i -> down(pointerId = i, middle) }
            repeat(2) { i ->
                moveBy(pointerId = i, Offset(0f, touchSlop + 10.dp.toPx()), delayMillis = 1_000)
            }
        }

        // We are transitioning to B because we used 2 fingers.
        val transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(TestScenes.SceneC)
        assertThat(transition).hasToScene(SceneB)

        // Release the fingers and wait for the animation to end. We are back to C because we only
        // swiped 10dp.
        rule.onRoot().performTouchInput { repeat(2) { i -> up(pointerId = i) } }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)
    }

    @Test
    fun defaultEdgeSwipe() {
        // Start at scene C.
        val layoutState = layoutState(TestScenes.SceneC)

        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            TestContent(layoutState)
        }

        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)

        // Swipe down from the top edge.
        rule.onRoot().performTouchInput {
            down(middleTop)
            moveBy(Offset(0f, touchSlop + 10.dp.toPx()), delayMillis = 1_000)
        }

        // We are transitioning to B (and not A) because we started from the top edge.
        var transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(TestScenes.SceneC)
        assertThat(transition).hasToScene(SceneB)

        // Release the fingers and wait for the animation to end. We are back to C because we only
        // swiped 10dp.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)

        // Swipe right from the left edge.
        rule.onRoot().performTouchInput {
            down(middleLeft)
            moveBy(Offset(touchSlop + 10.dp.toPx(), 0f), delayMillis = 1_000)
        }

        // We are transitioning to B (and not A) because we started from the left edge.
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(TestScenes.SceneC)
        assertThat(transition).hasToScene(SceneB)

        // Release the fingers and wait for the animation to end. We are back to C because we only
        // swiped 10dp.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(TestScenes.SceneC)
    }

    @Test
    fun swipeDistance() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f

        val verticalSwipeDistance = 50.dp
        val layoutState =
            layoutState(
                transitions =
                    transitions {
                        from(SceneA, to = SceneB) {
                            distance = FixedDistance(verticalSwipeDistance)
                        }
                    }
            )
        assertThat(verticalSwipeDistance).isNotEqualTo(LayoutHeight)

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop

            SceneTransitionLayout(
                state = layoutState,
                modifier = Modifier.size(LayoutWidth, LayoutHeight),
            ) {
                scene(SceneA, userActions = mapOf(Swipe.Down to SceneB)) {
                    Spacer(Modifier.fillMaxSize())
                }
                scene(SceneB) { Spacer(Modifier.fillMaxSize()) }
            }
        }

        assertThat(layoutState.currentTransition).isNull()

        // Swipe by half of verticalSwipeDistance.
        rule.onRoot().performTouchInput {
            down(middleTop)
            moveBy(Offset(0f, touchSlop + (verticalSwipeDistance / 2).toPx()), delayMillis = 1_000)
        }

        // We should be at 50%
        val transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).isNotNull()
        assertThat(transition).hasProgress(0.5f)
    }

    @Test
    fun swipeByTouchSlop() {
        val layoutState = layoutState()
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            TestContent(layoutState)
        }

        // Swipe down by exactly touchSlop, so that the drag overSlop is 0f.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, touchSlop), delayMillis = 1_000)
        }

        // We should still correctly compute that we are swiping down to scene C.
        var transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasToScene(TestScenes.SceneC)

        // Release the finger, animating back to scene A.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Swipe up by exactly touchSlop, so that the drag overSlop is 0f.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, -touchSlop), delayMillis = 1_000)
        }

        // We should still correctly compute that we are swiping up to scene B.
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasToScene(SceneB)

        // Release the finger, animating back to scene A.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Swipe left by exactly touchSlop, so that the drag overSlop is 0f.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(-touchSlop, 0f), delayMillis = 1_000)
        }

        // We should still correctly compute that we are swiping down to scene B.
        transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasToScene(SceneB)
    }

    @Test
    fun swipeEnabledLater() {
        val layoutState = layoutState()
        var swipesEnabled by mutableStateOf(false)
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            TestContent(layoutState, swipesEnabled = { swipesEnabled })
        }

        // Drag down from the middle. This should not do anything, because swipes are disabled.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, touchSlop), delayMillis = 1_000)
        }
        assertThat(layoutState.currentTransition).isNull()

        // Release finger.
        rule.onRoot().performTouchInput { up() }

        // Enable swipes.
        swipesEnabled = true
        rule.waitForIdle()

        // Drag down from the middle. Now it should start a transition.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, touchSlop), delayMillis = 1_000)
        }
        assertThat(layoutState.currentTransition).isNotNull()
    }

    @Test
    fun transitionKey() {
        val transitionkey = TransitionKey(debugName = "foo")
        val state =
            layoutState(
                SceneA,
                transitions {
                    from(SceneA, to = SceneB) { fade(TestElements.Foo) }
                    from(SceneA, to = SceneB, key = transitionkey) {
                        fade(TestElements.Foo)
                        fade(TestElements.Bar)
                    }
                },
            )

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(state, Modifier.size(LayoutWidth, LayoutHeight)) {
                scene(
                    SceneA,
                    userActions =
                        mapOf(
                            Swipe.Down to SceneB,
                            Swipe.Up to UserActionResult(SceneB, transitionKey = transitionkey),
                        ),
                ) {
                    Box(Modifier.fillMaxSize())
                }
                scene(SceneB) { Box(Modifier.fillMaxSize()) }
            }
        }

        // Swipe down for the default transition from A to B.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, touchSlop), delayMillis = 1_000)
        }

        assertThat(state.isTransitioning(from = SceneA, to = SceneB)).isTrue()
        assertThat(state.currentTransition?.transformationSpec?.transformations).hasSize(1)

        // Move the pointer up to swipe to scene B using the new transition.
        rule.onRoot().performTouchInput { moveBy(Offset(0f, -1.dp.toPx()), delayMillis = 1_000) }
        assertThat(state.isTransitioning(from = SceneA, to = SceneB)).isTrue()
        assertThat(state.currentTransition?.transformationSpec?.transformations).hasSize(2)
    }

    @Test
    fun dynamicSwipeDistance() {
        val swipeDistance =
            object : UserActionDistance {
                override fun UserActionDistanceScope.absoluteDistance(
                    fromSceneSize: IntSize,
                    orientation: Orientation,
                ): Float {
                    // Foo is going to have a vertical offset of 50dp. Let's make the swipe distance
                    // the difference between the bottom of the scene and the bottom of the element,
                    // so that we use the offset and size of the element as well as the size of the
                    // scene.
                    val fooSize = TestElements.Foo.targetSize(SceneB) ?: return 0f
                    val fooOffset = TestElements.Foo.targetOffset(SceneB) ?: return 0f
                    val sceneSize = SceneB.targetSize() ?: return 0f
                    return sceneSize.height - fooOffset.y - fooSize.height
                }
            }

        val state =
            layoutState(
                SceneA,
                transitions { from(SceneA, to = SceneB) { distance = swipeDistance } },
            )

        val layoutSize = 200.dp
        val fooYOffset = 50.dp
        val fooSize = 25.dp

        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop

            SceneTransitionLayout(state, Modifier.size(layoutSize)) {
                scene(SceneA, userActions = mapOf(Swipe.Up to SceneB)) {
                    Box(Modifier.fillMaxSize())
                }
                scene(SceneB) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.offset(y = fooYOffset).element(TestElements.Foo).size(fooSize))
                    }
                }
            }
        }

        // Swipe up by half the expected distance to get to 50% progress.
        val expectedDistance = layoutSize - fooYOffset - fooSize
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(0f, -touchSlop - (expectedDistance / 2f).toPx()), delayMillis = 1_000)
        }

        rule.waitForIdle()
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0.5f, tolerance = 0.01f)
    }

    @Test
    fun overscrollScopeExtendsDensity() {
        val swipeDistance = 100.dp
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    SceneA,
                    transitions {
                        from(SceneA, to = SceneB) { distance = FixedDistance(swipeDistance) }

                        overscroll(SceneB, Orientation.Vertical) {
                            progressConverter = ProgressConverter.linear()
                            translate(TestElements.Foo, x = { 20.dp.toPx() }, y = { 30.dp.toPx() })
                        }
                    },
                )
            }
        val layoutSize = 200.dp
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(state, Modifier.size(layoutSize)) {
                scene(SceneA, userActions = mapOf(Swipe.Down to SceneB)) {
                    Box(Modifier.fillMaxSize())
                }
                scene(SceneB) { Box(Modifier.element(TestElements.Foo).fillMaxSize()) }
            }
        }

        // Swipe down by twice the swipe distance so that we are at 100% overscrolling on scene B.
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(0f, touchSlop + (swipeDistance * 2).toPx()), delayMillis = 1_000)
        }

        // Foo should be translated by (20dp, 30dp).
        rule.onNode(isElement(TestElements.Foo)).assertPositionInRootIsEqualTo(20.dp, 30.dp)
    }

    @Test
    fun startEnd_ltrLayout() {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    initialScene = SceneA,
                    transitions =
                        transitions {
                            from(SceneA, to = SceneB) {
                                // We go to B by swiping to the start (left in LTR), so we make
                                // scene B appear from the end (right) edge.
                                translate(SceneB.rootElementKey, Edge.End)
                            }

                            from(SceneA, to = SceneC) {
                                // We go to C by swiping to the end (right in LTR), so we make
                                // scene C appear from the start (left) edge.
                                translate(SceneC.rootElementKey, Edge.Start)
                            }
                        },
                )
            }

        val layoutSize = 200.dp
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(state, Modifier.size(layoutSize)) {
                scene(SceneA, userActions = mapOf(Swipe.Start to SceneB, Swipe.End to SceneC)) {
                    Box(Modifier.fillMaxSize())
                }
                scene(SceneB) { Box(Modifier.element(SceneB.rootElementKey).fillMaxSize()) }
                scene(SceneC) { Box(Modifier.element(SceneC.rootElementKey).fillMaxSize()) }
            }
        }

        // Swipe to the left (start).
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(-touchSlop, 0f), delayMillis = 1_000)
        }

        // Scene B should come from the right (end) edge.
        var transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        rule
            .onNode(isElement(SceneB.rootElementKey))
            .assertPositionInRootIsEqualTo(layoutSize, 0.dp)

        // Release to go back to A.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneA)

        // Swipe to the right (end).
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(touchSlop, 0f), delayMillis = 1_000)
        }

        // Scene C should come from the left (start) edge.
        transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        rule
            .onNode(isElement(SceneC.rootElementKey))
            .assertPositionInRootIsEqualTo(-layoutSize, 0.dp)
    }

    @Test
    fun startEnd_rtlLayout() {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    initialScene = SceneA,
                    transitions =
                        transitions {
                            from(SceneA, to = SceneB) {
                                // We go to B by swiping to the start (right in RTL), so we make
                                // scene B appear from the end (left) edge.
                                translate(SceneB.rootElementKey, Edge.End)
                            }

                            from(SceneA, to = SceneC) {
                                // We go to C by swiping to the end (left in RTL), so we make
                                // scene C appear from the start (right) edge.
                                translate(SceneC.rootElementKey, Edge.Start)
                            }
                        },
                )
            }

        val layoutSize = 200.dp
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SceneTransitionLayout(state, Modifier.size(layoutSize)) {
                    scene(SceneA, userActions = mapOf(Swipe.Start to SceneB, Swipe.End to SceneC)) {
                        Box(Modifier.fillMaxSize())
                    }
                    scene(SceneB) { Box(Modifier.element(SceneB.rootElementKey).fillMaxSize()) }
                    scene(SceneC) { Box(Modifier.element(SceneC.rootElementKey).fillMaxSize()) }
                }
            }
        }

        // Swipe to the left (end).
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(-touchSlop, 0f), delayMillis = 1_000)
        }

        // Scene C should come from the right (start) edge.
        var transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneC)
        rule
            .onNode(isElement(SceneC.rootElementKey))
            .assertPositionInRootIsEqualTo(layoutSize, 0.dp)

        // Release to go back to A.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneA)

        // Swipe to the right (start).
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(touchSlop, 0f), delayMillis = 1_000)
        }

        // Scene C should come from the left (end) edge.
        transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        rule
            .onNode(isElement(SceneB.rootElementKey))
            .assertPositionInRootIsEqualTo(-layoutSize, 0.dp)
    }

    @Test
    fun whenOverscrollIsDisabled_dragGestureShouldNotBeConsumed() {
        val swipeDistance = 100.dp

        var availableOnPostScroll = Float.MIN_VALUE
        val connection =
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    availableOnPostScroll = available.y
                    return super.onPostScroll(consumed, available, source)
                }
            }
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    SceneA,
                    transitions {
                        from(SceneA, to = SceneB) { distance = FixedDistance(swipeDistance) }
                        overscrollDisabled(SceneB, Orientation.Vertical)
                    },
                )
            }
        val layoutSize = 200.dp
        var touchSlop = 0f
        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(state, Modifier.size(layoutSize).nestedScroll(connection)) {
                scene(SceneA, userActions = mapOf(Swipe.Down to SceneB)) {
                    Box(Modifier.fillMaxSize())
                }
                scene(SceneB) { Box(Modifier.element(TestElements.Foo).fillMaxSize()) }
            }
        }

        // Swipe down by the swipe distance so that we are on scene B.
        rule.onRoot().performTouchInput {
            val middle = (layoutSize / 2).toPx()
            down(Offset(middle, middle))
            moveBy(Offset(0f, touchSlop + (swipeDistance).toPx()), delayMillis = 1_000)
        }
        val transition = state.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(1f)
        assertThat(availableOnPostScroll).isEqualTo(0f)

        // Overscrolling on Scene B
        val ovescrollPx = 100f
        rule.onRoot().performTouchInput { moveBy(Offset(0f, ovescrollPx), delayMillis = 1_000) }
        // Overscroll is disabled on Scene B
        assertThat(transition.progress).isEqualTo(1f)
        assertThat(availableOnPostScroll).isEqualTo(ovescrollPx)
    }
}
