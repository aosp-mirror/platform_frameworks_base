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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.intermediateLayout
import androidx.compose.ui.test.junit4.createComposeRule
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
                    Element(TestElements.Foo, elementSize, elementOffset)
                }
            },
            transition = {
                spec = tween(nFrames * 16)

                // no-op transformations.
                translate(TestElements.Bar, x = 0.dp, y = 0.dp)
                scaleSize(TestElements.Bar, width = 1f, height = 1f)
            },
        ) {
            var numberOfLayoutsAfterOneAnimationFrame = 0
            var numberOfPlacementsAfterOneAnimationFrame = 0

            fun assertNumberOfLayoutsAndPlacements() {
                assertThat(fooLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)
                assertThat(fooPlacements).isEqualTo(numberOfPlacementsAfterOneAnimationFrame)
                assertThat(barLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)
                assertThat(barPlacements).isEqualTo(numberOfPlacementsAfterOneAnimationFrame)
            }

            at(16) {
                // Capture the number of layouts and placements that happened after 1 animation
                // frame.
                numberOfLayoutsAfterOneAnimationFrame = fooLayouts
                numberOfPlacementsAfterOneAnimationFrame = fooPlacements
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
                    Element(TestElements.Foo, elementSize, offset = 20.dp)
                }
            },
            transition = {
                spec = tween(nFrames * 16)

                // Only translate Bar.
                translate(TestElements.Bar, x = 20.dp, y = 20.dp)
                scaleSize(TestElements.Bar, width = 1f, height = 1f)
            },
        ) {
            var numberOfLayoutsAfterOneAnimationFrame = 0
            var lastNumberOfPlacements = 0

            fun assertNumberOfLayoutsAndPlacements() {
                // The number of layouts have not changed.
                assertThat(fooLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)
                assertThat(barLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)

                // The number of placements have increased.
                assertThat(fooPlacements).isGreaterThan(lastNumberOfPlacements)
                assertThat(barPlacements).isGreaterThan(lastNumberOfPlacements)
                lastNumberOfPlacements = fooPlacements
            }

            at(16) {
                // Capture the number of layouts and placements that happened after 1 animation
                // frame.
                numberOfLayoutsAfterOneAnimationFrame = fooLayouts
                lastNumberOfPlacements = fooPlacements
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
                currentScene = currentScene,
                onChangeScene = { currentScene = it },
                transitions = remember { transitions {} },
                state = remember { SceneTransitionLayoutState(currentScene) },
                edgeDetector = DefaultEdgeDetector,
                modifier = Modifier,
                transitionInterceptionThreshold = 0f,
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
        assertThat(element.sceneValues.keys).containsExactly(TestScenes.SceneB)

        // Scene C, state 0: the same element is reused.
        currentScene = TestScenes.SceneC
        sceneCState = 0
        rule.waitForIdle()

        assertThat(layoutImpl.elements.keys).containsExactly(key)
        assertThat(layoutImpl.elements.getValue(key)).isSameInstanceAs(element)
        assertThat(element.sceneValues.keys).containsExactly(TestScenes.SceneC)

        // Scene C, state 1: the element is removed from the map.
        sceneCState = 1
        rule.waitForIdle()

        assertThat(element.sceneValues).isEmpty()
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
                currentScene = TestScenes.SceneA,
                onChangeScene = {},
                transitions = remember { transitions {} },
                state = remember { SceneTransitionLayoutState(TestScenes.SceneA) },
                edgeDetector = DefaultEdgeDetector,
                modifier = Modifier,
                transitionInterceptionThreshold = 0f,
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
        assertThat(fooElement.sceneValues.keys).containsExactly(TestScenes.SceneA)

        key = TestElements.Bar

        // There is only Bar in the elements map and foo scene values was cleaned up.
        rule.waitForIdle()
        assertThat(layoutImpl.elements.keys).containsExactly(TestElements.Bar)
        val barElement = layoutImpl.elements.getValue(TestElements.Bar)
        assertThat(barElement.sceneValues.keys).containsExactly(TestScenes.SceneA)
        assertThat(fooElement.sceneValues).isEmpty()
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
                currentScene = TestScenes.SceneA,
                onChangeScene = {},
                transitions = remember { transitions {} },
                state = remember { SceneTransitionLayoutState(TestScenes.SceneA) },
                edgeDetector = DefaultEdgeDetector,
                modifier = Modifier,
                transitionInterceptionThreshold = 0f,
                onLayoutImpl = { nullableLayoutImpl = it },
            ) {
                scene(TestScenes.SceneA) {
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
        val sceneValues = element.sceneValues
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
        val newSceneValues = newElement.sceneValues
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
}
