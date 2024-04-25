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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.intermediateLayout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
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
                .intermediateLayout { measurable, constraints ->
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
    fun elementsNotInTransition_shouldNotBeDrawn() {
        val nFrames = 20
        val frameDuration = 16L
        val tween = tween<Float>(nFrames * frameDuration.toInt())
        val layoutSize = 100.dp
        val elementSize = 50.dp
        val elementOffset = 20.dp

        lateinit var changeScene: (SceneKey) -> Unit

        rule.testTransition(
            from = SceneA,
            to = SceneB,
            transitionLayout = { currentScene, onChangeScene ->
                changeScene = onChangeScene

                SceneTransitionLayout(
                    currentScene,
                    onChangeScene,
                    transitions {
                        from(SceneA, to = SceneB) { spec = tween }
                        from(SceneB, to = SceneC) { spec = tween }
                    },

                    // Disable interruptions so that the current transition is directly removed when
                    // starting a new one.
                    enableInterruptions = false,
                ) {
                    scene(SceneA) {
                        Box(Modifier.size(layoutSize)) {
                            // Transformed element
                            Element(
                                TestElements.Bar,
                                elementSize,
                                elementOffset,
                            )
                        }
                    }
                    scene(SceneB) { Box(Modifier.size(layoutSize)) }
                    scene(SceneC) { Box(Modifier.size(layoutSize)) }
                }
            },
        ) {
            // Start transition from SceneA to SceneB
            at(1 * frameDuration) {
                onElement(TestElements.Bar).assertExists()

                // Start transition from SceneB to SceneC
                changeScene(SceneC)
            }

            at(2 * frameDuration) { onElement(TestElements.Bar).assertIsNotDisplayed() }

            at(3 * frameDuration) { onElement(TestElements.Bar).assertDoesNotExist() }
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
        var currentScene by mutableStateOf(SceneA)
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
                scene(SceneA) { /* Nothing */}
                scene(SceneB) { Box(Modifier.element(key)) }
                scene(SceneC) {
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
        currentScene = SceneB
        rule.waitForIdle()

        assertThat(layoutImpl.elements.keys).containsExactly(key)
        val element = layoutImpl.elements.getValue(key)
        assertThat(element.sceneStates.keys).containsExactly(SceneB)

        // Scene C, state 0: the same element is reused.
        currentScene = SceneC
        sceneCState = 0
        rule.waitForIdle()

        assertThat(layoutImpl.elements.keys).containsExactly(key)
        assertThat(layoutImpl.elements.getValue(key)).isSameInstanceAs(element)
        assertThat(element.sceneStates.keys).containsExactly(SceneC)

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
                state = updateSceneTransitionLayoutState(currentScene = SceneA, onChangeScene = {}),
                onLayoutImpl = { nullableLayoutImpl = it },
            ) {
                scene(SceneA) { Box(Modifier.element(key)) }
            }
        }

        assertThat(nullableLayoutImpl).isNotNull()
        val layoutImpl = nullableLayoutImpl!!

        // There is only Foo in the elements map.
        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Foo)
        val fooElement = layoutImpl.elements.getValue(TestElements.Foo)
        assertThat(fooElement.sceneStates.keys).containsExactly(SceneA)

        key = TestElements.Bar

        // There is only Bar in the elements map and foo scene values was cleaned up.
        rule.waitForIdle()
        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Bar)
        val barElement = layoutImpl.elements.getValue(TestElements.Bar)
        assertThat(barElement.sceneStates.keys).containsExactly(SceneA)
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
                state = updateSceneTransitionLayoutState(currentScene = SceneA, onChangeScene = {}),
                onLayoutImpl = { nullableLayoutImpl = it },
            ) {
                scene(SceneA) {
                    // The pages are full-size and beyondBoundsPageCount is 0, so at rest only one
                    // page should be composed.
                    HorizontalPager(
                        pagerState,
                        outOfBoundsPageCount = 0,
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
        assertThat(sceneValues.keys).containsExactly(SceneA)

        // Get the ElementModifier node that should be reused later on when coming back to this
        // page.
        val nodes = sceneValues.getValue(SceneA).nodes
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
        assertThat(newSceneValues.keys).containsExactly(SceneA)

        // The ElementModifier node should be the same as before.
        val newNodes = newSceneValues.getValue(SceneA).nodes
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

    private fun setupOverscrollScenario(
        layoutWidth: Dp,
        layoutHeight: Dp,
        sceneTransitions: SceneTransitionsBuilder.() -> Unit,
        firstScroll: Float,
        animatedFloatRange: ClosedFloatingPointRange<Float>,
        onAnimatedFloat: (Float) -> Unit,
    ): MutableSceneTransitionLayoutStateImpl {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f

        val state =
            MutableSceneTransitionLayoutState(
                initialScene = SceneA,
                transitions = transitions(sceneTransitions),
            )
                as MutableSceneTransitionLayoutStateImpl

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            SceneTransitionLayout(
                state = state,
                modifier = Modifier.size(layoutWidth, layoutHeight)
            ) {
                scene(key = SceneA, userActions = mapOf(Swipe.Down to SceneB)) {
                    animateSceneFloatAsState(
                        value = animatedFloatRange.start,
                        key = TestValues.Value1,
                        false
                    )
                    Spacer(Modifier.fillMaxSize())
                }
                scene(SceneB) {
                    val animatedFloat by
                        animateSceneFloatAsState(
                            value = animatedFloatRange.endInclusive,
                            key = TestValues.Value1,
                            canOverflow = false
                        )
                    Spacer(Modifier.element(TestElements.Foo).fillMaxSize())
                    LaunchedEffect(Unit) {
                        snapshotFlow { animatedFloat }.collect { onAnimatedFloat(it) }
                    }
                }
            }
        }

        assertThat(state.currentTransition).isNull()
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()

        // Swipe by half of verticalSwipeDistance.
        rule.onRoot().performTouchInput {
            val middleTop = Offset((layoutWidth / 2).toPx(), 0f)
            down(middleTop)
            val firstScrollHeight = layoutHeight.toPx() * firstScroll
            moveBy(Offset(0f, touchSlop + firstScrollHeight), delayMillis = 1_000)
        }
        return state
    }

    @Test
    fun elementTransitionDuringOverscroll() {
        val layoutWidth = 200.dp
        val layoutHeight = 400.dp
        val overscrollTranslateY = 10.dp
        var animatedFloat = 0f

        val state =
            setupOverscrollScenario(
                layoutWidth = layoutWidth,
                layoutHeight = layoutHeight,
                sceneTransitions = {
                    overscroll(SceneB, Orientation.Vertical) {
                        // On overscroll 100% -> Foo should translate by overscrollTranslateY
                        translate(TestElements.Foo, y = overscrollTranslateY)
                    }
                },
                firstScroll = 0.5f, // Scroll 50%
                animatedFloatRange = 0f..100f,
                onAnimatedFloat = { animatedFloat = it },
            )

        val fooElement = rule.onNodeWithTag(TestElements.Foo.testTag, useUnmergedTree = true)
        fooElement.assertTopPositionInRootIsEqualTo(0.dp)
        val transition = state.currentTransition
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(0.5f)
        assertThat(animatedFloat).isEqualTo(50f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 150% (Scene B overscroll by 50%)
        assertThat(transition.progress).isEqualTo(1.5f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 0.5f)
        // animatedFloat cannot overflow (canOverflow = false)
        assertThat(animatedFloat).isEqualTo(100f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 250% (Scene B overscroll by 150%)
        assertThat(transition.progress).isEqualTo(2.5f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 1.5f)
        assertThat(animatedFloat).isEqualTo(100f)
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
                initialScene = SceneB,
                transitions =
                    transitions {
                        overscroll(SceneB, Orientation.Vertical) {
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
                scene(SceneA) { Spacer(Modifier.fillMaxSize()) }
                scene(SceneB, userActions = mapOf(Swipe.Up to SceneA)) {
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
        assertThat(state.currentTransition?.currentOverscrollSpec).isNull()
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
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        assertThat(transition).isNotNull()
        assertThat(transition!!.progress).isEqualTo(-0.5f)
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 0.5f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 150% (Scene B overscroll by 50%)
        assertThat(transition.progress).isEqualTo(-1.5f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(overscrollTranslateY * 1.5f)
    }

    @Test
    fun elementTransitionWithDistanceDuringOverscroll() {
        val layoutWidth = 200.dp
        val layoutHeight = 400.dp
        var animatedFloat = 0f
        val state =
            setupOverscrollScenario(
                layoutWidth = layoutWidth,
                layoutHeight = layoutHeight,
                sceneTransitions = {
                    overscroll(SceneB, Orientation.Vertical) {
                        // On overscroll 100% -> Foo should translate by layoutHeight
                        translate(TestElements.Foo, y = { absoluteDistance })
                    }
                },
                firstScroll = 1f, // 100% scroll
                animatedFloatRange = 0f..100f,
                onAnimatedFloat = { animatedFloat = it },
            )

        val fooElement = rule.onNodeWithTag(TestElements.Foo.testTag, useUnmergedTree = true)
        fooElement.assertTopPositionInRootIsEqualTo(0.dp)
        assertThat(animatedFloat).isEqualTo(100f)

        rule.onRoot().performTouchInput {
            // Scroll another 50%
            moveBy(Offset(0f, layoutHeight.toPx() * 0.5f), delayMillis = 1_000)
        }

        val transition = state.currentTransition
        assertThat(transition).isNotNull()
        assertThat(animatedFloat).isEqualTo(100f)

        // Scroll 150% (100% scroll + 50% overscroll)
        assertThat(transition!!.progress).isEqualTo(1.5f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(layoutHeight * 0.5f)
        assertThat(animatedFloat).isEqualTo(100f)

        rule.onRoot().performTouchInput {
            // Scroll another 100%
            moveBy(Offset(0f, layoutHeight.toPx()), delayMillis = 1_000)
        }

        // Scroll 250% (100% scroll + 150% overscroll)
        assertThat(transition.progress).isEqualTo(2.5f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(layoutHeight * 1.5f)
        assertThat(animatedFloat).isEqualTo(100f)
    }

    @Test
    fun elementTransitionWithDistanceDuringOverscrollBouncing() {
        val layoutWidth = 200.dp
        val layoutHeight = 400.dp
        var animatedFloat = 0f
        val state =
            setupOverscrollScenario(
                layoutWidth = layoutWidth,
                layoutHeight = layoutHeight,
                sceneTransitions = {
                    defaultSwipeSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        )

                    overscroll(SceneB, Orientation.Vertical) {
                        // On overscroll 100% -> Foo should translate by layoutHeight
                        translate(TestElements.Foo, y = { absoluteDistance })
                    }
                },
                firstScroll = 1f, // 100% scroll
                animatedFloatRange = 0f..100f,
                onAnimatedFloat = { animatedFloat = it },
            )

        val fooElement = rule.onNodeWithTag(TestElements.Foo.testTag, useUnmergedTree = true)
        fooElement.assertTopPositionInRootIsEqualTo(0.dp)
        assertThat(animatedFloat).isEqualTo(100f)

        rule.onRoot().performTouchInput {
            // Scroll another 50%
            moveBy(Offset(0f, layoutHeight.toPx() * 0.5f), delayMillis = 1_000)
        }

        val transition = state.currentTransition
        assertThat(transition).isNotNull()
        transition as TransitionState.HasOverscrollProperties

        // Scroll 150% (100% scroll + 50% overscroll)
        assertThat(transition.progress).isEqualTo(1.5f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        fooElement.assertTopPositionInRootIsEqualTo(layoutHeight * (transition.progress - 1f))
        assertThat(animatedFloat).isEqualTo(100f)

        // finger raised
        rule.onRoot().performTouchInput { up() }

        // The target value is 1f, but the spring (defaultSwipeSpec) allows you to go to a lower
        // value.
        rule.waitUntil(timeoutMillis = 10_000) { transition.progress < 1f }

        assertThat(transition.progress).isLessThan(1f)
        assertThat(state.currentTransition?.currentOverscrollSpec).isNotNull()
        assertThat(transition.bouncingScene).isEqualTo(transition.toScene)
        assertThat(animatedFloat).isEqualTo(100f)
    }

    @Test
    fun elementIsUsingLastTransition() {
        // 4 frames of animation.
        val duration = 4 * 16

        val state =
            MutableSceneTransitionLayoutState(
                SceneA,
                transitions {
                    // Foo is at the top left corner of scene A. We make it disappear during A => B
                    // to the right edge so it translates to the right.
                    from(SceneA, to = SceneB) {
                        spec = tween(duration, easing = LinearEasing)
                        translate(
                            TestElements.Foo,
                            edge = Edge.Right,
                            startsOutsideLayoutBounds = false,
                        )
                    }

                    // Bar is at the top right corner of scene C. We make it appear during B => C
                    // from the left edge so it translates to the right at same time as Foo.
                    from(SceneB, to = SceneC) {
                        spec = tween(duration, easing = LinearEasing)
                        translate(
                            TestElements.Bar,
                            edge = Edge.Left,
                            startsOutsideLayoutBounds = false,
                        )
                    }
                }
            )

        val layoutSize = 150.dp
        val elemSize = 50.dp
        lateinit var coroutineScope: CoroutineScope
        rule.setContent {
            coroutineScope = rememberCoroutineScope()

            SceneTransitionLayout(state) {
                scene(SceneA) {
                    Box(Modifier.size(layoutSize)) {
                        Box(
                            Modifier.align(Alignment.TopStart)
                                .element(TestElements.Foo)
                                .size(elemSize)
                        )
                    }
                }
                scene(SceneB) {
                    // Empty scene.
                    Box(Modifier.size(layoutSize))
                }
                scene(SceneC) {
                    Box(Modifier.size(layoutSize)) {
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .element(TestElements.Bar)
                                .size(elemSize)
                        )
                    }
                }
            }
        }

        rule.mainClock.autoAdvance = false

        // Trigger A => B then directly B => C so that Foo and Bar move together to the right edge.
        rule.runOnUiThread {
            state.setTargetScene(SceneB, coroutineScope)
            state.setTargetScene(SceneC, coroutineScope)
        }

        val transitions = state.currentTransitions
        assertThat(transitions).hasSize(2)
        assertThat(transitions[0].fromScene).isEqualTo(SceneA)
        assertThat(transitions[0].toScene).isEqualTo(SceneB)
        assertThat(transitions[0].progress).isEqualTo(0f)

        assertThat(transitions[1].fromScene).isEqualTo(SceneB)
        assertThat(transitions[1].toScene).isEqualTo(SceneC)
        assertThat(transitions[1].progress).isEqualTo(0f)

        // First frame: both are at x = 0dp. For the whole transition, Foo is at y = 0dp and Bar is
        // at y = layoutSize - elementSoze = 100dp.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNode(isElement(TestElements.Foo)).assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(isElement(TestElements.Bar)).assertPositionInRootIsEqualTo(0.dp, 100.dp)

        // Advance to the second frame (25% of the transition): they are both translating
        // horizontally to the final target (x = layoutSize - elemSize = 100dp), so they should now
        // be at x = 25dp.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNode(isElement(TestElements.Foo)).assertPositionInRootIsEqualTo(25.dp, 0.dp)
        rule.onNode(isElement(TestElements.Bar)).assertPositionInRootIsEqualTo(25.dp, 100.dp)

        // Advance to the second frame (50% of the transition): they should now be at x = 50dp.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNode(isElement(TestElements.Foo)).assertPositionInRootIsEqualTo(50.dp, 0.dp)
        rule.onNode(isElement(TestElements.Bar)).assertPositionInRootIsEqualTo(50.dp, 100.dp)

        // Advance to the third frame (75% of the transition): they should now be at x = 75dp.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNode(isElement(TestElements.Foo)).assertPositionInRootIsEqualTo(75.dp, 0.dp)
        rule.onNode(isElement(TestElements.Bar)).assertPositionInRootIsEqualTo(75.dp, 100.dp)

        // Advance to the end of the animation. We can't really test the fourth frame because when
        // pausing the clock, the layout/drawing code will still run (so elements will have their
        // size/offset when there is no more transition running) but composition will not (so
        // elements that should not be composed anymore will still be composed).
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertThat(state.currentTransitions).isEmpty()
        rule.onNode(isElement(TestElements.Foo)).assertDoesNotExist()
        rule.onNode(isElement(TestElements.Bar)).assertPositionInRootIsEqualTo(100.dp, 100.dp)
    }

    @Test
    fun interruption() = runTest {
        // 4 frames of animation.
        val duration = 4 * 16

        val state =
            MutableSceneTransitionLayoutStateImpl(
                SceneA,
                transitions {
                    from(SceneA, to = SceneB) { spec = tween(duration, easing = LinearEasing) }
                    from(SceneB, to = SceneC) { spec = tween(duration, easing = LinearEasing) }
                },
                enableInterruptions = false,
            )

        val layoutSize = DpSize(200.dp, 100.dp)
        val fooSize = DpSize(20.dp, 10.dp)

        @Composable
        fun SceneScope.Foo(modifier: Modifier = Modifier) {
            Box(modifier.element(TestElements.Foo).size(fooSize))
        }

        rule.setContent {
            SceneTransitionLayout(state, Modifier.size(layoutSize)) {
                // In scene A, Foo is aligned at the TopStart.
                scene(SceneA) {
                    Box(Modifier.fillMaxSize()) { Foo(Modifier.align(Alignment.TopStart)) }
                }

                // In scene B, Foo is aligned at the TopEnd, so it moves horizontally when coming
                // from A.
                scene(SceneB) {
                    Box(Modifier.fillMaxSize()) { Foo(Modifier.align(Alignment.TopEnd)) }
                }

                // In scene C, Foo is aligned at the BottomEnd, so it moves vertically when coming
                // from B.
                scene(SceneC) {
                    Box(Modifier.fillMaxSize()) { Foo(Modifier.align(Alignment.BottomEnd)) }
                }
            }
        }

        // The offset of Foo when idle in A, B or C.
        val offsetInA = DpOffset.Zero
        val offsetInB = DpOffset(layoutSize.width - fooSize.width, 0.dp)
        val offsetInC =
            DpOffset(layoutSize.width - fooSize.width, layoutSize.height - fooSize.height)

        // Initial state (idle in A).
        rule
            .onNode(isElement(TestElements.Foo, SceneA))
            .assertPositionInRootIsEqualTo(offsetInA.x, offsetInA.y)

        // Current transition is A => B at 50%.
        val aToBProgress = 0.5f
        val aToB =
            transition(
                from = SceneA,
                to = SceneB,
                progress = { aToBProgress },
                onFinish = neverFinish(),
            )
        val offsetInAToB = lerp(offsetInA, offsetInB, aToBProgress)
        rule.runOnUiThread { state.startTransition(aToB, transitionKey = null) }
        rule
            .onNode(isElement(TestElements.Foo, SceneB))
            .assertPositionInRootIsEqualTo(offsetInAToB.x, offsetInAToB.y)

        // Start B => C at 0%.
        var bToCProgress by mutableFloatStateOf(0f)
        var interruptionProgress by mutableFloatStateOf(1f)
        val bToC =
            transition(
                from = SceneB,
                to = SceneC,
                progress = { bToCProgress },
                interruptionProgress = { interruptionProgress },
            )
        rule.runOnUiThread { state.startTransition(bToC, transitionKey = null) }

        // The offset interruption delta, which will be multiplied by the interruption progress then
        // added to the current transition offset.
        val interruptionDelta = offsetInAToB - offsetInB

        // Interruption progress is at 100% and bToC is at 0%, so Foo should be at the same offset
        // as right before the interruption.
        rule
            .onNode(isElement(TestElements.Foo, SceneC))
            .assertPositionInRootIsEqualTo(offsetInAToB.x, offsetInAToB.y)

        // Move the transition forward at 30% and set the interruption progress to 50%.
        bToCProgress = 0.3f
        interruptionProgress = 0.5f
        val offsetInBToC = lerp(offsetInB, offsetInC, bToCProgress)
        val offsetInBToCWithInterruption =
            offsetInBToC +
                DpOffset(
                    interruptionDelta.x * interruptionProgress,
                    interruptionDelta.y * interruptionProgress,
                )
        rule.waitForIdle()
        rule
            .onNode(isElement(TestElements.Foo, SceneC))
            .assertPositionInRootIsEqualTo(
                offsetInBToCWithInterruption.x,
                offsetInBToCWithInterruption.y,
            )

        // Finish the transition and interruption.
        bToCProgress = 1f
        interruptionProgress = 0f
        rule
            .onNode(isElement(TestElements.Foo, SceneC))
            .assertPositionInRootIsEqualTo(offsetInC.x, offsetInC.y)
    }
}
