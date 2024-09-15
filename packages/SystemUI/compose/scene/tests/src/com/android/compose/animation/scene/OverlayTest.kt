/*
 * Copyright (C) 2024 The Android Open Source Project
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestOverlays.OverlayA
import com.android.compose.animation.scene.TestOverlays.OverlayB
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.subjects.assertThat
import com.android.compose.test.assertSizeIsEqualTo
import com.android.compose.test.setContentAndCreateMainScope
import com.android.compose.test.subjects.assertThat
import com.android.compose.test.transition
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTest {
    @get:Rule val rule = createComposeRule()

    @Composable
    private fun ContentScope.Foo(width: Dp = 100.dp, height: Dp = 100.dp) {
        Box(Modifier.element(TestElements.Foo).size(width, height))
    }

    @Test
    fun showThenHideOverlay() {
        val state = rule.runOnUiThread { MutableSceneTransitionLayoutState(SceneA) }
        lateinit var coroutineScope: CoroutineScope
        rule.setContent {
            coroutineScope = rememberCoroutineScope()
            SceneTransitionLayout(state, Modifier.size(200.dp)) {
                scene(SceneA) { Box(Modifier.fillMaxSize()) { Foo() } }
                overlay(OverlayA) { Foo() }
            }
        }

        // Initial state: overlay A is not shown, so Foo is displayed at the top left in scene A.
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertIsDisplayed()
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()

        // Show overlay A: Foo is now centered on screen and placed in overlay A. It is not placed
        // in scene A.
        rule.runOnUiThread { state.showOverlay(OverlayA, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertExists()
            .assertIsNotDisplayed()
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)

        // Hide overlay A: back to initial state, top-left in scene A.
        rule.runOnUiThread { state.hideOverlay(OverlayA, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertIsDisplayed()
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
    }

    @Test
    fun multipleOverlays() {
        val state = rule.runOnUiThread { MutableSceneTransitionLayoutState(SceneA) }
        lateinit var coroutineScope: CoroutineScope
        rule.setContent {
            coroutineScope = rememberCoroutineScope()
            SceneTransitionLayout(state, Modifier.size(200.dp)) {
                scene(SceneA) { Box(Modifier.fillMaxSize()) { Foo() } }
                overlay(OverlayA) { Foo() }
                overlay(OverlayB) { Foo() }
            }
        }

        // Initial state.
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertIsDisplayed()
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
        rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()

        // Show overlay A.
        rule.runOnUiThread { state.showOverlay(OverlayA, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertExists()
            .assertIsNotDisplayed()
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)
        rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()

        // Replace overlay A by overlay B.
        rule.runOnUiThread { state.replaceOverlay(OverlayA, OverlayB, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertExists()
            .assertIsNotDisplayed()
        rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayB))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)

        // Show overlay A: Foo is still placed in B because it has a higher zIndex, but it now
        // exists in A as well.
        rule.runOnUiThread { state.showOverlay(OverlayA, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertExists()
            .assertIsNotDisplayed()
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertExists()
            .assertIsNotDisplayed()
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayB))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)

        // Hide overlay B.
        rule.runOnUiThread { state.hideOverlay(OverlayB, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertExists()
            .assertIsNotDisplayed()
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)
        rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()

        // Hide overlay A.
        rule.runOnUiThread { state.hideOverlay(OverlayA, coroutineScope) }
        rule
            .onNode(isElement(TestElements.Foo, content = SceneA))
            .assertIsDisplayed()
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
        rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()
    }

    @Test
    fun movableElement() {
        val key = MovableElementKey("MovableBar", contents = setOf(SceneA, OverlayA, OverlayB))
        val elementChildTag = "elementChildTag"

        fun elementChild(content: ContentKey) = hasTestTag(elementChildTag) and inContent(content)

        @Composable
        fun ContentScope.MovableBar() {
            MovableElement(key, Modifier) {
                content { Box(Modifier.testTag(elementChildTag).size(100.dp)) }
            }
        }

        val state = rule.runOnUiThread { MutableSceneTransitionLayoutState(SceneA) }
        lateinit var coroutineScope: CoroutineScope
        rule.setContent {
            coroutineScope = rememberCoroutineScope()
            SceneTransitionLayout(state, Modifier.size(200.dp)) {
                scene(SceneA) { Box(Modifier.fillMaxSize()) { MovableBar() } }
                overlay(OverlayA) { MovableBar() }
                overlay(OverlayB) { MovableBar() }
            }
        }

        // Initial state.
        rule
            .onNode(elementChild(content = SceneA))
            .assertIsDisplayed()
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(elementChild(content = OverlayA)).assertDoesNotExist()
        rule.onNode(elementChild(content = OverlayB)).assertDoesNotExist()

        // Show overlay A: movable element child only exists (is only composed) in overlay A.
        rule.runOnUiThread { state.showOverlay(OverlayA, coroutineScope) }
        rule.onNode(elementChild(content = SceneA)).assertDoesNotExist()
        rule
            .onNode(elementChild(content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)
        rule.onNode(elementChild(content = OverlayB)).assertDoesNotExist()

        // Replace overlay A by overlay B: element child is only in overlay B.
        rule.runOnUiThread { state.replaceOverlay(OverlayA, OverlayB, coroutineScope) }
        rule.onNode(elementChild(content = SceneA)).assertDoesNotExist()
        rule.onNode(elementChild(content = OverlayA)).assertDoesNotExist()
        rule
            .onNode(elementChild(content = OverlayB))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)

        // Show overlay A: element child still only exists in overlay B because it has a higher
        // zIndex.
        rule.runOnUiThread { state.showOverlay(OverlayA, coroutineScope) }
        rule.onNode(elementChild(content = SceneA)).assertDoesNotExist()
        rule.onNode(elementChild(content = OverlayA)).assertDoesNotExist()
        rule
            .onNode(elementChild(content = OverlayB))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)

        // Hide overlay B: element child is in overlay A.
        rule.runOnUiThread { state.hideOverlay(OverlayB, coroutineScope) }
        rule.onNode(elementChild(content = SceneA)).assertDoesNotExist()
        rule
            .onNode(elementChild(content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)
        rule.onNode(elementChild(content = OverlayB)).assertDoesNotExist()

        // Hide overlay A: element child is in scene A.
        rule.runOnUiThread { state.hideOverlay(OverlayA, coroutineScope) }
        rule
            .onNode(elementChild(content = SceneA))
            .assertIsDisplayed()
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
        rule.onNode(elementChild(content = OverlayA)).assertDoesNotExist()
        rule.onNode(elementChild(content = OverlayB)).assertDoesNotExist()
    }

    @Test
    fun overlayAlignment() {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(SceneA, initialOverlays = setOf(OverlayA))
            }
        var alignment by mutableStateOf(Alignment.Center)
        rule.setContent {
            SceneTransitionLayout(state, Modifier.size(200.dp)) {
                scene(SceneA) { Box(Modifier.fillMaxSize()) { Foo() } }
                overlay(OverlayA, alignment = alignment) { Foo() }
            }
        }

        // Initial state: 100x100dp centered in 200x200dp layout.
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(50.dp, 50.dp)

        // BottomStart.
        alignment = Alignment.BottomStart
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(0.dp, 100.dp)

        // TopEnd.
        alignment = Alignment.TopEnd
        rule
            .onNode(isElement(TestElements.Foo, content = OverlayA))
            .assertSizeIsEqualTo(100.dp)
            .assertPositionInRootIsEqualTo(100.dp, 0.dp)
    }

    @Test
    fun overlayMaxSizeIsCurrentSceneSize() {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(SceneA, initialOverlays = setOf(OverlayA))
            }

        val contentTag = "overlayContent"
        rule.setContent {
            SceneTransitionLayout(state) {
                scene(SceneA) { Box(Modifier.size(100.dp)) { Foo() } }
                overlay(OverlayA) { Box(Modifier.testTag(contentTag).fillMaxSize()) }
            }
        }

        // Max overlay size is the size of the layout without overlays, not the (max) possible size
        // of the layout.
        rule.onNodeWithTag(contentTag).assertSizeIsEqualTo(100.dp)
    }

    @Test
    fun showAnimation() {
        rule.testShowOverlayTransition(
            fromSceneContent = {
                Box(Modifier.size(width = 180.dp, height = 120.dp)) {
                    Foo(width = 60.dp, height = 40.dp)
                }
            },
            overlayContent = { Foo(width = 100.dp, height = 80.dp) },
            transition = {
                // 4 frames of animation
                spec = tween(4 * 16, easing = LinearEasing)
            },
        ) {
            // Foo moves from (0,0) with a size of 60x40dp to centered (in a 180x120dp Box) with a
            // size of 100x80dp, so at (40,20).
            before {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(60.dp, 40.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
            }

            at(16) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(70.dp, 50.dp)
                    .assertPositionInRootIsEqualTo(10.dp, 5.dp)
            }

            at(32) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(80.dp, 60.dp)
                    .assertPositionInRootIsEqualTo(20.dp, 10.dp)
            }

            at(48) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(90.dp, 70.dp)
                    .assertPositionInRootIsEqualTo(30.dp, 15.dp)
            }

            after {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(100.dp, 80.dp)
                    .assertPositionInRootIsEqualTo(40.dp, 20.dp)
            }
        }
    }

    @Test
    fun hideAnimation() {
        rule.testHideOverlayTransition(
            toSceneContent = {
                Box(Modifier.size(width = 180.dp, height = 120.dp)) {
                    Foo(width = 60.dp, height = 40.dp)
                }
            },
            overlayContent = { Foo(width = 100.dp, height = 80.dp) },
            transition = {
                // 4 frames of animation
                spec = tween(4 * 16, easing = LinearEasing)
            },
        ) {
            // Foo moves from centered (in a 180x120dp Box) with a size of 100x80dp, so at (40,20),
            // to (0,0) with a size of 60x40dp.
            before {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(100.dp, 80.dp)
                    .assertPositionInRootIsEqualTo(40.dp, 20.dp)
            }

            at(16) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(90.dp, 70.dp)
                    .assertPositionInRootIsEqualTo(30.dp, 15.dp)
            }

            at(32) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(80.dp, 60.dp)
                    .assertPositionInRootIsEqualTo(20.dp, 10.dp)
            }

            at(48) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(70.dp, 50.dp)
                    .assertPositionInRootIsEqualTo(10.dp, 5.dp)
            }

            after {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(60.dp, 40.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
            }
        }
    }

    @Test
    fun replaceAnimation() {
        rule.testReplaceOverlayTransition(
            currentSceneContent = { Box(Modifier.size(width = 180.dp, height = 120.dp)) },
            fromContent = { Foo(width = 60.dp, height = 40.dp) },
            fromAlignment = Alignment.TopStart,
            toContent = { Foo(width = 100.dp, height = 80.dp) },
            transition = {
                // 4 frames of animation
                spec = tween(4 * 16, easing = LinearEasing)
            },
        ) {
            // Foo moves from (0,0) with a size of 60x40dp to centered (in a 180x120dp Box) with a
            // size of 100x80dp, so at (40,20).
            before {
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertSizeIsEqualTo(60.dp, 40.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()
            }

            at(16) {
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(70.dp, 50.dp)
                    .assertPositionInRootIsEqualTo(10.dp, 5.dp)
            }

            at(32) {
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(80.dp, 60.dp)
                    .assertPositionInRootIsEqualTo(20.dp, 10.dp)
            }

            at(48) {
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(90.dp, 70.dp)
                    .assertPositionInRootIsEqualTo(30.dp, 15.dp)
            }

            after {
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(100.dp, 80.dp)
                    .assertPositionInRootIsEqualTo(40.dp, 20.dp)
            }
        }
    }

    @Test
    fun replaceAnimation_elementInCurrentSceneAndOneOverlay() {
        val sharedIntKey = ValueKey("sharedInt")
        val sharedIntValueByContent = mutableMapOf<ContentKey, Int>()

        @Composable
        fun SceneScope.animateContentInt(targetValue: Int) {
            val animatedValue = animateContentIntAsState(targetValue, sharedIntKey)
            LaunchedEffect(animatedValue) {
                try {
                    snapshotFlow { animatedValue.value }
                        .collect { sharedIntValueByContent[contentKey] = it }
                } finally {
                    sharedIntValueByContent.remove(contentKey)
                }
            }
        }

        rule.testReplaceOverlayTransition(
            currentSceneContent = {
                Box(Modifier.size(width = 180.dp, height = 120.dp)) {
                    animateContentInt(targetValue = 1_000)
                    Foo(width = 60.dp, height = 40.dp)
                }
            },
            fromContent = {},
            fromAlignment = Alignment.TopStart,
            toContent = {
                animateContentInt(targetValue = 2_000)
                Foo(width = 100.dp, height = 80.dp)
            },
            transition = {
                // 4 frames of animation
                spec = tween(4 * 16, easing = LinearEasing)
            },
        ) {
            // Foo moves from (0,0) with a size of 60x40dp to centered (in a 180x120dp Box) with a
            // size of 100x80dp, so at (40,20).
            //
            // The animated Int goes from 1_000 to 2_000.
            before {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(60.dp, 40.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()

                assertThat(sharedIntValueByContent).containsEntry(SceneA, 1_000)
                assertThat(sharedIntValueByContent).doesNotContainKey(OverlayA)
                assertThat(sharedIntValueByContent).doesNotContainKey(OverlayB)
            }

            at(16) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(70.dp, 50.dp)
                    .assertPositionInRootIsEqualTo(10.dp, 5.dp)

                assertThat(sharedIntValueByContent).containsEntry(SceneA, 1_250)
                assertThat(sharedIntValueByContent).doesNotContainKey(OverlayA)
                assertThat(sharedIntValueByContent).containsEntry(OverlayB, 1_250)
            }

            at(32) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(80.dp, 60.dp)
                    .assertPositionInRootIsEqualTo(20.dp, 10.dp)

                assertThat(sharedIntValueByContent).containsEntry(SceneA, 1_500)
                assertThat(sharedIntValueByContent).doesNotContainKey(OverlayA)
                assertThat(sharedIntValueByContent).containsEntry(OverlayB, 1_500)
            }

            at(48) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(90.dp, 70.dp)
                    .assertPositionInRootIsEqualTo(30.dp, 15.dp)

                assertThat(sharedIntValueByContent).containsEntry(SceneA, 1_750)
                assertThat(sharedIntValueByContent).doesNotContainKey(OverlayA)
                assertThat(sharedIntValueByContent).containsEntry(OverlayB, 1_750)
            }

            after {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(100.dp, 80.dp)
                    .assertPositionInRootIsEqualTo(40.dp, 20.dp)

                // Outside of transitions, the value is equal to the target value in each content.
                assertThat(sharedIntValueByContent).containsEntry(SceneA, 1_000)
                assertThat(sharedIntValueByContent).doesNotContainKey(OverlayA)
                assertThat(sharedIntValueByContent).containsEntry(OverlayB, 2_000)
            }
        }
    }

    @Test
    fun replaceAnimation_elementInCurrentSceneAndOneOverlay_sharedElementDisabled() {
        rule.testReplaceOverlayTransition(
            currentSceneContent = {
                Box(Modifier.size(width = 180.dp, height = 120.dp)) {
                    Foo(width = 60.dp, height = 40.dp)
                }
            },
            fromContent = {},
            fromAlignment = Alignment.TopStart,
            toContent = { Foo(width = 100.dp, height = 80.dp) },
            transition = {
                // 4 frames of animation
                spec = tween(4 * 16, easing = LinearEasing)

                // Scale Foo to/from size 0 in each content instead of sharing it.
                sharedElement(TestElements.Foo, enabled = false)
                scaleSize(TestElements.Foo, width = 0f, height = 0f)
            },
        ) {
            before {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(60.dp, 40.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule.onNode(isElement(TestElements.Foo, content = OverlayB)).assertDoesNotExist()
            }

            at(16) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(45.dp, 30.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(25.dp, 20.dp)
                    .assertPositionInRootIsEqualTo(((180 - 25) / 2f).dp, ((120 - 20) / 2f).dp)
            }

            at(32) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(30.dp, 20.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(50.dp, 40.dp)
                    .assertPositionInRootIsEqualTo(((180 - 50) / 2f).dp, ((120 - 40) / 2f).dp)
            }

            at(48) {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertSizeIsEqualTo(15.dp, 10.dp)
                    .assertPositionInRootIsEqualTo(0.dp, 0.dp)
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(75.dp, 60.dp)
                    .assertPositionInRootIsEqualTo(((180 - 75) / 2f).dp, ((120 - 60) / 2f).dp)
            }

            after {
                rule
                    .onNode(isElement(TestElements.Foo, content = SceneA))
                    .assertExists()
                    .assertIsNotDisplayed()
                rule.onNode(isElement(TestElements.Foo, content = OverlayA)).assertDoesNotExist()
                rule
                    .onNode(isElement(TestElements.Foo, content = OverlayB))
                    .assertSizeIsEqualTo(100.dp, 80.dp)
                    .assertPositionInRootIsEqualTo(40.dp, 20.dp)
            }
        }
    }

    @Test
    fun overscrollingOverlay_movableElementNotInOverlay() {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutStateImpl(
                    SceneA,
                    transitions {
                        // Make OverlayA overscrollable.
                        overscroll(OverlayA, orientation = Orientation.Horizontal) {
                            translate(ElementKey("elementThatDoesNotExist"), x = 10.dp)
                        }
                    },
                )
            }

        val key = MovableElementKey("Foo", contents = setOf(SceneA))
        val movableElementChildTag = "movableElementChildTag"
        val scope =
            rule.setContentAndCreateMainScope {
                SceneTransitionLayout(state) {
                    scene(SceneA) {
                        MovableElement(key, Modifier) {
                            content { Box(Modifier.testTag(movableElementChildTag).size(100.dp)) }
                        }
                    }
                    overlay(OverlayA) { /* Does not contain the element. */ }
                }
            }

        // Overscroll on Overlay A.
        scope.launch { state.startTransition(transition(SceneA, OverlayA, progress = { 1.5f })) }
        rule
            .onNode(hasTestTag(movableElementChildTag) and inContent(SceneA))
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)
            .assertSizeIsEqualTo(100.dp)
            .assertIsDisplayed()
    }

    @Test
    fun overlaysAreModalByDefault() {
        val state = rule.runOnUiThread { MutableSceneTransitionLayoutStateImpl(SceneA) }

        val scrollState = ScrollState(initial = 0)
        val scope =
            rule.setContentAndCreateMainScope {
                SceneTransitionLayout(state) {
                    // Make the scene vertically scrollable.
                    scene(SceneA) {
                        Box(Modifier.size(200.dp).verticalScroll(scrollState)) {
                            Box(Modifier.size(200.dp, 400.dp))
                        }
                    }

                    // The overlay is at the center end of the scene.
                    overlay(OverlayA, alignment = Alignment.CenterEnd) {
                        Box(Modifier.size(100.dp))
                    }
                }
            }

        fun swipeUp() {
            rule.onRoot().performTouchInput {
                swipe(start = Offset(x = 0f, y = bottom), end = Offset(x = 0f, y = top))
            }
        }

        // Swiping up on the scene scrolls the list.
        assertThat(scrollState.value).isEqualTo(0)
        swipeUp()
        assertThat(scrollState.value).isNotEqualTo(0)

        // Reset the scroll.
        scope.launch { scrollState.scrollTo(0) }
        rule.waitForIdle()
        assertThat(scrollState.value).isEqualTo(0)

        // Show the overlay.
        rule.runOnUiThread { state.showOverlay(OverlayA, animationScope = scope) }
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentOverlays(OverlayA)

        // Swiping up does not scroll the scene behind the overlay.
        swipeUp()
        assertThat(scrollState.value).isEqualTo(0)

        // Clicking outside the overlay will close it.
        rule.onRoot().performTouchInput { click(Offset.Zero) }
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()
        assertThat(state.transitionState).hasCurrentOverlays(/* empty */ )
    }
}
