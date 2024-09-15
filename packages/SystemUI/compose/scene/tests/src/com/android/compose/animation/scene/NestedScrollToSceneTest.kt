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

import androidx.compose.foundation.gestures.Orientation.Vertical
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.subjects.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedScrollToSceneTest {
    @get:Rule val rule = createComposeRule()

    private var touchSlop = 0f
    private val layoutWidth: Dp = 200.dp
    private val layoutHeight = 400.dp

    private fun setup2ScenesAndScrollTouchSlop(
        modifierSceneA: @Composable ContentScope.() -> Modifier = { Modifier }
    ): MutableSceneTransitionLayoutState {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(SceneA, transitions = EmptyTestTransitions)
            }

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(
                state = state,
                modifier = Modifier.size(layoutWidth, layoutHeight),
            ) {
                scene(SceneA, userActions = mapOf(Swipe.Up to SceneB)) {
                    Spacer(modifierSceneA().fillMaxSize())
                }
                scene(SceneB, userActions = mapOf(Swipe.Down to SceneA)) {
                    Spacer(Modifier.fillMaxSize())
                }
            }
        }

        pointerDownAndScrollTouchSlop()

        assertThat(state.transitionState).isIdle()

        return state
    }

    private fun pointerDownAndScrollTouchSlop() {
        rule.onRoot().performTouchInput {
            val middleTop = Offset((layoutWidth / 2).toPx(), 0f)
            down(middleTop)
            // Scroll touchSlop
            moveBy(Offset(0f, touchSlop), delayMillis = 1_000)
        }
    }

    private fun scrollDown(percent: Float = 1f) {
        rule.onRoot().performTouchInput {
            moveBy(Offset(0f, layoutHeight.toPx() * percent), delayMillis = 1_000)
        }
    }

    private fun scrollUp(percent: Float = 1f) = scrollDown(-percent)

    private fun pointerUp() {
        rule.onRoot().performTouchInput { up() }
    }

    @Test
    fun scrollableElementsInSTL_shouldHavePriority() {
        val state = setup2ScenesAndScrollTouchSlop {
            Modifier
                // A scrollable that consumes the scroll gesture
                .scrollable(rememberScrollableState { it }, Vertical)
        }

        scrollUp(percent = 0.5f)

        // Consumed by the scrollable element
        assertThat(state.transitionState).isIdle()
    }

    @Test
    fun unconsumedScrollEvents_canBeConsumedBySTLByDefault() {
        val state = setup2ScenesAndScrollTouchSlop {
            Modifier
                // A scrollable that does not consume the scroll gesture
                .scrollable(rememberScrollableState { 0f }, Vertical)
        }

        scrollUp(percent = 0.5f)
        // STL will start a transition with the remaining scroll
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasProgress(0.5f)

        scrollUp(percent = 1f)
        assertThat(transition).hasProgress(1.5f)
    }

    @Test
    fun customizeStlNestedScrollBehavior_EdgeNoPreview() {
        var canScroll = true
        val state = setup2ScenesAndScrollTouchSlop {
            Modifier.verticalNestedScrollToScene(
                    bottomBehavior = NestedScrollBehavior.EdgeNoPreview
                )
                .scrollable(rememberScrollableState { if (canScroll) it else 0f }, Vertical)
        }

        scrollUp(percent = 0.5f)
        assertThat(state.transitionState).isIdle()

        // Reach the end of the scrollable element
        canScroll = false
        scrollUp(percent = 0.5f)
        assertThat(state.transitionState).isIdle()

        pointerUp()
        assertThat(state.transitionState).isIdle()

        // Start a new gesture
        pointerDownAndScrollTouchSlop()
        scrollUp(percent = 0.5f)
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasProgress(0.5f)

        pointerUp()
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun customizeStlNestedScrollBehavior_EdgeWithPreview() {
        var canScroll = true
        val state = setup2ScenesAndScrollTouchSlop {
            Modifier.verticalNestedScrollToScene(
                    bottomBehavior = NestedScrollBehavior.EdgeWithPreview
                )
                .scrollable(rememberScrollableState { if (canScroll) it else 0f }, Vertical)
        }

        scrollUp(percent = 0.5f)
        assertThat(state.transitionState).isIdle()

        // Reach the end of the scrollable element
        canScroll = false
        scrollUp(percent = 0.5f)
        val transition1 = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition1).hasProgress(0.5f)

        pointerUp()
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneA)

        // Start a new gesture
        pointerDownAndScrollTouchSlop()
        scrollUp(percent = 0.5f)
        val transition2 = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition2).hasProgress(0.5f)

        pointerUp()
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun customizeStlNestedScrollBehavior_EdgeAlways() {
        var canScroll = true
        val state = setup2ScenesAndScrollTouchSlop {
            Modifier.verticalNestedScrollToScene(bottomBehavior = NestedScrollBehavior.EdgeAlways)
                .scrollable(rememberScrollableState { if (canScroll) it else 0f }, Vertical)
        }

        scrollUp(percent = 0.5f)
        assertThat(state.transitionState).isIdle()

        // Reach the end of the scrollable element
        canScroll = false
        scrollUp(percent = 0.5f)
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasProgress(0.5f)

        pointerUp()
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun customizeStlNestedScrollBehavior_multipleRequests() {
        var canScroll = true
        val state = setup2ScenesAndScrollTouchSlop {
            Modifier
                // This verticalNestedScrollToScene is closer the STL (an ancestor node)
                .verticalNestedScrollToScene(bottomBehavior = NestedScrollBehavior.EdgeAlways)
                // Another verticalNestedScrollToScene modifier
                .verticalNestedScrollToScene(bottomBehavior = NestedScrollBehavior.EdgeNoPreview)
                .scrollable(rememberScrollableState { if (canScroll) it else 0f }, Vertical)
        }

        scrollUp(percent = 0.5f)
        assertThat(state.transitionState).isIdle()

        // Reach the end of the scrollable element
        canScroll = false

        scrollUp(percent = 0.5f)
        // EdgeAlways always consume the remaining scroll, EdgeNoPreview does not.
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasProgress(0.5f)
    }

    @Test
    fun resetScrollTracking_afterMissingPointerUpEvent() {
        var canScroll = true
        var hasScrollable by mutableStateOf(true)
        val state = setup2ScenesAndScrollTouchSlop {
            if (hasScrollable) {
                Modifier.scrollable(rememberScrollableState { if (canScroll) it else 0f }, Vertical)
            } else {
                Modifier
            }
        }

        // The gesture is consumed by the component in the scene.
        scrollUp(percent = 0.2f)

        // STL keeps track of the scroll consumed. The scene remains in Idle.
        assertThat(state.transitionState).isIdle()

        // The scrollable component disappears, and does not send the signal (pointer up) to reset
        // the consumed amount.
        hasScrollable = false
        pointerUp()

        // A new scrollable component appears and allows the scene to consume the scroll.
        hasScrollable = true
        canScroll = false
        pointerDownAndScrollTouchSlop()
        scrollUp(percent = 0.2f)

        // STL can only start the transition if it has reset the amount of scroll consumed.
        val transition = assertThat(state.transitionState).isSceneTransition()
        assertThat(transition).hasProgress(0.2f)
    }
}
