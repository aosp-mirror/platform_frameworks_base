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

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementTest {
    @get:Rule val rule = createComposeRule()

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun SceneScope.Element(
        key: ElementKey,
        size: Dp,
        offset: Dp,
        modifier: Modifier = Modifier,
        onLayout: () -> Unit = {},
        onPlacement: () -> Unit = {},
    ) {
        Box(
            modifier
                .offset(offset)
                .element(key)
                .approachLayout(
                    isMeasurementApproachInProgress = { layoutState.isTransitioning() }
                ) { measurable, constraints ->
                    onLayout()
                    val placement = measurable.measure(constraints)
                    layout(placement.width, placement.height) {
                        onPlacement()
                        placement.place(0, 0)
                    }
                }
                .size(size)
        )
    }

    @Test
    fun staticElements_noLayout_noPlacement() {
        val nFrames = 20
        val layoutSize = 100.dp
        val elementSize = 50.dp
        val elementOffset = 20.dp

        var fooLayouts = 0
        var fooPlacements = 0
        var barLayouts = 0
        var barPlacements = 0

        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(
                        TestElements.Foo,
                        elementSize,
                        elementOffset,
                        onLayout = { fooLayouts++ },
                        onPlacement = { fooPlacements++ },
                    )

                    // Transformed element
                    Element(
                        TestElements.Bar,
                        elementSize,
                        elementOffset,
                        onLayout = { barLayouts++ },
                        onPlacement = { barPlacements++ },
                    )
                }
            },
            toSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(
                        TestElements.Foo,
                        elementSize,
                        elementOffset,
                        onLayout = { fooLayouts++ },
                        onPlacement = { fooPlacements++ },
                    )
                }
            },
            transition = {
                spec = tween(nFrames * 16)

                // no-op transformations.
                translate(TestElements.Bar, x = 0.dp, y = 0.dp)
                scaleSize(TestElements.Bar, width = 1f, height = 1f)
            },
        ) {
            var fooLayoutsAfterOneAnimationFrame = 0
            var fooPlacementsAfterOneAnimationFrame = 0
            var barLayoutsAfterOneAnimationFrame = 0
            var barPlacementsAfterOneAnimationFrame = 0

            fun assertNumberOfLayoutsAndPlacements() {
                assertThat(fooLayouts).isEqualTo(fooLayoutsAfterOneAnimationFrame)
                assertThat(fooPlacements).isEqualTo(fooPlacementsAfterOneAnimationFrame)
                assertThat(barLayouts).isEqualTo(barLayoutsAfterOneAnimationFrame)
                assertThat(barPlacements).isEqualTo(barPlacementsAfterOneAnimationFrame)
            }

            at(16) {
                // Capture the number of layouts and placements that happened after 1 animation
                // frame.
                fooLayoutsAfterOneAnimationFrame = fooLayouts
                fooPlacementsAfterOneAnimationFrame = fooPlacements
                barLayoutsAfterOneAnimationFrame = barLayouts
                barPlacementsAfterOneAnimationFrame = barPlacements
            }
            repeat(nFrames - 2) { i ->
                // Ensure that all animation frames (except the final one) don't relayout or replace
                // static (shared or transformed) elements.
                at(32L + i * 16) { assertNumberOfLayoutsAndPlacements() }
            }
        }
    }

    @Test
    fun onlyMovingElements_noLayout_onlyPlacement() {
        val nFrames = 20
        val layoutSize = 100.dp
        val elementSize = 50.dp

        var fooLayouts = 0
        var fooPlacements = 0
        var barLayouts = 0
        var barPlacements = 0

        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(
                        TestElements.Foo,
                        elementSize,
                        offset = 0.dp,
                        onLayout = { fooLayouts++ },
                        onPlacement = { fooPlacements++ },
                    )

                    // Transformed element
                    Element(
                        TestElements.Bar,
                        elementSize,
                        offset = 0.dp,
                        onLayout = { barLayouts++ },
                        onPlacement = { barPlacements++ },
                    )
                }
            },
            toSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(
                        TestElements.Foo,
                        elementSize,
                        offset = 20.dp,
                        onLayout = { fooLayouts++ },
                        onPlacement = { fooPlacements++ },
                    )
                }
            },
            transition = {
                spec = tween(nFrames * 16)

                // Only translate Bar.
                translate(TestElements.Bar, x = 20.dp, y = 20.dp)
                scaleSize(TestElements.Bar, width = 1f, height = 1f)
            },
        ) {
            var fooLayoutsAfterOneAnimationFrame = 0
            var barLayoutsAfterOneAnimationFrame = 0
            var lastFooPlacements = 0
            var lastBarPlacements = 0

            fun assertNumberOfLayoutsAndPlacements() {
                // The number of layouts have not changed.
                assertThat(fooLayouts).isEqualTo(fooLayoutsAfterOneAnimationFrame)
                assertThat(barLayouts).isEqualTo(barLayoutsAfterOneAnimationFrame)

                // The number of placements have increased.
                assertThat(fooPlacements).isGreaterThan(lastFooPlacements)
                assertThat(barPlacements).isGreaterThan(lastBarPlacements)
                lastFooPlacements = fooPlacements
                lastBarPlacements = barPlacements
            }

            at(16) {
                // Capture the number of layouts and placements that happened after 1 animation
                // frame.
                fooLayoutsAfterOneAnimationFrame = fooLayouts
                barLayoutsAfterOneAnimationFrame = barLayouts
                lastFooPlacements = fooPlacements
                lastBarPlacements = barPlacements
            }
            repeat(nFrames - 2) { i ->
                // Ensure that all animation frames (except the final one) only replaced the
                // elements.
                at(32L + i * 16) { assertNumberOfLayoutsAndPlacements() }
            }
        }
    }

    @Test
    fun elementIsReusedBetweenScenes() {
        var currentScene by mutableStateOf(TestScenes.SceneA)
        var sceneCState by mutableStateOf(0)
        val key = TestElements.Foo
        var nullableLayoutImpl: SceneTransitionLayoutImpl? = null

        rule.setContent {
            SceneTransitionLayoutForTesting(
                state =
                    updateSceneTransitionLayoutState(
                        currentScene = currentScene,
                        onChangeScene = { currentScene = it }
                    ),
                onLayoutImpl = { nullableLayoutImpl = it },
            ) {
                scene(TestScenes.SceneA) { /* Nothing */}
                scene(TestScenes.SceneB) { Box(Modifier.element(key)) }
                scene(TestScenes.SceneC) {
                    when (sceneCState) {
                        0 -> Row(Modifier.element(key)) {}
                        else -> {
                            /* Nothing */
                        }
                    }
                }
            }
        }

        assertThat(nullableLayoutImpl).isNotNull()
        val layoutImpl = nullableLayoutImpl!!

        // Scene A: no elements in the elements map.
        rule.waitForIdle()
        assertThat(layoutImpl.elements).isEmpty()

        // Scene B: element is in the map.
        currentScene = TestScenes.SceneB
        rule.waitForIdle()

        assertThat(layoutImpl.elements.keys).containsExactly(key)
        val element = layoutImpl.elements.getValue(key)
        assertThat(element.sceneStates.keys).containsExactly(TestScenes.SceneB)

        // Scene C, state 0: the same element is reused.
        currentScene = TestScenes.SceneC
        sceneCState = 0
        rule.waitForIdle()

        assertThat(layoutImpl.elements.keys).containsExactly(key)
        assertThat(layoutImpl.elements.getValue(key)).isSameInstanceAs(element)
        assertThat(element.sceneStates.keys).containsExactly(TestScenes.SceneC)

        // Scene C, state 1: the element is removed from the map.
        sceneCState = 1
        rule.waitForIdle()

        assertThat(element.sceneStates).isEmpty()
        assertThat(layoutImpl.elements).isEmpty()
    }

    @Test
    fun throwsExceptionWhenElementIsComposedMultipleTimes() {
        val key = TestElements.Foo

        assertThrows(IllegalStateException::class.java) {
            rule.setContent {
                TestSceneScope {
                    Column {
                        Box(Modifier.element(key))
                        Box(Modifier.element(key))
                    }
                }
            }
        }
    }

    @Test
    fun throwsExceptionWhenElementIsComposedMultipleTimes_childModifier() {
        val key = TestElements.Foo

        assertThrows(IllegalStateException::class.java) {
            rule.setContent {
                TestSceneScope {
                    Column {
                        val childModifier = Modifier.element(key)
                        Box(childModifier)
                        Box(childModifier)
                    }
                }
            }
        }
    }

    @Test
    fun throwsExceptionWhenElementIsComposedMultipleTimes_childModifier_laterDuplication() {
        val key = TestElements.Foo

        assertThrows(IllegalStateException::class.java) {
            var nElements by mutableStateOf(1)
            rule.setContent {
                TestSceneScope {
                    Column {
                        val childModifier = Modifier.element(key)
                        repeat(nElements) { Box(childModifier) }
                    }
                }
            }

            nElements = 2
            rule.waitForIdle()
        }
    }

    @Test
    fun throwsExceptionWhenElementIsComposedMultipleTimes_updatedNode() {
        assertThrows(IllegalStateException::class.java) {
            var key by mutableStateOf(TestElements.Foo)
            rule.setContent {
                TestSceneScope {
                    Column {
                        Box(Modifier.element(key))
                        Box(Modifier.element(TestElements.Bar))
                    }
                }
            }

            key = TestElements.Bar
            rule.waitForIdle()
        }
    }

    @Test
    fun elementModifierSupportsUpdates() {
        var key by mutableStateOf(TestElements.Foo)
        var nullableLayoutImpl: SceneTransitionLayoutImpl? = null

        rule.setContent {
            SceneTransitionLayoutForTesting(
                state =
                    updateSceneTransitionLayoutState(
                        currentScene = TestScenes.SceneA,
                        onChangeScene = {}
                    ),
                onLayoutImpl = { nullableLayoutImpl = it },
            ) {
                scene(TestScenes.SceneA) { Box(Modifier.element(key)) }
            }
        }

        assertThat(nullableLayoutImpl).isNotNull()
        val layoutImpl = nullableLayoutImpl!!

        // There is only Foo in the elements map.
        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Foo)
        val fooElement = layoutImpl.elements.getValue(TestElements.Foo)
        assertThat(fooElement.sceneStates.keys).containsExactly(TestScenes.SceneA)

        key = TestElements.Bar

        // There is only Bar in the elements map and foo scene values was cleaned up.
        rule.waitForIdle()
        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Bar)
        val barElement = layoutImpl.elements.getValue(TestElements.Bar)
        assertThat(barElement.sceneStates.keys).containsExactly(TestScenes.SceneA)
        assertThat(fooElement.sceneStates).isEmpty()
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun elementModifierNodeIsRecycledInLazyLayouts() = runTest {
        val nPages = 2
        val pagerState = PagerState(currentPage = 0) { nPages }
        var nullableLayoutImpl: SceneTransitionLayoutImpl? = null

        // This is how we scroll a pager inside a test, as explained in b/315457147#comment2.
        lateinit var scrollScope: CoroutineScope
        fun scrollToPage(page: Int) {
            var animationFinished by mutableStateOf(false)
            rule.runOnIdle {
                scrollScope.launch {
                    pagerState.scrollToPage(page)
                    animationFinished = true
                }
            }
            rule.waitUntil(timeoutMillis = 10_000) { animationFinished }
        }

        rule.setContent {
            scrollScope = rememberCoroutineScope()

            SceneTransitionLayoutForTesting(
                state =
                    updateSceneTransitionLayoutState(
                        currentScene = TestScenes.SceneA,
                        onChangeScene = {}
                    ),
                onLayoutImpl = { nullableLayoutImpl = it },
            ) {
                scene(TestScenes.SceneA) {
                    // The pages are full-size and beyondBoundsPageCount is 0, so at rest only one
                    // page should be composed.
                    HorizontalPager(
                        pagerState,
                        beyondViewportPageCount = 0,
                    ) { page ->
                        when (page) {
                            0 -> Box(Modifier.element(TestElements.Foo).fillMaxSize())
                            1 -> Box(Modifier.fillMaxSize())
                            else -> error("page $page < nPages $nPages")
                        }
                    }
                }
            }
        }

        assertThat(nullableLayoutImpl).isNotNull()
        val layoutImpl = nullableLayoutImpl!!

        // There is only Foo in the elements map.
        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Foo)
        val element = layoutImpl.elements.getValue(TestElements.Foo)
        val sceneValues = element.sceneStates
        assertThat(sceneValues.keys).containsExactly(TestScenes.SceneA)

        // Get the ElementModifier node that should be reused later on when coming back to this
        // page.
        val nodes = sceneValues.getValue(TestScenes.SceneA).nodes
        assertThat(nodes).hasSize(1)
        val node = nodes.single()

        // Go to the second page.
        scrollToPage(1)
        rule.waitForIdle()

        assertThat(nodes).isEmpty()
        assertThat(sceneValues).isEmpty()
        assertThat(layoutImpl.elements).isEmpty()

        // Go back to the first page.
        scrollToPage(0)
        rule.waitForIdle()

        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Foo)
        val newElement = layoutImpl.elements.getValue(TestElements.Foo)
        val newSceneValues = newElement.sceneStates
        assertThat(newElement).isNotEqualTo(element)
        assertThat(newSceneValues).isNotEqualTo(sceneValues)
        assertThat(newSceneValues.keys).containsExactly(TestScenes.SceneA)

        // The ElementModifier node should be the same as before.
        val newNodes = newSceneValues.getValue(TestScenes.SceneA).nodes
        assertThat(newNodes).hasSize(1)
        val newNode = newNodes.single()
        assertThat(newNode).isSameInstanceAs(node)
    }

    @Test
    fun existingElementsDontRecomposeWhenTransitionStateChanges() {
        var fooCompositions = 0

        rule.testTransition(
            fromSceneContent = {
                SideEffect { fooCompositions++ }
                Box(Modifier.element(TestElements.Foo))
            },
            toSceneContent = {},
            transition = {
                spec = tween(4 * 16)

                scaleSize(TestElements.Foo, width = 2f, height = 0.5f)
                translate(TestElements.Foo, x = 10.dp, y = 10.dp)
                fade(TestElements.Foo)
            }
        ) {
            before { assertThat(fooCompositions).isEqualTo(1) }
            at(16) { assertThat(fooCompositions).isEqualTo(1) }
            at(32) { assertThat(fooCompositions).isEqualTo(1) }
            at(48) { assertThat(fooCompositions).isEqualTo(1) }
            after { assertThat(fooCompositions).isEqualTo(1) }
        }
    }

    @Test
    fun elementTransitionDuringOverscroll() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        val overscrollTranslateY = 10.dp
        val layoutWidth = 200.dp
        val layoutHeight = 400.dp

        val state =
            MutableSceneTransitionLayoutState(
                initialScene = TestScenes.SceneA,
                transitions =
                    transitions {
                        overscroll(TestScenes.SceneB, Orientation.Vertical) {
                            translate(TestElements.Foo, y = overscrollTranslateY)
                        }
                    }
            )
                as MutableSceneTransitionLayoutStateImpl

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(
                state = state,
                modifier = Modifier.size(layoutWidth, layoutHeight)
            ) {
                scene(
                    key = TestScenes.SceneA,
                    userActions = mapOf(Swipe.Down to TestScenes.SceneB)
                ) {
                    Spacer(Modifier.fillMaxSize())
                }
                scene(TestScenes.SceneB) {
                    Spacer(Modifier.element(TestElements.Foo).fillMaxSize())
                }
            }
        }

        assertThat(state.currentTransition).isNull()
        assertThat(state.currentOverscrollSpec).isNull()

        // Swipe by half of verticalSwipeDistance.
        rule.onRoot().performTouchInput {
            val middleTop = Offset((layoutWidth / 2).toPx(), 0f)
            down(middleTop)
            // Scroll 50%
            moveBy(Offset(0f, touchSlop + layoutHeight.toPx() * 0.5f), delayMillis = 1_000)
        }

        val fooElement = rule.onNodeWithTag(TestElements.Foo.testTag, useUnmergedTree = true)
        fooElement.assertTopPositionInRootIsEqualTo(0.dp)
        val transition = state.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(0.5f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 150% (Scene B overscroll by 50%)
        assertThat(transition.progress).isEqualTo(1.5f)
        assertThat(state.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 0.5f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 250% (Scene B overscroll by 150%)
        assertThat(transition.progress).isEqualTo(2.5f)
        assertThat(state.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 1.5f)
    }

    @Test
    fun elementTransitionDuringNestedScrollOverscroll() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        val overscrollTranslateY = 10.dp
        val layoutWidth = 200.dp
        val layoutHeight = 400.dp

        val state =
            MutableSceneTransitionLayoutState(
                initialScene = TestScenes.SceneB,
                transitions =
                    transitions {
                        overscroll(TestScenes.SceneB, Orientation.Vertical) {
                            translate(TestElements.Foo, y = overscrollTranslateY)
                        }
                    }
            )
                as MutableSceneTransitionLayoutStateImpl

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(
                state = state,
                modifier = Modifier.size(layoutWidth, layoutHeight)
            ) {
                scene(TestScenes.SceneA) { Spacer(Modifier.fillMaxSize()) }
                scene(TestScenes.SceneB, userActions = mapOf(Swipe.Up to TestScenes.SceneA)) {
                    Box(
                        Modifier
                            // Unconsumed scroll gesture will be intercepted by STL
                            .verticalNestedScrollToScene()
                            // A scrollable that does not consume the scroll gesture
                            .scrollable(
                                rememberScrollableState(consumeScrollDelta = { 0f }),
                                Orientation.Vertical
                            )
                            .fillMaxSize()
                    ) {
                        Spacer(Modifier.element(TestElements.Foo).fillMaxSize())
                    }
                }
            }
        }

        assertThat(state.currentTransition).isNull()
        assertThat(state.currentOverscrollSpec).isNull()
        val fooElement = rule.onNodeWithTag(TestElements.Foo.testTag, useUnmergedTree = true)
        fooElement.assertTopPositionInRootIsEqualTo(0.dp)

        // Swipe by half of verticalSwipeDistance.
        rule.onRoot().performTouchInput {
            val middleTop = Offset((layoutWidth / 2).toPx(), 0f)
            down(middleTop)
            // Scroll 50%
            moveBy(Offset(0f, touchSlop + layoutHeight.toPx() * 0.5f), delayMillis = 1_000)
        }

        val transition = state.currentTransition
        assertThat(state.currentOverscrollSpec).isNotNull()
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(-0.5f)
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 0.5f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 150% (Scene B overscroll by 50%)
        assertThat(transition.progress).isEqualTo(-1.5f)
        assertThat(state.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 1.5f)
    }
}
