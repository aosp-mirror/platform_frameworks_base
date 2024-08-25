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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestOverlays.OverlayA
import com.android.compose.animation.scene.TestOverlays.OverlayB
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.test.assertSizeIsEqualTo
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTest {
    @get:Rule val rule = createComposeRule()

    @Composable
    private fun ContentScope.Foo() {
        Box(Modifier.element(TestElements.Foo).size(100.dp))
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
                overlay(OverlayA, alignment) { Foo() }
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
}
