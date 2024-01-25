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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun layoutState(initialScene: SceneKey = TestScenes.SceneA) =
        MutableSceneTransitionLayoutState(initialScene, EmptyTestTransitions)

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
                TestScenes.SceneA,
                userActions =
                    if (swipesEnabled())
                        mapOf(
                            Swipe.Left to TestScenes.SceneB,
                            Swipe.Down to TestScenes.SceneC,
                            Swipe.Up to TestScenes.SceneB,
                        )
                    else emptyMap(),
            ) {
                Box(Modifier.fillMaxSize())
            }
            scene(
                TestScenes.SceneB,
                userActions =
                    if (swipesEnabled()) mapOf(Swipe.Right to TestScenes.SceneA) else emptyMap(),
            ) {
                Box(Modifier.fillMaxSize())
            }
            scene(
                TestScenes.SceneC,
                userActions =
                    if (swipesEnabled())
                        mapOf(
                            Swipe.Down to TestScenes.SceneA,
                            Swipe(SwipeDirection.Down, pointerCount = 2) to TestScenes.SceneB,
                            Swipe(SwipeDirection.Right, fromSource = Edge.Left) to
                                TestScenes.SceneB,
                            Swipe(SwipeDirection.Down, fromSource = Edge.Top) to TestScenes.SceneB,
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

        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneA)

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
        var transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneA)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneB)
        assertThat(transition.currentScene).isEqualTo(TestScenes.SceneA)
        assertThat(transition.progress).isEqualTo(55.dp / LayoutWidth)
        assertThat(transition.isInitiatedByUserInput).isTrue()

        // Release the finger. We should now be animating back to A (currentScene = SceneA) given
        // that 55dp < positional threshold.
        rule.onRoot().performTouchInput { up() }
        transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneA)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneB)
        assertThat(transition.currentScene).isEqualTo(TestScenes.SceneA)
        assertThat(transition.progress).isEqualTo(55.dp / LayoutWidth)
        assertThat(transition.isInitiatedByUserInput).isTrue()

        // Wait for the animation to finish. We should now be in scene A.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneA)

        // Now we do the same but vertically and with a drag distance of 56dp, which is >=
        // positional threshold.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, 56.dp.toPx() + touchSlop), delayMillis = 1_000)
        }

        // Drag is in progress, so currentScene = SceneA and progress = 56dp / LayoutHeight
        transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneA)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneC)
        assertThat(transition.currentScene).isEqualTo(TestScenes.SceneA)
        assertThat(transition.progress).isEqualTo(56.dp / LayoutHeight)
        assertThat(transition.isInitiatedByUserInput).isTrue()

        // Release the finger. We should now be animating to C (currentScene = SceneC) given
        // that 56dp >= positional threshold.
        rule.onRoot().performTouchInput { up() }
        transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneA)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneC)
        assertThat(transition.currentScene).isEqualTo(TestScenes.SceneC)
        assertThat(transition.progress).isEqualTo(56.dp / LayoutHeight)
        assertThat(transition.isInitiatedByUserInput).isTrue()

        // Wait for the animation to finish. We should now be in scene C.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)
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

        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneA)

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
        var transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneA)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneB)
        assertThat(transition.currentScene).isEqualTo(TestScenes.SceneA)
        assertThat(transition.progress).isEqualTo(55.dp / LayoutWidth)

        // Wait for the animation to finish. We should now be in scene A.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneA)

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
        transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneA)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneC)
        assertThat(transition.currentScene).isEqualTo(TestScenes.SceneC)
        assertThat(transition.progress).isEqualTo(55.dp / LayoutHeight)

        // Wait for the animation to finish. We should now be in scene C.
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)
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

        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)

        // Swipe down with two fingers.
        rule.onRoot().performTouchInput {
            repeat(2) { i -> down(pointerId = i, middle) }
            repeat(2) { i ->
                moveBy(pointerId = i, Offset(0f, touchSlop + 10.dp.toPx()), delayMillis = 1_000)
            }
        }

        // We are transitioning to B because we used 2 fingers.
        val transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneC)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneB)

        // Release the fingers and wait for the animation to end. We are back to C because we only
        // swiped 10dp.
        rule.onRoot().performTouchInput { repeat(2) { i -> up(pointerId = i) } }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)
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

        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)

        // Swipe down from the top edge.
        rule.onRoot().performTouchInput {
            down(middleTop)
            moveBy(Offset(0f, touchSlop + 10.dp.toPx()), delayMillis = 1_000)
        }

        // We are transitioning to B (and not A) because we started from the top edge.
        var transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneC)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneB)

        // Release the fingers and wait for the animation to end. We are back to C because we only
        // swiped 10dp.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)

        // Swipe right from the left edge.
        rule.onRoot().performTouchInput {
            down(middleLeft)
            moveBy(Offset(touchSlop + 10.dp.toPx(), 0f), delayMillis = 1_000)
        }

        // We are transitioning to B (and not A) because we started from the left edge.
        transition = layoutState.transitionState
        assertThat(transition).isInstanceOf(TransitionState.Transition::class.java)
        assertThat((transition as TransitionState.Transition).fromScene)
            .isEqualTo(TestScenes.SceneC)
        assertThat(transition.toScene).isEqualTo(TestScenes.SceneB)

        // Release the fingers and wait for the animation to end. We are back to C because we only
        // swiped 10dp.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).isInstanceOf(TransitionState.Idle::class.java)
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneC)
    }

    @Test
    fun swipeDistance() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f

        val layoutState = layoutState()
        val verticalSwipeDistance = 50.dp
        assertThat(verticalSwipeDistance).isNotEqualTo(LayoutHeight)

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop

            SceneTransitionLayout(
                state = layoutState,
                modifier = Modifier.size(LayoutWidth, LayoutHeight)
            ) {
                scene(
                    TestScenes.SceneA,
                    userActions =
                        mapOf(Swipe.Down to TestScenes.SceneB withDistance verticalSwipeDistance),
                ) {
                    Spacer(Modifier.fillMaxSize())
                }
                scene(TestScenes.SceneB) { Spacer(Modifier.fillMaxSize()) }
            }
        }

        assertThat(layoutState.currentTransition).isNull()

        // Swipe by half of verticalSwipeDistance.
        rule.onRoot().performTouchInput {
            down(middleTop)
            moveBy(Offset(0f, touchSlop + (verticalSwipeDistance / 2).toPx()), delayMillis = 1_000)
        }

        // We should be at 50%
        val transition = layoutState.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(0.5f)
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
        var transition = layoutState.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition?.toScene).isEqualTo(TestScenes.SceneC)

        // Release the finger, animating back to scene A.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertThat(layoutState.currentTransition).isNull()
        assertThat(layoutState.transitionState.currentScene).isEqualTo(TestScenes.SceneA)

        // Swipe up by exactly touchSlop, so that the drag overSlop is 0f.
        rule.onRoot().performTouchInput {
            down(middle)
            moveBy(Offset(0f, -touchSlop), delayMillis = 1_000)
        }

        // We should still correctly compute that we are swiping up to scene B.
        transition = layoutState.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition?.toScene).isEqualTo(TestScenes.SceneB)
    }

    @Test
    fun swipeEnabledLater() {
        val layoutState = MutableSceneTransitionLayoutState(TestScenes.SceneA)
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
}
